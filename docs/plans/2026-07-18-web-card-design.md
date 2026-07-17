# Web Card Plugin Design

**Date:** 2026-07-18
**Status:** Approved

## Goal

Build an independent `web-card` plugin that recognizes links from configured
websites in group messages, extracts structured content, renders an image card,
and replies to the originating group. The same plugin periodically discovers
new content from configured RSS, Sitemap, or list-page sources and proactively
delivers cards to configured groups.

Administrators own all configuration. Unconfigured domains are ignored. An AI
agent helps administrators generate and iteratively modify parsing rules and
card templates, but every proposed change requires review and confirmation
before it becomes a new draft version.

## Selected Architecture

Implement one standalone backend module and one remote frontend package:

- `yudream-plugins/yudream-plugin-web-card`
- `yudream-frontend/packages/plugin-web-card`

The plugin uses generic YuDream AI, interaction, messaging, rendering, storage,
connection, and group-option contracts. QQ/Milky is the first fully verified
messaging platform, but no domain type or configuration is QQ-specific.

Extending `qqbot-automation` was rejected because website parsing, template
authoring, scheduling, and version history are a separate business capability.
Splitting collection and template authoring into two plugins was rejected for
the first release because it would introduce an unnecessary cross-plugin API.

## Scope

The first release supports:

- automatic recognition of configured website links in group messages;
- scheduled discovery through RSS, Sitemap, and HTML or JSON list sources;
- HTML extraction with CSS selectors;
- JSON extraction with JSONPath;
- structured RSS and Sitemap parsing;
- public HTTP and administrator-supplied custom request headers;
- structured component templates and advanced sanitized HTML/CSS templates;
- image rendering and group delivery;
- an agent-assisted rule and template authoring workspace;
- immutable template and parsing-rule version history;
- site defaults with per-group delivery overrides;
- content and delivery deduplication, retries, and audit records.

The first release does not support browser automation, stored usernames and
passwords, JavaScript-dependent page acquisition, automatic handling of
unconfigured domains, or arbitrary administrator-authored parsing scripts.

## Administration Boundary

The plugin has only an administration surface protected by
`plugin:web-card:manage`. Group members trigger a published configuration by
sending a URL, but do not own or edit plugin records. There are no `/me/**`
endpoints and management permission never widens a user-scoped data query.

All authoritative connection, group, AI agent, and model values come from
host-provided option APIs. Administrators never have to type internal IDs when
the host can enumerate them.

## Domain Model

### Site Definition

`SiteDefinition` stores the display name, enabled state, exact allowed hosts,
access mode, secret-header reference, response type, redirect allowlist, and
default delivery policy. Host matching uses normalized host labels and never a
plain string suffix check.

### Parse Rule Set

`ParseRuleSet` defines source discovery, detail extraction, typed output fields,
canonical URL construction, content identity, and publish-time validation.
HTML rules use CSS selectors and explicit text or attribute extraction. JSON
rules use JSONPath. RSS and Sitemap inputs use structured parsers.

Parsing rules and template definitions are versioned together in a reproducible
configuration snapshot. Historical retries use the recorded version rather
than silently adopting current rules.

### Card Template And Version

`CardTemplate` identifies a template and points to its current draft and
published versions. `TemplateVersion` is immutable and contains the parsing
snapshot, field schema, structured layout or sanitized HTML/CSS source, preview
fixtures, origin, author, timestamp, and change summary.

Publishing changes the active published pointer. Rolling back creates a new
draft from an earlier version; it does not mutate history. Runtime automation
uses only a published version.

### Agent Session And Proposal

`AgentSession` binds conversation history to one site and one draft lineage.
`AgentProposal` contains a summary and allowlisted structured patch operations.
It is pending, applied, or rejected. Applying a proposal validates it on a
temporary copy and creates a new immutable draft version only after explicit
administrator confirmation.

### Group Binding

`GroupBinding` connects a site to a messaging connection and group. It inherits
site defaults and may override enabled state, published template version,
delivery window, cooldown, and rate limit. A group-message trigger replies only
to its originating group. Scheduled content is evaluated independently for
every enabled binding.

### Crawl Job

`CrawlJob` owns a source URL, source type, interval, initial item count, enabled
state, cursor, next execution time, and lease state. A newly enabled job sends
the most recent configurable number of items, defaulting to three, subject to
the target group's delivery window and rate limits.

### Content And Delivery Records

`ContentRecord` stores canonical URL, site-scoped content key, parsed field
snapshot, discovery time, configuration version, and processing state.
`DeliveryRecord` stores the target binding, template version, rendered artifact
reference, stage, attempt count, timestamps, and a sanitized failure summary.

