# Plugin Conformance Implementation Plan

> **For implementer:** Use test-first development. Add a failing deterministic conformance check before production edits, then make each plugin pass it and its package builds.

**Goal:** Make all official plugins comply with YuDream UI, management-page, pagination, DDD, and strict user/admin access-boundary rules.

**Architecture:** Separate public, personal, and administrative surfaces end to end. Personal endpoints derive the owner from the principal; admin endpoints own cross-user queries and mutations. Standardize tabular management UI on `@yudream/components` and use paged contracts for growing datasets.

**Tech Stack:** Java 21, Maven, YuDream Plugin SPI, Vue 3, TypeScript, Vite, `@yudream/plugin-sdk`, `@yudream/components`, Arco Design only where YuDream lacks a control.

---

### Task 1: Add The Conformance Gate

**Files:**
- Create: `ci/verify-plugin-development-conformance.sh`
- Modify: `ci/verify-plugin-repo-readiness.sh`

1. Add checks for management-permission branching in personal HTTP flows, personal endpoints that accept selectable owner IDs, raw HTML tables in management pages, missing shared UI dependencies, and mixed user/admin page routing patterns.
2. Run the script and confirm it fails against current violations.
3. Add it to repository readiness without weakening existing checks.

### Task 2: Fix Wallet And Alipay Security Boundaries

**Files:**
- Modify wallet controllers/facades/request types/API/composable/pages.
- Modify Alipay controllers/facades/routes/API/pages.

1. Split personal wallet operations from admin operations; remove management permission bypasses in recharge, balance, and transfer flows.
2. Use `/me/**` for wallet self-service and `/admin/**` for cross-user balances, transactions, assets, and settings.
3. Separate user and admin frontend loading paths and replace management raw tables with standard YuDream tables/pagination.
4. Give Alipay a real personal order/recharge surface and a distinct admin settings/orders surface; enforce ownership on personal order detail.

### Task 3: Fix Student Info And Activity Proof Boundaries

**Files:**
- Modify student-info controllers/routes/API/composable/pages.
- Modify activity-proof controllers/routes/API/composable/pages.

1. Split controllers by user/admin responsibility and keep personal requests free of selectable user IDs.
2. Preserve personal self-service while building standard paged admin tables with applicable CRUD/delete actions.
3. Move activity-proof admin endpoints under `/admin/**`; keep proof downloads ownership-scoped under `/me/**`.
4. Replace raw management tables and unbounded lists with `FaTable` and `FaPagination`.

### Task 4: Fix Minecraft Server Surfaces

**Files:**
- Modify minecraft-server controller/facade/API/composable/pages.

1. Separate public/view, personal economy records, admin server operations, and machine report endpoints.
2. Keep personal records under `/me/**` and derive identity from the principal.
3. Move admin server/player/season operations under `/admin/**`.
4. Convert admin and personal tabular datasets to `FaTable`; replace next/previous pseudo-pagination with total-backed pagination where the contract supports it.

### Task 5: Fix Project Progress Surfaces

**Files:**
- Modify project-progress controllers/facade/API/composable/pages while preserving existing local edits.

1. Separate member/user workflows from management and acceptance workflows by endpoint namespace and page component.
2. Enforce participant/assignee/acceptor scope in user-side detail, task, check-in, acceptance, file, event, and statistics queries.
3. Move cross-user project/detail/check-in/statistics management to `/admin/**` or explicit role-specific namespaces.
4. Standardize management lists and pagination using YuDream tables, retaining Arco only for controls not available in `@yudream/components`.

### Task 6: Finish Skin And Authlib Conformance

**Files:**
- Modify skin composable/pages/API where user/admin state is still mixed.
- Modify Authlib frontend/backend only for concrete conformance violations.

1. Preserve the existing split skin controllers, but separate frontend page components/state and remove management-dependent behavior from personal loading.
2. Ensure admin closet management and paged totals are complete where required.
3. Treat Authlib authentication/session/texture protocol endpoints as protocol surfaces, not artificial CRUD; keep its admin status/config UI isolated.

### Task 7: Verify Everything

**Commands:**

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-development-conformance.sh
cd yudream-frontend
pnpm -r --filter=@yudream/plugin-* run build
cd ..
mvn -s settings.xml clean package -DskipTests
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-repo-readiness.sh
```

Confirm every package emits `remoteEntry.js`, every plugin JAR embeds it, all conformance checks pass, and pre-existing user changes remain present.
