"""Generate procedural Minecraft-style textures for the neco-pixel theme.

All pixels are drawn from scratch (no Mojang assets):
  public/ui/neco-dialog.png      48x48, 16px 9-slice hollow dialog frame
  public/blockbg/deepslate.png   32x32 dark stone tile
  public/blockbg/blue-ice.png    32x32 pale ice tile
"""
import random
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).parent


def dialog_frame() -> None:
    size = 48
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    # MC 风格对话框：外白框 + 内侧灰阶收边 + 半透明深色内芯
    rings = [
        (255, 255, 255, 255),  # 最外层 1px 纯白
        (208, 208, 208, 255),
        (160, 160, 160, 255),
        (111, 111, 111, 255),
    ]
    for i, color in enumerate(rings):
        for x in range(i, size - i):
            px[x, i] = color
            px[x, size - 1 - i] = color
        for y in range(i, size - i):
            px[i, y] = color
            px[size - 1 - i, y] = color
    inner = len(rings)
    for y in range(inner, size - inner):
        for x in range(inner, size - inner):
            px[x, y] = (16, 16, 16, 208)
    out = ROOT / "public" / "ui" / "neco-dialog.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print("wrote", out)


def tile(name: str, base: tuple[int, int, int], blobs: list[tuple[int, int, int]], seed: int) -> None:
    rng = random.Random(seed)
    size = 32
    img = Image.new("RGB", (size, size), base)
    px = img.load()
    # 大块噪点：随机 2x2/1x1 斑块叠加，模拟石材结晶
    for _ in range(90):
        cx, cy = rng.randrange(size), rng.randrange(size)
        c = rng.choice(blobs)
        w = 2 if rng.random() < 0.4 else 1
        for dy in range(w):
            for dx in range(w):
                px[(cx + dx) % size, (cy + dy) % size] = c
    # 细颗粒抖动
    for y in range(size):
        for x in range(size):
            if rng.random() < 0.08:
                r, g, b = px[x, y]
                delta = rng.choice((-12, -8, 8, 12))
                px[x, y] = (
                    max(0, min(255, r + delta)),
                    max(0, min(255, g + delta)),
                    max(0, min(255, b + delta)),
                )
    out = ROOT / "public" / "blockbg" / f"{name}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print("wrote", out)


dialog_frame()
tile(
    "deepslate",
    base=(47, 47, 52),
    blobs=[(38, 38, 43), (56, 56, 62), (66, 66, 73), (32, 32, 36)],
    seed=114,
)
tile(
    "blue-ice",
    base=(116, 164, 216),
    blobs=[(146, 190, 232), (94, 142, 198), (172, 208, 240), (80, 124, 182)],
    seed=514,
)
