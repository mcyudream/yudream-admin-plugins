package online.yudream.base.plugin.ymcl.api;

/**
 * YAP §6.8 提供方页面包贡献：提供方插件把 module/extension 页面包
 * （自包含 zip）随 jar 携带，注册后由适配器按
 * {@code /v1/bundles/{bundleId}/{version}/package.zip} 统一下发，
 * 与管理台上传的页面包同通道、同缓存语义（不可变 + sha256 校验）。
 *
 * content 为完整 zip 字节；sha256 由适配器/提供方按字节重算，
 * 不出现在本记录里——页面描述中的 bundle 块经
 * {@link YmclModuleSupport#bundleDescriptor} 构建，天然与字节一致。
 */
public record YmclBundleContribution(
        String bundleId,
        String version,
        String entry,
        byte[] content) {
}
