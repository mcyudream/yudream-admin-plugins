# QQ Bot Automation Implementation Plan

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.

**Goal:** Deliver QQ group automation and safe media handling without embedding NoneBot, while extending Minecraft operations through YuDream contracts.

**Architecture:** Release the minimal host SPI and Milky event bridge required for a group-request plugin callback, then implement a standalone `qqbot-automation` remote plugin. Extend `minecraft-server` only for RCON/TPS operations; retain its existing status history and trend graph.

**Tech Stack:** Java 21, Maven, YuDream Plugin SPI 2.4+, Mongo-backed plugin document stores, Vue 3, TypeScript, `@yudream/plugin-sdk`, `@yudream/components`.

---

### Task 1: Publish typed group-request contracts in the core repository

**Files:**
- Create: `yudream-plugins/yudream-plugin-spi/src/main/java/online/yudream/base/plugin/spi/system/messaging/PluginGroupRequest.java` (YuDream Admin core repository)
- Create: `yudream-plugins/yudream-plugin-spi/src/main/java/online/yudream/base/plugin/spi/system/messaging/PluginGroupRequestDecision.java` (YuDream Admin core repository)
- Modify: `yudream-plugins/yudream-plugin-spi/src/main/java/online/yudream/base/plugin/spi/system/messaging/PluginMessagingService.java` (YuDream Admin core repository)
- Test: `yudream-plugins/yudream-plugin-spi/src/test/java/online/yudream/base/plugin/spi/system/messaging/PluginGroupRequestTest.java` (YuDream Admin core repository)

**Step 1: Write the failing test**

Add a test that constructs a group request with connection ID, group ID, request ID, applicant QQ ID, and comment, then asserts that a blank request ID is rejected and the decision enum exposes only approve/reject.

**Step 2: Run test - confirm it fails**

Command: `mvn -pl yudream-plugins/yudream-plugin-spi -Dtest=PluginGroupRequestTest test`

Expected: FAIL because the contract types do not exist.

**Step 3: Write minimal implementation**

Add immutable SPI records and `CompletionStage<Void> decideGroupRequest(PluginGroupRequest, PluginGroupRequestDecision, String reason)` to `PluginMessagingService`. Do not expose raw protocol method names or payload maps to business plugins.

**Step 4: Run test - confirm it passes**

Command: `mvn -pl yudream-plugins/yudream-plugin-spi -Dtest=PluginGroupRequestTest test`

Expected: PASS.

### Task 2: Dispatch QQ group-request events and apply typed decisions

**Files:**
- Modify: `yudream-infrastructure/src/main/java/online/yudream/base/infra/platform/plugin/service/MilkyPluginEventDispatcher.java` (YuDream Admin core repository)
- Modify: `yudream-infrastructure/src/main/java/online/yudream/base/infra/platform/plugin/service/MilkyPluginMessagingService.java` (YuDream Admin core repository)
- Test: `yudream-bootstrap/src/test/java/online/yudream/base/infra/platform/milky/MilkyPluginGroupRequestTest.java` (YuDream Admin core repository)

**Step 1: Write the failing test**

Add a fixture for a Milky `group_request` event. Assert that it reaches native interaction handlers with `type=group_request`, preserves only normalized request fields in the referrer map, and that approve/reject produces the provider's group-request action with the same connection and request ID.

**Step 2: Run test - confirm it fails**

Command: `mvn -pl yudream-bootstrap -Dtest=MilkyPluginGroupRequestTest test`

Expected: FAIL because the dispatcher drops every non-`message_receive` event.

**Step 3: Write minimal implementation**

Normalize `group_request` into a native `PluginEvent`, route it through `publishMessagingEvent`, and implement the typed decision method by translating only the approved/rejected action into the Milky request endpoint. Reject unknown/missing request identities and never dispatch all provider-native events indiscriminately.

**Step 4: Run test - confirm it passes**

Command: `mvn -pl yudream-bootstrap -Dtest=MilkyPluginGroupRequestTest test`

Expected: PASS.

### Task 3: Scaffold the qqbot-automation plugin and policy domain

