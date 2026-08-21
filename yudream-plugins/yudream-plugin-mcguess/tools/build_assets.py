#!/usr/bin/env python3
"""生成 mcguess 插件的物品数据集与图标资源。

数据来源：
- 物品与合成配方：PrismarineJS/minecraft-data（JE 1.20.5）
- 中文名：mcasset.cloud 的 zh_cn.json（JE 1.20.5）
- 图标：ccvaults.com（ccLeaf MC Icons，1024px，此处降采样为 64px）

产物（提交到仓库）：
- src/main/resources/mcguess/mcdata.json   物品 + 代表配方
- src/main/resources/mcguess/icons/<id>.png

缓存目录 tools/.cache 不提交。重复运行只会补抓缺失图标。
"""
from __future__ import annotations

import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
CACHE = HERE / ".cache"
RESOURCES = HERE.parent / "src" / "main" / "resources"
ICON_DIR = RESOURCES / "mcguess" / "icons"
ICON_SIZE = 64

MC_DATA_BASE = "https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data/pc/1.20.5/"
# mcasset.cloud 对直链返回 HTML 查看页，改用 InventivetalentDev 的 1.20.5 镜像
LANG_URL = "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.20.5/assets/minecraft/lang/zh_cn.json"
CC_BASE = "https://ccvaults.com"
CC_API_KEY = "242gag58XGJjOfPPl9nFE8xz92YjMHysKyvVaJ"  # 站点前端公开的客户端 key
CC_CATEGORIES = ["10. Items", "20. Blocks"]

# displayName 归一化后仍对不上 ccvaults 文件名时的手工映射（item id -> ccvaults 文件名去掉 .png）
ICON_OVERRIDES = {
    # 石化橡木台阶与橡木台阶外观一致
    "petrified_oak_slab": "Oak_Slab",
}

UA = {"User-Agent": "Mozilla/5.0 (mcguess asset pipeline)"}


