# Page Composition And Spacing

Treat information architecture, spacing, and workflow separation as functional UI requirements. A page is not complete when all controls merely fit into one component.

## Choose The Correct Surface

Use an independent route page when the capability:

- represents a distinct business goal that users may navigate to directly or return to;
- owns a list, filters, pagination, statistics, settings, audit history, or a substantial editor;
- contains multiple sections, conditional steps, uploads, previews, or more than a short form;
- needs its own loading, empty, error, permission, or unsaved-change state;
- is user-side or admin-side and therefore must remain visibly separate from the other surface.

Use `FaModal` when the task is focused, temporary, and normally completed without losing the current page context, for example:

- confirming a destructive action;
- creating or editing a small record with a short form;
- renaming, assigning, changing status, or entering one reason;
- showing a compact detail that does not need a permanent URL.

Use `FaDrawer` when users must keep the list or current record visible while inspecting or editing a moderate amount of contextual detail. Do not use a drawer as a hidden full application.

Use an inline section only when it directly supports the page's primary workflow and is needed repeatedly. Do not append unrelated settings, global statistics, import/export tools, and secondary CRUD forms below a list simply to avoid creating routes.

## Mandatory Split Rules

- Keep one primary workflow per route page. A page may include supporting summary or filters, but not several independent management centers.
- Give separate record types separate management pages when each has its own list, filters, pagination, or lifecycle.
- Give settings/configuration a separate admin page when they are not required for every use of the operational page.
- Give global statistics, audit history, exports, migration tools, or diagnostic status their own page when they form a meaningful workflow.
- Never use one giant tabbed page to conceal unrelated routes. Tabs are for alternate views of the same dataset or tightly coupled workflow, not for bundling an entire plugin.
- Never put admin controls into a user page, even behind a permission check. Create an admin route and page.
- Extract reusable panels or forms into `src/components` and stateful workflows into composables before a route component becomes a monolith.

## Modal And Drawer Limits

- Keep modal content focused on one command and one submit/cancel outcome.
- Prefer a route page over a modal when the form has several logical sections, a table, pagination, nested CRUD, extensive help, or a workflow users may need to bookmark or resume.
- Prefer a drawer over a modal for contextual inspection that benefits from a taller scroll surface, but move it to a route page once it becomes multi-step or independently navigable.
- Do not nest cards, drawers, or modals inside each other. Do not open a second modal from a modal except for a standard destructive confirmation with no reasonable inline alternative.
- Reset transient form state and errors when a modal or drawer closes.

## Spacing Baseline

Follow the host's spacing rhythm and existing utility conventions:

- Use `FaPageHeader` followed by `FaPageMain`; do not let page content touch the viewport or host shell edges.
- Separate independent page sections with at least the established `mt-4`, `gap-4`, or equivalent spacing. Use more separation when section ownership changes.
- Use `gap-3` or `gap-4` for form grids and filter groups. Use `gap-2` only for compact, closely related row actions or icon controls.
- Put `FaPagination class="mt-3"` below its table.
- Keep labels visibly separated from controls and keep validation/help text attached to its field rather than the next field.
- Keep page-level actions grouped and separated from titles; keep destructive actions visually distinct from ordinary actions.
- Use responsive grids that collapse to one column on narrow screens. Never rely on controls touching each other to fit a desktop layout onto mobile.
- Do not remove padding, margins, or gaps merely to keep every feature above the fold. Scrolling is preferable to a crowded, unreadable interface.

Avoid consecutive unwrapped controls such as:

```vue
<FaInput />
<FaSelect />
<FaButton />
<FaTable />
```

Group them by responsibility and provide explicit layout spacing:

```vue
<div class="grid grid-cols-1 gap-3 md:grid-cols-[minmax(220px,1fr)_220px_auto]">
  <FaInput />
  <FaSelect />
  <div class="flex flex-wrap justify-end gap-2">
    <FaButton variant="outline">重置</FaButton>
    <FaButton>查询</FaButton>
  </div>
</div>
```

## Review Questions

- Can a user state the page's primary job in one short sentence?
- Does any section have its own independent list, settings, statistics, or lifecycle and therefore deserve a route?
- Is the modal or drawer short, focused, and reversible, or is it hiding a full page?
- Are user and admin functions on visibly and technically separate pages?
- Can users visually distinguish the page header, filters, data surface, pagination, forms, and actions without reading every label?
- Are independent sections separated by deliberate spacing on both desktop and mobile?
- Do buttons wrap safely without touching, overlapping, or collapsing the surrounding layout?
