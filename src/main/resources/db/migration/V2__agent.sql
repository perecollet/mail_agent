-- =====================================================================
-- V2 · Agente: conversaciones, ejecuciones, coste y auditoría de herramientas
-- =====================================================================

-- ---------------------------------------------------------------------
-- Conversación por chat de Telegram. /reset cierra la abierta y crea otra.
-- ---------------------------------------------------------------------
CREATE TABLE conversation
(
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chat_id    bigint      NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    closed_at  timestamptz
);

-- Como mucho una conversación abierta por chat
CREATE UNIQUE INDEX uq_conversation_open ON conversation (chat_id) WHERE closed_at IS NULL;

-- ---------------------------------------------------------------------
-- Turnos de la conversación. Solo texto de usuario y respuesta final del
-- asistente: los bloques tool_use/tool_result viven dentro de una ejecución
-- y no se reenvían en turnos posteriores (ahorro de tokens y no se arrastra
-- contenido de correos no fiable al historial).
-- ---------------------------------------------------------------------
CREATE TABLE conversation_turn
(
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id bigint      NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    role            varchar(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content         text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_turn_conversation ON conversation_turn (conversation_id, id);

-- ---------------------------------------------------------------------
-- Una ejecución = una pregunta del usuario resuelta por el bucle de tool use.
-- Acumula tokens y coste de todas sus iteraciones.
-- ---------------------------------------------------------------------
CREATE TABLE agent_run
(
    id                 uuid PRIMARY KEY,
    conversation_id    bigint REFERENCES conversation (id) ON DELETE SET NULL,
    model              varchar(64)    NOT NULL,
    status             varchar(24)    NOT NULL
        CHECK (status IN ('RUNNING', 'COMPLETED', 'MAX_ITERATIONS', 'BUDGET_EXCEEDED', 'FAILED')),
    iterations         integer        NOT NULL DEFAULT 0,
    input_tokens       integer        NOT NULL DEFAULT 0,
    output_tokens      integer        NOT NULL DEFAULT 0,
    cache_read_tokens  integer        NOT NULL DEFAULT 0,
    cache_write_tokens integer        NOT NULL DEFAULT 0,
    cost_usd           numeric(10, 6) NOT NULL DEFAULT 0,
    started_at         timestamptz    NOT NULL DEFAULT now(),
    finished_at        timestamptz,
    error_message      text
);

-- Para el presupuesto diario (SUM(cost_usd) desde las 00:00) y /coste
CREATE INDEX ix_agent_run_started ON agent_run (started_at DESC);

-- ---------------------------------------------------------------------
-- Auditoría de llamadas a herramientas: qué consultó el modelo y cuánto
-- devolvió. Útil para depurar y para detectar comportamientos raros
-- (p. ej. una prompt injection que provoca búsquedas inesperadas).
-- ---------------------------------------------------------------------
CREATE TABLE agent_tool_call
(
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id       uuid        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    iteration    integer     NOT NULL,
    tool_name    varchar(64) NOT NULL,
    input        jsonb       NOT NULL,
    result_items integer,
    result_chars integer,
    duration_ms  integer,
    error        text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_tool_call_run ON agent_tool_call (run_id, id);
