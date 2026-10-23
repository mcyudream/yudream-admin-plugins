package online.yudream.base.plugin.interop.domain.aggregate;

/**
 * ymcl-adapter 聚合目录条目（导入前的中间形态，非 wire）。
 */
public record AdapterCatalogEntry(
        String packId,
        String packName,
        String version,
        String gameVersion,
        String loaderType,
        String loaderVersion,
        String sha512,
        Long sizeBytes,
        String mrpackPath,
        String serverId,
        String serverName,
        String serverAddress
) {
}
