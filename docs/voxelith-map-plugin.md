# Voxelith Map Plugin

`voxelith-map` renders a Minecraft world archive into a freely navigable 3-D map on top of the
Voxelith voxel render core. It coexists with the BlueMap-based `world-map` plugin: the two solve the
same problem with independent engines, storage layouts and front-end viewers.

The Voxelith source repository is consumed **read-only**. Nothing under this plugin patches,
recompiles, forks or vendors Voxelith Java sources; every artifact below is taken from an upstream
build output and packaged byte-for-byte.

## Engine Integration

| Bundled resource | Origin | Packaging |
| --- | --- | --- |
| `voxelith/server/voxelith-server.jar` | `apps/voxelith-server/build/libs/voxelith-server.jar` | Extracted to the runtime root, launched as a child process. |
| `voxelith/worker/runtime-worker.jar` | `modules/runtime-worker/build/libs/runtime-worker.jar` | Materialized with the shard-worker heap. |
| `voxelith/worker/gson-2.13.1.jar` | Voxelith worker classpath | Passed through `--worker-classpath`. |
| `THIRD-PARTY-NOTICES/Voxelith-MIT.txt` | Voxelith `LICENSE` (MIT) | Required by the packaged `store.json` license field. |

The engine is started as a subprocess, never in-process. `VoxelithProcessRunner` builds the argument
vector from a `Spec`, streams stdout through a bounded 64 KiB tail buffer and exposes the live
handle as `ActiveProcess` so long-running work (panorama, shard worker) can be polled and stopped.

### Launch posture

The bundled engine is a Spring Boot **fat jar**. This has one hard consequence:

```text
java -Dloader.main=<CLI class> -cp voxelith-server.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher <args>
```

`-jar` always selects `JarLauncher`, which cannot choose a main class and therefore cannot dispatch
to a CLI entry point. Every invocation in `VoxelithCliRenderEngine` goes through `PropertiesLauncher`,
and `VoxelithCliArguments` owns the argument vector so the exact strings stay unit-tested.

The four upstream CLI entry points are used as-is:

| CLI class | Used for |
| --- | --- |
| `RenderMapCli` | Full render (hires + LOD tiles, manifest, atlas). |
| `HarvestModelsCli` | Model geometry harvest, cached as `models.json.gz`. |
| `PanoramaCli` | Perspective PNG bake from the previous render's intermediates. |
| `ShardWorkerCli` | Distributed shard worker process. |

### Sidecar

An unmodified `voxelith-server` process is kept as a sidecar to serve the biome-tinted 2-D preview
used for region selection. The sidecar is **preview-only**: in fat-jar form Voxelith's
`RenderProcessLauncher.canSpawn()` is always `false`, so a sidecar that rendered internally would
run the whole render inside the web process and exhaust its heap. The plugin therefore never routes
rendering through the sidecar — all rendering goes to a `RenderMapCli` child process.

### Uploaded Archive Compatibility

The sidecar unpacks world archives with the JDK's `ZipInputStream`, which rejects any entry that
sets the data-descriptor bit (general purpose bit 3) while using `STORED` rather than `DEFLATE`:

```
java.util.zip.ZipException: only DEFLATED entries can have EXT descriptor
```

`WorldStore.unzip` turns that into HTTP 400, so the admin page only ever shows
「生成预览底图失败」. The input is not exotic: a 26 MB archive produced by a common third-party
zipper had **all 134** entries in `STORED` + data-descriptor form. A `ZipFile` reader (central
directory) accepts such an archive, a `ZipInputStream` reader (local header) does not — which is why
the same world can render while its preview refuses to open.

The upstream repository is read-only and the archive has to reach the sidecar for cataloguing, so
`WorldArchiveNormalizer` repairs it plugin-side. Before the upload it probes the archive in a single
sequential pass over the local headers (no decompression, ~60 ms for the 26 MB sample); on a hit it
rewrites an equivalent archive — same entry names, contents, directory entries and timestamps,
entries that were stored are copied straight through without re-compression, the rest stay streaming
DEFLATE — and the multipart file name stays the original one, because the sidecar derives the upload
id from it. Archives the sidecar can already read are uploaded untouched.

## Path Parameters Are Percent-Decoded Plugin-Side

The host hands the plugin a **non-decoded** request path: `PluginDispatchController#pluginPath` takes
`HttpServletRequest.getRequestURI()` and strips the `/api/plugins/{code}` prefix, and per the servlet
spec `getRequestURI()` is never percent-decoded. A map id is allowed to contain non-ASCII characters
by design (`slug()` only strips separators), so `/admin/maps/%E4%B8%87%E4%BD%9B%E5%B1%B1%E5%9C%B0%E5%BD%A2-27c4c`
reached the plugin as the literal escape text and every id-based endpoint (detail, preview, markers,
panorama, shard worker) answered `地图不存在: %E4%B8%87...`.

`RequestPaths` is the single place that fixes this: `segments()` / `segment()` decode every path
segment, and `join()` rebuilds a decoded relative path (never derive storage keys with `substring` on
the raw path). `+` is deliberately kept literal — this is a path, not a query string. Host route
matching is unaffected: `{mapId}` matches one segment whatever it contains.

## Child Processes Always Receive Absolute Paths

Every CLI child runs with its **working directory set to the per-task temporary directory**
(`<runtime-root>/tasks/render-{taskId}`), not the host's working directory. A relative path handed
over on the command line is therefore resolved *relative to that task directory* by the child, which
silently poisons all of them at once:

- `-Xlog:gc:file=dataoxelith-map	asks\...
ender-gc.log` — the nested directory does not exist,
  so the JVM refuses to start at all (`Error opening log file ... No such file or directory`,
  `Invalid -Xlog option`), before any Java code runs;
- `-cp dataoxelith-map
untime\...oxelith-server.jar` — the boot class is not found
  (`找不到或无法加载主类 ...PropertiesLauncher`), which is exactly what a failed harvest looks like;
- `--world-dir`, `--packs`, `--work-dir`, `--publish-dir`, `--models-file`, `--worker-classpath` —
  the CLI would read and write under a doubled prefix.

`VoxelithRuntimeConfig` therefore absolute-normalizes every path in its constructor (runtime root,
java executable, engine jar, worker classpath, models file), and the engine additionally normalizes
the task directory it derives work/publish/GC-log paths from. The sidecar does the same for its
`-jar` target. Relative *settings* values stay supported — they are simply resolved against the host
working directory once, which is the same base the host uses for the files it materializes itself.

## Storage Layout

`PluginFileStore` is an **object store**: `put` / `get` / `delete` on flat string keys. It exposes no
local filesystem path and no "serve this directory" API. Consequently tiles can never be handed to
the browser by directory mapping — every artifact is read back as bytes and re-emitted with an
explicit `Cache-Control`.

```text
maps/{mapId}/generations/{generationId}/{manifest.json,tiles/**,atlas.png,lod-atlas/**,panorama.png,aerial.png,panorama.json}
maps/{mapId}/generations/{generationId}/generation-manifest.txt
maps/{mapId}/markers.json
maps/{mapId}/world.zip
maps/{mapId}/client.jar
maps/{mapId}/sidecar.json
```

* Markers are **map-level**, not generation-level: annotations survive a re-render.
* The generation manifest lives *inside* the generation prefix and records **full keys**. It is the
  only reclamation index `deleteGeneration` has; registering relative paths would silently make a
  generation unreclaimable, because `deleteGeneration` filters the listed keys by
  `generationPrefix(mapId, generationId)` before deleting.
* Publication is atomic: a generation becomes visible only after
  `GenerationPublisher.stage` → `requirePublishable` → activate. Failed or cancelled renders never
  replace the generation currently being viewed.
* Every read goes through `MapArtifactStorage.find`, which maps a failed read onto `Optional.empty()`.
  The host's `PluginFileStore.get` **throws** when the object is missing (S3 `NoSuchKey` →
  `BizException("文件不存在")`) instead of returning `null`, and the SPI exposes no `exists`. Without
  that translation a map created but not yet rendered would break the whole admin list, because
  `listAdmin()` reads `sidecar.json` for every map.

## Viewer Modes, Aim Feedback and Coordinate Teleport

The public viewer (`Viewer.vue`) plus the wrapper (`src/viewer/VoxelithViewer.ts`) add four things on top
of the vendored render core — all in plugin code, never in `src/vendor/**`:

