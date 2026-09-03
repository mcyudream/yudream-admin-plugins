package online.yudream.base.plugin.material.application.dto;

/** 物料详情：基础信息 + 当前版本。 */
public record MaterialDetail(MaterialSummary material, VersionView currentVersionInfo) {
}
