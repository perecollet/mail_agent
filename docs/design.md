# Mail Agent · Design

Personal email agent for a homelab. It synchronizes several IMAP accounts **read-only** into PostgreSQL and
answers questions over Telegram using Claude with tools that **only query Postgres**.

This document is the source of truth for the design. When a change alters anything described here, update
this document in the same pull request.

## 1. Goals and non-goals

**Goals**

- Answer questions such as *"Did anyone reply to the email I sent on Monday about offer X?"*,
  *"Find my August electricity bill"*, *"What important emails did I get today?"*.
- Accounts: Gmail, iCloud and Movistar, all over IMAP (Gmail and iCloud with app passwords).
- Reply in the language the user writes in.
- Send attachments over Telegram on request.

**Non-goals**

- Writing to mailboxes in any way: no sending, moving, flagging, appending or deleting.
- Exposing anything to the internet.
- Proactive notifications: summaries are on demand only.

## 2. Stack

| Concern | Choice | Notes |
|---|---|---|
| Language | Java 25 | |
| Framework | Spring Boot 4.1.1 | |
| Database | PostgreSQL 17 + `unaccent` + `pg_trgm` | Own container, internal Docker network |
| Migrations | Flyway 12 | `src/main/resources/db/migration`; Flyway owns the schema |
| Mail index access | `JdbcClient` | See D3 |
| Agent data access | Spring Data JPA (Hibernate) | Added in phase 3; `ddl-auto: validate`. See D3 |
| IMAP | Eclipse Angus Mail (Jakarta Mail implementation) | `IMAPFolder` for UID, UIDVALIDITY, CONDSTORE |
| HTML → text | Jsoup 1.23 | |
| PDF → text | Apache PDFBox 3.0 | Text indexing only; binaries are never stored |
| LLM | Anthropic Java SDK (`com.anthropic:anthropic-java`) | Added in phase 3; own tool-use loop |
| Default model | `claude-haiku-4-5` | Configurable |
| Telegram | TelegramBots (`telegrambots-longpolling` + `telegrambots-client`) | Added in phase 4; long polling, no inbound port |
| Tests | JUnit 5, Mockito, AssertJ, Testcontainers 2 (Postgres), GreenMail 2.1 (IMAP), ArchUnit 1.5 | |

Versions are pinned in `pom.xml`. Dependencies are added in the phase that first needs them.
`spring-boot-starter-mail` is deliberately excluded: the application must not be able to send email.

## 3. Package structure

One Maven module and one deployable. Four bounded contexts with a lightweight hexagonal architecture:
`domain` (framework-free), `application` (use cases and ports) and `adapter` (inbound and outbound).