* **Two paths to a flat view, deliberately separate.** The 俯视倾斜 mode slot *is* the previous
  "2D map" display: the straight-down camera (`TopDownControls` — same tile set seen from above, drag
  pan, wheel zoom, click picking); entering the mode engages it, leaving restores the chosen 3D camera.
  The 二维地图 button is the real 2D map: the LOD aerial atlas page baked by the render task
  (`tiles/lod/{lvl}/lod-atlas.png`, lowest level = highest resolution), displayed as a plain
  `<img>` element via `composables/useMap2D` — decode and rendering are the browser's job (a canvas
  approach kept hitting "ready but blank" states tied to measure/draw timing), pan/zoom is a CSS
  transform, and click-to-take-coordinates works the same. The image the 2D map consumes is **the
  the render's **surface raster** (`surface.png`, 1 pixel = 1 block — the same raster the backend
  panorama renderer consumes), imported into the generation's artifacts when a render finishes
  (`VoxelithArtifactImporter.importSurface`) and served anonymously at
  `GET /maps/{id}/surface.png`. One pixel per block is the maximum meaningful precision, it stays
  crisp at any zoom (`image-rendering: pixelated`), and the 1:1 mapping against the manifest bounds
  makes click-to-coordinate and teleport centering exact. Maps rendered before this change (no
  imported raster) fall back to the LOD atlas page. The world↔image
  mapping comes from the manifest bounds. The two never reach into each other's state: an atlas-less
  old map shows an explicit "重新渲染即可生成" hint instead of silently hijacking the camera. Both
  survive switching maps or dimensions and re-resolve per map.

  **How the atlas image is fetched** (this went through three rounds on a live site where the image
  request hung forever with no error): the preferred channel is the plugin endpoint
  `GET /maps/{id}/lod-atlas-data`, which returns the best atlas page (lowest level) as **raw PNG
  bytes** on a path with no image extension and no query string — the same transport shape as `manifest.json`, with no image file extension and no query
  string, because gateways that apply static-file rules to image paths (swallowing or hanging the
  request while `.json`/`.glb` pass) were exactly what starved the direct `.png` fetches. The public
  `.png` URLs (with, then without, the `?sha=` cache-buster) and a final `<img>` fallback remain as
  lower-priority candidates — and the **first** candidate is the plugin data endpoint
  (`/maps/{id}/lod-atlas-data`), the transport shape that has survived every gateway seen so far
  (no image extension, no query string, always answers with a status). The browser-native `<img>` load
  of the query-string-free atlas URL — the lightest path, decoded off the main thread — follows; on one
  gateway, requests to image paths neither complete nor error, which is why it is not first. The
  loading chip names the attempt currently in flight, and a per-attempt log (label, outcome, elapsed)
  renders in the overlay's corner while loading or failed — a screenshot of it is a complete remote
  diagnosis — the panel appears immediately with the in-flight attempt (an earlier version only
  populated it after a candidate finished, which read as "no panel at all").

  **And the data endpoint serves the atlas in chunks.** On the live deployment a whole-atlas response
  (multiple MB) hung in the gateway layer forever while manifests and tiles — small responses — passed;
  `GET /maps/{id}/lod-atlas-data/meta` therefore returns `{size, chunkSize, sha1}` and
  `GET /maps/{id}/lod-atlas-data/{index}` serves 512 KiB slices, which the frontend fetches
  sequentially (per-chunk 10 s timeout, progress in the panel) and reassembles into the display image.
  The unchunked `/lod-atlas-data` (60 s budget) remains for older frontends, followed by the
  atlas-URL and `<img>` fallbacks. The controller caches the atlas bytes per map+generation (single
  entry, ≤64 MB) so the chunk requests don't each re-read the whole file — chunk count times file size
  is quadratic disk traffic on big atlases — and the frontend's overall watchdog **extends** its
  deadline on every received chunk, so a healthy multi-hundred-chunk download is never killed by a
  fixed cap; only genuine stalls (no progress past the deadline) fail. The endpoint reads the
  atlas via the same artifact key the 3-D tiles use
  (`maps/{mapId}/generations/{gid}/tiles/lod/{lvl}/lod-atlas.png` — the host maps that onto its
  `data/voxelith-map/work/{gid}/…` tree on disk), pinned by an object-key layout test. Every candidate carries an abort timeout that covers the
  **whole** attempt — request, body read and decode — because a timeout armed only until the response
  headers arrive lets a stalled body (big base64 payload buffered by a proxy, connections exhausted by
  the 3-D tile loading underneath) hang forever; the observed behaviour was 「加载中」 with no error
  until the user switched maps and the next map's attempt failed fast. The whole `load()` is in turn
  wrapped in a hard deadline, so 「加载中」 terminating is structural rather than a property of every
  call site remembering its own timeout, and a decode that succeeds after its attempt was aborted is
  discarded (the shared `Image` would otherwise let it overwrite a later candidate's result). Each
  candidate's failure reason (HTTP status / timeout / non-image response) is shown in the status chip.

  **And the environment itself was the final culprit.** On maps whose LOD atlas textures fail (this
  host 400s query strings; the vendored renderer's own `?sha=` cache-buster hits exactly that), the
  render loop uploads empty textures every frame and spews thousands of console warnings, which — with
  DevTools open — starve the main thread so badly that the 2D chain's timers and callbacks barely run:
  「加载中」 until switching maps disposes the 3-D viewer and frees the thread, at which point the
  queued failures surface in a burst. Opening the 2D map therefore **pauses the render loop**
  (`VoxelithViewer.setPaused`: stop the engine's animation loop; resume with an equivalent callback
  driving `MapEngine.render()`, which runs the frame hooks and then renders), and closing it resumes.
  The paused 3-D frame stays on the canvas underneath the overlay, and the per-frame empty-texture
  uploads (plus the futile LOD-tile retries) stop while the 2-D map is up. `RequestPaths.segments` additionally strips query strings defensively (unit-tested). On the
  deployment that motivated all this, the host 400s **any** plugin request carrying a query string —
  which also breaks the vendored 3D stack's `?sha=` cache-buster on LOD tiles; that fix belongs to the
  host/gateway, not the plugin.
* **Aim crosshair** — while picking an annotation from a non-tilt mode (in tilt mode picking is
  click-based, so a crosshair would be meaningless), a screen-centre crosshair plus a per-frame
  `setAimHighlight` mark the block under the view direction; the HUD shows its XYZ. Aim and recorded
  corner highlights are kept in separate slots, so the per-frame update never clears the corners.
* **Coordinate teleport** — X/Y/Z inputs feed `flyTo` in 3D and the top-down controls' centre in 2D;
  "取焦点" fills them from the current view hit (or the camera position). No server work.
* **俯视倾斜 is the straight-down view.** After two rounds of churn (tilt-orbit camera → straight-down
  camera labelled "2D 地图" → tilt-orbit restored), the settled arrangement is: the 俯视倾斜 slot shows
  the straight-down camera effect users approved, and the aerial-image 2D map lives on the 二维地图
  button.

### Keyboard input and the Esc clash

The vendored control classes listen on `window` directly and (except `TiltOrbitControls`) never check
whether the event landed in a text field, so typing a map name used to drive the camera. Two guards fix
it: the keymap's capture-phase interceptor skips events whose target is editable, and the viewer installs
its own capture-phase `keydown` guard that stops propagation for editable targets, so the vendored
bubble listeners never see the key. `keyup` is deliberately not swallowed, so a held key still gets
released.

Esc means "exit fullscreen" to the browser and used to mean "cancel the pick" to the plugin at the same
time. They are now split by state: **in fullscreen Esc belongs to the browser**, and cancelling a pick
uses the new, rebindable `annotateCancel` action (default `Backspace`); outside fullscreen Esc still
cancels the pick, and the pick hint spells out which key is active.

## Public Artifact URLs Must Match the Vendored Engine's Templates

`PublicVoxelithMapController` exists only so the vendored renderer can fetch artifacts without
modification. Its route table is therefore dictated by two external parties and breaks on upstream
syncs if not re-checked (2026-09-25: tiles 404 + atlas 400 in the public viewer):

1. **The vendored engine's URL templates** (`vendor/voxelith/core/index.ts` `tileUrl`): upstream
   `4200402+` requests `/tiles/hires/{x}/{z}.glb` and `/tiles/lod/{level}/{x}/{z}.glb` — the tail
   segment already carries `.glb`. Appending `.glb` again in the controller produces `{z}.glb.glb`
   and every tile 404s (`产物不存在`). Fix: normalize the tail segment with `glbName(...)`
   (accept both with and without suffix).
2. **The manifest's own `url` fields**: `TileManager` loads `lodAtlases[].url` verbatim as
   `${mapBaseUrl}/${page.url}`. Upstream moved the page address from `lod-atlas/{lvl}.png` to
   `tiles/lod/{lvl}/lod-atlas.png`; the old route `/maps/{id}/lod-atlas/{name}` no longer matches
   (different segment count) and the host answers 400 `插件接口不存在`. Because storage is
   manifest-driven (each `url` becomes the object key verbatim), a route for the new layout
   (`/maps/{id}/tiles/lod/{level}/{name}`) reads the same object back. Old-layout routes are kept
   for generations imported by older manifests.

Diagnostic shortcut: a 400 with body `插件接口不存在` means the request path matched **no registered
endpoint at all** (route/segment-count mismatch); a 404 with body `产物不存在` means the route
matched but the derived object key is wrong (path-building bug). Check the controller first on any
upstream engine sync.

## Persistent Work and Publish Directories

Two features read from a directory that a normal render would delete on completion, so the render
directories are made persistent under the runtime root:

| Path | Purpose |
| --- | --- |
| `<runtime-root>/work/{mapId}` | Render work directory. `PanoramaCli` reads `surface.png`, `surface.json` and `heightfield.bin` from here. |
| `<runtime-root>/panorama` | Panorama output directory. |
| `<runtime-root>/queue` | Shared shard queue directory. |
| `<runtime-root>/publish/{mapId}` | Disk-side publish directory. `ShardWorkerCli` requires `publishDir/<mapId>` to exist and writes into it directly. |

Map ids are sanitized through `VoxelithRuntimeConfig.safeSegment` before being used as path
segments, so an id can never escape the runtime root.

> **1.0.8 regression note.** `VoxelithCliRenderEngine.render` once substituted task-local
> `work/` and `publish/` directories for the persistent ones the orchestrator passed in via
> `RenderInvocation`. The task directory is deleted when the task finishes, so `surface.png` /
> `heightfield.bin` and the disk-side publish copy were never written — every panorama bake
> failed with "找不到渲染中间产物（surface.png/heightfield.bin）" and every shard worker start
> failed with "磁盘上找不到该代的产物目录". The engine now passes `invocation.workDir()` /
> `invocation.publishDir()` straight through to the CLI; the task directory is only the child
> process working directory and log location. Because the disk publish copy is per generation
> (`publish/{mapId}/{generationId}` — the orchestrator hands the CLI the generation id as the
> render map id, and `ShardWorkerService` does the same for the worker), the orchestrator also
> prunes generation directories that are no longer referenced after the publish pointer moves.

## Settings

All keys are read through the host framework settings service with the `yudream.voxelith-map.`
prefix. Only `runtime-root` normally needs attention.

| Setting | Required | Purpose |
| --- | --- | --- |
| `yudream.voxelith-map.runtime-root` | no | Runtime root holding the materialized engine, work, panorama, queue and publish directories. Default `data/voxelith-map`. |
| `yudream.voxelith-map.java-path` | no | Java 21 executable used for child processes. Default is the current JVM's `java.home/bin/java`. |
| `yudream.voxelith-map.engine-jar` | no | External engine fat jar, for troubleshooting a pinned build. Default is the bundled jar. |
| `yudream.voxelith-map.worker-classpath` | no | External worker classpath, comma- or semicolon-separated. Default is the bundled worker jar set. |
| `yudream.voxelith-map.models-file` | no | Explicit `models.json.gz`, bypassing the harvest cache lookup. |
| `yudream.voxelith-map.heap-mib` | no | Render child-process heap, default `4096`. Also used for the panorama bake. Raise this (e.g. `8192`+) when a render fails with exit code 3 or 4. `max-chunks` (per-map render setting) is a guard: the window's non-empty chunk count must stay below it, and the bake stage keeps every window chunk mesh in memory at once — so a large region needs both a large `max-chunks` and a large heap. `batch-chunks` only bounds the harvest stage and does NOT reduce bake memory. Sliced/parallel rendering of one region is an engine-side (shard pipeline) capability and is not reachable through the CLI surface. |
| `yudream.voxelith-map.timeout-minutes` | no | Single render timeout, default `180`. Acts as a **floor**: when the render window is known (a framed region) the budget is scaled by chunk count (~0.6 s/chunk, ≈20× the measured bake rate, capped at 24 h) — see `RenderBudget`. A large window is thus widened automatically, and an explicitly larger value is never shrunk. Whole-world renders (no region) have no chunk estimate and keep the configured value. |
| `yudream.voxelith-map.max-concurrent-tasks` | no | Concurrent render tasks, default `1`. |
| `yudream.voxelith-map.sidecar-enabled` | no | Serve the 2-D selection preview from a sidecar, default `true`. |
| `yudream.voxelith-map.sidecar-port` | no | Sidecar port; `0` picks a free port. |
| `yudream.voxelith-map.sidecar-heap-mib` | no | Sidecar heap, default `2048`. |
| `yudream.voxelith-map.oom-retry` | no | When a render dies with exit code 4 (out of memory), automatically retry once with double the child heap (capped at 32 GiB). Default `true`. |

The render invocation now carries `--worker-classpath` (bundled worker jars or the external setting), so when a models file is not provided the engine can harvest models in-render instead of falling back to static parsing ("模型来源： 静态解析"). Task logs state explicitly whether in-render harvest is armed.

For region-scoped renders the plugin pre-sizes the child heap from the window scale (~0.35 MiB per chunk, 256 MiB aligned, capped at 32 GiB) so the first attempt usually succeeds without a wasted OOM cycle; the configured `heap-mib` acts as the floor and values above 0 disable pre-sizing. The engine has no bake checkpoint: an OOM retry re-runs the whole bake, which is exactly what pre-sizing avoids. Note `--batch-chunks` only bounds the harvest stage — the engine may round the value up to align with region boundaries (e.g. 8192 → 8256), which is expected.

Setting the engine jar or worker classpath to a non-bundled build is a diagnostic escape hatch, not
a supported deployment mode — the shipped CLI argument contract is pinned to the bundled revision.

## Harvest Version Detection and the Model Cache

Model geometry (`models.json.gz`) is version-specific: harvesting 1.20.1 for a 1.21.1 world leaves
unresolved textures (rendered with the magenta fallback) and unmatched block models (rendered as
holes). `HarvestModelsCli` has **no auto-detection** — its `--mc-version` falls back to a hard-coded
constant — so a version must be decided *before* it is invoked.

The engine's own `RenderMapCli` resolves a version in this order:

```text
explicit --mc-version  ->  resource-pack (client jar) file name  ->  world level.dat version  ->  engine fallback
```

The plugin can only evaluate the first two: `level.dat` lives inside the uploaded archive and the
engine reads it itself. `RenderOrchestrator.mcVersionHint` therefore walks the same ladder and
**stops as soon as the evidence runs out** rather than inventing a value:

| Evidence | Behaviour |
| --- | --- |
| `settings.mcVersion` non-empty | Harvest with it into that version's bucket. |
| Empty, but `clientJarName` contains `1.x[.y]` | Harvest with it (`MinecraftVersions.versionOf`) into its bucket. |
| Empty and no version in the name | Pass **no** `--mc-version`, and defer the whole decision to the engine. |

The third row is deliberate. Guessing a version there is precisely what produced the magenta/hole
reports, and the engine resolves the world version correctly on its own. The cost is that such a map
harvests for itself instead of sharing a bucket, which is why the log line spells the trade-off out
and the create dialog asks for the version in the jar name. `RenderSettings.DEFAULT_MC_VERSION`
survives only as the engine-side fallback constant.

Harvest products are bucketed under the runtime root, one directory per version **and** pack:

```text
<runtime-root>/models/{mcVersion}-{shortDigest}/models.json.gz
<runtime-root>/provision-cache/                        # shared across every version
```

`modelsCacheKey` is `version + "-" + sha256(clientJarFileId)[0..8]`, extended to
`version + "-" + clientDigest + "-" + sha256(join("|", modJarFileIds))[0..8]` when the project binds
mod jars. The file id is part of the key because the same version can legitimately be rendered from
a different pack, and the short digest keeps the directory name readable. The mod part is omitted
when the mod list is empty, so legacy two-part buckets keep hitting — adding mod support did not
invalidate any existing cache. **The mod order participates in the digest**: pack order affects how
overrides layer, so two orderings of the same jars are deliberately different buckets. Reordering,
adding or removing any mod jar therefore forces a re-harvest instead of silently missing geometry.
`provision-cache` is deliberately **not** bucketed: it holds the downloaded Minecraft/Fabric
dependencies (~100 MB) and copying it per bucket would multiply disk use for nothing.

`yudream.voxelith-map.models-file`, when set, short-circuits all of the above. `EngineStatus.modelsCached`
and `modelsFile` follow the same rule: the explicit file if configured, otherwise any bucket under
`models/`.

The uploaded client jar is materialized under its **original name**
(`MinecraftVersions.jarFileName`, path-stripped and sanitized). Saving it as a neutral `client.jar`
would discard the most reliable evidence in the ladder above, since the engine prefers the
resource-pack file name over the world version.

## Mod Jars (Modded Rendering)

A map project can bind up to eight mod jars (`MapProject.modJars`, a list of `ModJarRef(fileId,
name)`). The upstream engine already accepts extra packs — mod jars simply layer on top of the
vanilla client jar in the resource-pack stack (`packs[0]` = vanilla, the rest follow in order) — so
the plugin's work is the data link: persisting the list, materializing the jars and feeding them to
every CLI invocation that takes packs.

* **Harvest** (`VoxelithCliRenderEngine.harvestModels`): each mod jar is passed as a repeatable
  `--mod` argument. The headless Fabric worker collects modded block models into the same
  `models.json.gz`; when a mod ships a custom loader the worker cannot statically resolve, it falls
  back to static parsing and those blocks simply lack geometry — an upstream limitation, not a
  plugin defect.
* **Render** (`RenderOrchestrator.prepare`): each mod jar is materialized next to the client jar
  under a sanitized name (`safeModJarFileName`: original basename, illegal characters replaced with
  `_`, `.jar` appended, prefixed `mod-<n>-` to keep order stable and collisions impossible) and
  appended to `--packs` in list order after the client jar.
* **Shard worker** (`ShardWorkerService`): materializes the same list the same way into its
  long-lived work directory, so worker and renderer always see identical packs.

### Update semantics

`UpdateMapCmd.modJars` is tri-state at the HTTP boundary: the controller forwards `null` when the
body omits (or nulls) `modJars`, meaning **leave unchanged**; a non-null list — including an empty
one — **replaces the whole list**. `MapProjectAppService.validatedModJars` drops blank entries and
verifies every `fileId` still exists in the plugin file store (`找不到上传的模组 jar：…` otherwise),
so a stale client cannot wedge the project into a state the next render cannot materialize.

### Admin UI

The create dialog accepts multiple mod jar uploads alongside the client jar (each is uploaded and
pushed into the payload after it). The map detail page shows the bound mods as tags and offers a
replace-or-clear block: uploading new files + save = replace the list, ticking clear + save = bind
nothing, touching neither = send no field at all. Any mod change only takes effect after the next
render — existing tiles are not retro-fitted.

**Auto-save feedback is specific.** The inline status does not just say 已自动保存: it names the fields
that actually changed and when — `已自动保存：名称、最低渲染高度 · 14:32:05`. The labels come from
diffing the current form against the last persisted snapshot (`composables/formDiff.ts`, a pure function
with unit tests, because a wrong diff either hides a real change or claims one that never happened —
both make users repeat work). Fields without a display name fall back to their path, and an empty diff
falls back to 渲染设置.

Selecting a zip uploads and parses it **with visible stages**: the panel shows 正在上传模组包… then
正在解析包内 jar… with an elapsed-seconds counter (parsing reads the whole pack — the central directory
sits at the end, so the server must materialize it first), and the finished listing carries the time it
was parsed. While that runs, 触发渲染 is disabled with an explanation: starting a 30-minute bake against a
mod set whose contents have not been confirmed yet is exactly the kind of surprise this avoids.

A bound mods pack additionally gets two actions in the mods section:

* **解析包内 jar** (`POST /admin/mod-packs/inspect`) — lists every mod jar inside the zip (name plus
  uncompressed size, sorted; capped at 500 entries with the true total still reported). It reads only
  the zip's central directory and never decompresses, but the host SPI hands out *sequential* streams
  while a zip's central directory sits at the end, so the server must materialize the whole pack
  locally first — one full object read. Hence it is an explicit, on-demand action with a loading state
  rather than something a page load triggers.
* **删除模组包** — clears just the pack reference (`updateMap` with `modsPackFileId: ''`); individually
  attached jars are untouched, and the change takes effect on the next render. Uploading a new pack
  replaces the same reference, so the semantics stay "one pack at most".

Selecting a zip now uploads and parses it **immediately** — in the map detail page and in the create
dialog alike (`composables/useModPackInspect.ts`). The listing appears at once, tagged 待保存 to separate
it from the pack already bound to the map, and the platform file id from that upload is remembered so
*saving reuses it instead of transferring the pack a second time* (`modsPackPreset` in the create flow
mirrors the existing `clientJarPreset` for client jars). Removing the zip from the upload list, or
closing the create dialog, discards the pending upload and its listing; a failed upload/parse only warns
(leaving the file in the list to be uploaded again on save) and never blocks the flow.

## Panorama

`PanoramaCli` bakes a perspective PNG so a client can show a map cover without drawing tens of
millions of triangles. It runs asynchronously and imports its three artifacts (`panorama.png`,
`aerial.png`, `panorama.json`) into the **currently published generation**, so a finished bake is
immediately visible on the public viewer.

Because it reads the surface grid and heightfield left behind by a render, a map must have been
rendered successfully at least once. When the intermediates are missing, the CLI exits with code `1`
and mentions `surface.png`; `PanoramaService` recognises that specific combination and reports
"render the map once, then bake again" instead of a generic failure.

Admin endpoints: `GET` / `POST /admin/maps/{mapId}/panorama`. The image itself is public:
`GET /maps/{mapId}/panorama.png`, `aerial.png`, `panorama.json`. The public endpoints are cached, so
the admin page appends a revision query parameter after a successful re-bake.

## Distributed Shard Worker

**Capability boundary.** The plugin hosts the worker **process**; it does not produce the shard
queue. The queue producer lives in Voxelith's own pipeline side (`RunPipelineUseCase` queue mode) and
has no command-line entry point. The plugin therefore only provides a shared queue directory that
external tooling or other workers write into. When the queue is empty, the worker exits by itself
after `idleMinutes`. The admin UI states this explicitly rather than implying that starting a worker
starts rendering.

On stop, artifacts written under `<runtime-root>/publish/{mapId}` with an mtime at or after the
worker's start time (minus a 2 s tolerance for filesystem timestamp granularity) are synced back into
object storage, and `syncedTiles` reports how many were returned. The disk publish directory is
reused across worker starts for the same world source instead of being unpacked again.

Admin endpoints: `GET` / `POST` / `DELETE /admin/maps/{mapId}/shard-worker`.

## Progress Reporting

Render progress is derived from child-process text output by `VoxelithProgressParser`, whose keyword
thresholds deliberately mirror upstream `RenderJobService.stageOf`:

```text
扫描区块 → 8   先采集模型几何 → 3   模型来源 → 10   非空区块 → 15
bake 完成 → 60   tile 完成 → 78   lod 完成 → 92   === 渲染完成 === → 100
```

Multi-pass renders print a batch line per pass, so the 15→94 span is spread across batches using the
same expression as upstream:

```text
percent = 15 + min(79, 79 * (index - 1) / total)
```

Every proportional branch uses integer floor division, so the sequence is monotonic by construction
and can never cross the ceiling of its own stage. `VoxelithProgressParserTest` pins the real upstream
wording, since this text protocol is the part most likely to break silently on an engine upgrade.

### Multi-Pass Rendering Requires a Prebaked Model

Batching is decided *inside* the engine (`RenderMapCli`), and its bytecode shows three conditions that
must all hold before `renderInBatches` is called:

```java
if (options.batchChunks() > 0                       // ① a batch size is configured
    && chunks.size() > options.batchChunks()        // ② the window is larger than one batch
    && prebakedQuadSource != null) {                // ③ a models.json.gz was loaded
    renderInBatches(...);                           // → logs 「多遍渲染：窗口 N 个非空区块 → M 批」
} else { singlePassBake(); }
```

Condition ③ is easy to lose: when the model harvest fails — typically because the mod set has an
unresolved dependency and the loader refuses to start (the `cristellib` case) — there is no
`models.json.gz`, so the engine silently falls back to a **single-pass bake**. Peak memory and wall
clock then scale with the whole window: large maps OOM or hit the timeout, and the only log trace used
to be "采集失败，退回静态解析", which says nothing about batching being switched off. Two plugin-side
changes close this:

* **Vanilla-only harvest retry.** If harvesting with the bound mods fails, the orchestrator retries
  once with an empty mod list. That yields a `models.json.gz`, which restores multi-pass rendering; the
  log states the trade-off (modded blocks will lack geometry).
* **A pre-render warning.** If batches are configured, the window is larger than one batch, and no
  prebaked model exists, the task log says so explicitly (「多批次渲染已失效，将单遍烘焙」) together
  with the consequence and the fix.

**Dimensions are one archive each.** A save has separate directories per dimension — `world`,
`world_nether`, `world_the_end` — and each needs its own map (`DIM-1`/`DIM1` legacy names and Chinese
keywords are recognised too). Manual upload derives the dimension from the uploaded file name
(`composables/dimensionFromName.ts`, unit-tested: the specific rule must win over the generic `world`
rule) and pre-selects it; an unrecognised name leaves the operator's choice alone.

**Instance import builds one map per dimension from a single transfer.** The job lists the instance
root, maps every recognised directory through `InstanceDimensionDir.dimensionOf` and packs them all
into one archive; `pick` keeps the *real* directory name, so a legacy `DIM-1` directory still resolves
inside the archive. The maps share that archive and each records its own directory as its `worldName`;
`WorldArchiveExtractor.locateWorldRoot(destination, rootHint)` then selects the `level.dat` whose parent
matches the hint and falls back to generic detection when it does not — a stale `worldName` (older rows
stored the map name) must never abort a render. Deleting a map reclaims storage only when no other map
references the same archive (`deleteImportedKeyUnlessShared`). The browser-transfer path (old panel
plugin) behaves the same way: it lists the root before packing, sends the directory list with
`completeImportJob`, and 「同步最新存档」 packs only that map's own directory instead of the whole save.

**Both pages switch dimensions, and the group is stored, not inferred.** Because each dimension is a map
of its own, switching dimension means switching to the sibling map, and the two pages differ in what they
offer:

| Page | Endpoint | Lists | Switch behaviour |
| --- | --- | --- | --- |
| Viewer (public) | `GET /maps/{id}/dimensions` | only dimensions with published artifacts | a 切换维度 select in the settings panel; switching syncs `?map=` so the URL stays shareable |
| Map detail (admin) | `GET /admin/maps/{id}/dimensions` | **all** dimensions, marking the ones with no artifacts yet | buttons with per-dimension 更新 (submit that dimension's render) and 下载 (download its archive); the current dimension uses the page's own buttons |

Both endpoints reuse the existing public/admin list DTOs (no new fields leak publicly), and
`composables/dimensionSwitch.ts` (unit-tested) turns the list into stable, deduplicated choices —
preferring the current map when a dimension appears twice.

On the admin page the **page owns the current map id** rather than reading `route.query.id` directly:
switching only changes the query (same route record), and if the host does not hand the new route down,
a route-derived id never changes and the click does nothing. `currentMapId` is assigned on switch and the
URL is synced; a watcher on the route value follows external changes (history, coming from the list).
That watcher also reloads everything the page shows — map, panorama, shard worker, client jar versions —
and releases the previous map's preview object URL and 3D preview.

Grouping is a stored `worldGroupId` (derived at creation from the archive, or assigned explicitly by an
instance import) **union the source MC panel instance**, and the group is the **transitive closure** over
both relations: two maps belong together when their group ids match *or* both point at the same instance.
The instance relation is what makes the feature work for the common case — maps imported from one
instance in several separate runs (even one dimension at a time) have different group ids, and matching on
the group id alone left every such map with no siblings at all. Manual uploads (no source instance, and
unrelated archives) stay ungrouped, and both relations are also why the closure matters: A shares an
instance with B while B shares an archive with C, so A, B and C must all be one group.

`worldFileId` is deliberately *not* one of the relations: syncing an instance world replaces that field,
which would silently break the group apart. Documents written before `worldGroupId` existed fall back to
the same archive-derived rule on read, so maps imported by 2.1.21 still switch. Switching maps mid-sync is
handled too: the sync captures its target map id up front and discards its upload if the page has moved on.

The admin page always renders the dimension row, including when there is only one dimension (it says why:
imports from one instance are grouped automatically) and when the list request failed (it shows the error).
Both are deliberate: the row is the page's only affordance for this feature, so "nothing appeared" must
never be the user-visible outcome of a data shape the grouping does not cover or of an unreachable
endpoint. `VoxelithEndpointLayoutTest` keeps the endpoint paths honest — duplicates and a wrong path
segment index both compile fine but surface as "接口不存在" or "查不到地图" in the UI.

**Viewer panel layout.** The settings panel is a two-column grid (label sized to content, control taking
the rest) rather than a flex row with `space-between`, which is what let a wide control overlap its label;
its height follows the content up to 200px and only then scrolls, instead of a fixed 200px box that either
left blank space or squeezed everything into a scroll area. The teleport dialog keeps the three coordinate
fields aligned in one grid, with the spinner buttons hidden and shrinking allowed, since a control with a
default minimum width breaks a three-column grid.

**Previews see the same archive the render does.** 2.1.23 briefly narrowed the preview upload to the map's
own dimension directory; that made previews *worse* (with the other dimensions removed the sidecar could no
longer find what it needs), so it was reverted. Preview and render consuming the same archive is the only
contract between this plugin and the upstream preview tool that holds.

**Legacy saves keep their dimensions inside `world/`.** Older servers put the nether and the end in
`world/DIM-1` and `world/DIM1`, with **no `level.dat`** of their own, instead of the modern sibling
directories. Two consequences, both fixed by normalising that layout at extraction time, driven by the
map's dimension (`WorldArchiveExtractor.extract(archive, destination, rootHint, dimension)`):

* Importing only looked one level deep, so a legacy save could only ever produce an overworld map. The
  instance probe now also lists `world/` and turns nested `DIM-1`/`DIM1` into nether/end maps
  (`InstanceDimensionDir.legacyNested`, which keeps `packPath = world` because the dimension data has no
  `level.dat` — the parent has to come along). Modern siblings win when both layouts are present.
* Rendering the overworld used to include the nested dimension directories, so their regions — at
  coordinates unrelated to the overworld — were folded into its bounds; the visible symptom was an
  overworld preview that was mostly blank, with the actual world in one corner. The overworld now drops
  the nested directories, while the nether/end maps get `world_nether`/`world_the_end` built from
  `DIM-1`/`DIM1` plus a copy of the world's `level.dat` (without it the engine's version check and the
  preview's cataloguing are blind).

「同步最新存档」 retries with the plain `world` directory when packing by the map's root name fails, which
is exactly the legacy case. The browser-transfer path detects the same layout: it packs the parent
directory and reports the normalised root names.

### Full Logs Are Kept, Not Just the Tail

`RenderTask` keeps only the last 4 000 characters (`MAX_LOG_TAIL_CHARS`) because a render prints a line
per batch — tens of thousands of lines would bloat the task document for no benefit. The child process'
full stdout *is* written to a file (`VoxelithProcessRunner` → `Spec.logFile()`), but that file used to
live inside the per-task temporary directory, which is deleted when the task ends — so "the log only
has its tail" was structural, and the interesting early lines (version warnings, model source) were
unreachable.

The orchestrator now moves that file to `<runtime-root>/logs/<taskId>.log` in the task's `finally`,
*before* deleting the task directory, and `TaskLogStore` owns the path, reads and retention
(`prune(30 days)` on plugin start; task ids are whitelisted before becoming file names). The admin
endpoint `GET /admin/tasks/{taskId}/log` returns it as `text/plain`, and the task centre as well as the
map detail page's task drawer offer 下载完整日志 next to the tail, whose caption now says it is a tail.
Tasks recorded before this version have no log file — the endpoint says so instead of returning an
empty body.

### Structured Diagnostics

The engine prints its most valuable output **first**: the version health warning, the resolved harvest
version and its evidence, the model source, entity and map-art statistics. A task keeps only the last
4 000 characters of the log tail, so by the time a render finishes those lines sit thousands of tile
lines away from the window and are effectively invisible — which is why a user staring at magenta
blocks had nothing to go on.

`VoxelithDiagnostics` consumes every child-process line and keeps the interesting ones in
`RenderTask.diagnostics` (at most 24 entries, 400 characters each), rendered by the admin page as
「渲染诊断」 above the raw log. It matches prefixes rather than whole sentences, and every entry keeps
the engine's own wording:

| Kept | Note |
| --- | --- |
| `!!! 警告：` block | Title and detail. The `后果：` (consequence) line is dropped as encyclopaedic; `处理：` (remedy) is kept. |
| `首次渲染：先采集模型几何（mc=… [判据] …）` | Collapsed to `采集版本：<version>（判据：<evidence>）`. |
| `模型来源` / `采集未产出` | Explicit artifact, cache hit or static-parse fallback. |
| `实体几何` / `装备缺贴图跳过` / `地图画：` | Entity (armour stand) and map-art statistics introduced by the 4200402 engine. |
| `跨版本贴图改名兜底` | Up to four samples, then a computed `…… 另有 N 种` line. |

The substitution block is the one place where the plugin does not simply forward the engine's text.
The engine only prints its own `…… 另有 N 种` summary **above 20** substitutions, while the admin page
shows four — so for 5 to 20 substitutions the engine emits no summary at all, and a pass-through
would quietly read as "there were only four". The total is therefore parsed from the header
(`兜底 6 种`) and the remainder reported by the plugin, which is also why the sample count and the
summary are unit-tested against the engine's real output.

`flushDiagnostics` runs before the task document is written and on every branch (success, failure and
cancel), so even a failed render carries its diagnostics.

## Permissions and Menus

The plugin declares one permission, `plugin:voxelith-map:manage`, guarding every `/admin/**`
endpoint. Public `/maps/**` endpoints are anonymous.

**Route layout.** Every page — the public viewer included — lives under
`/platform/plugins/voxelith-map/**` (`viewer`, `maps`, `gallery`, `map-detail`, `markers`,
`archived`, `tasks`), the layout the rest of the repo uses. No route occupies the bare
`/voxelith-map` prefix at all, and that is deliberate twice over:

* **Nothing may sit below a public plugin root.** The host guard (logged in + `notFound`) registers
  public plugin routes and re-enters the target when `to.path.startsWith(publicPath + '/')` matches.
  While the viewer owned `/voxelith-map`, every admin page below it matched that prefix, and on a
  hard refresh (dynamic routes not generated yet, or admin routes pruned by permission) the same path
  was re-entered forever — vue-router aborted startup, which reads as "刷新无响应". See the
  `Infinite redirect` row in Troubleshooting.
* **Route `name` is the menu registration key** (`pluginRegistrationKey = route:{module}:{name}`) and
  the host's menu projection only ever *creates* rows — it never updates or deletes them. Reassigning
  a name that a previous page used therefore leaves the old row owning that key forever, and the new
  page never gets a menu row (2.1.3 produced exactly that: two "地图列表" entries and a missing "任务中心").
  Names are consequently `voxelith-map-page-*` and must stay stable; renaming a page means a new key
  **and** a stale row to delete in menu management.

`grantPluginPermissionsToSystemRoles` only auto-grants to `SUPER_ADMIN` and `ADMIN`. A `systemType =
USER` role starts with zero permissions, so a user-facing menu entry will not appear until
`plugin:voxelith-map:<action>` is added to that role's `sysRole.permissions` explicitly. Removing or
renaming a route page also leaves a stale `sysMenu` row behind — the menu projection service creates
missing menus but never updates or deletes existing ones, which renders a dead entry. Check both
after adding or removing a page.

## Admin Images Cannot Be Loaded by a Plain `<img>`

Every `/admin/**` endpoint is guarded by `plugin:voxelith-map:manage`, and a browser attaches no
`Authorization` header to an `<img src>`, an ES module or an `EventSource`. A direct fetch therefore
always fails:

```bash
curl -s -i "$HOST/api/plugins/voxelith-map/admin/maps/<id>/preview.png?step=1"
# 400 {"code":1000,"message":"无此插件权限：plugin:voxelith-map:manage"}
```

So the preview base map and the selection window image must be fetched through `sdk.http.blob(...)`,
which does carry the token, and converted to an object URL. Three things to keep in mind:

* `sdk.http.blob()` resolves to `{ data, headers }`, **not** a bare `Blob`. Passing the wrapper to
  `URL.createObjectURL` throws `argument must be an instance of Blob` — and because that throw used
  to be swallowed, 1.0.6's symptom was a silent broken-image icon with no error message anywhere.
  Both the URL and the unwrapping live in `fetchImageObjectUrl`.
* An object URL is an explicit resource: revoke the previous one before replacing it and on unmount
  (`releasePreviewObjectUrl`), otherwise every re-generate leaks a copy of the PNG.
* Do not "try the direct URL first and fall back on `onerror`". The direct request is guaranteed to
  fail, so it only adds a wasted round trip and a visible flash of a broken image. Fetch the blob as
  soon as the preview reports ready.

Public `/maps/**` images (`panorama.png`, `atlas.png`, `aerial.png`) carry no permission and are fine
in a plain `<img>` — which is exactly why the panorama renders while the admin preview once did not.

The render task stream had the same constraint: `EventSource` cannot attach a token, so
`/admin/tasks/events` used to answer 400 and `useTaskStream` degraded to its 2 s polling loop.
Since 1.0.14 both composables consume the stream with `fetch` + manual SSE parsing instead
(`src/composables/sse-fetch.ts`, same pattern as the host's plugin devtools): the token comes from
the same-origin `localStorage` key `token` the host login writes, so the stream connects for real
and polling stays only as a disconnect fallback.

## Development Mode

The host loads plugins in dev mode from `target/classes` plus `packages/plugin-voxelith-map/dist`,
which takes precedence over a jar in `plugins/`. So the hot-reload loop is:

```bash
pnpm --filter @yudream/plugin-voxelith-map run build      # vite lib build -> dist/remoteEntry.js
sh .workbuddy/run-mvn.sh -s settings.xml.example -P dev-export \
  -pl yudream-plugins/yudream-plugin-voxelith-map package -DskipTests
```

`dev-export` copies the front-end `dist` into `target/classes/META-INF/yudream-plugin/frontend/
voxelith-map/`. A jar is only needed to hand the plugin to someone else; drop it into
`YuDream-Admin/plugins/` and remove any older jar with the same plugin code.

## Verification

```bash
sh .workbuddy/run-mvn.sh -o -s settings.xml.example \
  -pl yudream-plugins/yudream-plugin-voxelith-map test
```

The front-end package has its own checks:

```bash
pnpm --filter @yudream/plugin-voxelith-map run test         # api wrappers
pnpm --filter @yudream/plugin-voxelith-map run typecheck    # vue-tsc
```

`typecheck` currently reports five `TS2307` errors from `@yudream/components`' date/time pickers
because the local pnpm store copy of `@arco-design/web-vue` is incomplete. `plugin-world-map` and
`plugin-material` report the same five, so this is the existing environment baseline rather than a
regression in this package. One further error comes from the vendored copy itself
(`skyline/SkylineLayer.ts` declares an unused `mapCenter`); it is upstream's and is regenerated on
every re-vendor, so it is left alone. Anything else in `src/**` outside `src/vendor/**` is a real
regression.

The backend suite covers the CLI argument vectors (including the "no `--mc-version` when the
version is unknown" case), the progress parser, the diagnostics collector against the engine's verbatim
output lines, the version/JAR-name helpers, the bucketed runtime paths, the archive normalizer
(its fixture is a hand-assembled `STORED` + data-descriptor archive, which the JDK's own zip writer
cannot produce), the artifact-storage key contract and the generation publisher — all against an
in-memory `PluginFileStore`, needing no world, no network and no Voxelith checkout. Instance-import
specific coverage: `InstanceArchiveFetcherTest` (adaptive chunking, size mismatch and empty payload
failure, cancel between chunks, size cap) against a fake `InstanceFilePort`, `InstanceJarVersionsTest`
(server-core jar preference, fallback, ignoring unversioned names),
`McpanelIntegrationPostureTest` (soft dependency declared, contract class-name/reflection entry
confined to `infrastructure/mcpanel`, entry class free of contract references) and
`McpanelContractAbsentCompatibilityTest`, which loads the plugin classes in an **isolated classloader
without mcpanel** (the current 0.22.6 runtime) and asserts the adapter still links, reports `available() ==
false` and raises a readable `IllegalStateException` instead of `NoClassDefFoundError`. That last one
is the guard for the historical failure mode where a contract type in a member descriptor made the JVM
fail during class verification — i.e. plugin enable failure, which no try/catch can catch. The
reflective mapping is covered by `McpanelInstanceFileAdapterTest` with a local fake contract. None of
these needs a panel node: the fakes reproduce exactly the panel behaviours that matter.

The preview cataloguing path talks to a real HTTP endpoint, so the cheapest end-to-end check is to
start the bundled engine exactly the way the plugin does and post an archive at it:

```bash
D=$(mktemp -d)
java -jar src/main/resources/voxelith/server/voxelith-server.jar \
  --server.address=127.0.0.1 --server.port=18099 \
  --yudream.voxelith.work-dir="$D/work" --yudream.voxelith.upload-dir="$D/work/uploads" \
  --yudream.voxelith.publish-dir="$D/maps" --logging.file.name="$D/sidecar.log" &

curl -s -w '\n%{http_code}\n' -F "file=@world.zip;filename=world.zip" \
  "http://127.0.0.1:18099/api/uploads/archive?name=probe"
curl -s -X POST "http://127.0.0.1:18099/api/uploads/<id>/preview?dimension=minecraft:overworld"
curl -s -o preview.png "http://127.0.0.1:18099/api/uploads/<id>/preview.png?dimension=minecraft:overworld"
```

A `200` on the upload returns the catalogue entry (`id`, `worldDir`, `versionName`, `dimensions`);
the preview then reaches `READY` and the PNG is a real image. This is the only check that catches
"the archive is a valid zip but the sidecar cannot unpack it" — the plugin's own extractor accepts
more shapes than the sidecar does.

Re-vendoring the engine also re-copies the front-end render core, so always follow it with:

```bash
python .workbuddy/vendor-voxelith.py --check
```

which reports the upstream commit, the engine artifact digests and any unresolvable relative import
in the vendored tree.

The plugin's front-end bundle is served by the host, not by the jar, while in dev mode — and the
asset route prefix is `/api/platform/plugins/...` (a plain `/api/plugins/...` URL hits the plugin
HTTP dispatcher instead and answers `插件接口不存在`):

```bash
curl -s "http://127.0.0.1:8080/api/platform/plugins/voxelith-map/assets/remoteEntry.js" \
  | md5sum   # must equal md5sum of packages/plugin-voxelith-map/dist/remoteEntry.js
```

Dev mode reads that file from disk on **every** request, so a rebuilt `dist` is served without
restarting the host or reloading the plugin; `Cache-Control: no-cache` plus an `ETag` means a browser
refresh revalidates it too. Use this check whenever a front-end fix "does not seem to apply" — it
separates "the bundle never shipped" from "the bundle is fine and the bug is elsewhere".

## Instance Import: Two Transports, One Task Model

Importing a panel instance's world always produces an `IMPORT` task in the task centre — progress,
per-stage logs, the failure reason and cancel all live there. Only *who moves the bytes* differs, and
which transport is used is decided automatically. Neither requires the panel plugin to be upgraded
or changed.

| Transport | When | Who moves bytes | May the page be closed? |
| --- | --- | --- | --- |
| **Server-side job** (`POST /admin/import-instance-jobs`) | mcpanel exposes its instance-file contract (looked up by reflection; the current 0.22.6 panel does not) | the plugin process (node packs, chunks are pulled and streamed into the plugin file store) | **Yes** — the page only submits |
| **Browser transfer** (`POST /admin/import-instance-jobs/browser`) | mcpanel missing/disabled, **or the current panel without that contract (0.22.6)** | the admin's browser, via the panel's existing HTTP endpoints, then the host file channel | In-app navigation: yes (state is application-level). Reload/close: no — that ends the transfer and fails the task with an explicit reason |

The dialog only offers the import at all when the panel is reachable (`panelAvailable`, probed through
the panel's own HTTP endpoints on mount and on focus/visibility re-checks), so a disabled panel shows
no entry rather than an error after clicking.

`GET /admin/instance-import-capability` returns `{serverImport: true|false}` and is what the dialog
uses to choose. The key point is that **a panel without the contract is not an error**: the adapter
looks the contract up by class name, treats a missing class as "unavailable", and the dialog silently
takes the browser transport instead of telling the operator to upgrade the panel plugin.

The browser transport is still a real task, driven by the page:

```text
POST /admin/import-instance-jobs/browser            {instanceId, instanceName?, name?, version?, withMods?}
  → RenderTask(IMPORT) created RUNNING, pushed to the task stream; the page keeps its id
POST /admin/import-instance-jobs/{id}/progress      {stage, percent, detail?}   ← throttled stage updates
POST /admin/import-instance-jobs/{id}/complete      {…, worldFileId, modsFileId?, modsPackName?}
  → the server takes over on the job executor: validates, creates the map, finishes the task
POST /admin/import-instance-jobs/{id}/fail          {message}                   ← panel/upload errors
POST /admin/tasks/{id}/cancel                                                   ← cancel (shared with every task)
```

Three details make that task trustworthy rather than decorative:

* **The server owns the ending.** `complete` only hands over the uploaded file ids; validation and map
  creation run on the plugin's job executor, so the task's final state does not depend on the browser
  receiving a response.
* **The transfer state is application-level, not page-level.** It lives in the module singleton
  `src/composables/useBrowserImport.ts`, so navigating within the host SPA (to the task centre, to
  another plugin page) does **not** abort it: the requests keep running, stage updates keep flowing
  into the task, and the floating panel re-attaches when the admin returns to the map manager. Only a
  real `pagehide` (reload/close) ends it — and that path fails the task with an explicit reason via a
  `keepalive` fetch (not `sendBeacon`: the host only accepts an `Authorization` header), after
  aborting the in-flight requests so the failure branch cannot overwrite the reason with a raw
  `Failed to fetch`. Stale tasks from a killed browser are swept by the plugin's startup recovery.
* **Progress reporting is throttled** (stage change, or at most once every 2 s) and — because
  `reportBrowserImportProgress` only appends a log line when the stage text changes — chunk-level
  updates refresh the floating panel without flooding the task log.
* **The mods stage is non-blocking on both transports, packs only jars, and overlaps with the world.**
  A `mods` directory mixes caches, configs, logs and `.disabled` packages with the jars the renderer
  actually consumes, and zipping the whole thing node-side is the single slowest panel call (a large
  modpack easily exceeds a reverse proxy's read timeout, which the browser reports as
  `Failed to fetch` long before anything is wrong with the page). The import therefore lists the
  directory, packs only the participating jars (`modJarPaths` on the browser side, `modJarPaths` in
  `PluginJobService` server-side; both fall back to the whole directory when listing fails or yields
  no jars), and runs that node-side compression **concurrently with the world's download/upload** —
  compression is CPU/IO on the node, the transfer is network, so the two slowest steps overlap
  instead of adding up. Its failure is written into the task log and the import continues with the
  world; mods can be bound later on the map detail page. The world stage stays fatal — without the
  archive there is nothing to build — but its error text names the real cause (proxy timeout / node
  state) instead of guessing, and the node-side pack shows an "已等待 N 秒" counter while it runs.
  These fragilities are inherent to the browser transport: the request crosses the host's reverse
  proxy to the panel, whereas the server-side path calls the panel's optional Java contract reflectively and has no proxy
  in the middle.

The server-side path in full:

```text
POST /admin/import-instance-jobs  {instanceId, instanceName?, name?, version?, withMods?}
  → RenderTask(IMPORT) created, persisted, pushed to the task stream, submitted to the job executor
    1. listDir(instanceId, ".")            → version from root jar names (InstanceJarVersions) when blank
    2. packToZip(["world"], voxelith-import-<taskId>.zip)     ← node-side compression
    3. InstanceArchiveFetcher              → tasksDir/import-<taskId>/world.zip (byte ranges, streaming)
    4. files.put("imports/<taskId>/world.zip")                ← plugin file store, streamed
    5. same for ["mods"] when withMods (non-blocking: failures are logged, world import continues)
    6. MapProjectAppService.importInstanceWorld(...)          → creates the map
```

Properties that follow from running it in-process, and are the point of that path:

* **The page is not part of the transfer**, so an import cannot be killed by a refresh.
* **Nothing unbounded lands in memory.** `InstanceArchiveFetcher` pulls fixed byte ranges into a
  staging file; only one chunk buffer is resident. It also (a) shrinks the request length when the
  panel/node returns less than asked (window splitting, never truncation), (b) fails the job when a
  reported size and the received byte count disagree — a half archive must never become a map — and
  (c) honours cancellation between chunks.
* **Cleanup is unconditional.** The panel-side temp bundle is deleted on success, failure *and*
  cancel; the staging file and any store object written before the map exists are dropped too, so a
  cancelled import leaves no map record, no inputs and no panel residue.
* **Version detection is authoritative in one place.** The dialog still shows a detected version for
  the operator, but the job re-derives it with `InstanceJarVersions` (same `1.x[.y]` regex as the
  renderer) when the request leaves it blank, and writes the evidence into the task log.

### Mod-pack integrity is checked at import time

An incomplete mod set fails silently-ish: a missing library makes the mod loader refuse to start
(`t_and_t requires any version of cristellib, which is missing`), the harvest falls back to static
parsing, and the map renders with large holes where modded blocks should be — visible only deep in the
render log. So both import transports now read the mod pack back right after it is written and report:

* **missing dependencies** — each jar's `fabric.mod.json` (`id` / `provides` / `depends`) and
  `META-INF/mods.toml` / `neoforge.mods.toml` (`modId` plus `[[dependencies.*]]` entries with
  `mandatory=true`) are collected, and every required id that nothing in the pack provides is listed as
  `"<mod> 需要 <missing>"`. Platform ids (minecraft / loaders / java) and `mandatory=false` entries are
  ignored so the report stays signal-only;
* **jars with no recognisable metadata** — a real mod jar always declares itself, so one that does not
  is either not a mod or a damaged archive; both break the loader.

Findings go into the task log *and* the task's diagnostics block (visible in the task centre), together
with the next step: add the missing jars to the instance and re-import, or attach them to the map on its
detail page (individually attached jars layer on top of the pack, so a partial fix is enough).

**A pack that cannot be unpacked is never registered.** The inspector also *drains every entry* of every
jar, not just the metadata ones: `ZipInputStream` decompresses as it reads, so a pack truncated mid-entry
throws right there. Otherwise the render-side unpacker is the first to notice, minutes into a render, with
`模组包读取失败: Unexpected end of ZLIB input stream`. When the pack cannot be opened, or any jar fails to
decompress, `Report.usable()` is false and the import registers the map **without** the pack: the world is
still imported and renderable, and the log/diagnostics state why and what to do (make sure the instance's
`mods` directory is not being written while it is packed, re-import, or attach jars on the detail page).
Missing dependencies, by contrast, do not make a pack unusable — that is a partial set, not a broken file.

Both transports also validate the *transfer*: after a package is fetched, its zip shape is checked (local
header magic plus a central-directory-end record inside the tail window). When the panel cannot report a
size the transfer falls back to probing until an empty chunk, and a short read is then indistinguishable
from a complete one — a truncated payload would be uploaded, stored, and only explode at render time. The
check lives in `InstanceArchiveFetcher` (server side) and `requireCompleteZip` (browser side) and is
skipped for non-zip payloads so other artifact shapes are never misjudged.

`ModPackInspector` only reads — it never alters the mod set, because an unsatisfiable instance is the
instance's own problem, not something a renderer should silently "fix". Reading goes through
`ZipFile` (central directory) for the outer pack and per-entry bytes for each inner jar; handing
`ZipInputStream` straight to Jackson does not work, because Jackson closes the stream it parses
(`AUTO_CLOSE_SOURCE`) and the next `getNextEntry()` then fails with "Stream closed".

### Why mcpanel is a `softdepend` (and how the contract stays isolated)

The job reads instance files through `InstanceFilePort`, implemented by
`infrastructure/mcpanel/McpanelInstanceFileAdapter` — **the only class in the plugin allowed to
touch mcpanel's contract**. The current panel (0.22.6) does not ship that contract, so the adapter
does not compile against it. Inside it:

1. `context.dependencyAvailable("mcpanel")` first — a class-free existence check;
2. only then resolve the contract by its stable class name (`McpanelInstanceAccessApi`) with
   `Class.forName` and ask `context.service("mcpanel", …)` for the service, **per call**, never
   cached across a provider disable/reload;
3. `ClassNotFoundException`/`RuntimeException`/`LinkageError` all degrade to `available() == false`,
   which is what makes the current panel fall back to the browser transport instead of breaking
   the request.

The adapter calls the contract methods reflectively (`listDir`, `packToZip`, `readArchiveRange`,
`deleteFile`) and maps entries back to the plugin's own `InstanceDirEntry`; no mcpanel type ever
appears in the plugin's class descriptors. `McpanelInstanceFileAdapterTest` exercises this mapping
with a local fake contract; `McpanelContractAbsentCompatibilityTest` loads the plugin classes in an
isolated classloader without mcpanel and asserts the adapter still links and degrades.

**The dependency is a `softdepend`, deliberately, so local development works without the panel.**
The host builds each plugin's `PluginClassLoader` from a classloader list captured **at the moment the
consumer is created** (`JarPluginRuntimeGateway.dependencyClassLoaders`):

```java
for (String code : descriptor.dependencies()) {        // hard deps: provider must be enabled, else fail
    ... classLoaders.add(dependency.getClassLoader());
}
for (String code : descriptor.softDependencies()) {     // soft deps: only if ALREADY enabled
    if (dependency != null && dependency.isEnabled()) classLoaders.add(...);
}
```

A soft provider that loads later is never added to the chain, so the contract class may be invisible
when this plugin is created before the panel. The adapter's reflection lookup treats that exactly like
the current panel: `available() == false`, browser transport, and the plugin itself enables fine.
That is the accepted trade-off for mcpanel being optional: **a host without mcpanel (or with the
current panel) gets the full plugin minus server-side imports**, and local development needs no panel
install.

`McpanelIntegrationPostureTest` enforces the soft dependency, the absence of a `depend` duplicate,
an entry class free of contract references, and a bytecode scan asserting no class outside
`infrastructure/mcpanel` references the contract.

There is no `mcpanel` Maven dependency any more: the contract is looked up by class name at runtime
and is never shaded into the plugin jar.

> **Asymmetry to keep in mind.** Archive *sync* (「同步最新存档」 on the detail page, plus its
> optional periodic timer) still moves bytes through the browser and is cancelled by closing the
> page. Moving it to the same job mechanism is the natural follow-up, not part of this change.

## Refresh Responsiveness: Keep the 3-D Stack Off Every Page's Critical Path

The site runs on HTTP/1.1 (about six connections per origin), so anything an admin page pulls on
refresh competes with the plugin's own API calls, the host's navigation-guard requests and any
long-lived SSE stream. This class of bug has bitten four times already (cover images, tile
concurrency, missing read timeouts, and now the entry chunk), so the rules below are load-bearing:

1. **The 3-D stack is imported on demand, never statically.** `src/viewer/VoxelithViewer.ts` plus
   the vendored viewer/core/skyline trees and `three` are ~700 KB. `src/index.ts` statically
   imports every route page, so a single static `import { VoxelithViewer }` in `Viewer.vue` or
   `MapDetail.vue` folds the whole stack into `remoteEntry.js` and every plugin page —
   including the lightweight map list, gallery and markers pages — downloads, parses and
   evaluates it on each refresh. Both pages now go through `src/viewer/stack.ts`
   (`loadViewerStack()`, memoized, resets on failure so a dropped chunk can be retried), which
   Vite emits as separate chunks:

   ```text
   remoteEntry.js         ~215 KB   (was ~1.1 MB before the split)
   viewer-<hash>.js       ~790 KB   three + vendored viewer/skyline — fetched when a 3-D view opens
   core-<hash>.js         ~122 KB   zod + manifest/marker schemas — fetched with the stack
   VoxelithViewer-<hash>.js ~20 KB
   ```

   `base: './'` keeps the emitted `import("./viewer-…js")` specifiers relative, so the chunks
   resolve beside `remoteEntry.js` from wherever the host serves the plugin's front-end
   directory. Never add a static import of the viewer stack to a route page; type-only imports
   (`import type`) are erased and stay fine. The P3 support probe moved into `openMap` because
   `supportsDisplayP3()` lives in that stack.

2. **Anything fired automatically on load needs a lifecycle and a timeout.** The mcpanel instance
   probe is the cautionary example: it was called once at setup *and* from `window` `focus` and
   `document` `visibilitychange` listeners registered at setup scope with anonymous handlers.
   Those listeners could never be removed, so every visit to the list page left another permanent
   pair behind and one refresh fired the same probe three times — visible in server logs as three
   identical `GET /api/plugins/mcpanel/admin/instances?page=1&size=100` lines within ~120 ms.
   It now lives in `onMounted`/`onBeforeUnmount`, is deduped through a single in-flight promise
   (`refreshPanelInstances`), and every metadata call carries `PANEL_PROBE_TIMEOUT_MS` (15 s) —
   an untimed probe against an unresponsive panel would hold a connection open indefinitely and
   queue the plugin's own reads behind it, which is exactly what "刷新后无响应" looks like.
   The plugin's own read APIs carry a 20 s timeout for the same reason (`timedRead`); writes and
   long transfers deliberately do not, and `mcpanelJson(..., timeoutMs)` must stay unlimited for
   the world/mods zip calls.

## Troubleshooting


| Symptom | Cause |
| --- | --- |
| Import falls back to the browser transport although mcpanel is installed | Expected with the current panel 0.22.6: it does not expose `McpanelInstanceAccessApi`, so the reflective lookup fails and the adapter reports unavailable. Server-side import starts working automatically once the panel exposes the contract; until then the browser transport is the supported path. |
| Import task fails with `permission denied` / `无权限` in its log | The panel node cannot read the instance directory: the files' owner differs from the node's runtime user (containers often write as root). Fix ownership/permissions on the node host (`chown -R`/`chmod -R a+rX`), or run the node as the file owner. |
| Import task fails with `打包产物不完整` | The panel reported a size the transfer did not reach (node restarted/truncated the bundle mid-read). Retry; if it repeats, check the panel node's disk and logs. The plugin deliberately refuses to create a map from a short archive. |
| A plugin admin page hangs on refresh (spinner never resolves, no API requests appear in the server log) | Either the browser's ~6 same-origin connections are held by something that never finishes, or the page is blocked evaluating a huge bundle. Check, in order: an auto-fired request without a timeout (mcpanel probe — see `Refresh Responsiveness` above), a leaked `focus`/`visibilitychange` listener multiplying requests, and whether the 3-D stack is still imported statically by a route page (the entry chunk should stay in the low hundreds of KB, not ~1.1 MB). |
| Render dies before any Java output with `Error opening log file` / `Invalid -Xlog option` | The runtime root was relative while the GC log path was handed to a child whose working directory is the task directory, so the log landed on a non-existent nested path and the JVM aborted at startup. Paths are absolute-normalized in `VoxelithRuntimeConfig`. |
| Harvest log says `找不到或无法加载主类 ...PropertiesLauncher` | Same root cause: a relative `-cp` resolved against the child's working directory. |
| Render fails immediately with an unknown main class | The engine was launched with `-jar` instead of `PropertiesLauncher`; `JarLauncher` cannot select `RenderMapCli`. |
| Host heap grows during a render | Something let the sidecar render. `canSpawn()` is `false` in fat-jar form, so rendering must stay in a child process. |
| Panorama bake exits with code 1 and mentions `surface.png` | The map has never been rendered, or the work directory was wiped. Render once, then bake again. |
| Shard worker exits shortly after start with no output | The shard queue is empty. The plugin does not produce queue entries — see the capability boundary above. |
| Render fails with `模组包读取失败: Unexpected end of ZLIB input stream` | The stored mod pack is truncated: the render-side unpacker decompresses entries as it reads, so a pack cut mid-entry fails there. Since the import now drains every jar, a broken pack is rejected at import time instead (the map is created without it and the task explains why). For a map that already carries a broken pack, clear or re-select the pack on the detail page — or re-import. |
| Import reports `模组包已损坏：N 个 jar 解压失败` | Same root cause caught earlier: the pack was cut during panel-side packing or transfer. The world is still imported; the mods are dropped. Make sure nothing writes into the instance's `mods` directory while it is being packed, then re-import or attach the jars on the detail page. |
| Import reports `打包产物不完整（缺少 zip 尾部记录）` | The transfer ended early while the panel could not report a package size (so byte counts could not be compared). Retry the import; if it repeats, check the panel node's disk/network stability. |
| Render fails with exit code 137 (`未知退出码 137`) after a successful bake | 137 = 128+9 = **SIGKILL**: the process was killed from outside Java, almost always by the container/host OOM killer — the *sum* of host JVM + voxelith sidecar + render child exceeds the cgroup limit, and the kernel picks the largest process. It is **not** heap exhaustion, so raising `heap-mib` makes it worse (more total footprint). The failure reason now carries the cgroup limit/usage and host memory so you can tell which side to fix: shrink the window, lower `max-chunks`, crop more Y, or disable the sidecar during big renders (`sidecar-enabled=false`, frees ~2 GiB). Confirm on the host with `dmesg -T \| grep -i "killed process"` — it names the victim and its RSS. |
| A large render is killed as 「渲染超时被终止」 | The old fixed timeout no longer bounds large windows: since 2.1.18 the budget scales with the framed window's chunk count (~0.6 s/chunk, cap 24 h) and the failure message states the limit used. It only widens, never shrinks, so raising `timeout-minutes` still works as a floor. Whole-world renders cannot be estimated — set `timeout-minutes` explicitly for those. |
| Render fails with exit code 3 or 4 | Genuine heap shortfall or window guard: raise `yudream.voxelith-map.heap-mib` and/or shrink the window — `max-chunks` is a guard, `batch-chunks` only bounds harvest. |
| Public tiles return 404 but the admin page renders them | The public viewer reads `activeGenerationId` only; a staged or failed generation is invisible by design. |
| Admin menu entry missing for a normal user | `plugin:voxelith-map:manage` has not been granted to the `USER` role. |
| Any id-based endpoint returns `地图不存在: %E4%B8%87...` (escaped id) | The plugin was reading path segments without percent-decoding them; ids may contain non-ASCII characters. Route all path reads through `RequestPaths`. |
| Admin list returns 400 with `文件不存在` | A read of a missing object surfaced as an error. Reads must degrade to "not produced yet" — see the storage note above. |
| The menu shows two "地图列表" entries, or a page (e.g. 任务中心) has no menu entry at all | The route `name` was reused for a different page. Menu rows are keyed by `route:{module}:{name}` and the projection never updates existing rows, so the old page's row keeps the key and the new page can never register. Never reassign a route name; give a moved/renamed page a fresh name and delete the stale row in menu management. Rows whose path no longer exists are marked `runtimeAvailable: false`. |
| Refreshing an admin page white-screens, or the console reports `Infinite redirect in navigation guard` | Fixed by moving every route under `/platform/plugins/voxelith-map/**`: the admin pages used to live inside the *public* plugin route `/voxelith-map`, and the host guard (logged in + `notFound`) re-enters the target when `to.path.startsWith(publicPath + '/')` matches — on a hard refresh the same path was re-entered forever and vue-router aborted startup, which reads as "刷新无响应". No route occupies `/voxelith-map` now, so no public prefix can cover anything. Do not reintroduce routes below a public plugin path. |
| Whole map renders in magenta, or blocks are missing as holes | Model geometry was harvested for a different version than the world. Check 「渲染诊断」 for the version health warning and the `采集版本：…（判据：…）` line; align the client jar (or set `mcVersion` explicitly) and re-render. |
| Modded blocks are missing (holes) while vanilla blocks render | The mod ships a custom block loader the headless Fabric worker cannot statically resolve; upstream falls back to static parsing and those blocks get no geometry. Verify in 「渲染诊断」 that the harvest ran with the mod (`--mod` lines in the log); if the mod is listed yet blocks are holes, it is an upstream limitation. |
| Changed the mod list but the map looks unchanged | Mod changes take effect on the next render only — tiles of the already published generation are never retro-fitted. Also check the mod digest bucket: the harvest re-runs automatically because the cache key includes the mod list digest. |
| Update answers `找不到上传的模组 jar：…` | The payload references a `fileId` that no longer exists in the plugin file store (deleted or expired upload). Re-upload the mod jar and save again. |
| Log says the harvest ran again for a map that already rendered | The client jar file name carries no version, so the plugin cannot bucket the product and defers to the engine, which harvests per work directory. Name the jar with its version (`client-1.21.1.jar`) to share one bucket across maps of that version. |
| The first render after upgrading to 1.0.4 harvests again although a `models.json.gz` exists | Version 1.0.3 kept one file at `<runtime-root>/models.json.gz`; the bucketed layout under `models/{version}-{digest}/` does not read it, so nothing is reused until a bucket exists. The old file is inert and can be deleted. If `yudream.voxelith-map.models-file` is set it wins over every bucket, including a wrong-version one — unset it when in doubt. |
| `vite build` fails with `[UNRESOLVED_IMPORT] Could not resolve '../core/index'` under `src/vendor/voxelith/viewer/**` | `vendor-voxelith.py` wrote a fixed `../core/index` rewrite regardless of the file's depth, so files nested two levels deep pointed at the non-existent `viewer/core/index`. After re-vendoring run `python .workbuddy/vendor-voxelith.py --check`: it lists every unresolvable relative import in the vendored tree and exits non-zero. |
| Preview fails with `边车返回 HTTP 400：{"error":"only DEFLATED entries can have EXT descriptor"}` | The archive contains `STORED` entries that use a data descriptor, which the sidecar's JDK `ZipInputStream` refuses. Since 1.0.5 `WorldArchiveNormalizer` detects this before uploading and rewrites the archive; if it still happens, the archive is broken in some other way — check `sidecar.log` under the plugin runtime root. |
| Preview base map shows a broken-image icon although the preview reported `READY` | The image endpoint needs `plugin:voxelith-map:manage` and an `<img>` cannot send the bearer token, so the direct URL always answers 400 — the blob path is the only working route. Before 1.0.6 that fallback handed `sdk.http.blob()`'s `{ data, headers }` wrapper to `URL.createObjectURL`, which threw, and the error was swallowed without any message. Since 1.0.6 the failure is visible with a retry button. |
| Before 1.0.14 the render task list only refreshed every couple of seconds and the console showed a 400 on `/admin/tasks/events` | `EventSource` cannot carry the bearer token, so the host answered 400 and the stream closed immediately; polling was the only working channel. Since 1.0.14 the composables read the stream with `fetch` + manual SSE parsing (`sse-fetch.ts`, token from same-origin `localStorage`), so events arrive in real time and polling is only a fallback. |

## The Public Viewer Must Mount Exactly Once and Fill Its Flex Parent

Two independent defects made the public site page (`/voxelith-map`) show a blank
canvas with a flood of `WebGL: INVALID_OPERATION: uniformMatrix4fv: location is
not from the associated program`:

1. **Double mount → two renderers on one context.** `Viewer.vue` set
   `currentMapId` (triggering the `watch` → `openMap`) and *also* called
   `openMap()` explicitly. Both calls created a `VoxelithViewer` on the same
   `<canvas>`; a canvas can only ever return one WebGL context, so two
   `WebGLRenderer`s shared it while each kept its own `_currentProgram` state.
   From then on every per-object `modelViewMatrix`/`modelMatrix` upload used
   locations from the *other* renderer's program — hence the per-frame GL
   flood — and the first renderer leaked forever. Fix: a single open path
   (`openSeq` guard, stale continuations bail out) and the watcher is the only
   trigger.
2. **Zero-height canvas.** The host site chrome wraps plugin roots in
   `.plugin-site-page` (a *column* flex container). A plain `height: 100%`
   resolved to 0 there, the absolutely-positioned canvas was clipped by
   `overflow: hidden`, and `MapEngine.resizeToCanvas` bailed out on the 0
   height. Fix: `.voxelith-viewer` also declares `flex: 1 1 auto;
   min-height: 0` so it fills flex parents while block parents keep using the
   percentage height.

Diagnostic recipe that nailed it: wrap `getUniformLocation` in an init script
to build a `location → program` map, then compare each `uniformMatrix4fv`
location's program against `gl.getParameter(gl.CURRENT_PROGRAM)`. The mismatched
uniform names (`modelViewMatrix`/`modelMatrix`, i.e. per-object uploads) pointed
straight at renderer-vs-context ownership instead of shader code.
