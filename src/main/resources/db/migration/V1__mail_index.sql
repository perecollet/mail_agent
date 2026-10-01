-- =====================================================================
-- V1 · Índice de correo (lado del sincronizador, solo lectura de IMAP)
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- unaccent() no es IMMUTABLE (depende del diccionario), así que no puede
-- usarse en columnas generadas ni en índices. Este wrapper fija el
-- diccionario y es el patrón recomendado.
CREATE FUNCTION immutable_unaccent(text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE PARALLEL SAFE STRICT
    RETURN public.unaccent('public.unaccent'::regdictionary, $1);

-- ---------------------------------------------------------------------
-- Cuentas: la configuración (host, usuario, contraseña) vive en .env.
-- Aquí solo existe la identidad para las FK; el sync hace upsert al arrancar.
-- ---------------------------------------------------------------------
CREATE TABLE mail_account
(
    id         varchar(32) PRIMARY KEY,           -- 'gmail', 'icloud', 'movistar'
    created_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Carpetas sincronizadas y su cursor incremental IMAP.
-- ---------------------------------------------------------------------
CREATE TABLE mail_folder
(
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id         varchar(32) NOT NULL REFERENCES mail_account (id),
    name               text        NOT NULL,          -- nombre IMAP completo, p. ej. '[Gmail]/Sent Mail'
    role               varchar(8)  NOT NULL CHECK (role IN ('INBOX', 'SENT', 'OTHER')),
    uid_validity       bigint,                        -- null hasta el primer sync
    last_uid           bigint      NOT NULL DEFAULT 0,
    highest_modseq     bigint,                        -- solo si el servidor soporta CONDSTORE
    last_synced_at     timestamptz,
    last_reconciled_at timestamptz,
    CONSTRAINT uq_mail_folder UNIQUE (account_id, name)
);

-- ---------------------------------------------------------------------
-- Correos indexados. Una fila por (carpeta, UIDVALIDITY, UID): es la clave
-- natural de IMAP y garantiza la idempotencia del sync (ON CONFLICT DO NOTHING).
-- El mismo Message-ID puede aparecer en INBOX y SENT; se deduplica en consulta.
-- ---------------------------------------------------------------------
CREATE TABLE email
(
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id            varchar(32) NOT NULL REFERENCES mail_account (id),
    folder_id             bigint      NOT NULL REFERENCES mail_folder (id),
    uid_validity          bigint      NOT NULL,
    uid                   bigint      NOT NULL,

    -- Cabeceras de hilo, normalizadas sin '<>' y en minúsculas
    message_id            text,
    in_reply_to           text,
    references_ids        text[]      NOT NULL DEFAULT '{}',

    direction             varchar(8)  NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    from_address          text,                       -- en minúsculas
    from_name             text,
    to_addresses          text[]      NOT NULL DEFAULT '{}',
    cc_addresses          text[]      NOT NULL DEFAULT '{}',
    subject               text,
    subject_normalized    text,                       -- sin Re:/Fwd:/RV:/RE:, para el respaldo de hilos

    sent_at               timestamptz,                -- cabecera Date (puede mentir o faltar)
    received_at           timestamptz NOT NULL,       -- INTERNALDATE del servidor (fiable)

    -- Señales (los flags se refrescan; el resto es inmutable)
    is_seen               boolean     NOT NULL DEFAULT false,
    is_provider_important boolean     NOT NULL DEFAULT false,  -- \Important de Gmail
    has_list_unsubscribe  boolean     NOT NULL DEFAULT false,  -- heurística de newsletter
    has_attachments       boolean     NOT NULL DEFAULT false,

    body_text             text,                       -- texto plano limpio (HTML → texto)
    body_stripped         text,                       -- sin citas ni firma: lo que ve el modelo
    body_truncated        boolean     NOT NULL DEFAULT false,

    -- Nombres de adjunto (con _ - . convertidos en espacios) + texto extraído de
    -- los PDF, agregado aquí para que una búsqueda pueda combinar términos del
    -- cuerpo y del adjunto ("factura luz agosto"). Lo escribe el sync en la
    -- misma transacción que los adjuntos.
    attachment_text       text,

    search_vector         tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', immutable_unaccent(coalesce(subject, ''))), 'A') ||
        setweight(to_tsvector('simple', immutable_unaccent(
                coalesce(from_name, '') || ' ' || coalesce(from_address, ''))), 'B') ||
        setweight(to_tsvector('simple', immutable_unaccent(coalesce(body_stripped, ''))), 'C') ||
        setweight(to_tsvector('simple', immutable_unaccent(coalesce(attachment_text, ''))), 'D')
        ) STORED,

    flags_synced_at       timestamptz,
    deleted_at            timestamptz,                -- marcado por la reconciliación diaria
    ingested_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_email_imap UNIQUE (folder_id, uid_validity, uid)
);

CREATE INDEX ix_email_search ON email USING gin (search_vector);
CREATE INDEX ix_email_received ON email (received_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_email_account_received ON email (account_id, received_at DESC);
CREATE INDEX ix_email_outbound ON email (received_at DESC) WHERE direction = 'OUTBOUND';
CREATE INDEX ix_email_message_id ON email (message_id);
CREATE INDEX ix_email_in_reply_to ON email (in_reply_to);
CREATE INDEX ix_email_references ON email USING gin (references_ids);
CREATE INDEX ix_email_to ON email USING gin (to_addresses);
-- Búsqueda aproximada por remitente y asunto ("endesa" ~ "Endesa Energía XXI <no-reply@endesa.es>")
CREATE INDEX ix_email_from_trgm ON email USING gin (
    immutable_unaccent(coalesce(from_name, '') || ' ' || coalesce(from_address, '')) gin_trgm_ops);
CREATE INDEX ix_email_subject_trgm ON email USING gin (
    immutable_unaccent(coalesce(subject, '')) gin_trgm_ops);

-- ---------------------------------------------------------------------
-- Adjuntos: solo metadatos. El texto extraído se agrega en
-- email.attachment_text y el binario se descarga bajo demanda por IMAP
-- (BODY.PEEK, no marca como leído) cuando el usuario lo pide en Telegram.
-- ---------------------------------------------------------------------
CREATE TABLE email_attachment
(
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email_id          bigint      NOT NULL REFERENCES email (id) ON DELETE CASCADE,
    part_path         text        NOT NULL,           -- ruta en el árbol MIME, p. ej. '2.1'
    file_name         text,
    content_type      text        NOT NULL,
    size_bytes        bigint,
    is_inline         boolean     NOT NULL DEFAULT false,
    extraction_status varchar(16) NOT NULL DEFAULT 'SKIPPED'
        CHECK (extraction_status IN ('DONE', 'SKIPPED', 'FAILED')),
    CONSTRAINT uq_attachment_part UNIQUE (email_id, part_path)
);

CREATE INDEX ix_attachment_email ON email_attachment (email_id);

-- ---------------------------------------------------------------------
-- Historial de ejecuciones del sync (observabilidad y comando /estado)
-- ---------------------------------------------------------------------
CREATE TABLE sync_run
(
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    folder_id        bigint      NOT NULL REFERENCES mail_folder (id),
    kind             varchar(16) NOT NULL CHECK (kind IN ('INCREMENTAL', 'FLAGS', 'RECONCILE')),
    status           varchar(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    started_at       timestamptz NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    messages_fetched integer     NOT NULL DEFAULT 0,
    messages_updated integer     NOT NULL DEFAULT 0,
    error_message    text
);

CREATE INDEX ix_sync_run_folder_started ON sync_run (folder_id, started_at DESC);
