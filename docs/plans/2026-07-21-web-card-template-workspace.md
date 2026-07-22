# Web Card Template Workspace Implementation Plan

> **For implementer:** Follow the user's requested implementation-first workflow, then run the complete targeted test suite.

**Goal:** Provide a polished template editor with real-link preview for unsaved structured and HTML/CSS drafts.

**Architecture:** Add one transient preview use case to the existing application service and expose it through the protected admin controller. Refactor the Vue page around a reusable CodeMirror editor and a responsive editor/preview workspace.

**Tech Stack:** Java 21, YuDream plugin SPI, Vue 3, `@yudream/components`, CodeMirror 6, Vitest.

---

### Task 1: Transient URL preview

**Files:**
- Modify: `yudream-plugins/yudream-plugin-web-card/src/main/java/online/yudream/plugin/webcard/application/WebCardApplicationService.java`
- Modify: `yudream-plugins/yudream-plugin-web-card/src/main/java/online/yudream/plugin/webcard/interfaces/WebCardHttpFacade.java`
- Modify: `yudream-plugins/yudream-plugin-web-card/src/main/java/online/yudream/plugin/webcard/interfaces/WebCardAdminController.java`
- Test: `yudream-plugins/yudream-plugin-web-card/src/test/java/online/yudream/plugin/webcard/application/WebCardApplicationServiceDraftPreviewTest.java`

Implement an admin-only request that renders a transient template against parsed content from a matching URL without saving a version.

### Task 2: Code editor and workspace state

**Files:**
- Create: `yudream-frontend/packages/plugin-web-card/src/components/TemplateCodeEditor.vue`
- Create: `yudream-frontend/packages/plugin-web-card/src/composables/template-editor.ts`
- Modify: `yudream-frontend/packages/plugin-web-card/src/api/web-card-api.ts`
- Modify: `yudream-frontend/packages/plugin-web-card/src/types.ts`
- Modify: `yudream-frontend/packages/plugin-web-card/package.json`
- Modify: `yudream-frontend/pnpm-lock.yaml`

Add language-aware JSON/HTML/CSS editing and typed draft-preview transport.

### Task 3: Template page redesign

**Files:**
- Modify: `yudream-frontend/packages/plugin-web-card/src/pages/TemplateDesignerPage.vue`

Build the responsive two-column workspace, URL preview toolbar, parsed-field inspection, contextual version-history modal, and clear request states.

### Task 4: Verification and package

Run frontend tests/typecheck/build, backend tests/package, inspect the final JAR for `plugin.yml` and `META-INF/yudream-plugin/frontend/web-card/remoteEntry.js`, then visually verify desktop and mobile layouts when the host app is available.

