package online.yudream.base.plugin.material.application.dto;

/**
 * 预览信息。mode：
 * KKFILE —— url 为 kkFileView 完整 iframe 绝对地址；
 * DIRECT —— url 为平台签名公开地址（/api/ 开头，前端用 sdk.files.assetUrl 解析），浏览器原生渲染；
 * NONE —— 不可预览，message 为用户可读提示。
 */
public record PreviewInfo(String mode, String url, String message) {
    public static PreviewInfo kkfile(String url) {
        return new PreviewInfo("KKFILE", url, null);
    }

    public static PreviewInfo direct(String url) {
        return new PreviewInfo("DIRECT", url, null);
    }

    public static PreviewInfo none(String message) {
        return new PreviewInfo("NONE", null, message);
    }
}
