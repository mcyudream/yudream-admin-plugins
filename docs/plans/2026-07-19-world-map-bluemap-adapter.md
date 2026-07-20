# World Map BlueMap Adapter Implementation Plan

> **For implementer:** Use TDD throughout. Write a failing test first. Watch it fail. Then implement.

**Goal:** Deliver a Java 21 compatible, BlueMap-backed world-map plugin that has durable rendering tasks, immutable generations, responsive tile viewing, and stable extension APIs.

**Architecture:** The YuDream plugin owns map state, administration, task state, storage, and public APIs. A version-pinned BlueMap CLI v5.16 runs outside the host JVM through a `MapRenderEngine` adapter; the Vue remote owns a map shell and consumes a local adapter around the extracted BlueMap tile/viewer modules.

**Tech Stack:** Java 21, JUnit 5, YuDream Plugin SPI, BlueMap v5.16 CLI and MIT notice, Vue 3, Three.js, Vite, browser performance smoke tests.

---

### Task 1: Make task endpoints and task terminal states reliable

**Files:**
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/interfaces/controller/AdminMapController.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/application/service/RenderOrchestrator.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/application/service/MapAppService.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/domain/aggregate/MapInstance.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/domain/aggregate/RenderTask.java`
- Test: `yudream-plugins/yudream-plugin-world-map/src/test/java/online/yudream/plugin/worldmap/application/service/RenderOrchestratorTest.java`
- Test: `yudream-plugins/yudream-plugin-world-map/src/test/java/online/yudream/plugin/worldmap/interfaces/controller/AdminMapControllerTest.java`

**Step 1: Write failing tests**

Cover independent `GET /admin/tasks` and `POST /admin/tasks/{id}/cancel` annotations, queued cancellation, running cancellation, and startup recovery. The expected terminal state is `CANCELLED` or `FAILED` on both the task and owning map. No map may remain `RENDERING` without a live task.

**Step 2: Run red tests**

Command:

```powershell
$env:JAVA_HOME='C:\Users\SiberianHusky\.jdks\ms-21.0.10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl yudream-plugins/yudream-plugin-world-map -am -Dtest=RenderOrchestratorTest,AdminMapControllerTest test
```

Expected: compilation/route/state assertions fail before implementation.

**Step 3: Implement minimally**

- Put the GET endpoint annotation on `tasks()` and retain the POST cancel annotation only on `cancelTask()`.
- Add a `CANCELLED` task state and map transition, or use a separately explicit terminal cancellation representation if the existing enum contract requires it.
- Ensure `cancel()` persists terminal state synchronously when the queued future will never enter `runTask()`.
- Reconcile maps while recovering stale PENDING/RUNNING tasks.
- Never allow a new render request to be rejected by an orphaned active task.

**Step 4: Run green tests and package**

Run the command above, then:

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am package -DskipTests
```

Expected: tests pass and the plugin JAR builds.

**Step 5: Commit**

```powershell
git add yudream-plugins/yudream-plugin-world-map
git commit -m "fix: recover world map task terminal states"
```

### Task 2: Model render phases and private source-file handling

**Files:**
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/domain/aggregate/RenderTask.java`
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/domain/enumerate/RenderPhase.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/application/dto/RenderTaskDTO.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/application/assembler/WorldMapAppAssembler.java`
- Modify: `yudream-frontend/packages/plugin-world-map/src/types.ts`
- Modify: `yudream-frontend/packages/plugin-world-map/src/composables/useWorldMapAdmin.ts`
- Modify: `yudream-frontend/packages/plugin-world-map/src/pages/admin/MapDetail.vue`
- Test: `yudream-plugins/yudream-plugin-world-map/src/test/java/online/yudream/plugin/worldmap/domain/aggregate/RenderTaskTest.java`

**Step 1: Write failing tests**

Assert phase progress is monotonic, stays below 100 before `PUBLISH` succeeds, and a failed/cancelled task records a terminal message. Add a frontend type-level assertion or focused unit test where available for the expanded DTO.