```
dev.perecollet.mailagent
├── MailAgentApplication.java          @SpringBootApplication + @ConfigurationPropertiesScan
│
├── config/                            cross-cutting configuration
│   ├── MailAgentProperties            mailagent.* (accounts, sync, timezone)
│   └── ClockConfig                    Clock in mailagent.timezone (injectable in tests)
│
├── mail/                              read side: queries over the indexed mailbox
│   ├── domain/
│   │   ├── EmailSummary, EmailDetail, AttachmentRef, ThreadView, DayDigest   (read projections)
│   │   ├── PrivacyPolicy              is a sender protected? → decides redaction
│   │   └── ImportancePolicy           non-AI importance heuristic
│   ├── application/
│   │   ├── port/in/   SearchEmails, GetEmail, GetThread, FindUnanswered, GetDayDigest
│   │   ├── port/out/  EmailQueryRepository
│   │   └── service/   MailQueryService
│   └── adapter/out/persistence/
│       └── JdbcEmailQueryRepository
│
├── sync/                              IMAP → Postgres ingestion (no AI)
│   ├── domain/
│   │   ├── FolderRole, Direction, SyncKind, ExtractionStatus                 (enums)
│   │   ├── FolderRef, MailboxStatus, MailAccount, MailAccounts, SyncRunResult (values)
│   │   ├── FolderCursor               UIDVALIDITY + last UID of one folder (immutable)
│   │   ├── OwnAddresses               every address that is "the user", across accounts
│   │   ├── FetchedMessage, FetchedAttachment   message as read from the server, free of Jakarta Mail types
│   │   ├── EmailRecord                normalized email, ready to store
│   │   ├── MessageNormalizer          FetchedMessage → EmailRecord
│   │   ├── ReplyStripper              removes quoted history and signature from plain text
│   │   ├── SubjectNormalizer          strips Re:/Fwd:/RV:… prefixes; detects forwards
│   │   └── MessageIds                 normalizes Message-ID / In-Reply-To / References
│   ├── application/
│   │   ├── port/out/  MailServerReader, MailboxSession, EmailStore, SyncStateRepository
│   │   └── service/   MailboxSyncService, SyncSettings
│   └── adapter/
│       ├── in/scheduler/   SyncScheduler                      (@Scheduled)
│       └── out/
│           ├── imap/         AngusMailServerReader, MimeParser, HtmlToText, PdfTextExtractor, AttachmentFetcher
│           └── persistence/  JdbcEmailStore, JdbcSyncStateRepository
│
├── agent/                             tool-use loop
│   ├── domain/
│   │   ├── AgentRun, RunStatus, TokenUsage, CostCalculator
│   │   ├── Conversation, Turn
│   │   └── ReplyReferences            extracts and validates [[mail:ID]] / [[att:ID]]
│   ├── application/
│   │   ├── port/in/   AskAgent
│   │   ├── port/out/  LlmClient, ConversationRepository, RunRepository
│   │   ├── service/   AgentService    (loop, iteration cap, daily budget)
│   │   └── tools/     ToolRegistry, MailTool (interface) + one class per tool
│   └── adapter/out/
│       ├── anthropic/    AnthropicLlmClient   (the only code that knows the SDK)
│       └── persistence/  JPA entities + Spring Data repositories implementing the out ports
│
└── telegram/                          user interface
    ├── application/port/out/  AttachmentDownloader   (implemented by sync.adapter.out.imap)
    └── adapter/in/
        ├── MailAgentBot               long polling, chat ID allowlist
        ├── CommandHandler             /reset, /coste, /estado, /help
        ├── CallbackHandler            "view email" and "download attachment" buttons
        └── ReplyRenderer              plain text, no link previews, buttons
```

Packages are created when their first class is written; the tree above is the target.

### 3.1 Dependency rules (enforced with ArchUnit)

- `..domain..` does not depend on Spring, Jakarta Mail, JDBC, JPA, the Anthropic SDK or TelegramBots.
- `..application..` does not depend on `..adapter..`.
- Only `sync.adapter.out.imap` imports `jakarta.mail..` and `org.eclipse.angus..`.
- Only `agent.adapter.out.anthropic` imports `com.anthropic..`.
- Only `telegram.adapter..` imports `org.telegram..`.
- JPA (`jakarta.persistence..`) is only used in `agent.adapter.out.persistence`.
- Contexts talk to each other only through `application.port.in` (and `telegram` through its out port).
- No class anywhere calls Jakarta Mail methods that change server state (`Transport`, `setFlag(s)`,
  `copyMessages`, `moveMessages`, `appendMessages`, `expunge`, `Folder.open(READ_WRITE)`).

## 4. Design decisions

**D1. One process, no queues.** The synchronizer and the bot live in the same JVM. A failing account does not
block the others: each folder runs in its own `try` and records a `FAILED` row in `sync_run`.

**D2. Lightweight CQRS.** `sync` writes and `mail` reads the same tables. An email index has no rich domain
model; read projections (`EmailSummary`, `ThreadView`…) are immutable records.

**D3. Mixed persistence.**
- **Mail index (`sync`, `mail`) → `JdbcClient`.** Its core is Postgres-specific SQL: `ON CONFLICT DO NOTHING`,
  `websearch_to_tsquery`, trigram indexes, recursive CTEs, array operators. With JPA these would all be
  native queries on behaviour-less entities. Explicit SQL is tested against a real Postgres with Testcontainers.
