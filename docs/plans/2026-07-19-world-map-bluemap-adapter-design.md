# World Map BlueMap Adapter Design

> **Status:** Approved direction. This document defines the architecture before implementation.

**Goal:** Replace the current hand-written world-map rendering path with a version-pinned BlueMap-backed engine while keeping YuDream-owned tasks, permissions, storage, viewer shell, and extension APIs stable.

**Architecture:** BlueMap is an implementation behind a backend render-engine adapter and a frontend map-engine adapter. YuDream owns the domain model, immutable map generations, HTTP contracts, administration pages, and extension registry. The adapter exposes only YuDream transport types and opaque asset references, so a later engine replacement does not change plugin features.

**Tech Stack:** Java 21 plugin module, released YuDream SPI, BlueMap MIT-licensed core/web assets pinned to a tested version, Vue 3 remote package, Three.js-compatible browser runtime, typed binary/PRBM tile assets, JUnit 5, and browser/performance smoke tests.

---

## Architecture Overview

```text
YuDream HTTP and domain layer
  -> RenderOrchestrator (durable phases, cancellation, recovery)
  -> MapRenderEngine (stable plugin port)
       -> BlueMapRenderEngineAdapter (primary implementation)
       -> LegacyRenderEngineAdapter (temporary migration/read-only fallback)
  -> GenerationPublisher (staging, validation, atomic active-generation switch)
  -> TileManifest and immutable asset storage

YuDream viewer shell
  -> MapEngineAdapter (stable TypeScript port)
       -> BlueMap viewer/tile adapter (pinned assets and controls)
  -> MapLayerProvider registry (markers, routes, players, external overlays)
```

BlueMap types, classes, DOM selectors, and storage paths must not cross the adapter boundary. The public plugin API and HTTP DTOs remain YuDream-owned.

## Responsibilities

### Domain and application

- `MapInstance` owns map identity, dimension, source metadata, active generation, and lifecycle state.
- `RenderTask` owns phase, weighted progress, cancellation state, error, timestamps, and generation ID.
- `MapRenderEngine` receives a validated `RenderInput`, emits assets through an `AssetSink`, and reports progress and cancellation cooperatively.
- `GenerationPublisher` writes to a unique staging prefix, validates the manifest and required assets, then atomically changes the active generation pointer.
- A failed or cancelled render never changes the active generation. The previous READY generation remains readable.

### BlueMap adapter

- Converts uploaded world/resource-pack inputs into the BlueMap world/resource abstractions.
- Uses a version-pinned BlueMap core or isolated worker implementation selected by the integration spike.
- Emits the BlueMap-compatible tile/texture/metadata assets required by the viewer adapter, while translating them into the YuDream manifest.
- Keeps third-party dependency loading isolated so a missing optional engine cannot prevent the plugin from loading its admin and API surfaces.

### Viewer shell

- Owns route identity, map selection, URL state, YuDream host controls, loading/error states, and responsive layout.
- `MapEngineAdapter` owns camera, tile visibility, LOD scheduling, rendering wake/sleep, and engine resource disposal.
- `MapLayerProvider` is presentation-only: it supplies typed marker/route/player records and receives selection/visibility events. It cannot mutate map storage or bypass permissions.
- Map ID, projection, camera, time-of-day, and enabled layer IDs are serialised in a versioned URL state.

### Cross-plugin extension API

Expose a small stable `online.yudream.plugin.worldmap.api` package in the plugin JAR:

- `MapLayerProvider` for server-side layer data and capability metadata.
- `MapMarker`, `MapPolyline`, and `MapRegion` transport records with string IDs and world coordinates.
- `MapLayerRegistration` with a plugin-scoped code, display metadata, permission, and data supplier.
- A read-only `MapQuery` port for map/generation metadata and coordinate links.

Consumers use a declared soft dependency and direct public API calls. They do not import BlueMap classes, host-internal classes, or the plugin's infrastructure packages. The frontend consumes the resulting public DTOs through the world-map API rather than importing another plugin's Vue components.

## Data Flow

