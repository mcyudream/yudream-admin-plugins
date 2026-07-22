# QQ 群自动化策略与媒体测试 Implementation Plan

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.

**Goal:** 支持连接默认策略与群级字段覆盖，并提供不向 QQ 群发送消息的媒体解析测试工作流。

**Architecture:** 后端将完整默认策略和稀疏群覆盖分开存储，在事件处理和管理 API 中合并为有效策略。媒体测试复用解析执行路径，但以手动测试上下文运行并禁止发送消息；前端以两个独立管理员页面管理策略和测试任务。

**Tech Stack:** Java 21、JUnit 5、YuDream Plugin SPI、Vue 3、TypeScript、Vite、`@yudream/components`。

---

### Task 1: 建立层级策略模型与字段继承

**Files:**
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/plugin/qqbotautomation/application/dto/AutomationPolicyOverride.java`
- Modify: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/plugin/qqbotautomation/application/dto/AutomationPolicy.java`
- Modify: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/plugin/qqbotautomation/application/service/AutomationPolicyService.java`
- Modify: `yudream-plugins/yudream-plugin-qqbot-automation/pom.xml`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/test/java/online/yudream/plugin/qqbotautomation/application/service/AutomationPolicyServiceTest.java`

**Step 1: Write the failing tests**

Implement an in-memory `PluginDocumentStore` following `AiChatbotPolicyServiceTest`. Cover these cases:

```java
@Test
void resolvesUnsetGroupFieldsFromConnectionDefaults() {
    service.saveDefaults(defaults("milky", true, true, "https://parser.example"));
    service.saveOverride(new AutomationPolicyOverride("milky", "group-1", false,
            null, null, null, null, null, null, null, null));

    AutomationPolicy effective = service.resolve("milky", "group-1");

    assertFalse(effective.enabled());
    assertTrue(effective.mediaEnabled());
    assertEquals("https://parser.example", effective.mediaProviderEndpoint());
}

@Test
void deletingGroupOverrideRestoresConnectionDefault() {
    service.saveDefaults(defaults("milky", true, false, ""));
    service.saveOverride(new AutomationPolicyOverride("milky", "group-1", false,
            null, null, null, null, null, null, null, null));

    service.deleteOverride("milky", "group-1");

    assertTrue(service.resolve("milky", "group-1").enabled());
}
```

Also assert that a legacy complete `automation-policy` record still resolves unchanged after upgrade.

**Step 2: Run the tests and confirm they fail**

Command:

```powershell
mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am test -Dtest=AutomationPolicyServiceTest
```

Expected: FAIL because `AutomationPolicyOverride`, default-policy persistence, and `resolve` do not exist.

**Step 3: Implement the minimal model and service**

- Add JUnit 5 test dependency matching the repository's existing test modules.
- Store complete defaults in `automation-policy-default` keyed by connection ID.
- Store overrides in `automation-policy-override` keyed by `connectionId:channelId`.
- Use nullable boxed fields in `AutomationPolicyOverride`; do not use primitive `boolean` fields for inheritable values.
- Add `saveDefaults`, `getDefaults`, `saveOverride`, `getOverride`, `pageOverrides`, `deleteOverride`, and `resolve` methods.
- Merge every unset override field from the connection default, then from the existing built-in safe default.
- Read legacy `automation-policy` documents as complete overrides so existing deployed policies retain their behavior.

**Step 4: Run the tests and confirm they pass**

Command:

```powershell
mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am test -Dtest=AutomationPolicyServiceTest
```

Expected: PASS.

**Step 5: Commit**

```powershell
git add yudream-plugins/yudream-plugin-qqbot-automation
git commit -m "feat: add hierarchical QQ automation policies"
```

### Task 2: Add safe media parsing test jobs

**Files:**
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/plugin/qqbotautomation/application/dto/MediaJobTestRequest.java`
- Modify: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/plugin/qqbotautomation/application/service/MediaJobService.java`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/test/java/online/yudream/plugin/qqbotautomation/application/service/MediaJobServiceTest.java`

**Step 1: Write the failing tests**

Use a local `HttpServer` test endpoint and a capturing messaging implementation. Cover the success case and safety boundary:

```java
@Test
void testJobPersistsDownloadResultWithoutSendingToGroup() {
    policyService.saveDefaults(enabledMediaDefaults("milky", parserUrl));

    String jobId = mediaJobs.startTest(new MediaJobTestRequest("milky", "group-1", "https://b23.tv/example"));
    await().untilAsserted(() -> assertEquals("COMPLETED", document(jobId).get("status")));

    assertEquals("https://files.example/result.mp4", document(jobId).get("downloadUrl"));
    assertEquals("MANUAL_TEST", document(jobId).get("trigger"));
    assertEquals(0, messaging.sentRequests().size());
}
```

Add tests that a disabled effective policy, disabled media parsing, a non-supported URL, or a blank endpoint is rejected before any HTTP request is made.

**Step 2: Run the tests and confirm they fail**

Command:

```powershell
mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am test -Dtest=MediaJobServiceTest
```

Expected: FAIL because `startTest` and the no-send execution path do not exist.

**Step 3: Implement the minimal execution split**

- Validate the request against the effective policy returned by `AutomationPolicyService.resolve`.
- Extract the existing HTTP parsing operation into a common execution method taking an explicit trigger and `sendResultToGroup` flag.
- Keep message-event handling as `EVENT` with `sendResultToGroup=true`.
- Add `startTest` as `MANUAL_TEST` with `sendResultToGroup=false`.
- Persist the trigger, connection, channel, source URL, status, result URL, and sanitized error consistently.

**Step 4: Run the tests and confirm they pass**

Command:

```powershell
mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am test -Dtest=MediaJobServiceTest
```

Expected: PASS.

**Step 5: Commit**

```powershell
git add yudream-plugins/yudream-plugin-qqbot-automation
git commit -m "feat: add safe QQ media parsing tests"
```

### Task 3: Expose a management-protected hierarchy API

**Files:**
- Modify: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/plugin/qqbotautomation/interfaces/http/QqbotAutomationHttpFacade.java`
- Modify: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/plugin/qqbotautomation/interfaces/controller/QqbotAutomationController.java`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/test/java/online/yudream/plugin/qqbotautomation/interfaces/http/QqbotAutomationHttpFacadeTest.java`

