package online.yudream.base.plugin.ymcl.interfaces.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CAS 对象的管理端预览分类与内容提取。
 *
 * kind：text（json/toml/properties/yml 等文本配置）、mod（jar/zip 的 loader 元数据）、
 * image（浏览器可直显的位图）、binary（其余，仅元数据 + 下载地址）。
 */
public final class CasFilePreview {

    public static final String KIND_TEXT = "text";
    public static final String KIND_MOD = "mod";
    public static final String KIND_IMAGE = "image";
    public static final String KIND_BINARY = "binary";

    /** 文本预览最大字节数，超出截断。 */
    public static final int MAX_TEXT_BYTES = 256 * 1024;
    /** 读取 zip 成员时的单文件上限（防超大 entry 撑爆内存）。 */
    private static final int MAX_ZIP_MEMBER_BYTES = 512 * 1024;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "json", "json5", "toml", "properties", "ini", "cfg", "conf", "yml", "yaml",
            "txt", "log", "mcmeta", "lang", "csv", "xml", "mcfunction", "nbt", "snbt",
            "md", "html", "js", "ts", "css", "glsl", "vert", "frag", "fsh", "vsh",
            "info", "txtx", "list", "txt2", "url", "gitignore", "gitattributes");

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg");

    private static final Set<String> MOD_ARCHIVE_EXTENSIONS = Set.of("jar", "zip");

    private static final ObjectMapper JSON = new ObjectMapper();

    private CasFilePreview() {
    }

    public static String extension(String path) {
        if (path == null) {
            return "";
        }
        String name = path;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (slash >= 0) {
            name = path.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String kindFor(String path) {
        String ext = extension(path);
        if (TEXT_EXTENSIONS.contains(ext) || "nbt".equals(ext)) {
            return KIND_TEXT;
        }
        if (MOD_ARCHIVE_EXTENSIONS.contains(ext)) {
            return KIND_MOD;
        }
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return KIND_IMAGE;
        }
        return KIND_BINARY;
    }

    /** 文本：按 UTF-8 解码；JSON 内容尽量 pretty-print。超限截断。 */
    public static Map<String, Object> textPreview(byte[] bytes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        int length = Math.min(bytes.length, MAX_TEXT_BYTES);
        boolean truncated = bytes.length > MAX_TEXT_BYTES;
        String text = new String(bytes, 0, length, StandardCharsets.UTF_8);
        if (!truncated && looksLikeJson(text)) {
            try {
                Object parsed = JSON.readValue(text, Object.class);
                text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
            } catch (Exception ignored) {
                // 非合法 JSON 或含注释等，保持原文
            }
        }
        payload.put("kind", KIND_TEXT);
        payload.put("content", text);
        payload.put("truncated", truncated);
        payload.put("size", bytes.length);
        return payload;
    }

    /** 从 jar/zip 流式提取 loader 元数据（只缓冲已知小文件）。 */
    public static Map<String, Object> modPreview(InputStream input) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", KIND_MOD);
        Map<String, Object> meta = new LinkedHashMap<>();
        List<String> found = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String normalized = name.replace('\\', '/');
                if (normalized.startsWith("/")) {
                    normalized = normalized.substring(1);
                }
                if (!isModMetaEntry(normalized)) {
                    continue;
                }
                byte[] member = readLimited(zip, MAX_ZIP_MEMBER_BYTES);
                if (member == null) {
                    continue;
                }
                found.add(normalized);
                applyModMetaEntry(meta, normalized, member);
            }
        }
        payload.put("metaFiles", found);
        payload.put("meta", meta);
        if (meta.isEmpty()) {
            payload.put("message", "未在压缩包中找到 fabric/quilt/forge/neoforge 元数据，可能是资源包或不带 loader 描述的 jar");
        }
        return payload;
    }

    private static boolean isModMetaEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("fabric.mod.json")
                || lower.equals("quilt.mod.json")
                || lower.equals("mcmod.info")
                || lower.equals("meta-inf/mods.toml")
                || lower.equals("meta-inf/neoforge.mods.toml")
                || lower.equals("meta-inf/jarjar/metadata.json")
                || lower.endsWith("/fabric.mod.json")
                || lower.endsWith("/quilt.mod.json");
    }

    @SuppressWarnings("unchecked")
    private static void applyModMetaEntry(Map<String, Object> meta, String path, byte[] bytes) {
        String lower = path.toLowerCase(Locale.ROOT);
        String body = new String(bytes, StandardCharsets.UTF_8);
        try {
            if (lower.endsWith("fabric.mod.json") || lower.endsWith("quilt.mod.json")) {
                Map<String, Object> parsed = JSON.readValue(body, Map.class);
                putIfPresent(meta, "id", firstOf(parsed, "id", "quilt_loader.id", "modid"));
                putIfPresent(meta, "version", firstOf(parsed, "version", "quilt_loader.version"));
                Object name = parsed.get("name");
                if (name == null && parsed.get("quilt_loader") instanceof Map<?, ?> loader) {
                    name = firstOf((Map<String, Object>) loader, "metadata.name", "name");
                }
                putIfPresent(meta, "name", name);
                putIfPresent(meta, "description", parsed.get("description"));
                if (parsed.get("authors") instanceof List<?> authors && !authors.isEmpty()) {
                    meta.put("authors", authors.stream().map(String::valueOf).limit(12).toList());
                }
                Object minecraft = firstOf(parsed, "minecraft", "depends.minecraft", "depends.minecraft");
                if (minecraft != null) {
                    meta.put("minecraft", String.valueOf(minecraft));
                }
                meta.put("loader", lower.endsWith("quilt.mod.json") ? "quilt" : "fabric");
            } else if (lower.endsWith("mods.toml") || lower.endsWith("neoforge.mods.toml")) {
                applyModsToml(meta, body);
                meta.put("loader", lower.endsWith("neoforge.mods.toml") ? "neoforge" : "forge");
            } else if (lower.endsWith("mcmod.info")) {
                Object parsed = JSON.readValue(body, Object.class);
                if (parsed instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
                    Map<String, Object> entry = (Map<String, Object>) first;
                    putIfPresent(meta, "id", firstOf(entry, "modid", "id"));
                    putIfPresent(meta, "name", entry.get("name"));
                    putIfPresent(meta, "version", entry.get("version"));
                    putIfPresent(meta, "description", entry.get("description"));
                    if (entry.get("authorList") instanceof List<?> authors) {
                        meta.put("authors", authors.stream().map(String::valueOf).limit(12).toList());
                    }
                } else if (parsed instanceof Map<?, ?> single) {
                    Map<String, Object> entry = (Map<String, Object>) single;
                    putIfPresent(meta, "id", firstOf(entry, "modid", "id"));
                    putIfPresent(meta, "name", entry.get("name"));
                    putIfPresent(meta, "version", entry.get("version"));
                    putIfPresent(meta, "description", entry.get("description"));
                }
                meta.put("loader", "forge-legacy");
            }
        } catch (Exception ignored) {
            // 元数据解析失败不阻断预览，前端展示原始文件列表
        }
    }

    /** 极简 mods.toml：提取 [[mods]] 首条与常见键。 */
    private static void applyModsToml(Map<String, Object> meta, String body) {
        boolean inMods = false;
        for (String raw : body.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                inMods = line.equals("[[mods]]") || line.equals("[mods]");
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = stripQuotes(line.substring(eq + 1).trim());
            if (inMods) {
                if ("modId".equals(key) || "modid".equals(key)) {
                    putIfPresent(meta, "id", value);
                } else if ("displayName".equals(key)) {
                    putIfPresent(meta, "name", value);
                } else if ("version".equals(key)) {
                    putIfPresent(meta, "version", value);
                } else if ("description".equals(key)) {
                    putIfPresent(meta, "description", value);
                } else if ("authors".equals(key)) {
                    putIfPresent(meta, "authors", value);
                }
            } else if ("minecraft".equals(key) || "loaderVersion".equals(key)) {
                putIfPresent(meta, key.equals("minecraft") ? "minecraft" : "loaderVersion", value);
            }
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Object firstOf(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (key.contains(".")) {
                String[] parts = key.split("\\.");
                Object cursor = map;
                for (String part : parts) {
                    if (!(cursor instanceof Map<?, ?>)) {
                        cursor = null;
                        break;
                    }
                    cursor = ((Map<String, Object>) cursor).get(part);
                }
                if (cursor != null) {
                    return cursor;
                }
                continue;
            }
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (text.isBlank() || "null".equals(text)) {
            return;
        }
        if (text.length() > 2000) {
            text = text.substring(0, 2000) + "…";
        }
        target.put(key, text);
    }

    private static boolean looksLikeJson(String text) {
        String trimmed = text.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > limit) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
