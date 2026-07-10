# UI Library Rules

## Priority

Use UI capabilities in this order:

1. `@yudream/components` for the host's visual language and reusable behavior.
2. `@arco-design/web-vue` for capabilities not exported by `@yudream/components`, especially advanced form, date/time, cascader, tree, transfer, or other specialized controls.
3. A plugin-local component composed from YuDream and Arco primitives when the behavior is plugin-specific.
4. A new UI dependency only after documenting why the first three options cannot meet the requirement.

Do not replace an available `Fa*` component with raw HTML or a visually similar third-party component. Do not add a second general-purpose UI framework.

## Dependency Form

Use the workspace contract for shared YuDream packages:

```json
{
  "dependencies": {
    "@yudream/components": "catalog:",
    "@yudream/plugin-sdk": "catalog:"
  }
}
```

When Arco is required, follow the repository's established dependency:

```json
{
  "dependencies": {
    "@arco-design/web-vue": "^2.58.0",
    "@yudream/components": "catalog:"
  }
}
```

Add Arco only to packages that actually import it. Keep shared YuDream versions in `yudream-frontend/pnpm-workspace.yaml`.

## Available YuDream Exports

The current `@yudream/components` public surface includes:

- Feedback and overlays: `FaAlert`, `FaDrawer`, `FaModal`, `FaPopover`, `FaToast`, `FaTooltip`, `useFaDrawer`, `useFaImagePreview`, `useFaModal`, `useFaToast`.
- Actions and display: `FaButton`, `FaButtonGroup`, `FaBadge`, `FaCard`, `FaDescriptions`, `FaDivider`, `FaIcon`, `FaProgress`, `FaTag`, `FaTrend`.
- Inputs: `FaCheckbox`, `FaCheckboxGroup`, `FaFileUpload`, `FaImageUpload`, `FaInput`, `FaInputOTP`, `FaNumberField`, `FaRadioGroup`, `FaSelect`, `FaSlider`, `FaSwitch`, `FaTextarea`.
- Navigation and data: `FaPageHeader`, `FaPageMain`, `FaPagination`, `FaSearchBar`, `FaTable`, `FaTabs`.
- Supporting UI: `FaAvatar`, `FaCollapsible`, `FaContextMenu`, `FaDropdown`, `FaFixedBar`, `FaHoverCard`, `FaImagePreview`, `FaKbd`, `FaKbdGroup`, `FaLabel`, `FaPasswordStrength`, `FaScrollArea`.

Inspect the installed package types or the upstream source at `D:/code/yudream-admim/yudream-frontend/packages/components/src` when available. Read the component README/type declarations before guessing props, slots, events, or `v-model` names.

## Imports

Remote plugin packages must use explicit imports:

```ts
import type { TableColumn } from '@yudream/components'
import { FaButton, FaModal, FaTable, useFaToast } from '@yudream/components'
```

Do not copy source paths such as `packages/components/src/...` into plugin imports. Import only from the published package entry.

## Page Composition

- Prefer `FaPageMain`/`FaPageHeader` for page structure where appropriate.
- Prefer `FaCard`, `FaSearchBar`, `FaTable`, `FaPagination`, and `FaModal` for standard management pages.
- Use `useFaToast` and `useFaModal` for consistent feedback and confirmation.
- Use Arco components only for the missing control, not as a reason to rebuild the whole page in a different visual system.
- Preserve loading, empty, error, disabled, permission-denied, and destructive-confirmation states.
- For management tables, follow `management-pages.md` instead of inventing a plugin-specific list style.

## Custom Components

Create a plugin-local component when it represents plugin-specific behavior or a repeated composition. Keep its public API typed and narrow. Do not wrap a shared component merely to rename props or apply one-off styling.
