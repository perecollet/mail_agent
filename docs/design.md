# Mail Agent · Diseño (fase 1)

Agente personal de correo para el homelab: sincroniza varias cuentas IMAP en
**solo lectura** hacia PostgreSQL y responde preguntas por Telegram usando
Claude con herramientas que **solo consultan Postgres**.

## 1. Stack y versiones

| Pieza | Elección | Notas |
|---|---|---|
| Lenguaje | Java 25 (LTS) | |
| Framework | Spring Boot 4.1.x | 4.1.1 es la última a fecha 2026-10 |
| Base de datos | PostgreSQL 17 + `unaccent` + `pg_trgm` | Contenedor propio, red interna |
| Migraciones | Flyway | `src/main/resources/db/migration` |
| Acceso a datos | `JdbcClient` (Spring) | Ver decisión D3 |
| IMAP | Eclipse Angus Mail (implementación de Jakarta Mail) | `IMAPFolder` para UID, UIDVALIDITY, CONDSTORE |
| HTML → texto | Jsoup | |
| PDF → texto | Apache PDFBox 3 | Solo para indexar, no se guarda el binario |
| LLM | `com.anthropic:anthropic-java` 2.48.x | Bucle de tool use propio |
| Modelo por defecto | `claude-haiku-4-5` | 1 $/MTok entrada, 5 $/MTok salida |
| Telegram | `org.telegram:telegrambots-longpolling` + `telegrambots-client` 10.x | Long polling: sin puertos de entrada |
| Tests | JUnit 5, Mockito, AssertJ, Testcontainers (Postgres), GreenMail (IMAP), ArchUnit | |

Las versiones exactas se fijan en el `pom.xml` en la fase 2.

## 2. Estructura de paquetes

Un solo módulo Maven y un solo desplegable. Cuatro contextos con arquitectura
hexagonal ligera: `domain` (sin Spring), `application` (casos de uso y puertos)
y `adapter` (entrada y salida). ArchUnit hace cumplir las reglas de la sección 2.1.

```
dev.perecollet.mailagent
├── MailAgentApplication.java
│
├── mail/                          # Núcleo compartido: el correo indexado y sus consultas
│   ├── domain/
│   │   ├── EmailId, AccountId, EmailAddress          (value objects)
│   │   ├── EmailSummary, EmailDetail, AttachmentRef, ThreadView, DayDigest
│   │   ├── Direction                                 (INBOUND | OUTBOUND)
│   │   ├── PrivacyPolicy        # ¿remitente excluido? → decide la redacción
│   │   └── ImportancePolicy     # heurística sin IA
│   ├── application/
│   │   ├── port/in/   SearchEmails, GetEmail, GetThread, FindUnanswered, GetDayDigest
│   │   ├── port/out/  EmailQueryRepository
│   │   └── service/   MailQueryService (implementa los puertos de entrada)
│   └── adapter/out/persistence/
│       └── JdbcEmailQueryRepository
│
├── sync/                          # Ingesta IMAP → Postgres (sin IA)
│   ├── domain/
│   │   ├── FolderCursor          # UIDVALIDITY + último UID + MODSEQ
│   │   ├── FetchedMessage        # mensaje ya parseado, independiente de Jakarta Mail
│   │   ├── BodyCleaner           # HTML→texto, quita citas y firma (lógica pura, muy testeada)
│   │   └── SubjectNormalizer
│   ├── application/
│   │   ├── port/out/  MailServerReader, EmailStore, SyncStateRepository
│   │   └── service/   IncrementalSyncService, FlagRefreshService, ReconciliationService
│   └── adapter/
│       ├── in/scheduler/  SyncScheduler          (@Scheduled fixedDelay, 15 min / diario)
│       └── out/
│           ├── imap/         AngusMailServerReader, MimeParser, AttachmentFetcher
│           └── persistence/  JdbcEmailStore, JdbcSyncStateRepository
│
├── agent/                         # Bucle de tool use
│   ├── domain/
│   │   ├── AgentRun, RunStatus, TokenUsage, CostCalculator
│   │   ├── Conversation, Turn
│   │   └── ReplyReferences       # extrae y valida [[mail:ID]] / [[att:ID]]
│   ├── application/
│   │   ├── port/in/   AskAgent
│   │   ├── port/out/  LlmClient, ConversationRepository, RunRepository
│   │   ├── service/   AgentService   (bucle, límite de iteraciones, presupuesto diario)
│   │   └── tools/     ToolRegistry, MailTool (interfaz) + una clase por herramienta
│   └── adapter/out/
│       ├── anthropic/    AnthropicLlmClient   (único sitio que conoce el SDK)
│       └── persistence/  JdbcConversationRepository, JdbcRunRepository
│
├── telegram/                      # Interfaz de usuario
│   ├── application/   port/out/AttachmentDownloader (lo implementa sync/imap)
│   └── adapter/in/
│       ├── MailAgentBot          # long polling, lista blanca de chat ID
│       ├── CommandHandler        # /reset, /coste, /estado, /help
│       ├── CallbackHandler       # botones "ver correo" y "descargar adjunto"
│       └── ReplyRenderer         # texto plano, sin vistas previas, botones
│
└── config/                        # @ConfigurationProperties y wiring de beans
    ├── MailAccountsProperties, PrivacyProperties, AgentProperties, TelegramProperties
    └── ClockConfig                # Clock con zona Europe/Madrid (inyectable en tests)
```