**Step 2: Run red tests**

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am -Dtest=RenderTaskTest test
```

Expected: phase APIs are absent.

**Step 3: Implement minimally**

- Use `IMPORT`, `EXTRACT`, `ASSETS`, `HIRES`, `LOWRES`, `PUBLISH` phase values with fixed weights.
- Persist phase with task records and return it through admin APIs/SSE.
- Pass `{ module: 'world-map', publicAccess: false }` when uploading world archives or client jars.
- Render the active phase, percentage, and failure/cancel explanation in the admin detail page.

**Step 4: Run green tests, typecheck, and build**

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am -Dtest=RenderTaskTest test
pnpm --filter @yudream/plugin-world-map run typecheck
pnpm --filter @yudream/plugin-world-map run build
```

**Step 5: Commit**

```powershell
git add yudream-plugins/yudream-plugin-world-map yudream-frontend/packages/plugin-world-map
git commit -m "feat: report phased world map rendering"
```

### Task 3: Build a sparse world render manifest

**Files:**
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/infrastructure/world/WorldTileManifest.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/infrastructure/world/WorldArchive.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/infrastructure/world/anvil/RegionFile.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/infrastructure/render/DefaultWorldMapRenderer.java`
- Test: `yudream-plugins/yudream-plugin-world-map/src/test/java/online/yudream/plugin/worldmap/infrastructure/world/WorldArchiveTest.java`

**Step 1: Write failing tests**

Create two distant region fixtures with one populated chunk each. Assert the manifest contains only the two corresponding 32x32 render tiles, never the rectangular range between them. Assert the count uses `long`.

**Step 2: Run red tests**

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am -Dtest=WorldArchiveTest test
```

Expected: the existing range algorithm schedules empty intermediate tiles.

**Step 3: Implement minimally**

- Read Anvil location headers through `RegionFile.hasChunk()`.
- Build a deterministic set of tile coordinates from actual chunks plus the required border policy.
- Make render progress and task totals use the manifest count.
- Retain bounds only for metadata and legacy cleanup, not scheduling.

**Step 4: Run green tests**

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am -Dtest=WorldArchiveTest,RegionFileTest test
```

**Step 5: Commit**

```powershell
git add yudream-plugins/yudream-plugin-world-map
git commit -m "fix: render only populated world map tiles"
```

### Task 4: Introduce immutable map generations and manifest-based asset routes

**Files:**
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/domain/aggregate/MapGeneration.java`
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/application/service/GenerationPublisher.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/infrastructure/storage/TileStorage.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/application/service/RenderOrchestrator.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/interfaces/controller/PublicMapController.java`
- Test: `yudream-plugins/yudream-plugin-world-map/src/test/java/online/yudream/plugin/worldmap/application/service/GenerationPublisherTest.java`

**Step 1: Write failing tests**

Assert an incomplete/failed generation cannot replace the active one, a published generation changes URLs/cache version, and the old active assets remain available while staging is removed.

**Step 2: Run red tests**

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am -Dtest=GenerationPublisherTest test
```

**Step 3: Implement minimally**

- Store assets below `maps/{mapId}/generations/{generationId}/`.
- Publish only after manifest and atlas checks succeed.
- Return immutable cache headers for generation URLs and stream stored content instead of eagerly reading it into a byte array.

**Step 4: Run green tests and package**

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am -Dtest=GenerationPublisherTest,TileStorageTest test
mvn -pl yudream-plugins/yudream-plugin-world-map -am package -DskipTests
```

**Step 5: Commit**

```powershell
git add yudream-plugins/yudream-plugin-world-map
git commit -m "feat: publish immutable world map generations"
```

### Task 5: Add a Java 21 BlueMap CLI worker adapter

**Files:**
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/application/port/out/MapRenderEngine.java`
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/infrastructure/render/bluemap/BlueMapCliRenderEngine.java`
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/infrastructure/render/bluemap/BlueMapCliLocator.java`
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/resources/THIRD-PARTY-NOTICES/BlueMap-MIT.txt`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/bootstrap/WorldMapPlugin.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/application/service/RenderOrchestrator.java`
- Test: `yudream-plugins/yudream-plugin-world-map/src/test/java/online/yudream/plugin/worldmap/infrastructure/render/bluemap/BlueMapCliRenderEngineTest.java`