- **Agent data (`agent`) → JPA.** Conversations, turns, runs and tool-call audit are plain CRUD with simple
  relationships, where JPA removes boilerplate.
- **Flyway owns the schema in both cases.** `spring.jpa.hibernate.ddl-auto` is `validate`; Hibernate never
  creates or alters tables. Entities map onto the tables of `V2__agent.sql`.

**D4. Idempotency through the IMAP natural key.** `UNIQUE (folder_id, uid_validity, uid)` with
`ON CONFLICT DO NOTHING`. Re-running a sync or retrying after a failure never duplicates anything. A batch of
emails and the advanced folder cursor are stored in the same transaction.

**D5. Deduplication at query time, not at ingestion.** An email sent to oneself exists in INBOX and in Sent.
Both rows are kept (they are two distinct IMAP facts) and queries use `DISTINCT ON (message_id)`.

**D6. Exclusions evaluated at query time.** The list of protected domains and addresses lives in configuration
and `PrivacyPolicy` applies it when building each result. Changing the list takes effect immediately, without
reindexing.

**D7. Importance computed at query time.** `ImportancePolicy` combines signals stored at ingestion
(`has_list_unsubscribe`, `is_provider_important`, direct recipient) with "known correspondent" (someone the
user has written to), resolved with a subquery over `OUTBOUND` emails.

**D8. Conversation history stores text only.** `tool_use` and `tool_result` blocks exist only inside a run.
Later turns never carry email content, which saves tokens and keeps an injection from persisting in history.

**D9. Single-threaded scheduler.** `spring.task.scheduling.pool.size: 1`, so the incremental sync and the
daily reconciliation never run concurrently, never open two connections to the same account and never race
on a folder cursor. `spring.threads.virtual.enabled` stays off: with it, every scheduled task would run on its
own virtual thread and they could overlap.

**D10. Direction is decided by the sender, not by the folder.** An email is `OUTBOUND` if its sender is one of
the configured addresses of any account (`OwnAddresses`), wherever it is stored. This also covers sending
from one account and receiving the reply in another.

## 5. Synchronizer

### 5.1 Cycles

| Cycle | When | What it does |
|---|---|---|
| Incremental | `fixedDelay` every 15 min (first run 10 s after startup) | New messages by UID, in batches of 50 |
| Flags | Same cycle, right after the incremental sync of each folder | Refreshes `\Seen` for emails received in the last 7 days |
| Reconciliation | Daily, 04:00 in `mailagent.timezone` | `UID SEARCH ALL`; marks `deleted_at` on UIDs no longer on the server |
| Run cleanup | Daily, with reconciliation | Deletes `sync_run` rows older than 30 days |

`fixedDelay` counts from the end of the previous cycle, so a long first import never overlaps the next cycle.
The cron uses the `zone` attribute of `@Scheduled` with `mailagent.timezone`: the container runs in UTC.

### 5.2 Incremental sync of one folder

```
open session (one IMAP connection per account per cycle)
resolve configured folders (\Sent → real name via RFC 6154 special-use attribute)
for each folder:
    cursor  = stored cursor, or an uninitialized one the first time
    status  = server UIDVALIDITY, UIDNEXT, message count
    if cursor not initialized          → cursor.resetTo(status.uidValidity)
    else if UIDVALIDITY changed        → mark the folder's stored emails as deleted, cursor.resetTo(new)
    pending = cursor.lastUid == 0 ? UIDs received since today − initial window   (SEARCH SINCE)
                                  : UIDs after cursor.lastUid
    pending = sorted, distinct, only uid > cursor.lastUid
    for each batch of 50:
        fetch → normalize → store batch + advanced cursor (one transaction)
    if the server reported UIDNEXT     → cursor.advancedTo(uidNext − 1)
    persist cursor; record sync_run
```

Rules:

- **Read-only.** Folders are opened with `Folder.READ_ONLY` (IMAP `EXAMINE`) and bodies are fetched with
  `BODY.PEEK`. No operation can change server state; GreenMail integration tests assert it.
- **The cursor never moves backwards.** `FolderCursor.advancedTo` ignores lower or equal UIDs. Servers answer
  `UID FETCH n:*` with the last message even when its UID is below `n`, so pending UIDs are always filtered by
  `uid > lastUid` too.
- **Jump to `UIDNEXT − 1` after a successful pass.** Messages below `UIDNEXT` that were outside the initial
  window will never be pending, so the next cycle does not search the whole history again.
- **First sync:** last 6 months only (configurable), in resumable batches: the cursor is stored after each batch.
- **UIDVALIDITY change:** the folder's emails under the old UIDVALIDITY get `deleted_at`, and the folder is
  imported again from scratch.
- **Gmail:** `INBOX` and the folder flagged `\Sent` (`[Gmail]/Sent Mail` in English accounts). The real name
  depends on the account language, hence special-use detection.

### 5.3 Flags

Read status is refreshed for emails received within `flags-window` (7 days): `UID FETCH … FLAGS` (with
`CHANGEDSINCE` where CONDSTORE is supported). Read status may lag up to one cycle (~15 minutes); this is accepted.

### 5.4 Reconciliation

`UID SEARCH ALL` per folder; stored, non-deleted UIDs of the current UIDVALIDITY that are not on the server get
`deleted_at`. Safety rules:

- Skipped when UIDVALIDITY changed: the next incremental sync handles it.
- If the server lists no UIDs for a folder whose message count is not zero, the run fails instead of marking
  everything as deleted.

### 5.5 Normalization (`MessageNormalizer`)

- **Body:** `text/plain` part if present; otherwise HTML converted with Jsoup keeping line structure.
  Quote containers in HTML (`blockquote`, `div.gmail_quote`, Outlook `#divRplyFwdMsg`…) are removed before
  conversion, which is more reliable than guessing from text.
- **`body_text`:** full clean text. **`body_stripped`:** without quoted history and signature; this is what the
  model sees. `ReplyStripper` recognizes English, Spanish and Catalan formats (Gmail, Apple Mail, Outlook,
  mobile clients) and is conservative:
  - Forwards (detected by subject prefix) are never cut: the forwarded content is the message.
  - If cutting would leave nothing, the original text is kept.
- **Thread headers:** angle brackets removed, trimmed, lower-cased; `References` keeps the last 50 ids.
- **Subject:** `subject_normalized` without reply/forward prefixes (EN, ES, CA, FR, DE, PT, IT) and `[TAG]`s.
- **Addresses:** lower-cased and deduplicated.
- **Safety for Postgres:** NUL characters removed; bodies truncated to 100,000 characters without splitting a
  surrogate pair (`body_truncated` is set). A tsvector is limited to 1 MB.
- **Attachments:** metadata and MIME part path only. Text is extracted from PDFs under 10 MB (first 20 pages).
  File names (split on `_ - .`, so `Factura_agosto_2026.pdf` matches *agosto*) and extracted text are
  aggregated into `email.attachment_text`, so one search can combine body and attachment terms.

## 6. Database

Migrations live in `src/main/resources/db/migration/` and have been applied on PostgreSQL 17.
An applied migration is never edited: every change is a new `V<n>__<description>.sql`.

- **V1 (`V1__mail_index.sql`)** — mail index, accessed with `JdbcClient`:
  - `mail_account`: account ids only; connection settings live in the environment.
  - `mail_folder`: folder, role (`INBOX`/`SENT`/`OTHER`) and IMAP cursor (`uid_validity`, `last_uid`,
    `highest_modseq`, `last_synced_at`, `last_reconciled_at`).
  - `email`: one row per (folder, UIDVALIDITY, UID); thread headers, direction, addresses, subject, dates
    (`sent_at` from the `Date` header, `received_at` from INTERNALDATE), flags and signals, `body_text`,
    `body_stripped`, `attachment_text`, generated `search_vector`, `deleted_at`.
  - `email_attachment`: metadata, MIME `part_path`, `extraction_status` (`DONE`/`SKIPPED`/`FAILED`).
  - `sync_run`: history of runs per folder and kind.