### 2.1 Reglas de dependencia (ArchUnit)

- `..domain..` no depende de Spring, Jakarta Mail, el SDK de Anthropic ni TelegramBots.
- `..application..` no depende de `..adapter..`.
- Solo `agent.adapter.out.anthropic` importa `com.anthropic..`.
- Solo `sync.adapter.out.imap` importa `jakarta.mail..` y `org.eclipse.angus..`.
- Solo `telegram.adapter..` importa `org.telegram..`.
- `agent` consume `mail` únicamente a través de `mail.application.port.in`.

## 3. Decisiones de diseño

**D1. Un proceso, sin colas.** El sync y el bot conviven en la misma JVM. El
sync usa `@Scheduled(fixedDelay)`, así que dos ejecuciones nunca se solapan, y
una cuenta que falla no bloquea a las demás (`try` por cuenta y un `sync_run`
con estado `FAILED`).

**D2. CQRS ligero.** `sync` escribe y `mail` lee el mismo esquema. No hay
modelo de dominio rico para un índice de correo; las proyecciones de lectura
(`EmailSummary`, `ThreadView`…) son records inmutables.

**D3. `JdbcClient` en vez de JPA.** Las piezas centrales son SQL específico de
Postgres: `ON CONFLICT DO NOTHING`, `websearch_to_tsquery`, trigramas, CTE
recursivas y operadores de arrays. Con JPA acabarían como consultas nativas y
entidades sin comportamiento. Con `JdbcClient` el SQL queda explícito y se
prueba con Testcontainers contra un Postgres real.

**D4. Idempotencia por clave natural IMAP.** La clave es
`UNIQUE (folder_id, uid_validity, uid)` con `ON CONFLICT DO NOTHING`.
Relanzar un sync o reintentar tras un fallo no duplica nada. El cursor de la
carpeta se avanza en la misma transacción que el lote insertado.

**D5. Deduplicación en consulta, no en ingesta.** Un correo que te envías a ti
mismo existe en INBOX y en SENT. Se guardan ambas filas (son dos hechos IMAP
distintos) y las consultas hacen `DISTINCT ON (message_id)`.

**D6. Exclusiones evaluadas en consulta.** La lista de dominios y remitentes
excluidos vive en configuración y la aplica `PrivacyPolicy` al construir cada
resultado. Cambiar la lista tiene efecto inmediato, sin reindexar.

**D7. Importancia calculada en consulta.** `ImportancePolicy` combina señales
guardadas en ingesta (`has_list_unsubscribe`, `is_provider_important`,
destinatario directo) con "remitente conocido" (alguien a quien has escrito
alguna vez), que se resuelve con una subconsulta sobre los correos `OUTBOUND`.