def fetch(url: str, dest: Path, headers: dict | None = None, binary: bool = True) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(url, headers={**UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
    dest.write_bytes(data)


def cached_json(path: Path, url: str, headers: dict | None = None) -> object:
    if not path.exists():
        print(f"download {url}")
        fetch(url, path, headers)
    return json.loads(path.read_text(encoding="utf-8"))


def normalize_name(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", name.lower())


def cc_token() -> str:
    req = urllib.request.Request(
        f"{CC_BASE}/api/token",
        data=b"{}",
        headers={
            **UA,
            "x-api-key": CC_API_KEY,
            "Origin": CC_BASE,
            "Referer": f"{CC_BASE}/",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())["token"]


def cc_get(path: str, token: str) -> object:
    req = urllib.request.Request(
        f"{CC_BASE}{path}",
        headers={**UA, "Authorization": f"Bearer {token}", "Referer": f"{CC_BASE}/"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read())


def cc_asset_index(token: str) -> dict[str, str]:
    """归一化文件名 -> 下载路径 /assets/{category}/{subcategory}/{file}"""
    index: dict[str, str] = {}
    for category in CC_CATEGORIES:
        path = CACHE / f"cc-{category}.json"
        if path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
        else:
            data = cc_get(f"/api/assets/{urllib.parse.quote(category)}", token)
            path.write_text(json.dumps(data), encoding="utf-8")
        for group in data:
            for sub in group.get("subcategories") or []:
                for file in sub["files"]:
                    rel = f"/assets/{urllib.parse.quote(category)}/{urllib.parse.quote(sub['name'])}/{urllib.parse.quote(file)}"
                    index.setdefault(normalize_name(file.removesuffix(".png")), rel)
            for file in group.get("files") or []:
                rel = f"/assets/{urllib.parse.quote(category)}/{urllib.parse.quote(file)}"
                index.setdefault(normalize_name(file.removesuffix(".png")), rel)
    return index


def build_recipes(recipes_raw: dict, id_to_name: dict[int, str]) -> dict[str, dict]:
    """每个成品取一个代表配方，归一化为 3x3 网格（靠左上）。"""
    result: dict[str, dict] = {}
    for result_id, variants in recipes_raw.items():
        name = id_to_name.get(int(result_id))
        if not name:
            continue
        # 优先有序配方，其次原料更少的无序配方
        def score(recipe: dict) -> tuple[int, int]:
            if recipe.get("inShape"):
                cells = [c for row in recipe["inShape"] for c in row if c is not None]
                return (0, len(cells))
            ingredients = recipe.get("ingredients") or []
            return (1, len(ingredients))

        best = sorted(variants, key=score)[0]
        grid: list[str | None] = [None] * 9
        if best.get("inShape"):
            shape = best["inShape"]
            for r, row in enumerate(shape[:3]):
                for c, cell in enumerate(row[:3]):
                    if cell is not None:
                        cell_name = id_to_name.get(int(cell)) if not isinstance(cell, list) else id_to_name.get(int(cell[0]))
                        if cell_name:
                            grid[r * 3 + c] = cell_name
        else:
            slot = 0
            for cell in (best.get("ingredients") or [])[:9]:
                cell_name = id_to_name.get(int(cell)) if not isinstance(cell, list) else id_to_name.get(int(cell[0]))
                if cell_name:
                    grid[slot] = cell_name
                    slot += 1
        count = (best.get("result") or {}).get("count", 1)
        result[name] = {"g": grid, "c": count}
    return result


def main() -> int:
    CACHE.mkdir(parents=True, exist_ok=True)
    ICON_DIR.mkdir(parents=True, exist_ok=True)

    items_raw = cached_json(CACHE / "items.json", MC_DATA_BASE + "items.json")
    recipes_raw = cached_json(CACHE / "recipes.json", MC_DATA_BASE + "recipes.json")
    zh_lang = cached_json(CACHE / "zh_cn.json", LANG_URL)

    id_to_name = {item["id"]: item["name"] for item in items_raw}
    recipes = build_recipes(recipes_raw, id_to_name)

    items = []
    for item in items_raw:
        name = item["name"]
        zh = zh_lang.get(f"item.minecraft.{name}") or zh_lang.get(f"block.minecraft.{name}")
        if not zh:
            continue  # 无中文名的物品（如空气、仅命令物品）不参与游戏
        items.append({"id": name, "en": item["displayName"], "zh": zh, "craft": name in recipes})

    token = cc_token()
    cc_index = cc_asset_index(token)
    print(f"ccvaults icons indexed: {len(cc_index)}")

    missing: list[str] = []
    icon_paths: dict[str, str] = {}
    for item in items:
        override = ICON_OVERRIDES.get(item["id"])
        candidates = [override] if override else []
        candidates += [item["en"], item["id"].replace("_", " "), item["id"]]
        rel = None
        for candidate in candidates:
            if candidate is None:
                continue
            rel = cc_index.get(normalize_name(candidate))
            if rel:
                break
        if rel:
            icon_paths[item["id"]] = rel
        else:
            missing.append(item["id"])

    def download_icon(item_id: str) -> tuple[str, bool]:
        dest = ICON_DIR / f"{item_id}.png"
        if dest.exists():
            return item_id, True
        cache_png = CACHE / "icons" / f"{item_id}.png"
        if not cache_png.exists():
            url = f"{CC_BASE}{icon_paths[item_id]}"
            for attempt in range(3):
                try:
                    fetch(url, cache_png)
                    break
                except (urllib.error.URLError, TimeoutError) as exc:
                    if attempt == 2:
                        print(f"icon failed {item_id}: {exc}", file=sys.stderr)
                        return item_id, False
                    time.sleep(1 + attempt)
        with Image.open(cache_png) as image:
            image = image.convert("RGBA").resize((ICON_SIZE, ICON_SIZE), Image.NEAREST)
            image.save(dest, optimize=True)
        return item_id, True

    todo = sorted(icon_paths)
    print(f"icons matched: {len(todo)}, missing: {len(missing)}")
    failed: list[str] = []
    with ThreadPoolExecutor(max_workers=8) as pool:
        for item_id, ok in pool.map(download_icon, todo):
            if not ok:
                failed.append(item_id)

    for item in items:
        item["icon"] = item["id"] in icon_paths and item["id"] not in failed

    dataset = {
        "game": "MC猜物",
        "mcVersion": "1.20.5",
        "iconCredit": "ccvaults.com (ccLeaf MC Icons)",
        "items": items,
        "recipes": recipes,
    }
    out = RESOURCES / "mcguess" / "mcdata.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(dataset, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    report = {
        "items": len(items),
        "craftable": len(recipes),
        "icons": sum(1 for i in items if i["icon"]),
        "missingIcons": missing,
        "failedDownloads": failed,
        "missingCraftableIcons": [i for i in missing if i in recipes],
    }
    (CACHE / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: (len(v) if isinstance(v, list) else v) for k, v in report.items()}, ensure_ascii=False))
    print("craftable without icon:", report["missingCraftableIcons"][:40])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