- **V2 (`V2__agent.sql`)** — agent data, accessed with JPA (phase 3):
  - `conversation`: one open conversation per chat (partial unique index); `/reset` closes it.
  - `conversation_turn`: `USER` and `ASSISTANT` text only (D8).
  - `agent_run`: one question; iterations, tokens (input, output, cache read/write) and cost. `id` is a UUID
    assigned by the application.
  - `agent_tool_call`: audit of every tool call; `input` is `jsonb` (mapped with `@JdbcTypeCode(SqlTypes.JSON)`).

### 6.1 Query notes

- **Full text:** `simple` configuration with an immutable wrapper around `unaccent`, so Spanish, Catalan and
  English emails are not processed with the rules of a single language. The tsvector is weighted: subject (A),
  sender (B), stripped body (C), attachments (D).
- **Fuzzy sender and subject:** `ILIKE` over expressions with trigram indexes (`"endesa"` matches
  `Endesa Energía XXI <no-reply@endesa.es>`).
- **Arrays:** use `references_ids @> ARRAY[:id]`, not `:id = ANY(references_ids)`: only the first form uses the
  GIN index (checked with `EXPLAIN`).
- **Threads:** recursive CTE over `message_id`, `in_reply_to` and `references_ids`, across accounts. Fallback
  when headers are missing: `subject_normalized` within ±30 days.
- **Unanswered:** `OUTBOUND` emails with no later `INBOUND` email referencing them in `in_reply_to` or
  `references_ids`.
- Every query excludes rows with `deleted_at`.

## 7. Agent tools

Definitions live in `src/main/resources/agent/tools/*.json` (API format: `name`, `description`,
`input_schema`). `ToolRegistry` loads them at startup and adds an `enum` with the configured account ids to every
`account` parameter. A test checks that every JSON has a `MailTool` implementation and vice versa.
Descriptions are in English; the reply language is set by the system prompt.

| Tool | Parameters | Returns |
|---|---|---|
| `search_emails` | `account?`, `from?`, `to?`, `subject?`, `text?`, `direction?`, `since?`, `until?`, `unread_only?`, `has_attachments?`, `limit` (1–25, default 10) | Summaries: id, account, direction, sender, up to 3 recipients, subject, local date, read, has attachments, 200-char snippet |
| `get_email` | `id` | Headers, `body_stripped` truncated to 4,000 chars, attachments (id, name, type, size) |
| `get_thread` | `email_id` | Messages oldest first (max 20) with direction and 500-char snippet |
| `find_unanswered` | `since?` (default −14 days), `until?`, `account?`, `to?`, `min_age_hours?`, `limit` | Sent emails without a reply, with days waiting |
| `get_day_digest` | `date`, `account?`, `max_important` | Totals, important emails with snippet, the rest grouped by sender with counts |

Dates are calendar dates (`YYYY-MM-DD`) in the user's timezone, both bounds inclusive. `get_day_digest` does not
summarize: it returns compact material and the model writes the summary.

### 7.1 Result format

Compact JSON; dates in local time with offset. Text written by third parties is always in string values, never
in keys. A protected email returns only:

```json
{"id": 812, "date": "2026-09-30T10:12+02:00", "account": "gmail", "protected": true}
```

## 8. Agent loop

```
question → check daily budget
        → messages = system prompt + last N turns + question
        → repeat up to max_iterations (default 6):
              response = LlmClient.create(messages, tools)
              add tokens and cost to agent_run
              if stop_reason ≠ tool_use → done
              run each tool_use (arguments validated against the schema), audit in agent_tool_call
              append assistant(tool_use) + user(tool_result) and continue
        → if the cap is reached: one last call without tools asking for an answer with what it has
          (status MAX_ITERATIONS)
        → store USER and ASSISTANT turns, return AgentReply(text, references)
```