**D8. El historial solo guarda texto.** Los bloques `tool_use` y
`tool_result` existen solo dentro de una ejecución. Los turnos siguientes no
arrastran contenido de correos, lo que ahorra tokens y evita que una
injection persista en el historial.

## 4. Sincronizador

### 4.1 Ciclos

| Ciclo | Frecuencia | Qué hace |
|---|---|---|
| Incremental | cada 15 min | `UID FETCH last_uid+1:*` por carpeta, en lotes de 200 |
| Flags | cada 15 min, tras el incremental | Refresca `\Seen` de los últimos 7 días; usa `CHANGEDSINCE` (CONDSTORE) si el servidor lo soporta |
| Reconciliación | diaria, 04:00 | `UID SEARCH ALL` y marca `deleted_at` en los UID que ya no existen |

### 4.2 Reglas

- Las carpetas se abren siempre con `Folder.READ_ONLY` (comando IMAP
  `EXAMINE`), y los cuerpos se leen con `BODY.PEEK`. Ninguna operación puede
  cambiar el estado del servidor. Esta garantía se testea con GreenMail.
- **Primer sync:** solo los correos de los últimos 6 meses (`SEARCH SINCE`),
  configurable. Funciona por lotes y se puede reanudar, porque el cursor se
  guarda tras cada lote.
- **Cambio de UIDVALIDITY:** se marcan como borrados (`deleted_at`) los correos
  de la carpeta con el UIDVALIDITY anterior y se resincroniza desde cero.
- **Dirección del correo:** es `OUTBOUND` si el remitente está en las
  direcciones configuradas de cualquier cuenta, independientemente de la
  carpeta.
- **Limpieza del cuerpo:** si hay parte `text/plain` se usa; si no, el HTML se
  pasa por Jsoup. `body_stripped` elimina las líneas citadas (`>`), los bloques
  "El … escribió:" y "On … wrote:", los reenvíos y la firma (`-- `, "Enviado
  desde mi iPhone"…). Se trunca a 100 000 caracteres (el límite de un tsvector
  es 1 MB).
- **Adjuntos:** se guardan solo los metadatos y la ruta MIME. Se extrae el
  texto de los PDF de menos de 10 MB, que se agrega en `email.attachment_text`
  junto con los nombres de fichero, partidos por `_ - .`.
- **Gmail:** se sincronizan `INBOX` y `[Gmail]/Sent Mail`. El nombre exacto de
  Enviados se detecta por el atributo especial `\Sent` (RFC 6154), porque
  depende del idioma de la cuenta.

## 5. Esquema de base de datos

Ver `src/main/resources/db/migration/`. Ambas migraciones se han probado contra
un Postgres real.

- **V1 (`V1__mail_index.sql`)**: `mail_account`, `mail_folder` (cursor IMAP),
  `email`, `email_attachment` y `sync_run`.
- **V2 (`V2__agent.sql`)**: `conversation`, `conversation_turn`, `agent_run`
  (tokens y coste agregados) y `agent_tool_call` (auditoría).

Notas sobre las consultas:

- **Búsqueda full-text:** configuración `simple` con `immutable_unaccent` para
  no aplicar reglas de un solo idioma a correos en castellano, catalán e
  inglés. El tsvector se pondera: asunto (A), remitente (B), cuerpo (C) y
  adjuntos (D).
- **Remitente y asunto aproximados:** `ILIKE` sobre expresiones con índice de
  trigramas (`pg_trgm`).
- **Arrays:** las consultas usan `references_ids @> ARRAY[:id]`, no
  `:id = ANY(references_ids)`, porque solo la primera forma usa el índice GIN.
  Está comprobado con `EXPLAIN`.
- **Hilos:** CTE recursiva sobre `message_id`, `in_reply_to` y
  `references_ids`, sin filtrar por cuenta (el correo se envía desde Gmail y la
  respuesta puede llegar a iCloud). Si no hay cabeceras útiles, el respaldo es
  `subject_normalized` dentro de una ventana de ±30 días.
- **No respondidos:** correos `OUTBOUND` sin ningún `INBOUND` posterior que
  los referencie en `in_reply_to` o `references_ids`.

## 6. Herramientas del agente

