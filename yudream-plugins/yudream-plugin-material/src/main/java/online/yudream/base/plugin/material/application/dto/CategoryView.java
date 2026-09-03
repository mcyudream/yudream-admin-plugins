package online.yudream.base.plugin.material.application.dto;

/** 分类视图，含物料引用计数。 */
public record CategoryView(String id, String name, int sort, long materials, long createdAt) {
}
