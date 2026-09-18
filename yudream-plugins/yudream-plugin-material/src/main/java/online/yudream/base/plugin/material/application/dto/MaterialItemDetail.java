package online.yudream.base.plugin.material.application.dto;

/** 子物料详情：基础信息 + 当前版本。 */
public record MaterialItemDetail(MaterialItemView item, MaterialItemVersionView currentVersionInfo) {
}
