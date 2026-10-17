// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 从后端拉取并校验地图清单。
 */
import { parseManifest, type MapManifest } from "../../core/index";

export async function loadManifest(mapBaseUrl: string): Promise<MapManifest> {
  const resp = await fetch(`${mapBaseUrl}/manifest.json`);
  if (!resp.ok) {
    throw new Error(`清单加载失败: HTTP ${resp.status} (${mapBaseUrl}/manifest.json)`);
  }
  return parseManifest(await resp.json());
}
