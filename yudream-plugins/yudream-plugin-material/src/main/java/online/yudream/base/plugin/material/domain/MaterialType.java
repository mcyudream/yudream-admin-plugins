package online.yudream.base.plugin.material.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 物料类型，按扩展名归类。 */
public enum MaterialType {
    IMAGE, DESIGN, DOCUMENT, SPREADSHEET, PRESENTATION, VIDEO, AUDIO, ARCHIVE, OTHER;

    private static final Set<String> IMAGE_EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "avif", "tif", "tiff");
    private static final Set<String> DESIGN_EXTS = Set.of("psd", "psb", "ai", "eps", "sketch", "xd");
    private static final Set<String> DOCUMENT_EXTS = Set.of("pdf", "doc", "docx", "txt", "md", "markdown", "log", "rtf", "odt", "json", "xml", "yml", "yaml", "properties", "ini", "html", "htm");
    private static final Set<String> SPREADSHEET_EXTS = Set.of("xls", "xlsx", "csv", "ods", "et");
    private static final Set<String> PRESENTATION_EXTS = Set.of("ppt", "pptx", "odp", "dps");
    private static final Set<String> VIDEO_EXTS = Set.of("mp4", "webm", "mov", "mkv", "avi", "flv", "m4v");
    private static final Set<String> AUDIO_EXTS = Set.of("mp3", "wav", "ogg", "flac", "m4a", "aac");
    private static final Set<String> ARCHIVE_EXTS = Set.of("zip", "rar", "7z", "tar", "gz", "bz2");

    /** 浏览器可直接渲染的文本格式（以 text/* MIME 直读）。 */
    private static final Set<String> TEXT_EXTS = Set.of("txt", "md", "markdown", "json", "xml", "yml", "yaml", "log", "csv", "properties", "ini");

    /** 浏览器可直接渲染的视频格式（其余交给 kkFileView）。 */
    private static final Set<String> BROWSER_VIDEO_EXTS = Set.of("mp4", "webm", "m4v", "mov");

    public static String extOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    public static MaterialType fromFilename(String filename) {
        return fromExt(extOf(filename));
    }

    public static MaterialType fromExt(String ext) {
        if (IMAGE_EXTS.contains(ext)) {
            return IMAGE;
        }
        if (DESIGN_EXTS.contains(ext)) {
            return DESIGN;
        }
        if (DOCUMENT_EXTS.contains(ext)) {
            return DOCUMENT;
        }
        if (SPREADSHEET_EXTS.contains(ext)) {
            return SPREADSHEET;
        }
        if (PRESENTATION_EXTS.contains(ext)) {
            return PRESENTATION;
        }
        if (VIDEO_EXTS.contains(ext)) {
            return VIDEO;
        }
        if (AUDIO_EXTS.contains(ext)) {
            return AUDIO;
        }
        if (ARCHIVE_EXTS.contains(ext)) {
            return ARCHIVE;
        }
        return OTHER;
    }

    /** 未启用 kkFileView 时，浏览器无需插件即可直接渲染的类型。 */
    public static boolean browserRenderable(MaterialType type, String ext) {
        return switch (type) {
            case IMAGE, AUDIO -> true;
            case VIDEO -> BROWSER_VIDEO_EXTS.contains(ext);
            case DOCUMENT -> "pdf".equals(ext) || TEXT_EXTS.contains(ext);
            default -> false;
        };
    }

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("bmp", "image/bmp"),
            Map.entry("svg", "image/svg+xml"), Map.entry("ico", "image/x-icon"), Map.entry("avif", "image/avif"),
            Map.entry("tif", "image/tiff"), Map.entry("tiff", "image/tiff"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("psd", "image/vnd.adobe.photoshop"), Map.entry("psb", "image/vnd.adobe.photoshop"),
            Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("markdown", "text/markdown; charset=utf-8"), Map.entry("log", "text/plain; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"), Map.entry("xml", "application/xml; charset=utf-8"),
            Map.entry("yml", "application/yaml; charset=utf-8"), Map.entry("yaml", "application/yaml; charset=utf-8"),
            Map.entry("properties", "text/plain; charset=utf-8"), Map.entry("ini", "text/plain; charset=utf-8"),
            Map.entry("csv", "text/csv; charset=utf-8"), Map.entry("rtf", "application/rtf"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("mov", "video/quicktime"),
            Map.entry("mkv", "video/x-matroska"), Map.entry("avi", "video/x-msvideo"), Map.entry("flv", "video/x-flv"),
            Map.entry("m4v", "video/mp4"),
            Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"), Map.entry("ogg", "audio/ogg"),
            Map.entry("flac", "audio/flac"), Map.entry("m4a", "audio/mp4"), Map.entry("aac", "audio/aac"),
            Map.entry("zip", "application/zip"), Map.entry("rar", "application/vnd.rar"),
            Map.entry("7z", "application/x-7z-compressed"), Map.entry("tar", "application/x-tar"),
            Map.entry("gz", "application/gzip"));

    public static String mimeOf(String ext) {
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    /** 类型中文标签，供列表展示。 */
    public static String labelOf(MaterialType type) {
        return switch (type) {
            case IMAGE -> "图片";
            case DESIGN -> "设计稿";
            case DOCUMENT -> "文档";
            case SPREADSHEET -> "表格";
            case PRESENTATION -> "演示";
            case VIDEO -> "视频";
            case AUDIO -> "音频";
            case ARCHIVE -> "压缩包";
            case OTHER -> "其他";
        };
    }

    public static List<String> allLabels() {
        return List.of("IMAGE", "DESIGN", "DOCUMENT", "SPREADSHEET", "PRESENTATION", "VIDEO", "AUDIO", "ARCHIVE", "OTHER");
    }
}
