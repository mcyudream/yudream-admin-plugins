package online.yudream.plugin.voxelithmap.application.dto;

import java.util.List;

/**
 * 模组包清单（管理页「解析包内 jar」用）。
 *
 * @param fileId      模组包文件 id
 * @param name        展示名（优先平台文件原名，其次调用方给的名称）
 * @param jarCount    包内 jar 总数
 * @param totalBytes  包内 jar 的字节总和（解压后体量，展示用）
 * @param jars        包内 jar 明细（按名排序，超出上限时只返回前若干条）
 * @param truncated   明细是否被截断（jarCount 仍为全量）
 */
public record ModPackListingDTO(String fileId, String name, int jarCount, long totalBytes,
                                List<JarEntryDTO> jars, boolean truncated) {

    /** 单个 jar 明细。 */
    public record JarEntryDTO(String name, long sizeBytes) {
    }
}
