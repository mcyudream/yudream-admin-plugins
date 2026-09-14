package online.yudream.base.plugin.launcher.interfaces.request;

/**
 * 上传 overrides zip。SPI 端点只收 JSON，因此内容以 base64 内嵌。
 */
public record UploadOverridesRequest(
        String overridesContentBase64,
        String expectedSha256
) {
}
