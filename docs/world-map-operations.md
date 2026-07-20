# World Map Operations

## BlueMap Worker

The world-map plugin uses the built-in renderer by default. To use the BlueMap v5.16 worker,
configure every setting below through the host framework settings service:

| Setting | Required | Purpose |
| --- | --- | --- |
| `yudream.world-map.bluemap.java-path` | yes | Absolute path to a Java 21 executable. |
| `yudream.world-map.bluemap.cli-path` | yes | Absolute path to the pinned BlueMap v5.16 CLI JAR. Its SHA-256 is verified before every launch. |
| `yudream.world-map.bluemap.config-template` | yes | Absolute path to a resolved BlueMap configuration template containing `core.conf`, `maps/template.conf`, and `storages/file.conf`. |
| `yudream.world-map.bluemap.storage-root` | yes | Relative output directory below each render task work directory. |
| `yudream.world-map.bluemap.minecraft-version` | no | Minecraft client version, default `1.21.4`. |
| `yudream.world-map.bluemap.heap-mib` | no | Worker heap limit in MiB, default `1024`. |
| `yudream.world-map.bluemap.timeout-minutes` | no | Per-render worker timeout, default `60`. |
| `yudream.world-map.bluemap.resource-cache-root` | no | Absolute directory for reusable BlueMap resources. A version-specific child directory is used automatically. |

The task-local BlueMap configuration accepts the required resource download. The worker always
uses a task-local world archive, output directory, log and configuration. When
`resource-cache-root` is set, only downloaded BlueMap resources and the matching Minecraft client
JAR persist between renders; this avoids repeating first-run resource initialization for every map.

`maps/template.conf` must be a render-ready map configuration and include the `${world}`, `${dimension}`
and `${name}` placeholders. Resolve any other placeholders from BlueMap's distribution template before
using it here. The worker replaces `webserver.conf` and `webapp.conf` with disabled task-local files,
because public web serving and webapp generation are handled by the YuDream plugin rather than the CLI.

Do not use the world archive upload directory or the public map asset directory as the resource
cache root. The process account needs read/write access to the cache root and network access during
its first initialization for a Minecraft version.

## Real CLI Smoke Test

`BlueMapCliEndToEndTest` is disabled by default so normal CI does not require a Minecraft world or
network access. It creates a task-local BlueMap configuration, runs the real CLI, imports its PRBM
and low-resolution tiles, then verifies the immutable generation publication boundary. Run it on a
render host with these explicit system properties:

```powershell
mvn -pl yudream-plugins/yudream-plugin-world-map -am `
  -Dtest=BlueMapCliEndToEndTest `
  -Dyudream.world-map.e2e.enabled=true `
  -Dyudream.world-map.e2e.java-path=C:/path/to/java `
  -Dyudream.world-map.e2e.cli-path=C:/path/to/bluemap-cli.jar `
  -Dyudream.world-map.e2e.world-dir=C:/path/to/world `
  -Dyudream.world-map.e2e.client-jar=C:/path/to/client.jar `
  -Dyudream.world-map.e2e.resource-data-root=C:/path/to/bluemap-data `
  test
```

Use a resource-data directory that already contains the matching BlueMap resources when validating
an offline render host. The test verifies the CLI JAR's pinned v5.16 SHA-256 before launching it.

## Render Recovery

Render tasks publish phased progress through the administration task endpoint. If the process
restarts, orphaned pending or running tasks become failed terminal tasks and their maps leave the
`RENDERING` state. Cancelling a task terminates the BlueMap worker and preserves the previously
published generation.

Public viewers only read an atomically published generation. Failed or cancelled renders never
replace the currently visible map generation.
