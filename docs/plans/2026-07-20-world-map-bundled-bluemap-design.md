# World Map Bundled BlueMap Runtime Design

## Goal

Make BlueMap rendering work after installing the world-map plugin without administrator-maintained executable, CLI, template, or task-output paths.

## Runtime Selection

The default runtime uses the JVM that loaded the plugin, a pinned BlueMap v5.16 CLI JAR packaged in the plugin, a packaged template, and the task-local `output` directory. The adapter extracts the binary and template under the render work directory, so each render remains isolated.

The historic path settings remain compatible only when `yudream.world-map.bluemap.external-runtime-enabled=true` is explicitly set. This prevents incomplete or stale legacy settings from overriding the bundled runtime.

## Resource Contract

The bundled template contains `core.conf`, `maps/template.conf`, and `storages/file.conf`. Its required placeholders are `${data}`, `${world}`, `${dimension}`, `${name}`, and `${root}`. The existing worker continues to substitute those values into a fresh task-local copy.

## Failure Handling

The bundled CLI is copied from the classpath to the work directory and verified against the pinned SHA-256 before execution. Missing, unreadable, or altered packaged resources fail the render with an actionable error; they never silently fall back to the legacy renderer.

## Verification

Unit tests cover bundled defaults, explicit external override behavior, and extraction of all runtime resources. Maven packaging verifies that the final plugin JAR contains the CLI and template resources.