**Step 1: Write failing tests**

Use a fake executable to assert the adapter supplies the configured Java executable, pinned v5.16 CLI JAR, isolated working directory, bounded JVM memory, cancellation signal, and rejects malformed output before publication.

**Step 2: Run red tests**

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am -Dtest=BlueMapCliRenderEngineTest test
```

**Step 3: Implement minimally**

- Resolve the v5.16 CLI from an explicit administrator-configured path with SHA-256 verification. Do not download at render time.
- Launch it with Java 21, a bounded `-Xmx`, per-task temp directory, timeouts, and redirected structured logs.
- Read only a validated output manifest, copy it into the staging generation, and leave the previous generation active on any failure.
- Make cancellation terminate the process and await its exit before persisting the terminal task state.

**Step 4: Run green tests**

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am -Dtest=BlueMapCliRenderEngineTest test
```

**Step 5: Commit**

```powershell
git add yudream-plugins/yudream-plugin-world-map
git commit -m "feat: add isolated BlueMap CLI render adapter"
```

### Task 6: Replace unbounded frontend tile scheduling with an adapter contract

**Files:**
- Create: `yudream-frontend/packages/plugin-world-map/src/map/engine/MapEngineAdapter.ts`
- Create: `yudream-frontend/packages/plugin-world-map/src/map/engine/TileRequestScheduler.ts`
- Modify: `yudream-frontend/packages/plugin-world-map/src/map/MapViewer.ts`
- Modify: `yudream-frontend/packages/plugin-world-map/src/map/TileManager.ts`
- Modify: `yudream-frontend/packages/plugin-world-map/src/api/world-map-api.ts`
- Test: `yudream-frontend/packages/plugin-world-map/src/map/engine/TileRequestScheduler.test.ts`

**Step 1: Write failing tests**

Assert central/view-frustum tiles are requested before edge tiles, stale view requests are aborted, 404s are generation-scoped negative cached, transient failures use bounded backoff, and repeated distant jumps keep pending work bounded.

**Step 2: Run red tests**

Add the package test script if absent, then run:

```powershell
pnpm --filter @yudream/plugin-world-map test -- TileRequestScheduler
```

Expected: scheduler module is absent.

**Step 3: Implement minimally**

- Use a monotonically increasing view generation and `AbortController` per stale request.
- Rank requests by projected screen distance and camera direction.
- Cap active and queued work; keep failed and empty tile state separate.
- Move the existing engine behind `MapEngineAdapter` so BlueMap PRBM support can replace only the adapter implementation.

**Step 4: Run green tests, typecheck, and build**

```powershell
pnpm --filter @yudream/plugin-world-map test -- TileRequestScheduler
pnpm --filter @yudream/plugin-world-map run typecheck
pnpm --filter @yudream/plugin-world-map run build
```

**Step 5: Commit**

```powershell
git add yudream-frontend/packages/plugin-world-map
git commit -m "fix: bound world map tile scheduling"
```

### Task 7: Correct rendering lifecycle, alpha handling, and BlueMap viewer module bridge

**Files:**
- Create: `yudream-frontend/packages/plugin-world-map/src/bluemap-adapter/`
- Modify: `yudream-frontend/packages/plugin-world-map/src/map/material.ts`
- Modify: `yudream-frontend/packages/plugin-world-map/src/map/MapViewer.ts`
- Modify: `yudream-frontend/packages/plugin-world-map/src/map/TileManager.ts`
- Modify: `yudream-frontend/packages/plugin-world-map/src/map/controls/CameraController.ts`
- Modify: `yudream-frontend/packages/plugin-world-map/src/composables/useWorldMapViewer.ts`
- Modify: `yudream-frontend/packages/plugin-world-map/src/styles.css`
- Test: `yudream-frontend/packages/plugin-world-map/src/map/material.test.ts`

**Step 1: Write failing tests**

Assert cutout textures discard transparent pixels, translucent materials preserve texture alpha, lowres respects alpha, idle rendering sleeps, URL round-trips map/camera/projection, and changing maps disposes/aborts prior resources.