**Step 1: Write the failing facade tests**

Assert that the facade exposes the following administrative actions and returns the expected record shape:

```java
assertEquals("milky", facade.defaults(requestWithConnection("milky")).body().get("connectionId"));
assertEquals(1L, facade.overrides(requestWithPagination("milky", 1, 10)).body().get("total"));
assertEquals("MANUAL_TEST", facade.startMediaTest(testRequest).body().get("trigger"));
```

**Step 2: Run the tests and confirm they fail**

Command:

```powershell
mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am test -Dtest=QqbotAutomationHttpFacadeTest
```

Expected: FAIL because the hierarchy and test endpoints do not exist.

**Step 3: Implement the API surface**

- Replace the ambiguous current policy endpoints with explicit `/admin/default-policy` and `/admin/group-overrides` resources.
- Provide paginated group overrides filtered by connection, plus get, put, and delete operations.
- Add `POST /admin/media-jobs/test` for `MediaJobTestRequest`.
- Keep every endpoint protected by `MANAGE_PERMISSION`; do not add a user-scoped alternative.
- Validate that selected connection and group exist in `framework.messaging()` before saving an override or starting a test.

**Step 4: Run the tests and confirm they pass**

Command:

```powershell
mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am test -Dtest=QqbotAutomationHttpFacadeTest
```

Expected: PASS.

**Step 5: Commit**

```powershell
git add yudream-plugins/yudream-plugin-qqbot-automation
git commit -m "feat: expose QQ automation policy management APIs"
```

### Task 4: Rebuild policy and media-task management pages

**Files:**
- Modify: `yudream-frontend/packages/plugin-qqbot-automation/src/types.ts`
- Modify: `yudream-frontend/packages/plugin-qqbot-automation/src/api/qqbot-automation-api.ts`
- Create: `yudream-frontend/packages/plugin-qqbot-automation/src/components/PolicyFieldsForm.vue`
- Modify: `yudream-frontend/packages/plugin-qqbot-automation/src/pages/PoliciesPage.vue`
- Modify: `yudream-frontend/packages/plugin-qqbot-automation/src/pages/MediaJobsPage.vue`

**Step 1: Write the failing type check**

Add TypeScript API methods for defaults, overrides, deletion, and manual tests. Update both pages to reference those methods before adding their implementations.

**Step 2: Run the type check and confirm it fails**

Command:

```powershell
Set-Location yudream-frontend
pnpm --filter @yudream/plugin-qqbot-automation run typecheck
```

Expected: FAIL because the management API types and page state are missing.

**Step 3: Implement focused UI workflows**

- Create `PolicyFieldsForm.vue` using explicit `FaSwitch`, `FaInput`, `FaTextarea`, and `FaSelect` imports. It accepts a full effective policy and a sparse override, exposes override toggles per field, and disables inherited inputs.
- Rebuild `PoliciesPage.vue` with a connection selector, one default-policy section, a paginated group-overrides `FaTable`, and focused `FaModal` flows for edit and delete confirmation.
- Rebuild `MediaJobsPage.vue` with table filters/pagination and a focused `FaModal` for selecting connection/group and entering a supported media link.
- Use `useFaToast` for success and API errors. Preserve loading, empty, disabled, and error states. Use normal UTF-8 Chinese labels throughout.
- Refresh the affected table after save, delete, or test submission. Do not render an unbounded group override list or add client-side ownership logic.

**Step 4: Run the type check and build**

Command:

```powershell
Set-Location yudream-frontend
pnpm --filter @yudream/plugin-qqbot-automation run typecheck
pnpm --filter @yudream/plugin-qqbot-automation run build
```

Expected: PASS and `packages/plugin-qqbot-automation/dist/remoteEntry.js` is generated.

**Step 5: Commit**

```powershell
git add yudream-frontend/packages/plugin-qqbot-automation yudream-frontend/pnpm-lock.yaml
git commit -m "feat: improve QQ automation management pages"
```

### Task 5: Verify the packaged plugin

**Files:**
- Modify only files required to resolve failures discovered in Tasks 1-4.

**Step 1: Run backend tests**

```powershell
mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am test
```

Expected: PASS.

**Step 2: Build the frontend and package the plugin**

```powershell
Set-Location yudream-frontend
pnpm --filter @yudream/plugin-qqbot-automation run build
Set-Location ..
mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am package -DskipTests
sh ci/verify-plugin-jar-assets.sh
```

Expected: PASS; the final JAR includes `META-INF/yudream-plugin/frontend/qqbot-automation/remoteEntry.js`.

**Step 3: Run repository boundary checks**

```powershell
sh ci/verify-plugin-maven-boundary.sh
sh ci/verify-doc-independence.sh
```

Expected: PASS.
