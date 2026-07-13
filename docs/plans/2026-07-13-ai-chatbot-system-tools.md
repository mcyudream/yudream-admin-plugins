# AI Chatbot System Tools And Memory Profile Implementation Plan

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.

**Goal:** Add privacy-preserving QQ account tools, group-scoped managed memory
profiles, and per-policy random-reply tool calling to the AI chatbot plugin.

**Architecture:** Register two `READ` `PluginAiTool` implementations through
the released SPI. Keep profile persistence in the plugin document store, with
application services owning profile lifecycle and controller/facade code
owning only HTTP parsing. Extend the existing group policy with one explicit
random-tool flag; keep group settings and profile management as separate
frontend routes.

**Tech Stack:** Java 21, JUnit 5, `yudream-plugin-spi` 2.3.0, Vue 3,
TypeScript, `@yudream/components`, `@yudream/plugin-sdk`.

---

### Task 1: Establish backend test support and policy regression coverage

**Files:**
- Modify: `yudream-plugins/yudream-plugin-ai-chatbot/pom.xml`
- Modify: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/application/dto/AiChatbotGroupPolicy.java`
- Modify: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/application/service/AiChatbotPolicyService.java`
- Test: `yudream-plugins/yudream-plugin-ai-chatbot/src/test/java/online/yudream/plugin/aichatbot/application/service/AiChatbotPolicyServiceTest.java`

**Step 1: Write the failing test.** Add JUnit Jupiter test coverage that saves
a policy with `randomToolCallingEnabled=true`, reloads it from an in-memory
`PluginDocumentStore`, and asserts `toolCallingEnabled("RANDOM")` is true.
Also assert the default policy keeps random tool calling disabled while mention
tool calling remains enabled when tools are selected.

**Step 2: Run the test and confirm it fails.**

```powershell
mvn -pl yudream-plugins/yudream-plugin-ai-chatbot -am test -Dtest=AiChatbotPolicyServiceTest
```

Expected: compilation failure because the policy field and decision method do
not exist.

**Step 3: Write the minimal implementation.** Add a boolean field after
`enabledToolNames`, default it to `false`, persist it in `toDocument`, read it
with a backwards-compatible false fallback, and expose a method that returns
true for mentions with selected tools and true for random only with both the
flag and selected tools. Add test-scoped JUnit Jupiter dependencies and the
Surefire version required for Java 21.

**Step 4: Run the test and confirm it passes.** Re-run the command above.

**Step 5: Commit.**

```powershell
git add yudream-plugins/yudream-plugin-ai-chatbot/pom.xml yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/application/dto/AiChatbotGroupPolicy.java yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/application/service/AiChatbotPolicyService.java yudream-plugins/yudream-plugin-ai-chatbot/src/test/java/online/yudream/plugin/aichatbot/application/service/AiChatbotPolicyServiceTest.java
git commit -m "feat: configure random chatbot tool calling"
```

### Task 2: Implement scoped memory profile persistence and lifecycle

**Files:**
- Create: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/application/dto/AiChatbotMemoryFact.java`
- Create: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/application/dto/AiChatbotMemoryProfile.java`
- Create: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/application/dto/AiChatbotMemoryProfilePage.java`
- Create: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/application/service/AiChatbotMemoryProfileService.java`
- Test: `yudream-plugins/yudream-plugin-ai-chatbot/src/test/java/online/yudream/plugin/aichatbot/application/service/AiChatbotMemoryProfileServiceTest.java`

**Step 1: Write the failing tests.** Test an in-memory document store for:

- document IDs differing across group scope for the same user;
- save/update retaining approved human facts when a rebuild supplies candidate
  facts with the same key;
- page results returning records and the document-store total;
- disable and delete changing the visible lifecycle predictably;
- a profile lookup rejecting a mismatched connection or channel.

**Step 2: Run the test and confirm it fails.**

```powershell
mvn -pl yudream-plugins/yudream-plugin-ai-chatbot -am test -Dtest=AiChatbotMemoryProfileServiceTest
```

Expected: test compilation failure because profile DTOs and service do not
exist.

**Step 3: Write the minimal implementation.** Store documents in
`memory-profile` keyed by `connectionId:channelId:userId`. Use records for
profile, fact, and page DTOs. Keep profile facts bounded and validate nonblank
scope/user identity, fact keys/values, and confidence in `[0, 1]`. Implement
`get`, `page`, `save`, `rebuild`, `setEnabled`, and `delete`; ensure candidate
facts cannot replace an approved fact with the same key.

**Step 4: Run the test and confirm it passes.** Re-run the command above.

**Step 5: Commit.**

```powershell
git add yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/application yudream-plugins/yudream-plugin-ai-chatbot/src/test/java/online/yudream/plugin/aichatbot/application/service/AiChatbotMemoryProfileServiceTest.java
git commit -m "feat: add scoped chatbot memory profiles"
```

### Task 3: Register privacy-preserving read tools

