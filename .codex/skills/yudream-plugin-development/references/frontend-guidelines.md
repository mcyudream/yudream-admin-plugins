# Frontend Plugin Guidelines

## Package Layout

Use the established package responsibilities:

```text
yudream-frontend/packages/plugin-{code}/
  src/
    api/           SDK-backed API wrappers
    components/    Reusable plugin UI
    composables/   Stateful workflows and orchestration
    pages/         Route-level pages
    index.ts       Remote module contract
    types.ts       Shared view models
  package.json
  vite.config.ts
  tsconfig.json
```

Add folders only when they carry a clear responsibility. Do not place a complete plugin in one `.vue` file.

## Remote Contract

- Export the shape expected by the host from `src/index.ts` and follow the closest existing plugin.
- Build an ESM remote entry named `remoteEntry.js`.
- Treat workspace loading as development convenience only. Production loading is the remote ESM entry from the plugin JAR.
- Keep major management surfaces as separate route entries and real page components.
- Align route paths, titles, icons, sort order, and permissions with backend declarations.
- Separate user routes/pages from admin routes/pages. Read `access-boundaries.md`; do not switch a user page into cross-user mode merely because the current account also has management permission.
- Read `page-composition.md` before adding a route, tab, modal, drawer, or another major section to an existing page.

## API Access

- Use the SDK/client supplied by `@yudream/plugin-sdk` and the host runtime.
- Put endpoint details and response typing in `src/api`.
- Put multi-step loading, mutation, refresh, and feedback flows in composables.
- Keep pages focused on view state and interaction composition.
- Do not create a private axios instance or hard-code a host origin for ordinary plugin endpoints.
- Keep user API wrappers scoped to `/me/**` or an equivalent principal-bound namespace and admin wrappers scoped to `/admin/**`. Do not add optional `userId` parameters to personal-data methods.

## Types And IDs

- Define API and view-model types explicitly.
- Represent Java `Long` and Snowflake IDs as `string` in TypeScript.
- Never call `Number(id)` for record identifiers or bind long IDs to numeric form controls.
- Separate API transport types from heavily transformed UI state when the shapes differ.

## Migrated Framework Conventions

- Prefer Composition API with `<script setup lang="ts">`.
- Reuse `@yudream/components` before raw form controls, hand-built modals, pagination, loading overlays, or toast systems.
- Use Arco as the approved second-level UI library where the YuDream package lacks a control.
- Keep forms typed, validate before submit, disable duplicate submissions, surface backend errors, and reset transient state when dialogs close.
- Keep list queries, page/size, filters, sorting, selection, and refresh behavior explicit and testable.
- Use a composable for shared state before introducing Pinia. Add a package-local store only when state must span unrelated routes or survive component lifecycles.
- Do not use host `apps/*/src/views`, host slots, theme settings, or host-local auto-import conventions as plugin implementation paths.
- Keep Chinese display text as normal UTF-8; do not introduce Unicode escapes or preserve actual mojibake.

## Managed Collections

Read `management-pages.md` whenever a page displays repeated business records.

- If a collection is user-managed and can contain more than 5 records, provide pagination plus every applicable management action, such as view, create, edit, enable/disable, or delete.
- Use a `FaTable` management page when records have obvious repeated fields suitable for columns and comparison.
- Do not use cards merely to avoid implementing a table, pagination, filtering, or row actions.
- Back pagination with the API for remotely stored or potentially growing data. Client-side slicing is acceptable only for an already bounded, fully loaded collection.
- Treat destructive removal as a full-stack capability: permission, endpoint, API wrapper, confirmation, feedback, refresh, and empty-page correction must all work.
- Provide a separate admin management page for user-owned records when administrators are expected to maintain those records. Do not expose admin filters, user selectors, global totals, or cross-user row actions in the user page.

## UI Quality Checklist

- Provide loading, empty, error, disabled, and success states.
- Confirm destructive actions.
- Keep buttons, inputs, tables, pagination, and dialogs consistent with the host.
- Ensure narrow viewport behavior does not overlap or truncate critical controls.
- Use icons through `FaIcon` or the established library rather than hand-written SVGs.
- Keep page components small enough that API calls, reusable panels, and route-specific logic can be tested independently.
- Give each route one primary business workflow. Split unrelated management domains, settings, statistics, and editors into separate routes instead of accumulating them in one page.
- Use a modal or drawer only when the task is focused and temporary; use a full route page for deep, multi-section, linkable, or long-running work.
- Keep deliberate vertical and horizontal spacing between independent sections, fields, filters, and actions. Do not accept edge-to-edge controls or visually merged blocks merely because they compile.

## Frontend Validation

Run the scripts declared by the target package. Typical commands:

```powershell
cd yudream-frontend
pnpm --filter @yudream/plugin-{code} run typecheck
pnpm --filter @yudream/plugin-{code} run build
```

If the package exposes `lint` instead of `typecheck`, run `lint` and `build`. For workspace-wide contract changes, run the root frontend build.
