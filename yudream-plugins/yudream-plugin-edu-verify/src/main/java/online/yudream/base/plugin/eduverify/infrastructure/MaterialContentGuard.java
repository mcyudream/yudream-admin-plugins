package online.yudream.base.plugin.eduverify.infrastructure;

import java.util.Locale;

/** 人工审核材料：只接受图片/PDF，且以魔数而非扩展名/声明类型为准。 */
public final class MaterialContentGuard {

    public static final long MAX_BYTES = 8L * 1024 * 1024;
    public static final int MAX_BASE64_CHARS = 11_534_400;
    public static final int MAX_FILENAME = 80;

    private MaterialContentGuard() {
    }

    public record Detected(String contentType, String extension) {
    }

    public static String sanitizeFilename(String raw, String extension) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", "");
        name = name.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}._\\-\\u4e00-\\u9fff]", "_");
        while (name.contains("..")) {
            name = name.replace("..", ".");
        }
        name = name.replaceAll("^\\.+", "");
        if (name.length() > MAX_FILENAME) {
            name = name.substring(0, MAX_FILENAME);
        }
        String ext = extension == null ? "bin" : extension.toLowerCase(Locale.ROOT);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith("." + ext)) {
            int dot = name.lastIndexOf('.');
            name = (dot > 0 ? name.substring(0, dot) : name) + "." + ext;
        }
        if (name.length() <= ext.length() + 1) {
            name = "material." + ext;
        }
        return name;
    }

    public static Detected detect(byte[] bytes, String declaredType, String filename) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("单份材料不能超过 8MB");
        }
        Detected magic = sniff(bytes);
        if (magic == null) {
            throw new IllegalArgumentException("仅支持 JPEG/PNG/GIF/WebP 图片或 PDF");
        }
        String declared = declaredType == null ? "" : declaredType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (!declared.isBlank() && !declared.equals("application/octet-stream") && !declared.equals(magic.contentType())) {
            throw new IllegalArgumentException("文件类型与声明不一致");
        }
        String ext = extensionOf(filename);
        if (ext != null && !ext.equals(magic.extension()) && !(magic.extension().equals("jpeg") && ext.equals("jpg"))) {
            throw new IllegalArgumentException("文件扩展名与实际类型不一致");
        }
        return magic;
    }

    public static byte[] decodeBase64(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        String payload = raw.trim();
        int comma = payload.indexOf(',');
        if (payload.regionMatches(true, 0, "data:", 0, 5) && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        payload = payload.replaceAll("\\s+", "");
        if (payload.length() > MAX_BASE64_CHARS) {
            throw new IllegalArgumentException("单份材料不能超过 8MB");
        }
        if (payload.length() % 4 != 0 || !payload.matches("[A-Za-z0-9+/]*={0,2}")) {
            throw new IllegalArgumentException("文件编码无效");
        }
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(payload);
            if (decoded.length > MAX_BYTES) {
                throw new IllegalArgumentException("单份材料不能超过 8MB");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("8MB")) {
                throw e;
            }
            throw new IllegalArgumentException("文件编码无效");
        }
    }

    private static Detected sniff(byte[] bytes) {
        if (startsWith(bytes, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF)) {
            return new Detected("image/jpeg", "jpeg");
        }
        if (startsWith(bytes, (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return new Detected("image/png", "png");
        }
        if (startsWith(bytes, 'G', 'I', 'F', '8', '7', 'a') || startsWith(bytes, 'G', 'I', 'F', '8', '9', 'a')) {
            return new Detected("image/gif", "gif");
        }
        if (bytes.length >= 12 && startsWith(bytes, 'R', 'I', 'F', 'F') && at(bytes, 8, 'W', 'E', 'B', 'P')) {
            return new Detected("image/webp", "webp");
        }
        if (startsWith(bytes, '%', 'P', 'D', 'F', '-')) {
            return new Detected("application/pdf", "pdf");
        }
        return null;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if ("jpg".equals(ext)) {
            return "jpeg";
        }
        return ext.isBlank() ? null : ext;
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        return at(bytes, 0, expected);
    }

    private static boolean at(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
