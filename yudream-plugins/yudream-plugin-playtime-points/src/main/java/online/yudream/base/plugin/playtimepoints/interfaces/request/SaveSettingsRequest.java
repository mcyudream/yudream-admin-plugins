package online.yudream.base.plugin.playtimepoints.interfaces.request;

import java.util.Map;

/** PUT /admin/settings 请求体。servers 键为服务器 ID；weight 用 Double 接收 JSON 数字，保存前转十进制字符串。 */
public record SaveSettingsRequest(
        Boolean enabled,
        String assetCode,
        Long minutesPerPoint,
        Boolean subtractAfk,
        Map<String, ServerRuleRequest> servers) {

    public record ServerRuleRequest(Double weight, Boolean enabled) {
    }
}
