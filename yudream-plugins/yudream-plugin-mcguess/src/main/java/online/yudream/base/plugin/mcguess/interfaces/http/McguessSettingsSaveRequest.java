package online.yudream.base.plugin.mcguess.interfaces.http;

/** 保存插件设置请求体；gameVersion 为空串/null 表示清除钉住、跟随 mc-wiki 默认发布版本。 */
public record McguessSettingsSaveRequest(String gameVersion) {
}
