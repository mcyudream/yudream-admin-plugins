# Plugin Conformance Design

## Goal

Bring every official plugin into conformance with `.codex/skills/yudream-plugin-development`: strict public/user/admin boundaries, principal-scoped user data, complete admin management loops, standard YuDream management tables and pagination, and published SPI/SDK/UI boundaries.

## Scope

Audit and modify all backend modules under `yudream-plugins/yudream-plugin-*` and all frontend packages under `yudream-frontend/packages/plugin-*`. Preserve protocol-only public surfaces and read-only dashboards; do not manufacture CRUD where the domain does not contain managed records.

## Architecture

Classify each endpoint and route as public, user, or admin. User operations derive ownership exclusively from the authenticated principal. Cross-user behavior moves to explicit admin paths protected by management permissions. Frontend user and admin routes use separate page components and API methods, while sharing presentation-only components when useful.

Management collections use `FaPageHeader`, `FaPageMain`, `FaTable`, `FaSearchBar`, and `FaPagination`. Persisted/growing collections expose page responses containing records and totals. Domain-appropriate destructive actions remain delete, disable, revoke, archive, or unpublish rather than forcing hard deletion.

## Verification

Add a deterministic conformance check to the repository readiness pipeline. Use it as the initial failing test and regression guard. Then run every frontend package typecheck/build, the full Maven package, JAR asset verification, and repository readiness validation.

## Worktree Constraint

The project-progress plugin contains pre-existing uncommitted user changes. Preserve and build on those changes. Do not create intermediate commits because they would mix unrelated pre-existing work with the conformance migration.
