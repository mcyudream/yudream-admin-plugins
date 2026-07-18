package online.yudream.plugin.worldmap.infrastructure.resource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 合成客户端 jar：按 assets/minecraft 目录结构写入少量 blockstates/models/textures，
 * 覆盖父链合并、纹理变量链、旋转、multipart、动画贴图、colormap 等测试场景。
 */
final class SynthClientJar {

    private SynthClientJar() {
    }

    static Path create(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path jar = dir.resolve("synth-client.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            // ---------- blockstates ----------
            putJson(zip, "assets/minecraft/blockstates/test_cube.json", """
                    {"variants": {"": {"model": "block/test_child"}}}
                    """);
            // 1.20.2+ 精简格式：不影响模型的属性被省略，仅 "" 空键 → 子集匹配
            putJson(zip, "assets/minecraft/blockstates/test_subset.json", """
                    {"variants": {"": {"model": "block/test_child"}}}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_log.json", """
                    {"variants": {
                      "axis=x": {"model": "block/test_column", "x": 90, "y": 90},
                      "axis=y": {"model": "block/test_column"},
                      "axis=z": {"model": "block/test_column", "x": 90}
                    }}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_fence.json", """
                    {"multipart": [
                      {"apply": {"model": "block/test_post"}},
                      {"when": {"north": "true"}, "apply": {"model": "block/test_arm"}},
                      {"when": {"OR": [{"east": "true"}, {"south": "true"}]}, "apply": {"model": "block/test_arm2"}},
                      {"when": {"type": "a|b"}, "apply": {"model": "block/test_extra"}}
                    ]}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_multi_empty.json", """
                    {"multipart": [
                      {"when": {"north": "true"}, "apply": {"model": "block/test_arm"}}
                    ]}
                    """);
            // 键值冲突场景：variants 仅 axis=z，查询其他 axis → 无匹配 → 缺失回退
            putJson(zip, "assets/minecraft/blockstates/test_conflict.json", """
                    {"variants": {"axis=z": {"model": "block/test_column", "x": 90}}}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_broken.json", """
                    {"variants": {"": {"model": "block/nonexistent"}}}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_air.json", """
                    {"variants": {"": {"model": "block/test_air_model"}}}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_animcube.json", """
                    {"variants": {"": {"model": "block/test_animcube_model"}}}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_glassy.json", """
                    {"variants": {"": {"model": "block/test_glassy_model"}}}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_water.json", """
                    {"variants": {"": {"model": "block/test_water_model"}}}
                    """);
            // 真名液体：模型仅有 particle 纹理、无 elements（与原版 water 一致）→ 触发内置液体几何
            putJson(zip, "assets/minecraft/blockstates/water.json", """
                    {"variants": {"": {"model": "block/water"}}}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_uvlock_off.json", """
                    {"variants": {"": {"model": "block/test_uvcolumn", "y": 90}}}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_uvlock_on.json", """
                    {"variants": {"": {"model": "block/test_uvcolumn", "y": 90, "uvlock": true}}}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_rot.json", """
                    {"variants": {"": {"model": "block/test_rotated"}}}
                    """);
            putJson(zip, "assets/minecraft/blockstates/test_rot_rescale.json", """
                    {"variants": {"": {"model": "block/test_rotated_rescale"}}}
                    """);

            // ---------- models ----------
            // 父：textures.base=block/test_base，chained 经 #base 间接引用；三个面覆盖 cullface/tintindex/uv旋转
            putJson(zip, "assets/minecraft/models/block/test_parent.json", """
                    {
                      "textures": {"base": "block/test_base", "chained": "#base"},
                      "elements": [
                        {"from": [0, 0, 0], "to": [16, 16, 16],
                         "faces": {
                           "north": {"texture": "#chained", "cullface": "north"},
                           "up": {"texture": "#base", "tintindex": 0},
                           "east": {"texture": "#base", "uv": [0, 0, 8, 8], "rotation": 90}
                         }}
                      ]
                    }
                    """);
            // 子：覆盖 base → block/test_derived（验证父链 textures 覆盖 + 变量链 #chained→#base→派生）
            putJson(zip, "assets/minecraft/models/block/test_child.json", """
                    {"parent": "block/test_parent", "textures": {"base": "block/test_derived"}}
                    """);
            putJson(zip, "assets/minecraft/models/block/test_column.json", """
                    {
                      "textures": {"end": "block/test_end", "side": "block/test_derived"},
                      "elements": [
                        {"from": [0, 0, 0], "to": [16, 16, 16],
                         "faces": {
                           "up": {"texture": "#end", "cullface": "up"},
                           "down": {"texture": "#end", "cullface": "down"},
                           "north": {"texture": "#side", "cullface": "north"},
                           "south": {"texture": "#side", "cullface": "south"},
                           "east": {"texture": "#side", "cullface": "east"},
                           "west": {"texture": "#side", "cullface": "west"}
                         }}
                      ]
                    }
                    """);
            putJson(zip, "assets/minecraft/models/block/test_post.json", """
                    {"textures": {"base": "block/test_derived"},
                     "elements": [{"from": [6, 0, 6], "to": [10, 16, 10],
                                   "faces": {"north": {"texture": "#base"}}}]}
                    """);
            putJson(zip, "assets/minecraft/models/block/test_arm.json", """
                    {"textures": {"base": "block/test_derived"},
                     "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                                   "faces": {"up": {"texture": "#base"}}}]}
                    """);
            putJson(zip, "assets/minecraft/models/block/test_arm2.json", """
                    {"textures": {"base": "block/test_derived"},
                     "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                                   "faces": {"east": {"texture": "#base"}}}]}
                    """);
            putJson(zip, "assets/minecraft/models/block/test_extra.json", """
                    {"textures": {"base": "block/test_derived"},
                     "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                                   "faces": {"down": {"texture": "#base"}}}]}
                    """);
            putJson(zip, "assets/minecraft/models/block/test_air_model.json", "{}");
            putJson(zip, "assets/minecraft/models/block/test_animcube_model.json", """
                    {"textures": {"a": "block/test_anim"},
                     "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                                   "faces": {"up": {"texture": "#a"}}}]}
                    """);
            putJson(zip, "assets/minecraft/models/block/test_glassy_model.json", """
                    {"textures": {"a": "block/test_glassy"},
                     "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                                   "faces": {"up": {"texture": "#a"}}}]}
                    """);
            putJson(zip, "assets/minecraft/models/block/test_water_model.json", """
                    {"textures": {"a": "block/test_water"},
                     "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                                   "faces": {"up": {"texture": "#a", "tintindex": 0}}}]}
                    """);
            putJson(zip, "assets/minecraft/models/block/water.json", """
                    {"textures": {"particle": "block/water_still"}}
                    """);
            putJson(zip, "assets/minecraft/models/block/test_uvcolumn.json", """
                    {"textures": {"end": "block/test_end"},
                     "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                                   "faces": {"up": {"texture": "#end", "uv": [0, 0, 8, 16]}}}]}
                    """);
            putJson(zip, "assets/minecraft/models/block/test_rotated.json", """
                    {"textures": {"a": "block/test_derived"},
                     "elements": [{"from": [2, 0, 2], "to": [14, 16, 14],
                                   "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45},
                                   "faces": {"north": {"texture": "#a"}}}]}
                    """);
            putJson(zip, "assets/minecraft/models/block/test_rotated_rescale.json", """
                    {"textures": {"a": "block/test_derived"},
                     "elements": [{"from": [2, 0, 2], "to": [14, 16, 14],
                                   "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": true},
                                   "faces": {"north": {"texture": "#a"}}}]}
                    """);

            // ---------- textures ----------
            putPng(zip, "assets/minecraft/textures/block/test_derived.png", solid(0xFFFF0000));
            putPng(zip, "assets/minecraft/textures/block/test_base.png", solid(0xFF0000FF));
            putPng(zip, "assets/minecraft/textures/block/test_end.png", solid(0xFF00FF00));
            // 动画长条：16x32，首帧红、次帧蓝
            BufferedImage anim = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
            fill(anim, 0, 0, 16, 16, 0xFFFF0000);
            fill(anim, 0, 16, 16, 32, 0xFF0000FF);
            putPng(zip, "assets/minecraft/textures/block/test_anim.png", anim);
            // 半透明玻璃
            putPng(zip, "assets/minecraft/textures/block/test_glassy.png", solid(0x80CCCCFF));
            // 不透明但名字含 water
            putPng(zip, "assets/minecraft/textures/block/test_water.png", solid(0xFF2277CC));
            // 液体 still 贴图
            putPng(zip, "assets/minecraft/textures/block/water_still.png", solid(0xFF3355DD));
            putPng(zip, "assets/minecraft/textures/block/lava_still.png", solid(0xFFDD5522));

            // ---------- colormap ----------
            // plains 采样点：t=0.8f,d=0.4f → x=(int)((1-t)*255)=50（float 提升 double 后截断，与原版一致），y=173
            BufferedImage grass = solid256(0xFF00FF00);
            grass.setRGB(50, 173, 0xFF123456);
            putPng(zip, "assets/minecraft/textures/colormap/grass.png", grass);
            BufferedImage foliage = solid256(0xFF00FF00);
            foliage.setRGB(50, 173, 0xFF654321);
            putPng(zip, "assets/minecraft/textures/colormap/foliage.png", foliage);
        }
        return jar;
    }

    // ---------- 工具 ----------

    private static void putJson(ZipOutputStream zip, String name, String json) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(json.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void putPng(ZipOutputStream zip, String name, BufferedImage img) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        zip.write(out.toByteArray());
        zip.closeEntry();
    }

    private static BufferedImage solid(int argb) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        fill(img, 0, 0, 16, 16, argb);
        return img;
    }

    private static BufferedImage solid256(int argb) {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        fill(img, 0, 0, 256, 256, argb);
        return img;
    }

    private static void fill(BufferedImage img, int x0, int y0, int x1, int y1, int argb) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                img.setRGB(x, y, argb);
            }
        }
    }

    /** 从贴图集 PNG 解码（测试用）。 */
    static BufferedImage decodePng(byte[] png) {
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
