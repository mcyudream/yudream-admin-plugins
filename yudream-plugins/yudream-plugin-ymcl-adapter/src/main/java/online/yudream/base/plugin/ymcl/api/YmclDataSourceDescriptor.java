package online.yudream.base.plugin.ymcl.api;

/**
 * YAP §6.5 数据源贡献描述。
 *
 * {@code sourceCode} 与提供方 {@code providerCode} 共同构成数据端点路由：
 * GET /v1/data/{providerCode}/{sourceCode}。
 * {@code title} 为人类可读名称，随 manifest 下发，供启动器首页数据卡等场景展示。
 */
public record YmclDataSourceDescriptor(
        String sourceCode,
        String title,
        int schemaVersion,
        int cacheTtl) {

	public YmclDataSourceDescriptor(String sourceCode, int schemaVersion, int cacheTtl) {
		this(sourceCode, null, schemaVersion, cacheTtl);
	}
}
