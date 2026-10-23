package online.yudream.base.plugin.interop.domain.aggregate;

/**
 * 一条对外目录条目 = 一个整合包（可省略）+ 一个服务器地址 + 版本（mc-interop v1 §4/§5）。
 * 字段即 wire 形状的 camelCase 投影来源；校验在 CatalogService（fail-closed）。
 *
 * @param id            站内稳定 ID（^[A-Za-z0-9._-]{1,128}$，站点内唯一）
 * @param name          展示名
 * @param summary       简短说明（可空）
 * @param revision      有 artifact 时必填，单调递增
 * @param gameVersion   MC 版本，必填
 * @param loader        vanilla|fabric|forge|neoforge|quilt
 * @param loaderVersion 加载器版本（vanilla 时空）
 * @param artifactFormat mrpack|multimc|curseforge-zip|server-zip|raw（无包时空）
 * @param artifactUrl   绝对 https 下载地址
 * @param artifactSize  字节数
 * @param sha1          40 位 hex 或 null
 * @param sha512        128 位 hex 或 null（两者至少一个非 null）
 * @param distSystem    yda-launcher-v2|yap-mip（可选增量分发后端）
 * @param distPackId    分发后端 packId
 * @param distVersionId 分发后端 versionId
 * @param serverAddress 服务器主地址，必填
 * @param serverPort    端口（空 = 交给 SRV/客户端默认，禁止补 25565）
 * @param published     管理员发布开关：未勾选不进入出口（协议 §3.1 强制）
 * @param updatedAt     最后修改时间（epoch 毫秒）
 */
public record CatalogEntry(
        String id,
        String name,
        String summary,
        Long revision,
        String gameVersion,
        String loader,
        String loaderVersion,
        String artifactFormat,
        String artifactUrl,
        Long artifactSize,
        String sha1,
        String sha512,
        String distSystem,
        String distPackId,
        String distVersionId,
        String serverAddress,
        Integer serverPort,
        boolean published,
        long updatedAt
) {

    public boolean hasArtifact() {
        return artifactUrl != null && !artifactUrl.isBlank();
    }
}