- **Tool errors** go back to the model as `tool_result` with `is_error: true` and a generic message (no stack
  traces), so it can correct its arguments.
- **Prompt caching:** `cache_control` on the system prompt and on the last tool definition. Cost is computed
  from configurable per-model prices, cache reads and writes included.
- **Limits (configurable):** `max-iterations` (6), `max-tokens` (1,024), `history-turns` (6),
  `daily-budget-usd` (1.00). Once today's spend reaches the budget the bot says so and makes no API call.
- **System prompt:** current date and time in `mailagent.timezone`, the account list, that email content is
  untrusted data, that it cannot take actions, that it cites emails as `[[mail:ID]]` and attachments as
  `[[att:ID]]`, and that it should rephrase empty searches (e.g. "luz" → "electricidad", or the utility's name
  as sender).
- **Testing:** the loop is tested with Mockito against the `LlmClient` port; the real API is never called.

## 9. Telegram

- **Dedicated bot**, created with @BotFather. A token can only be consumed by one long-polling client, so the
  existing n8n bot is not reused.
- **Allowlist:** updates from chat IDs outside `mailagent.telegram.allowed-chat-ids` are ignored silently and
  logged. Group messages and forwards are ignored as well.
- **References:** `ReplyReferences` replaces each `[[mail:ID]]` / `[[att:ID]]` with a numbered reference and an
  inline button (`callback_data` such as `m:812` or `a:3301`, within the 64-byte limit). Only ids returned by a
  tool in the same run are accepted, so the model cannot make references up.
- **"View email" button:** reads from Postgres and shows the content without going through Claude. Works for
  protected emails too.
- **"Download attachment" button:** `AttachmentDownloader` opens the folder read-only, checks UIDVALIDITY is
  unchanged, fetches the MIME part with `BODY.PEEK` and sends it with `sendDocument` (50 MB limit). If the email
  is no longer on the server it answers "no longer available". The model is never involved.
- **Output:** plain text with link previews disabled on every message, which closes the exfiltration channel of
  link previews.
- **Commands:** `/reset` (new conversation), `/coste` (today's and this month's spend), `/estado` (last sync per
  folder and errors), `/help`.

## 10. Security model

Email content is written by third parties, so any email may carry a prompt injection. The design limits what
an injection could achieve:

| Risk | Mitigation |
|---|---|
| Changing or sending mail | No write operation exists: read-only IMAP, no mail-sending dependency, no writing tool, ArchUnit rule |
| Leaking sensitive email to the API | Protected senders: content, sender and subject never reach the model |
| Exfiltration through a crafted URL | Plain-text replies, link previews disabled |
| Invented references or files | Only ids returned by a tool in the same run become buttons; attachments are sent by the bot |
| Injection persisting across questions | History stores text turns only (D8) |
| Runaway cost | Iteration cap, `max-tokens`, daily budget, per-run audit |
| Unauthorized access | Allowlisted chat IDs only; long polling; no published ports; other access through WireGuard |
| Credential leaks | Secrets only in `.env` (git-ignored, `chmod 600`); records holding credentials override `toString()` |

## 11. Configuration

Non-secret defaults live in `application.yaml`. Secrets and per-installation values come from environment
variables (the `.env` file, loaded by Docker Compose or by the shell), bound through Spring's relaxed binding:
`MAILAGENT_ACCOUNTS_0_ID` → `mailagent.accounts[0].id`. `.env.example` documents every variable.

**Database** (the same variables configure the Postgres container):

```
POSTGRES_DB=mailagent
POSTGRES_USER=mailagent
POSTGRES_PASSWORD=...
MAILAGENT_DB_HOST=postgres      # localhost when running from the IDE
```

**Accounts** (one block per account, numbered from 0 without gaps):

