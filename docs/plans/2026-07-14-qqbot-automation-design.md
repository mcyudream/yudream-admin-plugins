# QQ Bot Automation Design

## Goal

Reimplement the non-chat capabilities of `YuDream-QQBot-V1` as YuDream plugins: media link processing, group policy automation, join-request verification, QQ-to-Minecraft relay, and Minecraft operations. Keep the existing AI chatbot unchanged and retain the existing Minecraft server status trend chart.

## Scope

The implementation is split by domain instead of embedding a Python/NoneBot runtime in the platform.

| Area | Owner | Capability |
| --- | --- | --- |
| Minecraft status and operations | `minecraft-server` | Existing status and online trend, plus protected TPS and RCON operations with audit records. |
| Group automation | New `qqbot-automation` plugin | Per-connection/group policies, media link jobs, moderation rules, join-request verification, and QQ-to-Minecraft relay. |
| Protocol event bridge | Core framework and SPI | Publish QQ group-request events to plugin native handlers and expose a constrained decision operation. |
| AI chat | Existing `ai-chatbot` plugin | No new conversation, memory, or tool behavior. The platform AI service is used only when a configured join rule asks for an AI fallback decision. |

## Architecture

### Framework Boundary

`MilkyPluginEventDispatcher` currently accepts only `message_receive`; a QQ join request cannot reach a plugin. The core change publishes a normalized native `group_request` `PluginEvent`, preserving the provider payload only as native data. The SPI should add a narrow, typed group-request decision port instead of making business plugins depend on arbitrary protocol method strings.

The resulting plugin callback receives the connection, group channel, applicant QQ ID, request identifier, and verification text. It returns no synchronously visible value. The automation plugin submits a single approve or reject decision through the typed port, records the result, and is idempotent by request identifier.

### Group Automation Plugin

The new backend module is `yudream-plugins/yudream-plugin-qqbot-automation`; its remote frontend is `yudream-frontend/packages/plugin-qqbot-automation`.

The plugin owns the following persisted records:

- group policies, keyed by connection ID and channel ID;
- join-verification rules and decision audit entries;
- media jobs with source URL, parser provider, status, generated file/URL, and sanitized failure message;
- moderation and relay audit entries.

Administrators select connections and groups from `FrameworkServices.messaging()` rather than entering opaque IDs. Each group policy may independently enable media parsing, message relay, moderation actions, and join verification.

Join verification is rules-first: normalized text is compared against configured approved and rejected answers. If no rule decides the request and AI fallback is enabled, the plugin calls the host AI service with a constrained classification prompt and accepts only a structured allow/reject outcome. Every decision is audited; AI failures leave the request pending or follow an explicitly configured fail-closed rule.

Media parsing is an asynchronous job. Providers are configured by endpoint and credential reference, not copied from the legacy source. Downloaded files are stored through the host storage capability when available and sent only after job completion. The plugin never embeds third-party session cookies, internal asset hosts, API keys, or fixed output file names.

### Minecraft Operations

`minecraft-server` remains the source of truth for server endpoints and status history. The status page retains its existing online-player trend graph. RCON credentials are stored as protected settings; commands require a distinct operations permission, use an explicit per-server allowlist, apply timeouts, redact sensitive output, and create an audit record. TPS is a dedicated read operation rather than a free-form command.

QQ-to-Minecraft relay is configured by group policy and targets existing Minecraft server records. It rejects bot-originated messages, respects cooldown and quiet-hour policies, formats a bounded plain-text message, and uses the protected RCON service rather than duplicating server connection settings.

## User Surfaces And Permissions

All new surfaces are administrative. They use independent routes for group policies, media jobs, join-verification audit, and moderation/relay audit. Lists are server-paginated `FaTable` management pages with filtering, loading/error/empty states, destructive confirmations where applicable, and row-level detail actions.

Permissions are separate by operation: manage automation configuration, review media jobs, moderate groups, decide join requests, and operate Minecraft RCON. The backend independently enforces each permission. There is no ownership-bypassing `/me` endpoint because the requested capabilities are system and group administration.

## Validation Strategy

Tests are written before production code. Unit coverage verifies normalization, rule decisions, idempotency, cooldown/quiet-hour behavior, RCON allowlist checks, and media-job state transitions. Framework tests verify a group-request event is normalized and dispatched only to matching native handlers, and that group-request decisions carry the correct connection and request identity.

After implementation, run target Maven tests/packages, target frontend typechecks/builds, inspect `remoteEntry.js` in the final JARs, and run repository readiness verification. No legacy plaintext credentials are migrated; affected source credentials must be rotated outside this change.