Las definiciones están en `src/main/resources/agent/tools/*.json` (formato de
la API: `name`, `description`, `input_schema`). `ToolRegistry` las carga al
arrancar y añade dinámicamente un `enum` con los IDs de cuenta configurados al
parámetro `account`. Un test comprueba que cada JSON tiene una clase
`MailTool` correspondiente y al revés.

Los nombres de herramienta van en `snake_case`. Las descripciones están en
inglés porque el modelo las sigue mejor; el idioma de la respuesta lo decide
el system prompt (el mismo que use el usuario).

| Herramienta | Parámetros | Devuelve |
|---|---|---|
| `search_emails` | `account?`, `from?`, `to?`, `subject?`, `text?`, `direction?`, `since?`, `until?`, `unread_only?`, `has_attachments?`, `limit` (1–25, 10 por defecto) | Lista de resúmenes: id, cuenta, dirección, remitente, destinatarios (máx. 3), asunto, fecha local, leído, tiene adjuntos, fragmento de 200 caracteres |
| `get_email` | `id` | Cabeceras, `body_stripped` truncado a 4 000 caracteres y la lista de adjuntos (id, nombre, tipo, tamaño) |
| `get_thread` | `email_id` | Mensajes en orden cronológico (máx. 20) con dirección y fragmento de 500 caracteres |
| `find_unanswered` | `since?` (−14 días), `until?`, `account?`, `to?`, `min_age_hours?`, `limit` | Enviados sin respuesta, con los días de espera |
| `get_day_digest` | `date`, `account?`, `max_important` | Totales, correos importantes con fragmento y el resto agrupado por remitente con su recuento |

Cambios respecto al planteamiento inicial:

- `summarizeDay` pasa a llamarse `get_day_digest`. La herramienta no resume:
  prepara material compacto y el resumen lo escribe el modelo.
- `searchEmails` gana los parámetros `direction`, `to` y `has_attachments`.
  Son los que hacen falta para "el correo que envié sobre la oferta X" y para
  "la factura de la luz".

### 6.1 Formato de los resultados

Las herramientas devuelven JSON compacto con las fechas en hora local y
offset. El contenido escrito por terceros siempre va en campos de texto, nunca
en las claves.

Ejemplo de resultado protegido:
```json
{"id": 812, "date": "2026-09-30T10:12+02:00", "account": "gmail", "protected": true}
```

## 7. Bucle del agente

```
pregunta → comprobar presupuesto diario
        → mensajes = system + últimos N turnos + pregunta
        → repetir hasta max_iterations (6 por defecto):
              respuesta = LlmClient.create(mensajes, tools)
              registrar tokens y coste en agent_run
              si stop_reason ≠ tool_use → terminar
              ejecutar cada tool_use (con validación de los argumentos contra el schema)
              añadir assistant(tool_use) + user(tool_result) y continuar
        → si se alcanza el límite: última llamada sin herramientas pidiendo una
          respuesta con lo que tenga (estado MAX_ITERATIONS)
        → guardar turnos USER y ASSISTANT y devolver AgentReply(texto, referencias)
```

- **Errores de herramienta:** se devuelven al modelo como `tool_result` con
  `is_error: true` y un mensaje genérico (sin trazas), para que pueda corregir
  los argumentos.
- **Prompt caching:** `cache_control` en el system prompt y en la última
  definición de herramienta. El coste se calcula con precios configurables por
  modelo, incluidas la lectura y la escritura de caché.
- **Límites configurables:** `max-iterations`, `max-tokens` (1 024),
  `history-turns` (6) y `daily-budget-usd` (1,00). Si se supera el presupuesto,
  el bot avisa y no llama a la API.
- **System prompt** (se redacta en la fase 3): fecha y hora actuales en
  Europe/Madrid, la lista de cuentas, que el contenido de los correos son datos
  no fiables, que no puede realizar acciones, que debe citar correos con
  `[[mail:ID]]` y adjuntos con `[[att:ID]]`, y que si una búsqueda no da
  resultados debe reformular (por ejemplo, "luz" → "electricidad", o buscar por
  la comercializadora como remitente).

## 8. Telegram