**Step 2: Run red tests**

```powershell
pnpm --filter @yudream/plugin-world-map test -- material
```

**Step 3: Implement minimally**

- Split opaque, cutout, and translucent material paths.
- Request animation only while controls, tile loads, fades, or layers changed.
- Introduce the BlueMap PRBM/lowres/control adapter bridge under a pinned MIT notice; do not import BlueMap menus or Vue application shell.
- Support perspective, orthographic, free-flight, compass/reset, coordinates, map choice, and responsive touch controls through the YuDream page shell.

**Step 4: Run green tests and browser validation**

```powershell
pnpm --filter @yudream/plugin-world-map test
pnpm --filter @yudream/plugin-world-map run typecheck
pnpm --filter @yudream/plugin-world-map run build
```

Run desktop and 390x844 browser smoke tests against a fixture generation. Check blank state, console health, controls, a distant jump, URL restore, and idle resource use.

**Step 5: Commit**

```powershell
git add yudream-frontend/packages/plugin-world-map
git commit -m "feat: adapt BlueMap viewer rendering controls"
```

### Task 8: Expose the stable map extension API and layer UI

**Files:**
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/base/plugin/worldmap/api/PluginWorldMapService.java`
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/base/plugin/worldmap/api/PluginWorldMapLayerProvider.java`
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/base/plugin/worldmap/api/PluginWorldMapMarker.java`
- Create: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/base/plugin/worldmap/api/PluginWorldMapMarkerSet.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/bootstrap/WorldMapPlugin.java`
- Modify: `yudream-plugins/yudream-plugin-world-map/src/main/java/online/yudream/plugin/worldmap/interfaces/controller/PublicMapController.java`
- Modify: `yudream-frontend/packages/plugin-world-map/src/map/MarkerLayer.ts`
- Modify: `yudream-frontend/packages/plugin-world-map/src/pages/Viewer.vue`
- Test: `yudream-plugins/yudream-plugin-world-map/src/test/java/online/yudream/plugin/worldmap/api/PluginWorldMapServiceTest.java`

**Step 1: Write failing tests**

Assert provider records never expose BlueMap, filesystem, or host-internal types; unavailable optional providers do not prevent public map access; layer visibility filtering is deterministic.

**Step 2: Run red tests**

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am -Dtest=PluginWorldMapServiceTest test
```

**Step 3: Implement minimally**

- Publish the provider service through the plugin context using only the public API package.
- Merge marker sets into public DTOs, preserve layer IDs in URL state, and render point/line/region overlays in the frontend bridge.
- Keep live Minecraft server data optional through a soft dependency and runtime capability check.

**Step 4: Run green tests, build, and package**

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am test
pnpm --filter @yudream/plugin-world-map run typecheck
pnpm --filter @yudream/plugin-world-map run build
mvn -pl yudream-plugins/yudream-plugin-world-map -am package -DskipTests
```

**Step 5: Commit**

```powershell
git add yudream-plugins/yudream-plugin-world-map yudream-frontend/packages/plugin-world-map
git commit -m "feat: expose extensible world map layers"
```

### Task 9: Run repository and visual acceptance gates

**Files:**
- Modify only when verification finds a defect.

**Step 1: Run all validation**

```powershell
$env:JAVA_HOME='C:\Users\SiberianHusky\.jdks\ms-21.0.10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl yudream-plugins/yudream-plugin-world-map -am test
pnpm --filter @yudream/plugin-world-map run typecheck
pnpm --filter @yudream/plugin-world-map run build
mvn -pl yudream-plugins/yudream-plugin-world-map -am package -DskipTests
& 'C:/Program Files/Git/bin/sh.exe' ci/verify-plugin-repo-readiness.sh
```

**Step 2: Run rendered checks**

Test a real fixture at desktop and mobile sizes. Verify no error overlay, no permanent loading state, phase updates, map controls, map switching, URL restore, layer toggle, stale-request cancellation, and rendering idles when unchanged.

**Step 3: Commit remediation only if necessary**

```powershell
git add <verified files>
git commit -m "fix: satisfy world map acceptance checks"
```

