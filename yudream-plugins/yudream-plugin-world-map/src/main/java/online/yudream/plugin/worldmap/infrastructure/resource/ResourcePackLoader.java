package online.yudream.plugin.worldmap.infrastructure.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 资源包加载器：从原版客户端 jar（ZipFile）读取 assets/minecraft 下的
 * blockstates、models、textures 与 colormap。
 *
 * <p>JSON 先保留原始字节，使用时再按需解析（models 数量大，懒解析省时）；
 * PNG 解码为 BufferedImage 也按需进行。</p>
 */
final class ResourcePackLoader implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT = "assets/minecraft/";

    /** 加载结果：全部为 "相对路径（不含扩展名）→ 原始字节" 的映射。 */
    record LoadedPack(
            /** blockstate 名（如 stone）→ blockstate JSON 原文 */
            Map<String, byte[]> blockstates,
            /** 模型路径（如 block/stone）→ 模型 JSON 原文 */
            Map<String, byte[]> models,
            /** 纹理路径（如 block/stone）→ PNG 字节 */
            Map<String, byte[]> textures,
            /** 草地 colormap（可缺失为 null） */
            BufferedImage grassColormap,
            /** 树叶 colormap（可缺失为 null） */
            BufferedImage foliageColormap) {
    }

    private final ZipFile zip;

    private ResourcePackLoader(ZipFile zip) {
        this.zip = zip;
    }

    /** 打开客户端 jar 并读取 assets/minecraft 资产索引。 */
    static LoadedPack load(Path clientJar) throws IOException {
        try (ResourcePackLoader loader = new ResourcePackLoader(new ZipFile(clientJar.toFile()))) {
            return loader.readAll();
        }
    }

    private LoadedPack readAll() throws IOException {
        Map<String, byte[]> blockstates = new HashMap<>();
        Map<String, byte[]> models = new HashMap<>();
        Map<String, byte[]> textures = new HashMap<>();
        BufferedImage grass = null;
        BufferedImage foliage = null;

        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (!name.startsWith(ROOT)) {
                continue;
            }
            String rel = name.substring(ROOT.length());
            if (rel.startsWith("blockstates/") && rel.endsWith(".json")) {
                blockstates.put(stripExt(rel.substring("blockstates/".length())), readBytes(entry));
            } else if (rel.startsWith("models/") && rel.endsWith(".json")) {
                models.put(stripExt(rel.substring("models/".length())), readBytes(entry));
            } else if (rel.equals("textures/colormap/grass.png")) {
                grass = readImage(entry);
            } else if (rel.equals("textures/colormap/foliage.png")) {
                foliage = readImage(entry);
            } else if (rel.startsWith("textures/") && rel.endsWith(".png")) {
                textures.put(stripExt(rel.substring("textures/".length())), readBytes(entry));
            }
        }
        return new LoadedPack(blockstates, models, textures, grass, foliage);
    }

    private byte[] readBytes(ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private BufferedImage readImage(ZipEntry entry) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(readBytes(entry)));
    }

    /** 解析 JSON 字节为 JsonNode（调用方按需使用）。 */
    static JsonNode parseJson(byte[] raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("JSON 解析失败", e);
        }
    }

    /** 解码 PNG 字节。 */
    static BufferedImage decodePng(byte[] raw) {
        try {
            return ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException e) {
            throw new UncheckedIOException("PNG 解码失败", e);
        }
    }

    private static String stripExt(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(0, dot) : path;
    }

    /** 规范化资源路径：去 {@code minecraft:} 前缀。 */
    static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        int idx = path.indexOf(':');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
