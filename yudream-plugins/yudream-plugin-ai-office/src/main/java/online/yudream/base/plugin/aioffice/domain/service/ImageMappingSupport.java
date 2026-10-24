package online.yudream.base.plugin.aioffice.domain.service;

import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType;
import online.yudream.base.plugin.spi.system.document.PluginWordTemplateImage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图片映射支持（纯函数）：解析映射声明里的图片变量，并把解析后的渲染数据中的
 * 文件键/附件描述转换为 SPI 图片值。
 *
 * <p>声明方式：顶层映射 transform 写 {@code image} 或 {@code image:宽度px}；
 * 列表项映射的行数据源写 {@code image:item.fileKey}（前缀 image:）。</p>
 */
public final class ImageMappingSupport {

    private static final int PX_TO_EMU = 9525;

    private ImageMappingSupport() {
    }

    /** 顶层映射中声明为图片的变量名（transform 以 image 开头）。 */
    public static List<String> topLevelImageVars(List<OfficeDocType.Mapping> mappings) {
        List<String> vars = new ArrayList<>();
        for (OfficeDocType.Mapping mapping : mappings) {
            if (mapping.transform() != null && mapping.transform().trim().toLowerCase(Locale.ROOT).startsWith("image")
                    && mapping.var() != null && !mapping.var().isBlank()) {
                vars.add(mapping.var());
            }
        }
        return vars;
    }

    /**
     * 列表映射中声明为图片的行变量：外层 key 为渲染数据里的列表变量，
     * 内层列表为该列表每一行中的图片行变量名。
     */
    public static Map<String, List<String>> rowImageVars(List<OfficeDocType.Mapping> mappings) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (OfficeDocType.Mapping mapping : mappings) {
            if (mapping.itemMapping() == null || mapping.itemMapping().isEmpty()) {
                continue;
            }
            List<String> imageRows = new ArrayList<>();
            mapping.itemMapping().forEach((rowVar, rowSource) -> {
                if (rowSource != null && rowSource.trim().toLowerCase(Locale.ROOT).startsWith("image:")) {
                    imageRows.add(rowVar);
                }
            });
            if (!imageRows.isEmpty()) {
                result.put(mapping.var(), imageRows);
            }
        }
        return result;
    }

    /** 解析宽度声明：transform "image:480" → 480px 的 EMU；空/非法 → null（引擎按原始尺寸）。 */
    public static Integer widthEmu(String transform) {
        String rule = transform == null ? "" : transform.trim().toLowerCase(Locale.ROOT);
        int colon = rule.indexOf(':');
        if (colon < 0 || colon == rule.length() - 1) {
            return null;
        }
        try {
            int px = Integer.parseInt(rule.substring(colon + 1).trim());
            return px > 0 ? px * PX_TO_EMU : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 附件描述或裸文件键 → 图片值；非图片、格式不支持或字节不可读时返回 null（调用方应置空串）。 */
    public static PluginWordTemplateImage toImage(Object attachment, ImageBytesLoader loader) {
        String fileKey;
        String contentType;
        String filename;
        if (attachment instanceof Map<?, ?> map) {
            fileKey = text(map.get("fileKey"));
            contentType = text(map.get("contentType"));
            filename = text(map.get("filename"));
        }
        else {
            fileKey = text(attachment);
            contentType = "";
            filename = "";
        }
        if (fileKey.isBlank()) {
            return null;
        }
        byte[] content = loader.load(fileKey);
        if (content == null || content.length == 0) {
            return null;
        }
        // 魔数嗅探优先（行数据源只有裸文件键时无元数据），contentType/扩展名兜底
        String format = sniffFormat(content);
        if (format == null) {
            format = resolveFormat(contentType, filename);
        }
        if (format == null) {
            return null;
        }
        return new PluginWordTemplateImage(content, format, null);
    }

    /** 按文件头识别图片格式；识别不了返回 null。 */
    public static String sniffFormat(byte[] content) {
        if (content == null || content.length < 4) {
            return null;
        }
        if ((content[0] & 0xFF) == 0x89 && content[1] == 0x50 && content[2] == 0x4E && content[3] == 0x47) {
            return "png";
        }
        if ((content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8 && (content[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (content[0] == 'G' && content[1] == 'I' && content[2] == 'F' && content[3] == '8') {
            return "gif";
        }
        if (content[0] == 'B' && content[1] == 'M') {
            return "bmp";
        }
        return null;
    }

    /** contentType / 文件名 → SPI 图片格式；不支持返回 null。 */
    public static String resolveFormat(String contentType, String filename) {
        String type = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (type.startsWith("image/")) {
            String sub = type.substring("image/".length());
            if ("jpg".equals(sub)) {
                return "jpeg";
            }
            if (PluginWordTemplateImage.isSupportedFormat(sub)) {
                return sub;
            }
        }
        String name = filename == null ? "" : filename.trim().toLowerCase(Locale.ROOT);
        for (String extension : List.of(".png", ".jpg", ".jpeg", ".gif", ".bmp")) {
            if (name.endsWith(extension)) {
                return extension.equals(".jpg") ? "jpeg" : extension.substring(1);
            }
        }
        return null;
    }

    /** 按文件键取字节。 */
    public interface ImageBytesLoader {
        byte[] load(String fileKey);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