**Files:**
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/pom.xml`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/resources/plugin.yml`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/base/plugin/qqbotautomation/bootstrap/QqbotAutomationPlugin.java`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/base/plugin/qqbotautomation/application/service/GroupPolicyService.java`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/test/java/online/yudream/base/plugin/qqbotautomation/application/service/GroupPolicyServiceTest.java`
- Modify: `pom.xml`

**Step 1: Write the failing test**

Define policy tests for connection/group identity, duplicate-safe saves, quiet-hour normalization, and a rule decision returning approve, reject, or undecided.

**Step 2: Run test - confirm it fails**

Command: `mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am -Dtest=GroupPolicyServiceTest test`

Expected: FAIL because the module and service do not exist.

**Step 3: Write minimal implementation**

Register plugin metadata, administrative permissions and independent routes. Persist policies in the plugin document store, keyed by connection and channel. Keep all identifiers as strings and validate configured group IDs against `messaging().groups(connectionId)`.

**Step 4: Run test - confirm it passes**

Command: `mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am -Dtest=GroupPolicyServiceTest test`

Expected: PASS.

### Task 4: Implement idempotent join verification and audit history

**Files:**
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/base/plugin/qqbotautomation/application/service/JoinVerificationService.java`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/base/plugin/qqbotautomation/interfaces/controller/QqbotAutomationNativeController.java`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/test/java/online/yudream/base/plugin/qqbotautomation/application/service/JoinVerificationServiceTest.java`

**Step 1: Write the failing test**

Cover approved-answer precedence, rejected-answer precedence, an undecided request without AI fallback, duplicate request IDs producing one decision, and AI failure following the configured fail-closed/fail-open policy.

**Step 2: Run test - confirm it fails**

Command: `mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am -Dtest=JoinVerificationServiceTest test`

Expected: FAIL because no native event handler exists.

**Step 3: Write minimal implementation**

Register `onNative(new PluginInteractionFilter(Set.of("group_request"), "milky", null, null), ...)`. Normalize comment text, evaluate configured rules first, optionally invoke the host AI service with a fixed classification prompt, call the typed decision port once, and persist a redacted audit record.

**Step 4: Run test - confirm it passes**

Command: `mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am -Dtest=JoinVerificationServiceTest test`

Expected: PASS.

### Task 5: Add media jobs and safe provider adapters