## Secret Headers

Access modes are `PUBLIC_HTTP` and `CUSTOM_HEADERS`. Custom headers may contain
`Cookie`, `Authorization`, or API keys. Secret values are encrypted by a
plugin-scoped host secret service. Plugin documents store only a secret
reference and non-sensitive header metadata.

Management reads expose header names, masked display values, and presence only.
Updates explicitly retain, replace, or remove each value. Plaintext and
ciphertext never enter logs, audit descriptions, version diffs, preview
fixtures, API responses, or agent context.

Sensitive headers are sent only to an allowed host. They are stripped on a
cross-host redirect unless the destination is explicitly allowlisted for that
site. Fetching also enforces scheme, DNS, resolved-address, redirect, timeout,
response-size, and content-type restrictions to prevent SSRF and credential
exfiltration.

The current released SPI does not expose a plugin secret store. A stable
`PluginSecretStore` contract and host implementation must be added, tested, and
released in the core repository before this plugin consumes it. The plugin may
not import an internal encryption service or derive its own key from host
configuration.

## Message Trigger Flow

1. Receive a generic group message event.
2. Extract HTTP(S) URLs and normalize each URL.
3. Match an enabled site by exact allowed host.
4. Ignore unmatched domains without agent calls or administrator notifications.
5. Resolve the originating connection/group binding and effective policy.
6. Enforce enabled state, quiet window, cooldown, rate limit, and message
   idempotency.
7. Fetch the detail response with the site's access policy.
8. Parse typed fields using the published configuration snapshot.
9. Render an image from the binding's effective published template.
10. Reply to the originating message and persist the delivery outcome.

The message idempotency key is
`connectionId + channelId + messageId + canonicalUrl`.

## Scheduled Discovery Flow

1. Acquire the persistent lease for `jobId + scheduledTime`.
2. Fetch and parse the configured RSS, Sitemap, HTML list, or JSON list source.
3. Normalize detail URLs and derive site-scoped content keys.
4. On first execution, select the most recent `initialItemCount` items; the
   default is three.
5. On later executions, retain only unseen items.
6. Fetch and parse each detail into an immutable field snapshot.
7. Evaluate every enabled group binding independently.
8. Delay delivery when the group is outside its delivery window or rate limit.
9. Render and send the card, then record each target result independently.
10. Advance the source cursor and release the lease.

The content idempotency key is `siteId + canonicalUrl/contentKey`. The delivery
idempotency key is
`contentRecordId + groupBindingId + templateVersionId`.

An in-plugin lifecycle-managed scheduler is acceptable for the first release,
provided job state and leases are persistent and multi-instance-safe. A future
host scheduler SPI may replace this adapter without changing application or
domain code.

## Agent Authoring Flow

The agent receives sanitized sample content or extracted DOM/JSON, current
rules, field schema, template source, recent accepted changes, render errors,
and administrator feedback. It never receives secret headers.

Every response must conform to a versioned proposal schema containing a summary
and allowlisted patch operations. The server performs:

1. JSON Schema validation.
2. Target path and operation allowlist validation.
3. Application to an isolated temporary copy.
4. Sample re-fetch or fixture parse.
5. parsing-rule and template safety validation;
6. preview rendering;
7. rule, source, and visual diff presentation;
8. explicit administrator apply or reject;
9. immutable draft-version creation after apply.

The agent cannot publish, mutate a published version, change secrets, add an
unapproved host, send a message, or bypass a validation failure.

## Template Modes And Rendering Safety

Structured templates contain a bounded tree of title, image, text, tag, field
list, column, divider, and footer components. The server compiles this tree to
escaped HTML and CSS.

Advanced templates accept HTML/CSS but remove scripts, frames, objects, forms,
event-handler attributes, JavaScript URLs, external CSS imports, and arbitrary
remote fonts. Images must be proxied and validated resources from parsed
content. Variables are typed and contextually escaped; templates do not execute
arbitrary expressions.

Both modes limit source length, component depth/count, image count, output
dimensions, and rendering time. Publication requires at least one successful
parse and render fixture. Runtime HTML is rendered through the existing
`PluginRenderService.html` capability.

Preview supports a live sample URL and stored sanitized field fixtures. Stored
fixtures make version comparison reproducible when the remote page changes or
disappears.

## Processing States And Failure Policy

The observable delivery pipeline is:

`DISCOVERED -> FETCHED -> PARSED -> RENDERED -> DELIVERED`

Each failure records the stage, safe summary, attempts, site, content,
configuration version, and target binding.