**Files:**
- Create: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/application/service/AiChatbotToolSubjectResolver.java`
- Create: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/interfaces/tool/AiChatbotUserLookupTool.java`
- Create: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/interfaces/tool/AiChatbotMemoryProfileLookupTool.java`
- Modify: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/bootstrap/AiChatbotPlugin.java`
- Test: `yudream-plugins/yudream-plugin-ai-chatbot/src/test/java/online/yudream/plugin/aichatbot/interfaces/tool/AiChatbotSystemToolsTest.java`

**Step 1: Write the failing tests.** Use test doubles for `PluginUserService`
and `PluginDocumentStore`; verify that each tool accepts the current QQ and
listed message mentions, rejects another arbitrary QQ, projects no email or
phone from a profile result, returns role names for a valid user, and returns
only matching group-scope profile facts. Assert both descriptors are `READ`,
require `plugin:ai-chatbot:use`, and allow exactly `MENTION` and `RANDOM`.

**Step 2: Run the test and confirm it fails.**

```powershell
mvn -pl yudream-plugins/yudream-plugin-ai-chatbot -am test -Dtest=AiChatbotSystemToolsTest
```

Expected: test compilation failure because resolver and tools do not exist.

**Step 3: Write the minimal implementation.** Parse `qq` only as a string,
compare it to the trusted `PluginAiExecutionContext.platformUserId` and
mentions supplied to the execution context extension, and return a neutral
out-of-scope result for all other values. Project only safe account fields and
role names. Register both tools in `onEnable`. Do not register a profile
mutation tool.

**Step 4: Run the test and confirm it passes.** Re-run the command above.

**Step 5: Commit.**

```powershell
git add yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot yudream-plugins/yudream-plugin-ai-chatbot/src/test/java/online/yudream/plugin/aichatbot/interfaces/tool/AiChatbotSystemToolsTest.java
git commit -m "feat: add safe chatbot system tools"
```

### Task 4: Capture bounded observations and enable policy-controlled tools

**Files:**
- Modify: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/bootstrap/AiChatbotPlugin.java`
- Modify: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/application/service/AiChatbotMemoryProfileService.java`
- Test: `yudream-plugins/yudream-plugin-ai-chatbot/src/test/java/online/yudream/plugin/aichatbot/bootstrap/AiChatbotReplyDecisionTest.java`

**Step 1: Write the failing test.** Extract reply execution-request creation
into a package-visible helper and assert random events set
`toolCallingEnabled` only when both the group flag and selected tool names are
present; mention events keep existing selected-tool behavior. Assert user
observations are appended only for QQ-bound users and remain group-scoped and
bounded.

**Step 2: Run the test and confirm it fails.**

```powershell
mvn -pl yudream-plugins/yudream-plugin-ai-chatbot -am test -Dtest=AiChatbotReplyDecisionTest
```

Expected: assertion failure because random responses currently hard-disable
tools and no observation collection exists.

**Step 3: Write the minimal implementation.** Use the policy decision method
when constructing `PluginAiChatRequest`, add a random-mode prompt constraint
that tools are optional and fact-driven, and record only bounded source
messages for the resolved system user in `memory-observation`. Keep existing
reply quotas and queue sequencing unchanged.

**Step 4: Run the test and confirm it passes.** Re-run the command above,
then run all chatbot backend tests.

**Step 5: Commit.**

```powershell
git add yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot yudream-plugins/yudream-plugin-ai-chatbot/src/test/java/online/yudream/plugin/aichatbot/bootstrap/AiChatbotReplyDecisionTest.java
git commit -m "feat: gate chatbot tools by reply mode"
```

### Task 5: Expose management-protected memory profile APIs