- **Lista blanca:** cualquier actualización de un chat ID no autorizado se
  ignora en silencio y se registra en el log. Mensajes de grupos y reenvíos
  también se ignoran.
- **Referencias en la respuesta:** `ReplyReferences` sustituye cada
  `[[mail:ID]]` y `[[att:ID]]` por una referencia numerada y genera botones
  inline (el `callback_data` como `m:812` o `a:3301`, dentro del límite de 64
  bytes). Solo se aceptan IDs que alguna herramienta haya devuelto en esa misma
  ejecución, así que el modelo no puede inventar referencias.
- **Botón "ver correo":** lee de Postgres y muestra el contenido sin pasar por
  Claude. Funciona también con correos protegidos.
- **Botón "descargar adjunto":** `AttachmentDownloader` abre la carpeta en solo
  lectura, comprueba que UIDVALIDITY no ha cambiado, descarga la parte MIME
  con `BODY.PEEK` y la envía con `sendDocument` (límite de 50 MB). Si el
  correo ya no existe, responde "ya no disponible".
- **Salida:** texto plano con `disable_web_page_preview=true` en todos los
  mensajes, para cerrar el canal de exfiltración por vista previa de enlaces.
- **Comandos:** `/reset` (nueva conversación), `/coste` (gasto de hoy y del
  mes), `/estado` (último sync por carpeta y errores) y `/help`.

## 9. Configuración (.env)

Los nombres de las variables de entorno siguen el relaxed binding de Spring
para listas.

```
MAILAGENT_ACCOUNTS_0_ID=gmail
MAILAGENT_ACCOUNTS_0_HOST=imap.gmail.com
MAILAGENT_ACCOUNTS_0_PORT=993
MAILAGENT_ACCOUNTS_0_USERNAME=...
MAILAGENT_ACCOUNTS_0_PASSWORD=...            # contraseña de aplicación
MAILAGENT_ACCOUNTS_0_ADDRESSES=a@gmail.com,b@gmail.com
MAILAGENT_ACCOUNTS_0_FOLDERS=INBOX,\Sent     # \Sent = detectar por atributo especial
MAILAGENT_SYNC_INITIAL_WINDOW=P6M
MAILAGENT_PRIVACY_EXCLUDED_DOMAINS=caixabank.es,bbva.es,gencat.cat
MAILAGENT_PRIVACY_EXCLUDED_ADDRESSES=
MAILAGENT_AGENT_MODEL=claude-haiku-4-5
MAILAGENT_AGENT_MAX_ITERATIONS=6
MAILAGENT_AGENT_DAILY_BUDGET_USD=1.00
MAILAGENT_TIMEZONE=Europe/Madrid
ANTHROPIC_API_KEY=...
TELEGRAM_BOT_TOKEN=...
TELEGRAM_ALLOWED_CHAT_IDS=123456789
```

Una exclusión de dominio cubre también sus subdominios (`gencat.cat` incluye
`salut.gencat.cat`).

## 10. Estrategia de tests

| Capa | Herramientas | Qué cubre |
|---|---|---|
| Dominio | JUnit + AssertJ, tests parametrizados | `BodyCleaner` con fixtures reales anonimizados (Gmail, Outlook, Apple Mail, respuestas en ES/CA/EN), `SubjectNormalizer`, `PrivacyPolicy`, `ImportancePolicy`, `CostCalculator`, `ReplyReferences` |
| Persistencia | Testcontainers (Postgres 17) + Flyway | Idempotencia, búsqueda full-text sin acentos, hilos entre cuentas, no respondidos, deduplicación |
| IMAP | GreenMail | Sync incremental, cambio de UIDVALIDITY, reconciliación, flags, y que el servidor no cambia de estado (sin `\Seen` ni borrados) |
| Agente | Mockito sobre `LlmClient` | Secuencias tool_use → tool_result → final, límite de iteraciones, presupuesto, errores de herramienta, redacción de protegidos |
| SDK de Anthropic | WireMock | Un test de contrato del adaptador (serialización de tools, lectura de `usage`) |
| Arquitectura | ArchUnit | Reglas de la sección 2.1 |
