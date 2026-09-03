package online.yudream.base.plugin.material.application.dto;

/** 标签云条目：标签名 + 当前用户未归档物料中的使用次数。 */
public record TagView(String name, long count) {
}