```
MAILAGENT_ACCOUNTS_0_ID=gmail               # [a-z0-9-]{1,32}
MAILAGENT_ACCOUNTS_0_HOST=imap.gmail.com
MAILAGENT_ACCOUNTS_0_PORT=993               # default 993
MAILAGENT_ACCOUNTS_0_USERNAME=...
MAILAGENT_ACCOUNTS_0_PASSWORD=...           # app password
MAILAGENT_ACCOUNTS_0_ADDRESSES=a@gmail.com,b@gmail.com
# MAILAGENT_ACCOUNTS_0_FOLDERS=INBOX,Enviados   # optional; default INBOX and the \Sent special-use folder
```

**Synchronizer** (`mailagent.sync.*`, defaults in `application.yaml`): `enabled`, `initial-delay` (PT10S),
`interval` (PT15M), `reconcile-cron` (`0 0 4 * * *`), `initial-window` (P6M), `batch-size` (50), `flags-window`
(P7D), `run-retention` (P30D), `imap-timeout` (PT30S), `max-body-chars` (100000), `max-attachment-size` (10MB).

**Added in later phases:**

```
# Phase 3 · privacy and agent
MAILAGENT_PRIVACY_EXCLUDED_DOMAINS=bank.example,health.example   # a domain covers its subdomains
MAILAGENT_PRIVACY_EXCLUDED_ADDRESSES=
ANTHROPIC_API_KEY=...
MAILAGENT_AGENT_MODEL=claude-haiku-4-5
MAILAGENT_AGENT_MAX_ITERATIONS=6
MAILAGENT_AGENT_DAILY_BUDGET_USD=1.00

# Phase 4 · Telegram
TELEGRAM_BOT_TOKEN=...
MAILAGENT_TELEGRAM_ALLOWED_CHAT_IDS=123456789
```

All properties are bound to `@ConfigurationProperties` records with Bean Validation, so a missing password or an
empty account list fails at startup. Each phase adds its own record (e.g. `mailagent.agent`, `mailagent.telegram`).

## 12. Testing strategy

| Layer | Tools | Covers |
|---|---|---|
| Domain | JUnit 5, AssertJ, parameterized tests | `FolderCursor`, `OwnAddresses`, `ReplyStripper` with anonymized real-world fixtures (Gmail, Outlook, Apple Mail; ES/CA/EN), `SubjectNormalizer`, `MessageIds`, `MessageNormalizer`, `PrivacyPolicy`, `ImportancePolicy`, `CostCalculator`, `ReplyReferences` |
| Sync service | Mockito on the out ports | Initial window, incremental sync, UIDVALIDITY change, batching, cursor never moving back, failure isolation per folder |
| Persistence | Testcontainers (Postgres 17) + Flyway | Idempotency, accent-insensitive full text, cross-account threads, unanswered, deduplication, JPA mappings validate against the schema |
| IMAP | GreenMail | Incremental sync, UIDVALIDITY change, reconciliation, flags, and **server state unchanged** (no `\Seen`, nothing deleted) |
| Agent | Mockito on `LlmClient` | tool_use → tool_result → final sequences, iteration cap, budget, tool errors, redaction of protected emails |
| Anthropic adapter | WireMock | One contract test: tool serialization, reading `usage` |
| Architecture | ArchUnit | Rules in §3.1 |

- `*Test` are unit tests (Surefire, `./mvnw test`); `*IT` are integration tests (Failsafe, `./mvnw verify`,
  Docker required).
- Fixtures are anonymized: no real addresses, names or email content in the repository.
- `./mvnw verify` must pass before a change is merged.

## 13. Deployment (phase 5)

- Multi-stage `Dockerfile`; the application runs as a non-root user.
- Compose service plus its own Postgres 17 on an internal network (`internal: true`) with no exposed ports.
  Outbound traffic is only needed to the IMAP servers, `api.anthropic.com` and the Telegram API.
- Healthcheck on `/actuator/health` (details hidden), compatible with the homelab's autoheal container.
- Not published through Caddy or Cloudflare Tunnel.