# Repository Workflow

## Workspace Boundaries

- Backend modules live under `yudream-plugins/yudream-plugin-{code}`.
- Frontend remote packages live under `yudream-frontend/packages/plugin-{code}`.
- The frontend workspace intentionally includes only `packages/plugin-*`.
- Do not restore generic `packages/*`, copy `@yudream/plugin-sdk` or `@yudream/components` source here, or depend on the host root parent.

The relevant upstream rules were migrated from:

- `D:/code/yudream-admim/.codex/skills/yudream-ddd-architecture`;
- `D:/code/yudream-admim/.codex/skills/yudream-contract-release`;
- `D:/code/yudream-admim/yudream-frontend/skills`.

Use those sources only for comparison when available. This skill is the plugin-repository adaptation and takes precedence for paths and independent-repository boundaries.

## Shared Dependency Ownership

- SPI version: root `pom.xml` property `yudream.plugin.spi.version`.
- SDK and components versions: `yudream-frontend/pnpm-workspace.yaml` catalog.
- Plugin `package.json` files consume shared packages with `catalog:`.
- Refresh `pnpm-lock.yaml` after catalog/dependency changes.
- Do not point the plugin repository at unpublished contract versions.

## Packaging Contract

- The frontend package must produce `dist/remoteEntry.js`.
- The backend Maven module copies the frontend `dist` output into:

```text
META-INF/yudream-plugin/frontend/{pluginCode}/
```

- The final JAR must contain:

```text
META-INF/yudream-plugin/frontend/{pluginCode}/remoteEntry.js
```

- Keep the standard runtime entry default unless the frontend is hosted externally or uses a genuinely non-standard entry path.

## Typical Validation

Frontend package:

```powershell
cd yudream-frontend
pnpm install --frozen-lockfile
pnpm --filter @yudream/plugin-{code} run build
```

Backend package:

```powershell
mvn -pl yudream-plugins/yudream-plugin-{code} -am test
mvn -pl yudream-plugins/yudream-plugin-{code} -am package -DskipTests
```

Repository readiness:

```powershell
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-repo-readiness.sh
```

Use `settings.xml` when Nexus access is required by the local environment.

## Contract Release Boundary

- Release affected contracts in the core repository before updating this repository.
- Release SPI when Java plugin interfaces, lifecycle, DTOs, runtime contracts, or host service ports change.
- Release `@yudream/plugin-sdk` when frontend client contracts or runtime APIs change.
- Release `@yudream/components` when shared plugin-facing UI exports or behavior change.
- Verify publication in Nexus before syncing the root Maven property or frontend catalog here.

## Completion Checklist

- Backend and frontend plugin codes match.
- Routes, menus, permissions, and endpoints match across both sides.
- Shared dependencies use the repository-owned version locations.
- Frontend build passes and emits `remoteEntry.js`.
- Backend package/test passes.
- Final JAR contains the remote frontend asset.
- Readiness verification passes for cross-cutting or release work.
