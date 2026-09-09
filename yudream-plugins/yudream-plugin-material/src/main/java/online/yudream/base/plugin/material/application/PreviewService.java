package online.yudream.base.plugin.material.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.material.application.dto.PreviewInfo;
import online.yudream.base.plugin.material.domain.Material;
import online.yudream.base.plugin.material.domain.MaterialVersion;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.preview.PluginFilePreviewService;
import online.yudream.base.plugin.spi.system.preview.PluginPreviewFile;
import online.yudream.base.plugin.spi.system.preview.PluginPreviewInfo;

/**
 * 预览信息构建：委托平台文件预览能力（{@link FrameworkServices#filePreview()}）。
 * kkFileView 配置、签名公开文件端点与预览决策都由平台统一持有，插件侧不再维护设置与签名。
 */
public final class PreviewService {
    private static final String PLUGIN_BASE = "/api/plugins/material";

    private final PluginFilePreviewService filePreview;
    private final String pluginCode;

    public PreviewService(FrameworkServices framework, String pluginCode) {
        this.filePreview = framework.filePreview();
        this.pluginCode = pluginCode;
    }

    /** 登录态预览：平台决策 KKFILE（iframe 绝对地址）/ DIRECT（平台签名公开地址）/ NONE。 */
    public PreviewInfo preview(Material material, MaterialVersion version, PluginHttpRequest request) {
        return map(filePreview.preview(pluginCode, new PluginPreviewFile(
                version.objectKey(), displayName(material, version), version.contentType(), version.size())));
    }

    /** 签发当前版本的平台签名公开地址（/api/public/preview/file/...），前端用 sdk.files.assetUrl 解析。 */
    public String signedFilePath(Material material, MaterialVersion version) {
        return filePreview.signedFileUrl(pluginCode, version.objectKey(), displayName(material, version));
    }

    /** 签发库页缩略图；没有封面对象时返回 null，前端回退类型图标，避免把原图塞进列表。 */
    public String signedCoverPath(MaterialVersion version) {
        if (version == null) {
            return null;
        }
        String key = version.coverObjectKey();
        if (key == null || key.isBlank()) {
            return null;
        }
        return filePreview.signedFileUrl(pluginCode, key, "cover.jpg");
    }

    /** 分享页预览：文件地址走分享 token 端点（自带凭证的绝对地址），决策委托平台 previewExternal。 */
    public PreviewInfo previewShared(Material material, MaterialVersion version, String shareToken, PluginHttpRequest request) {
        String filename = displayName(material, version);
        String base = filePreview.callbackBaseUrl();
        if (base == null || base.isBlank()) {
            base = deriveBase(request);
        }
        String path = PLUGIN_BASE + "/public/share/" + shareToken + "/file/" + encodePath(filename);
        String absolute = trimSlash(base) + path;
        String ext = material.ext() != null && !material.ext().isBlank() ? material.ext() : version.ext();
        PreviewInfo info = map(filePreview.previewExternal(absolute, ext == null ? "" : ext, version.size()));
        if ("DIRECT".equals(info.mode())) {
            // 回源绝对地址只对 kk 容器可达；浏览器直连改用分享页相对地址，跟随访问者自己的来源
            return new PreviewInfo("DIRECT", shareToken + "/file/" + encodePath(filename), info.message());
        }
        return info;
    }

    private static PreviewInfo map(PluginPreviewInfo info) {
        return new PreviewInfo(info.mode(), info.url(), info.message());
    }

    private static String displayName(Material material, MaterialVersion version) {
        return version.originalName() != null ? version.originalName() : material.name();
    }

    /** 回源地址未配置时从请求头推导（反向代理场景取 X-Forwarded-Proto + Host）。 */
    private static String deriveBase(PluginHttpRequest request) {
        String host = header(request, "host");
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("无法推导回源地址，请管理员在「系统配置 > 文件预览」中配置回源地址");
        }
        String proto = header(request, "x-forwarded-proto");
        if (proto == null || proto.isBlank()) {
            proto = "http";
        }
        return proto + "://" + host.trim();
    }

    private static String header(PluginHttpRequest request, String name) {
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static String trimSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** 路径段编码：URLEncoder 会把空格编成 +，路径中需换成 %20。 */
    private static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