**Files:**
- Create: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/interfaces/request/AiChatbotMemoryProfileSaveRequest.java`
- Modify: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/interfaces/controller/AiChatbotController.java`
- Modify: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/interfaces/http/AiChatbotHttpFacade.java`
- Modify: `yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot/bootstrap/AiChatbotPlugin.java`
- Test: `yudream-plugins/yudream-plugin-ai-chatbot/src/test/java/online/yudream/plugin/aichatbot/interfaces/http/AiChatbotMemoryProfileHttpFacadeTest.java`

**Step 1: Write failing tests.** Assert the facade maps `/admin/memory-profiles`
page/size queries to a page result and that detail/save/rebuild/enable/delete
all target one scoped profile. Assert controller endpoint annotations carry
`MANAGE_PERMISSION`, never `USE_PERMISSION`, and all identifiers remain
strings at the HTTP boundary.

**Step 2: Run the test and confirm it fails.**

```powershell
mvn -pl yudream-plugins/yudream-plugin-ai-chatbot -am test -Dtest=AiChatbotMemoryProfileHttpFacadeTest
```

Expected: test compilation failure because profile management endpoints do not
exist.

**Step 3: Write the minimal implementation.** Add only `/admin/memory-profiles`
endpoints: paged list, detail, PUT save, POST rebuild, POST enabled state, and
DELETE. Parse input through `JsonSupport`, use the profile service, and return
the plugin's standard HTTP response. Register a second frontend route for the
management page in the plugin annotation.

**Step 4: Run the test and confirm it passes.** Re-run the command above.

**Step 5: Commit.**

```powershell
git add yudream-plugins/yudream-plugin-ai-chatbot/src/main/java/online/yudream/plugin/aichatbot
git commit -m "feat: manage chatbot memory profiles"
```

### Task 6: Extend policy types and settings UI for random tool selection

**Files:**
- Modify: `yudream-frontend/packages/plugin-ai-chatbot/src/types.ts`
- Modify: `yudream-frontend/packages/plugin-ai-chatbot/src/pages/SettingsPage.vue`
- Test: `yudream-frontend/packages/plugin-ai-chatbot/src/pages/SettingsPage.spec.ts`

**Step 1: Write the failing test.** Verify a form initialized from a policy
serializes `randomToolCallingEnabled`, displays the switch next to existing
tool selection, and retains selected tool names when a group policy reloads.

**Step 2: Run the test and confirm it fails.**

```powershell
cd yudream-frontend
pnpm --filter @yudream/plugin-ai-chatbot exec vitest run src/pages/SettingsPage.spec.ts
```

Expected: test/configuration failure because the field and test runner setup
do not exist.

**Step 3: Write minimal implementation.** Add the field to `GroupPolicy` and
the form default. Add Vitest and Vue Test Utils only if the frontend workspace
does not already expose them; otherwise use its established test command.
Use `FaSwitch`, explain its impact in the field label, and preserve the
existing `FaSelect` tool allow-list as the second required control.

**Step 4: Run the focused test and then typecheck/build.**

```powershell
cd yudream-frontend
pnpm --filter @yudream/plugin-ai-chatbot run typecheck
pnpm --filter @yudream/plugin-ai-chatbot run build
```

Expected: all commands pass and `dist/remoteEntry.js` exists.

**Step 5: Commit.**

```powershell
git add yudream-frontend/packages/plugin-ai-chatbot
git commit -m "feat: configure random reply tools"
```

### Task 7: Build the separate memory profile management route

**Files:**
- Modify: `yudream-frontend/packages/plugin-ai-chatbot/src/types.ts`
- Modify: `yudream-frontend/packages/plugin-ai-chatbot/src/api/ai-chatbot-api.ts`
- Create: `yudream-frontend/packages/plugin-ai-chatbot/src/pages/MemoryProfilesPage.vue`
- Modify: `yudream-frontend/packages/plugin-ai-chatbot/src/AiChatbotPlugin.vue`
- Modify: `yudream-frontend/packages/plugin-ai-chatbot/src/index.ts`
- Test: `yudream-frontend/packages/plugin-ai-chatbot/src/pages/MemoryProfilesPage.spec.ts`

**Step 1: Write the failing test.** Mock SDK responses and assert that the
page requests server page 1 with size 10, renders a `FaTable` with an operation
column, binds `FaPagination`, opens a focused edit/details surface, confirms
delete, and refreshes/clamps the page after the last deletion.

**Step 2: Run the test and confirm it fails.**

```powershell
cd yudream-frontend
pnpm --filter @yudream/plugin-ai-chatbot exec vitest run src/pages/MemoryProfilesPage.spec.ts
```

Expected: test failure because the page and API methods do not exist.

**Step 3: Write minimal implementation.** Add typed API wrappers for every
management endpoint and use string identifiers throughout. Implement one
`FaPageHeader`/`FaPageMain` management page with `FaTable`, row key, loading,
empty/error state, `FaPagination`, and compact operation controls. Use a
focused `FaDrawer` or `FaModal` for editable summary/tags/facts. Keep settings
out of this route and use a separate remote route export.

**Step 4: Run focused test, typecheck, and build.** Re-run the Vitest command,
then the Task 6 typecheck/build commands.

**Step 5: Commit.**

```powershell
git add yudream-frontend/packages/plugin-ai-chatbot
git commit -m "feat: add chatbot memory profile management"
```

### Task 8: Execute complete plugin validation and packaging inspection

**Files:**
- Modify only if a validation exposes a real defect in earlier tasks.

**Step 1: Run all plugin backend tests.**

```powershell
mvn -pl yudream-plugins/yudream-plugin-ai-chatbot -am test
```

Expected: all unit tests pass.

**Step 2: Build the remote frontend.**

```powershell
cd yudream-frontend
pnpm --filter @yudream/plugin-ai-chatbot run typecheck
pnpm --filter @yudream/plugin-ai-chatbot run build
```

Expected: build passes and `packages/plugin-ai-chatbot/dist/remoteEntry.js`
exists.

**Step 3: Package the backend JAR.**

```powershell
mvn -pl yudream-plugins/yudream-plugin-ai-chatbot -am package -DskipTests
jar tf yudream-plugins/yudream-plugin-ai-chatbot/target/yudream-plugin-ai-chatbot-1.0-SNAPSHOT.jar | Select-String 'META-INF/yudream-plugin/frontend/ai-chatbot/remoteEntry.js'
```

Expected: Maven succeeds and the JAR listing contains the remote entry.

**Step 4: Run repository readiness.**

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-repo-readiness.sh
```

Expected: readiness validation passes; investigate and fix only relevant
failures.

**Step 5: Commit validation fixes, if any.**

```powershell
git add <relevant-files>
git commit -m "fix: complete chatbot system tools integration"
```
