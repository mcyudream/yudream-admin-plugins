package online.yudream.base.plugin.launcher.interfaces.request;

import java.util.List;

/**
 * VANILLA 渠道版本发布请求（协议 v2 §5.5）。
 *
 * @param versionId              SemVer 版本号
 * @param gameVersion            MC 版本，如 1.21.1
 * @param loader                 vanilla | fabric | forge | neoforge | quilt（缺省 vanilla）
 * @param loaderVersion          loader 版本（vanilla 时必须为空）
 * @param changelog              变更说明
 * @param ignorePatterns         自定义保护清单（缺省用系统默认）
 * @param overridesContentBase64 可选 overrides.zip（base64），物化为 managed entries
 * @param expectedSha256         overrides 的 SHA-256 校验（可选）
 */
public record PublishVanillaVersionRequest(
        String versionId,
        String gameVersion,
        String loader,
        String loaderVersion,
        String changelog,
        List<String> ignorePatterns,
        String overridesContentBase64,
        String expectedSha256
) {
}
