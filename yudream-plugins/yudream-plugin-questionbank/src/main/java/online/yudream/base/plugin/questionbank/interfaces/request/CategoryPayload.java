package online.yudream.base.plugin.questionbank.interfaces.request;

/** 分类创建/更新请求体。 */
public record CategoryPayload(String name, Integer sort) {
}