1. Admin uploads a world archive and optional resource pack/client asset through the host file API with private access.
2. The application validates archive shape, identifies the world root and data version, and creates a PENDING task.
3. The orchestrator records weighted phases: `IMPORT`, `EXTRACT`, `ASSETS`, `HIRES`, `LOWRES`, and `PUBLISH`.
4. The engine scans actual chunk headers into a bounded manifest instead of rendering the region filename bounding rectangle.
5. The BlueMap adapter renders into a staging generation using bounded memory and cooperative cancellation.
6. The publisher validates required metadata, textures, tile counts, and checksums, then switches the active generation atomically.
7. Public settings and tile URLs include the generation/version so immutable assets can be cached safely.
8. The viewer loads the manifest first, displays low-resolution 3D terrain, and schedules view-frustum/ screen-error-prioritised high-resolution tiles. Decoding happens off the main thread where supported.
9. Layer providers are loaded independently and can be toggled without rebuilding terrain geometry.

## Error and Recovery Rules

- Queued and running cancellation both transition the task and map to a terminal `CANCELLED` state within one second.
- Startup recovery marks orphaned tasks failed and reconciles the corresponding map state; it never leaves `RENDERING` without a live task.
- Engine exceptions, timeouts, rejected assets, and worker process failures clean the staging generation and preserve the prior active generation.
- Progress is persisted at least once per second and SSE subscribers receive the current snapshot on connect; polling remains a fallback.
- Tile requests use immutable URLs, bounded concurrency, abortable stale requests, negative caching for 404s, and exponential retry for transient network failures.
- Public tile endpoints stream bytes, return explicit cache/version headers, and never expose the uploaded world archive or client asset.

## Extension and Upgrade Rules

- BlueMap version is pinned and covered by adapter contract tests. Upgrading it changes only the adapter and fixture baselines.
- The viewer shell and layer providers target `MapEngineAdapter`, never BlueMap internal modules.
- New features that need engine support are expressed as capabilities. The UI hides unavailable controls instead of assuming a specific engine.
- Custom layers, route lines, player overlays, and metadata are stored/versioned separately from terrain generations so they can update without re-rendering the world.
- A future non-BlueMap engine can implement the same ports and consume the same generation/extension contracts.

## Security and Surface Boundaries

- Public map viewing is read-only and exposes only active-generation assets and explicitly public layer data.
- Admin upload, render, cancel, delete, generation management, and layer configuration stay under `/admin/**` and the management permission.
- User-facing links never accept an arbitrary owner or storage key. File IDs and generation IDs remain strings at every boundary.
- Uploaded ZIP/JAR objects are private and are never returned by public endpoints.

## Verification Strategy

### Unit and integration tests

- Adapter contract tests validate settings, manifest, PRBM/texture output, alpha metadata, cancellation, and missing-resource behavior.
- Archive tests cover sparse distant regions, alternate world roots, malformed ZIP entries, data-version selection, and actual chunk manifests.
- Orchestrator tests cover every phase, queued/running cancellation, restart recovery, worker failure, atomic publish, rollback, and progress monotonicity.
- Storage tests cover immutable generations, cache headers, streaming reads, cleanup, and deletion races.
- Extension tests cover provider isolation, permission metadata, layer filtering, and unavailable soft dependencies.

### Browser and performance tests

- First meaningful low-resolution terrain in <= 1.5 seconds and central high-resolution tiles in <= 3 seconds on the recorded fixture under throttled network.
- Desktop frame-time p95 <= 20 ms and mobile p95 <= 33 ms during ten seconds of panning.
- Idle after one second produces <= 1 render per second and no new tile requests.
- Ten distant jumps keep pending work bounded and renderer memory within 120% of a stable view.
- Shared URLs round-trip map, projection, camera, and layers with less than one block position error.
- 360x800 and 390x844 viewports have no horizontal overflow and retain 44px touch targets.

## Rollout and Compatibility

1. Ship task-state, route, privacy, and generation primitives while the legacy renderer remains available for existing generations.
2. Add the BlueMap adapter and render new generations into staging; keep legacy generations readable through a manifest version discriminator.
3. Switch the viewer to the manifest-driven adapter after browser/performance gates pass.
4. Provide an admin re-render/migration action and retain legacy read-only support until all active maps have a verified BlueMap generation.
5. Remove the legacy renderer only after a documented migration check and a release containing the BlueMap license notice.

## Non-goals for the first implementation

- Editing blocks or writing changes back to the uploaded world.
- Making the map viewer a general-purpose host application or embedding another plugin's UI components.
- Unbounded real-time synchronization without a server-side source and explicit update policy.
- Exposing BlueMap's internal classes as a cross-plugin contract.

