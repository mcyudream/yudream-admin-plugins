package online.yudream.base.plugin.ymcl.api;

/**
 * YAP §6.5 数据源贡献描述。
 *
 * {@code sourceCode} 与提供方 {@code providerCode} 共同构成数据端点路由：
 * GET /v1/data/{providerCode}/{sourceCode}。
 */
public record YmclDataSourceDescriptor(
        String sourceCode,
        int schemaVersion,
        int cacheTtl) {
}
