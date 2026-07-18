# world-map 插件渲染契约（前后端共享）

本文件是 world-map 插件渲染管线的内部契约：后端渲染输出格式 + HTTP 接口 + 前端查看器消费方式。
目标视觉效果对齐 BlueMap 5.x（3D 等距视角、AO、双光源、昼夜、低清金字塔回退）。

## 1. 支持范围（一期）

- Minecraft Java 版 **1.20–1.21.x** 存档（Anvil region 格式，DataVersion 3465+）
- 维度：overworld / nether / the_end（nether 默认剥离基岩顶）
- 渲染资产：原版客户端 jar（blockstates/models/textures + 生物群系 colormap）

## 2. HTTP 接口（插件命名空间 `/api/plugins/world-map`）

### 公开（匿名可访问，端点不声明 permission）

- `GET /maps` → `{ "maps": [MapSummary] }`
- `GET /maps/{mapId}/settings` → `MapSettings`（见 §3）
- `GET /maps/{mapId}/tiles/hires/{tx}/{tz}` → **gzip JSON** 高精度 tile（见 §4），`Content-Type: application/json`，`Content-Encoding: gzip`，`Cache-Control: public, max-age=86400`
- `GET /maps/{mapId}/tiles/lowres/{lod}/{tx}/{tz}` → **PNG** 低清 tile（见 §5），长缓存
- `GET /maps/{mapId}/textures/atlas.png` → 贴图集 PNG，长缓存
- `GET /maps/{mapId}/markers` → `{ "markerSets": [] }`（二期标注，一期返回空集占位）

tile 不存在时返回 404（`rawJson`，`{"message":"tile not found"}`）。

### 管理（permission = `plugin:world-map:manage`）

- `GET /admin/maps` 地图列表（含渲染状态/进度）
- `POST /admin/maps` 创建地图：body `{ "name", "dimension", "worldFileId", "clientJarFileId"? }`
  （worldFileId 为平台 `/api/files/upload` 上传的存档 zip；插件经 SPI `platformFile()` 读取）
- `POST /admin/maps/{id}/render` 触发全量渲染
- `DELETE /admin/maps/{id}` 删除地图与全部 tile
- `GET /admin/tasks` 渲染任务列表
- `GET /admin/tasks/events` SSE 推送任务进度（event: `task`，data: `{taskId,mapId,state,progress,totalTiles,doneTiles,message}`）

## 3. MapSettings JSON

```json
{
  "id": "survival-overworld",
  "name": "生存世界",
  "dimension": "overworld",
  "spawn": { "x": 0, "y": 64, "z": 0 },
  "minY": -64,
  "maxY": 320,
  "hiresTileSize": 32,
  "lowresTileSize": 512,
  "lowresMaxLod": 4,
  "atlasUrl": "textures/atlas.png",
  "renderedAt": 1752800000000
}
```

- `hiresTileSize`：hires tile 边长（方块数），固定 32
- `lowresTileSize`：低清 tile 像素边长，固定 512；lod0 每 tile 覆盖 512×512 方块（1 方块/px），lodN 覆盖 `512 * 2^N` 方块
- `lowresMaxLod`：最高 lod 级别（含）

## 4. Hires tile 格式（gzip JSON）

tile (tx,tz) 覆盖世界方块 `[tx*32, tx*32+32) × [tz*32, tz*32+32)`，Y 全高度。
顶点坐标为**世界绝对坐标 float**。

```json
{
  "x": 0,
  "z": 0,
  "positions": [0.0, 64.0, 0.0],
  "indices": [0, 1, 2],
  "uvs": [0.125, 0.5],
  "colors": [1.0, 1.0, 1.0],
  "ao": [1.0],
  "blocklight": [0],
  "skylight": [15],
  "translucent": {
    "positions": [], "indices": [], "uvs": [], "colors": [],
    "ao": [], "blocklight": [], "skylight": []
  }
}
```

- `positions`：float32，xyz 三元组，顶点数 N
- `indices`：uint32 三角形索引（3 的倍数）
- `uvs`：float32，uv 二元组，**已映射到 atlas 空间 [0,1]**
- `colors`：float32，rgb 三元组，顶点染色（草/树叶/水等 tint；无染色为 1,1,1）
- `ao`：float32 0..1，**AO × 面方向漫反射明暗**（原版 DiffuseLight：上 1.0、下 0.5、南北 0.8、东西 0.6）
- `blocklight` / `skylight`：float 0..15，逐顶点光照（原版平滑光照：邻域 4 格平均，可为非整数）
- `translucent`：可选，半透明段（水面等），结构同上；前端用半透明材质单独渲染（alpha≈0.78、关深度写入）
- 空 tile（无可渲染面）返回 404

渲染约定（前端 shader 对应）：
- 最终颜色 = `texture(uv) * color * ao * light`，其中 `light = clamp(ambient + sun * skylight/15 + torch * blocklight/15, 0, 1)`
- 水/熔岩等半透明面：走 `translucent` 段分层渲染；树叶/玻璃 cutout 一期按不透明处理

## 5. Lowres tile 格式（PNG）

- 512×512 px，俯视正交，lod0 每 px 一个方块柱
- 取该柱最高可渲染方块顶面色，按相对高度做明暗渐变（越高越亮，参考原版地图物品配色规则）
- lodN 由 lod(N-1) 2×2 平均下采样生成

## 6. 存储布局（PluginFileStore，key 前缀 `plugins/world-map/`）

```
maps/{mapId}/world.zip               # 原始存档 zip（渲染输入，重渲染时可复用）
maps/{mapId}/client.jar              # 渲染用客户端 jar
maps/{mapId}/textures/atlas.png
maps/{mapId}/tiles/hires/{tx}/{tz}.json.gz
maps/{mapId}/tiles/lowres/{lod}/{tx}/{tz}.png
```

元数据（MapInstance / RenderTask）存 PluginDocumentStore（Mongo）。

## 7. 后端内部模块边界

- `infrastructure/world`：NBT 读取、region/chunk/palette 解码 → `WorldAccess`（`blockState(x,y,z)` / `blockLight` / `skyLight` / `biome` / `maxY(x,z)`）
- `infrastructure/resource`：client jar 解析 → `BlockModelRegistry`（`bakedQuads(blockState)` → 面片列表：顶点、uv（atlas 空间）、tint、cullface、ao 标志）+ `TextureAtlas`（atlas.png 输出）
- `infrastructure/render`：消费 world + resource，产出 §4/§5 tile 字节流
- `infrastructure/storage`：tile/atlas/存档 的 PluginFileStore 读写
