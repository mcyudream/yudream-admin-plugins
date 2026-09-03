package online.yudream.base.plugin.material.interfaces;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import online.yudream.base.plugin.material.application.MaterialService;
import online.yudream.base.plugin.material.application.NotFoundException;
import online.yudream.base.plugin.material.application.PreviewService;
import online.yudream.base.plugin.material.application.ShareService;
import online.yudream.base.plugin.material.application.dto.PreviewInfo;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialType;
import online.yudream.base.plugin.material.domain.MaterialVersion;
import online.yudream.base.plugin.material.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 分享外链公开端点：无权限注解，安全边界是分享 token（存储型凭证，可撤销、可过期）。
 * 页面端点对人输出 HTML；下载/文件流端点对机器输出二进制。
 */
public final class SharePublicController {
    private static final String PLUGIN_BASE = "/api/plugins/material";

    private final ShareService shareService;
    private final MaterialService materialService;
    private final PreviewService previewService;

    public SharePublicController(ShareService shareService, MaterialService materialService, PreviewService previewService) {
        this.shareService = shareService;
        this.materialService = materialService;
        this.previewService = previewService;
    }

    @PluginHttpEndpoint(method = "GET", path = "/public/share/{token}")
    public PluginHttpResponse page(PluginHttpRequest request) {
        String token = HttpSupport.segmentAfter(request.path(), "share");
        Material material;
        try {
            material = shareService.resolveValid(token);
        }
        catch (NotFoundException e) {
            return html(403, errorPage("链接无效或已过期", "该分享链接可能已被撤销、过期或对应物料已删除。"));
        }
        MaterialVersion version = materialService.resolveVersion(material, null);
        PreviewInfo preview;
        try {
            preview = previewService.previewShared(material, version, token, request);
        }
        catch (IllegalStateException e) {
            preview = PreviewInfo.none(e.getMessage());
        }
        return html(200, sharePage(material, version, token, preview));
    }

    @PluginHttpEndpoint(method = "GET", path = "/public/share/{token}/download")
    public PluginHttpResponse download(PluginHttpRequest request) {
        try {
            Material material = shareService.resolveValid(HttpSupport.segmentAfter(request.path(), "share"));
            MaterialVersion version = materialService.resolveVersion(material, null);
            byte[] bytes = materialService.readBytes(version);
            return HttpSupport.download(materialService.resolveVersionFilename(material, version),
                    version.contentType(), bytes);
        }
        catch (NotFoundException e) {
            return PluginHttpResponse.rawJson(404, Map.of("message", e.getMessage()));
        }
    }

    @PluginHttpEndpoint(method = "GET", path = "/public/share/{token}/file/{filename}")
    public PluginHttpResponse file(PluginHttpRequest request) {
        try {
            Material material = shareService.resolveValid(HttpSupport.segmentAfter(request.path(), "share"));
            MaterialVersion version = materialService.resolveVersion(material, null);
            byte[] bytes = materialService.readBytes(version);
            return HttpSupport.rangeAware(HttpSupport.header(request, "range"), version.contentType(), bytes);
        }
        catch (NotFoundException e) {
            return PluginHttpResponse.rawJson(404, Map.of("message", e.getMessage()));
        }
    }

    // ---------- HTML 渲染 ----------

    private String sharePage(Material material, MaterialVersion version, String token, PreviewInfo preview) {
        String name = escape(material.name());
        String downloadUrl = PLUGIN_BASE + "/public/share/" + token + "/download";
        StringBuilder body = new StringBuilder();
        body.append("<header class=\"bar\"><div class=\"meta\"><strong class=\"name\">").append(name).append("</strong>")
                .append("<span class=\"sub\">.").append(escape(material.ext() == null || material.ext().isBlank() ? "?" : material.ext()))
                .append(" · ").append(formatSize(version.size())).append(" · 当前版本 v").append(version.version()).append("</span></div>")
                .append("<a class=\"btn\" href=\"").append(downloadUrl).append("\">下载文件</a></header>");
        body.append("<main class=\"stage\">");
        switch (preview.mode()) {
            case "KKFILE" -> body.append("<iframe class=\"frame\" src=\"").append(escape(preview.url())).append("\" allowfullscreen></iframe>");
            case "DIRECT" -> body.append(directTag(material, preview.url()));
            default -> body.append("<div class=\"empty\"><p>").append(escape(preview.message() == null ? "该文件暂不支持在线预览" : preview.message()))
                    .append("</p><a class=\"btn\" href=\"").append(downloadUrl).append("\">下载后查看</a></div>");
        }
        body.append("</main>");
        return shell(name, body.toString());
    }

    private String directTag(Material material, String url) {
        String escaped = escape(url);
        return switch (material.type()) {
            case IMAGE -> "<div class=\"image-box\"><img src=\"" + escaped + "\" alt=\"预览\"></div>";
            case VIDEO -> "<video class=\"media\" src=\"" + escaped + "\" controls></video>";
            case AUDIO -> "<div class=\"empty\"><audio src=\"" + escaped + "\" controls style=\"width:min(560px,90%)\"></audio></div>";
            case DOCUMENT -> "<iframe class=\"frame\" src=\"" + escaped + "\"></iframe>";
            default -> "<div class=\"empty\"><p>该格式无法在浏览器中直接预览</p></div>";
        };
    }

    private String errorPage(String title, String detail) {
        return shell(title, "<main class=\"stage\"><div class=\"empty\"><p class=\"bad\">" + escape(title) + "</p><p>"
                + escape(detail) + "</p></div></main>");
    }

    private String shell(String title, String body) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + " - 物料分享</title><style>"
                + "*{margin:0;box-sizing:border-box}body{font-family:system-ui,-apple-system,\"Segoe UI\",sans-serif;"
                + "background:#f5f6f8;color:#1f2329;display:flex;flex-direction:column;min-height:100vh}"
                + ".bar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;"
                + "padding:12px 20px;background:#fff;border-bottom:1px solid #e5e6eb}"
                + ".name{font-size:15px;word-break:break-all}.sub{color:#86909c;font-size:12px;margin-left:8px}"
                + ".btn{display:inline-block;padding:7px 16px;border-radius:6px;background:#165dff;color:#fff;"
                + "text-decoration:none;font-size:13px;white-space:nowrap}.btn:hover{background:#0e42d2}"
                + ".stage{flex:1;display:flex;padding:16px}"
                + ".frame{flex:1;border:0;border-radius:8px;background:#fff;min-height:60vh}"
                + ".image-box{flex:1;display:flex;align-items:center;justify-content:center;"
                + "background:repeating-conic-gradient(#e5e6eb 0 25%,#fff 0 50%) 0 0/20px 20px;border-radius:8px}"
                + ".image-box img{max-width:100%;max-height:78vh;object-fit:contain}"
                + ".media{margin:auto;max-width:100%;max-height:78vh}"
                + ".empty{margin:auto;text-align:center;color:#4e5969;font-size:14px;line-height:2}"
                + ".bad{color:#f53f3f;font-size:16px;font-weight:600}"
                + "</style></head><body>" + body + "</body></html>";
    }

    private static PluginHttpResponse html(int status, String page) {
        return new PluginHttpResponse(status, Map.of("Cache-Control", "no-cache"),
                "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8), false);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        }
        if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / 1024.0 / 1024);
        }
        return String.format("%.2f GB", size / 1024.0 / 1024 / 1024);
    }
}