- Network and `5xx` fetch failures use bounded exponential retry.
- `401` and `403` indicate that configured credentials may need replacement.
- `404` is not retried automatically.
- Parse failures record missing field paths and a sanitized response summary.
- Render failures retain the configuration version and field fixture.
- Send failures retry the target delivery without re-fetching content.
- Quiet-window and rate-limit outcomes enter a delayed persistent queue.
- Restart recovery resumes incomplete deliveries under the same idempotency key.
- One group's failure does not block another group.

Configuration publication, secret replacement, manual retries, and lifecycle
changes produce audit records without secret or response-body leakage.

## Backend Responsibilities

The Java module follows plugin-local DDD boundaries:

- `domain`: aggregates, values, policies, repositories, version and idempotency
  invariants;
- `application`: site management, rule tests, template authoring, agent
  proposals, publishing, discovery, delivery, and retry use cases;
- `infrastructure`: document repositories, HTTP fetcher, parsers, secret-store
  adapter, rendering adapter, persistent scheduler and lease adapter;
- `interfaces`: admin controllers, requests, responses, assemblers, and boundary
  validation;
- `bootstrap`: dependency construction, contribution registration, event
  subscription, scheduler startup, and lifecycle cleanup.

Controllers remain thin. Domain aggregates and persistence documents never
cross the HTTP boundary. Java `Long` and Snowflake identifiers are serialized
as strings.

## Management API

All endpoints are under `/admin/**` and require
`plugin:web-card:manage`:

- `/admin/sites`
- `/admin/sites/{id}/parse-rules`
- `/admin/sites/{id}/test-fetch`
- `/admin/sites/{id}/test-parse`
- `/admin/templates`
- `/admin/templates/{id}/versions`
- `/admin/templates/{id}/preview`
- `/admin/templates/{id}/publish`
- `/admin/templates/{id}/rollback`
- `/admin/agent-sessions`
- `/admin/agent-sessions/{id}/messages`
- `/admin/agent-proposals/{id}/apply`
- `/admin/agent-proposals/{id}/reject`
- `/admin/group-bindings`
- `/admin/crawl-jobs`
- `/admin/crawl-runs`
- `/admin/content-records`
- `/admin/deliveries`
- `/admin/deliveries/{id}/retry`
- `/admin/options/connections`
- `/admin/options/groups`
- `/admin/options/ai-agents`

Managed collections use server-side pagination. Sites, templates, bindings, and
jobs expose their complete create, edit, enable/disable, and delete/archive
lifecycle. Versions and delivery audit records are immutable.

## Frontend Routes And Composition

The remote frontend exposes separate administration routes for:

- Sites
- Template Designer
- Agent Workspace
- Group Bindings
- Crawl Jobs
- Runs And Deliveries

Sites, bindings, jobs, and records use `FaPageHeader`, `FaPageMain`,
`FaSearchBar`, `FaTable`, and `FaPagination`. Focused short forms use
`FaModal`; complex parsing and template workflows use route pages.

The template designer is an unframed responsive two-column workspace: editing
on the left and a stable-dimension preview on the right. Narrow layouts use an
edit/preview segmented view. It does not nest cards or combine unrelated
management workflows in tabs.

The frontend uses the injected `@yudream/plugin-sdk` client and explicit
`@yudream/components` imports, with Arco only for controls that the YuDream
library does not provide.

## Host Contract Dependencies

Reuse released SPI capabilities for AI, interaction events, messaging,
rendering, plugin document/file storage, and connection/group options. Add and
release the plugin-scoped secret-store contract before plugin implementation.

No plugin code may import host application, domain, infrastructure, Spring, or
bootstrap classes. The repository root owns the released SPI version, and the
frontend workspace catalog owns SDK and component versions.

## Verification

Domain tests cover exact host matching, URL normalization, policy inheritance,
initial-three selection, version publication/rollback, idempotency, and leases.
Parser contract tests use committed HTML, JSON, RSS, and Sitemap fixtures.

Security tests cover SSRF, DNS rebinding, redirect header stripping, response
limits, HTML/CSS sanitization, proposal allowlists, and absence of secrets from
API responses, logs, agent context, diffs, and audit data.

Authorization tests prove that unauthenticated and non-management callers
cannot access any management operation. Integration tests cover group URL to
image reply, scheduled discovery to multiple groups, delayed policy handling,
restart recovery, and isolated target retries.

Frontend tests cover proposal confirmation, diff and preview states, version
rollback, table pagination, destructive confirmation, and narrow-layout
behavior.

Completion requires:

- target frontend typecheck and build;
- target Maven tests and package;
- final JAR inspection for
  `META-INF/yudream-plugin/frontend/web-card/remoteEntry.js`;
- repository readiness verification;
- confirmation that only released host contracts are consumed.
