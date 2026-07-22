# Web Card Streaming Artifact Design

## Goal

Make the Web Card studio a true streaming Agent workspace with editable proposals, resilient structured-output handling, and no crawl job unless the operator explicitly requests collection or monitoring.

## Architecture

The host SPI exposes `runAgentStream` as a backward-compatible default method and the host implementation overrides it with the existing `AgentAppService.debugByCode` delta callback. The plugin creates a stream through a protected SDK POST, then exposes its buffered `PluginSseStream` through a protected GET endpoint so the frontend can subscribe with `EventSource` without bypassing the plugin SDK.

Each user turn has two stages. The first Agent call streams a concise human-readable response. The second call produces the canonical `WorkspacePlan`; its output is normalized by stripping Markdown fences, extracting a balanced JSON object, validating required sections, and removing `job` unless the user explicitly requested collection. Structured-output failure emits a warning but does not discard the conversation.

The frontend uses TokUI only inside Agent-generated output surfaces. YuDream components continue to own page chrome, commands, fields, secrets, and validation. Proposals are copied into an editable draft, submitted through a proposal update endpoint, and only then applied.

## Event Contract

- `message.start`: user turn accepted.
- `message.delta`: Agent text delta.
- `message.complete`: streamed assistant response persisted.
- `proposal.ready`: validated editable proposal available.
- `proposal.warning`: conversation succeeded but no valid proposal was produced.
- `message.error`: unrecoverable Agent execution error.

## Safety

Credentials never enter Agent context. TokUI DSL is generated from validated plugin data and uses named frontend handlers only. The canonical plan remains plugin-owned JSON and is revalidated on update and apply.

## Verification

Use test-first coverage for SPI delta forwarding, tolerant JSON extraction, proposal-update validation, and crawl opt-in behavior. Then run frontend typecheck/build, backend tests/package, inspect the final JAR, and verify the live desktop and mobile flows.
