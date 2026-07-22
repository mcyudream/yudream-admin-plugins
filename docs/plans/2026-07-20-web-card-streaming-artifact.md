# Web Card Streaming Artifact Implementation Plan

> **For implementer:** Use TDD throughout. Write a failing test first, observe the expected failure, then implement.

**Goal:** Deliver true Agent streaming, resilient and editable proposals, TokUI output rendering, and opt-in-only crawl jobs.

**Architecture:** Extend the released host SPI with a backward-compatible streaming callback, expose plugin SSE events, keep structured proposal generation separate from the readable stream, and render only Agent output with TokUI. Persist and apply only plugin-validated `WorkspacePlan` JSON.

**Tech Stack:** Java 21, YuDream plugin SPI, `PluginSseStream`, Vue 3, `@yudream/components`, `@jboltai/tokui`, TypeScript, Vite.

---

### Task 1: Host Agent streaming SPI

**Files:**
- Modify: `<host-repository>/yudream-plugins/yudream-plugin-spi/src/main/java/online/yudream/base/plugin/spi/system/ai/PluginAiService.java`
- Modify: `<host-repository>/yudream-infrastructure/src/main/java/online/yudream/base/infra/platform/plugin/service/PluginAiFrameworkService.java`
- Test: matching host infrastructure test

Add a failing test proving deltas are forwarded by code, add the compatible SPI method, override it with `debugByCode`, and run targeted host tests.

### Task 2: Resilient proposal and SSE backend

**Files:**
- Modify: `yudream-plugins/yudream-plugin-web-card/src/main/java/online/yudream/plugin/webcard/application/AgentAuthoringService.java`
- Modify: plugin HTTP controller/facade
- Add: focused application tests

Add failing tests for fenced JSON, surrounding prose, invalid proposal fallback, update validation, and crawl opt-in. Implement the two-stage Agent turn, SSE event stream, proposal update endpoint, and canonical plan normalization.

### Task 3: TokUI streaming and editable proposal frontend

**Files:**
- Modify: `yudream-frontend/packages/plugin-web-card/package.json`
- Modify: `yudream-frontend/packages/plugin-web-card/src/api/web-card-api.ts`
- Modify: `yudream-frontend/packages/plugin-web-card/src/pages/StudioPage.vue`
- Add: TokUI adapter component and focused tests when supported by the package

Consume the protected POST SSE endpoint, feed message deltas to TokUI, keep a plain-text accessible fallback, edit a local proposal draft with YuDream controls, save it through the proposal endpoint, and keep crawl absent by default.

### Task 4: Integration verification

Run host contract tests, plugin Maven tests/package, frontend typecheck/build, final JAR inspection, and real desktop/mobile browser checks. Confirm invalid structured output leaves the assistant response visible and that applying an unrequested crawl job is impossible.