**Files:**
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/base/plugin/qqbotautomation/application/service/MediaJobService.java`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/base/plugin/qqbotautomation/infrastructure/service/MediaParserClient.java`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/test/java/online/yudream/base/plugin/qqbotautomation/application/service/MediaJobServiceTest.java`

**Step 1: Write the failing test**

Test Douyin/Bilibili URL recognition, rejected unsupported URLs, queued-to-completed transition, one retryable parser failure, and redaction of endpoint credentials from stored error text.

**Step 2: Run test - confirm it fails**

Command: `mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am -Dtest=MediaJobServiceTest test`

Expected: FAIL because media jobs are absent.

**Step 3: Write minimal implementation**

Queue one job per incoming supported link, use configured provider endpoints with timeouts and size limits, store assets through `FrameworkServices.files()`, send the finished media through `messaging()`, and persist job status/audit entries. Do not copy legacy cookies, token strings, private hosts, shell commands, or fixed temporary filenames.

**Step 4: Run test - confirm it passes**

Command: `mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am -Dtest=MediaJobServiceTest test`

Expected: PASS.

### Task 6: Add moderation and QQ-to-Minecraft relay

**Files:**
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/base/plugin/qqbotautomation/application/service/GroupAutomationService.java`
- Create: `yudream-plugins/yudream-plugin-qqbot-automation/src/test/java/online/yudream/base/plugin/qqbotautomation/application/service/GroupAutomationServiceTest.java`
- Modify: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/java/online/yudream/base/plugin/qqbotautomation/bootstrap/QqbotAutomationPlugin.java`

**Step 1: Write the failing test**

Test that bot-originated messages are ignored, quiet hours block relay, cooldown suppresses duplicate relay, message formatting is bounded/plain text, and moderation requests require the management permission.

**Step 2: Run test - confirm it fails**

Command: `mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am -Dtest=GroupAutomationServiceTest test`

Expected: FAIL because no interaction service is registered.

**Step 3: Write minimal implementation**

Listen to configured `message_receive` events, enforce policy at runtime, create audit records, and call the Minecraft plugin's public API only when its hard dependency is enabled. Keep moderation protocol calls in a dedicated adapter and never accept a client-provided target group on a user endpoint.

**Step 4: Run test - confirm it passes**

Command: `mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am -Dtest=GroupAutomationServiceTest test`

Expected: PASS.

### Task 7: Extend Minecraft operations without changing status visualization

**Files:**
- Modify: `yudream-plugins/yudream-plugin-minecraft-server/src/main/java/online/yudream/base/plugin/minecraft/bootstrap/MinecraftServerPlugin.java`
- Create: `yudream-plugins/yudream-plugin-minecraft-server/src/main/java/online/yudream/base/plugin/minecraft/application/service/MinecraftRconOperationService.java`
- Modify: `yudream-plugins/yudream-plugin-minecraft-server/src/main/java/online/yudream/base/plugin/minecraft/interfaces/controller/MinecraftServerAdminController.java`
- Modify: `yudream-plugins/yudream-plugin-minecraft-server/src/main/java/online/yudream/base/plugin/minecraft/interfaces/http/MinecraftServerHttpFacade.java`
- Test: `yudream-plugins/yudream-plugin-minecraft-server/src/test/java/online/yudream/base/plugin/minecraft/application/service/MinecraftRconOperationServiceTest.java`

**Step 1: Write the failing test**

Cover dedicated TPS reads, allowlisted command acceptance, denied commands, timeout behavior, output redaction, and immutable audit record creation.

**Step 2: Run test - confirm it fails**

Command: `mvn -pl yudream-plugins/yudream-plugin-minecraft-server -am -Dtest=MinecraftRconOperationServiceTest test`

Expected: FAIL because no RCON operation service exists.

**Step 3: Write minimal implementation**

Add a narrow `PluginMinecraftService` operation API so the automation plugin does not use RCON directly. Add an admin-only operations route/page and preserve the existing online trend chart unchanged.

**Step 4: Run test - confirm it passes**

Command: `mvn -pl yudream-plugins/yudream-plugin-minecraft-server -am -Dtest=MinecraftRconOperationServiceTest test`

Expected: PASS.

### Task 8: Build the QQ automation remote management UI

**Files:**
- Create: `yudream-frontend/packages/plugin-qqbot-automation/package.json`
- Create: `yudream-frontend/packages/plugin-qqbot-automation/vite.config.ts`
- Create: `yudream-frontend/packages/plugin-qqbot-automation/tsconfig.json`
- Create: `yudream-frontend/packages/plugin-qqbot-automation/src/index.ts`
- Create: `yudream-frontend/packages/plugin-qqbot-automation/src/api/qqbot-automation-api.ts`
- Create: `yudream-frontend/packages/plugin-qqbot-automation/src/pages/GroupPoliciesPage.vue`
- Create: `yudream-frontend/packages/plugin-qqbot-automation/src/pages/MediaJobsPage.vue`
- Create: `yudream-frontend/packages/plugin-qqbot-automation/src/pages/JoinAuditsPage.vue`
- Create: `yudream-frontend/packages/plugin-qqbot-automation/src/pages/AutomationAuditsPage.vue`
- Test: `yudream-frontend/packages/plugin-qqbot-automation/src/api/qqbot-automation-api.test.ts`

**Step 1: Write the failing test**

Test SDK endpoint construction for each admin API and string preservation for all identifiers.

**Step 2: Run test - confirm it fails**

Command: `pnpm --filter @yudream/plugin-qqbot-automation test`

Expected: FAIL because the package is absent.

**Step 3: Write minimal implementation**

Use separate `FaPageHeader`/`FaPageMain` management routes. Render policy, media, verification audit, and automation audit as paginated `FaTable` pages. Use selectable labeled connections/groups, modal-only focused edits, SDK HTTP wrappers, confirmation for destructive actions, and responsive filter grids.

**Step 4: Run test - confirm it passes**

Command: `pnpm --filter @yudream/plugin-qqbot-automation test`

Expected: PASS.

### Task 9: Package, release contracts, and verify integration

**Files:**
- Modify: `yudream-plugins/yudream-plugin-qqbot-automation/pom.xml`
- Modify: `yudream-plugins/yudream-plugin-qqbot-automation/src/main/resources/plugin.yml`
- Modify: `pom.xml`
- Modify: `yudream-plugins/yudream-plugin-spi/pom.xml` (YuDream Admin core repository)

**Step 1: Verify release boundary**

Release the modified SPI from the framework repository, verify it in Nexus, then update this repository's central `yudream.plugin.spi.version`. Do not point a plugin at an unpublished local SPI version.

**Step 2: Build frontend and backend**

Commands:
`pnpm --dir yudream-frontend --filter @yudream/plugin-qqbot-automation run typecheck`

`pnpm --dir yudream-frontend --filter @yudream/plugin-qqbot-automation run build`

`mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am test`

`mvn -pl yudream-plugins/yudream-plugin-qqbot-automation -am package -DskipTests`

**Step 3: Inspect final artifact**

Command: `jar tf yudream-plugins/yudream-plugin-qqbot-automation/target/yudream-plugin-qqbot-automation-*.jar | Select-String 'META-INF/yudream-plugin/frontend/qqbot-automation/remoteEntry.js'`

Expected: the remote entry and `plugin.yml` are present.
