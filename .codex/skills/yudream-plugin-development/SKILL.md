---
name: yudream-plugin-development
description: Enforce YuDream Admin standalone plugin development conventions. Use when creating, modifying, reviewing, migrating, or debugging a plugin in yudream-admin-plugins, including separate user/admin surfaces, personal-data scoping, permissions and authorization, Vue remote frontend packages, management pages, tables, pagination, CRUD or delete flows, @yudream/components or Arco UI work, @yudream/plugin-sdk API usage, Java plugin backends, SPI annotations and lifecycle, DDD layering, plugin routes/menus/permissions, remoteEntry packaging, contract dependency updates, or full-stack plugin features.
---

# YuDream Plugin Development

Develop official YuDream plugins as independently buildable frontend remote modules and backend JARs while preserving the host framework's UI, contract, and DDD conventions.

## Mandatory First Steps

1. Identify the target plugin code and inspect both matching modules when they exist:
   - backend: `yudream-plugins/yudream-plugin-{code}`;
   - frontend: `yudream-frontend/packages/plugin-{code}`.
2. Inspect the nearest existing plugin with similar behavior before inventing a new structure. Prefer `plugin-project-progress` for a full DDD example and a smaller plugin for simple features.
3. Read the relevant references before editing:
   - frontend or UI work: `references/frontend-guidelines.md`, `references/ui-library.md`, and `references/page-composition.md`;
   - list, CRUD, table, pagination, or data-management work: `references/management-pages.md`;
   - user/admin routes, permissions, ownership, or personal data: `references/access-boundaries.md`;
   - backend work: `references/backend-guidelines.md`;
   - dependency, packaging, build, or release work: `references/repository-workflow.md`.
4. Preserve unrelated user changes. This repository may have active edits in the target plugin.

## Contract Boundary

- Consume only released host contracts:
  - Maven: `online.yudream.base:yudream-plugin-spi`;
  - npm: `@yudream/plugin-sdk` and `@yudream/components`.
- Do not copy host source packages into this repository or depend on host DDD modules, bootstrap classes, frontend apps, or local host workspaces.
- Add a host capability through a stable SPI/SDK contract first. Do not bypass the contract with internal imports, private HTTP clients, or direct Spring bean access.
- Keep Java `Long` and Snowflake identifiers as strings across JSON, plugin DTOs, TypeScript models, form state, route params, and SDK calls.

## Plugin Metadata And Cross-Plugin APIs

- Declare plugin metadata in `plugin.yml` packaged with the plugin JAR. Every plugin must define `name`, `main`, and `version`; use `depend` for required provider plugins and `softdepend` for optional provider plugins. Do not package `META-INF/services/online.yudream.base.plugin.spi.core.YuDreamPlugin`: runtime instantiates the `main` class from `plugin.yml` and does not use `ServiceLoader`.

```yaml
name: yudream-plugin-order
displayName: 订单插件
main: online.yudream.plugin.order.OrderPlugin
version: 1.0.0
depend:
  - yudream-plugin-wallet
softdepend:
  - yudream-plugin-coupon
```

- `name` is the unique stable plugin code used by dependency declarations and service lookup. Optional `displayName` is the user-facing localized name and falls back to `name`; never use it in `depend`, `softdepend`, routes, or service lookup. `main` is the fully qualified plugin entry class. `version` is the plugin release version. Use YAML lists for `depend` and `softdepend`; omit either key when empty.
- A hard dependency in `depend` must exist and enable successfully before its consumer is loaded. Missing, disabled, or failed hard dependencies prevent the consumer plugin from loading or enabling.
- A soft dependency in `softdepend` controls load order only when the provider is present and enabled. Runtime restores enabled soft providers before creating the consumer class loader, so their API classes are visible; missing, disabled, or failed providers do not block the consumer. The consumer must remain loadable without an optional provider, and must not register, display, or enable routes, menus, permissions, scheduled work, or operations that require the absent provider. React appropriately if an optional provider is disabled or unloaded.
- Keep host-wide technical capabilities in `yudream-plugin-spi`. Do not place a plugin's business ports, DTOs, or extension interfaces in the host SPI merely so another plugin can consume them.
- A provider plugin may package its public `*.api` interfaces and DTOs in the same JAR as its implementation. Keep implementation types outside that API package and make the API deliberately stable and minimal.
- A consumer declares a hard or soft plugin dependency and compiles against the provider's API with `provided` scope. Do not package, shade, relocate, or duplicate the provider API classes in the consumer JAR.
- Resolve and call a provider plugin's public API directly through the runtime's dependency class-loader relationship, not through `registerExtension`, `getExtension`, `framework().extension(s)`, or a plugin-specific HTTP proxy. The consumer must tolerate an unavailable soft provider and must not cache API objects across provider disable/reload.
- The runtime must load the provider before each dependent consumer and expose the provider's classes to that consumer. Before disabling or unloading a provider, disable/unload all plugins with a hard or active soft dependency on it; otherwise Java interfaces with the same name but different class loaders cannot be cast safely.

## User And Admin Boundary

- Implement user and administration capabilities as separate surfaces with distinct routes, menu entries, frontend pages, API wrappers/endpoints, permissions, and use cases. Read `references/access-boundaries.md` for the mandatory model.
- Scope every user-side operation to the authenticated principal. An administrator using a user route remains an ordinary user for that request and must not view or mutate unrelated users' records.
- Never treat `MANAGE_PERMISSION` as an ownership bypass inside `/me/**`, `/my/**`, or other user-side endpoints. Put cross-user queries and mutations behind explicit `/admin/**` endpoints and management permissions.
- Plugins that react autonomously to group/chat events must expose a management-protected, plugin-scoped policy API and remote configuration page. Persist policy by connection and channel, apply updates on the next event without restart, and keep disabled, rate-limited, cooldown, and quiet-hour states in the runtime decision path rather than only in the UI.
- Never ask an operator to type an internal identifier, provider code, model code, connection ID, group ID, user ID, or similar value when the system already owns an authoritative list. Expose a plugin API for that list and render a labeled selector; preserve manual entry only for genuinely external values with no discoverable source.
- When a plugin exposes user-maintained records, provide the corresponding management surface for authorized administrators to list, inspect, create when meaningful, edit, change status, and delete/archive according to domain rules.

## Implementation Workflow

### 1. Define The Plugin Surface

- Confirm plugin code, routes, menu entries, permissions, HTTP endpoints, lifecycle behavior, migrations, and frontend pages affected by the request.
- Classify every route and endpoint as public, user, or admin before implementing it, and define its data scope independently of the caller's other permissions.
- Prefer annotation-driven static declarations. Use imperative `PluginContext.registerXxx(...)` only for dynamic or conditional contributions.
- Keep endpoint paths plugin-scoped and permission-protected.

### 2. Implement The Backend

- Apply the host DDD responsibilities inside the plugin: domain, application, infrastructure, interfaces, migration, and bootstrap as complexity requires.
- Keep the plugin entry class limited to metadata, dependency construction, registration, lifecycle, and cleanup.
- Keep controllers thin; delegate parsing/conversion to interface assemblers or facades and use cases to application services.
- Access host abilities only through SPI ports such as `FrameworkServices`.
- Put plugin-owned Thymeleaf image templates under the backend plugin's `src/main/resources/templates` directory and render them through `PluginContext.templateRenderer()` after SPI 2.1.0 is released. Use logical template names without `.html`, keep selectors inside the template, and never place plugin templates in the host repository.

### 3. Implement The Frontend

- Build route pages under `src/pages`, reusable UI under `src/components`, stateful workflows under `src/composables`, SDK-backed calls under `src/api`, and shared view models in `src/types.ts`.
- Use the host-injected `@yudream/plugin-sdk` client. Do not create a private axios/fetch abstraction for normal plugin APIs.
- Follow this UI priority:
  1. `@yudream/components` (`Fa*` components and `useFa*` composables);
  2. `@arco-design/web-vue` when no suitable YuDream component exists;
  3. a plugin-local component composed from those libraries;
  4. a new third-party UI dependency only with a concrete capability gap.
- Import shared components explicitly from `@yudream/components`; remote plugin packages do not inherit the host app's auto-import setup.
- Expose real route-level pages through the remote entry. Do not hide unrelated management surfaces in one giant tabbed page.
- Split distinct business workflows into distinct route pages. Use a modal or drawer only for a focused, temporary task with a clear completion or cancellation point. Follow `references/page-composition.md`; never pack unrelated lists, settings, forms, statistics, and operations into one dense page.
- Preserve visible hierarchy and breathing room between page headers, filters, tables, forms, action groups, and independent sections. A technically functional page with collapsed or missing spacing is incomplete UI work.
- Treat a collection as a management surface when users must create, inspect, edit, enable/disable, delete, filter, or otherwise maintain individual records. If such a collection can contain more than 5 records, implement pagination and the applicable row management actions instead of rendering an unbounded list.
- Use the framework-standard management table style whenever records have clear, repeated fields that are naturally compared by columns. Do not replace tabular management data with decorative cards.

### 4. Integrate And Package

- Keep backend and frontend plugin codes, route names, permissions, endpoint paths, and asset paths aligned.
- Ensure the frontend builds to `dist/remoteEntry.js` and the backend JAR packages it under `META-INF/yudream-plugin/frontend/{pluginCode}/remoteEntry.js`.
- Manage shared contract versions centrally in the root `pom.xml` and `yudream-frontend/pnpm-workspace.yaml`; do not mass-edit every plugin for a shared version bump.

### 5. Verify

- Run the target frontend package's typecheck/build after frontend changes.
- Run the target Maven module tests/package after backend changes.
- For cross-cutting, dependency, packaging, or release changes, run `ci/verify-plugin-repo-readiness.sh`.
- Check that no new host-internal dependency, duplicate UI library, private API client, numeric long ID, or unprotected plugin endpoint was introduced.
- Review every changed route against `references/page-composition.md`: correct page/modal/drawer choice, one primary workflow per page, deliberate section spacing, and no crowded action or form layout.

## Completion Standard

Report the affected plugin modules, UI library choices, contract changes, validation commands, and any host contract work still required. Do not claim completion when the remote entry or final plugin JAR packaging has not been verified for a full-stack change.

## References

- `references/ui-library.md`: component priority, available exports, and Arco fallback rules.
- `references/frontend-guidelines.md`: remote frontend architecture and migrated frontend conventions.
- `references/page-composition.md`: route-page boundaries, modal/drawer selection, information density, spacing, and responsive composition.
- `references/management-pages.md`: mandatory table selection, pagination threshold, CRUD behavior, and framework management-page style.
- `references/access-boundaries.md`: strict user/admin separation, ownership enforcement, endpoint design, and authorization tests.
- `references/backend-guidelines.md`: plugin-specific DDD, SPI, HTTP, and lifecycle rules.
- `references/repository-workflow.md`: workspace boundaries, dependencies, packaging, and validation.
