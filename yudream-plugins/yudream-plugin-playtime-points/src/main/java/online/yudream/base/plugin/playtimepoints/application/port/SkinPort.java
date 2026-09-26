package online.yudream.base.plugin.playtimepoints.application.port;

import java.util.Optional;

/** 皮肤站插件端口：把 MC 玩家（uuid/名字）映射为宿主网站用户 ID。实现集中在 infrastructure。 */
public interface SkinPort {

    /** 依次按 uuid（原样/补横线）与玩家名查找皮肤站角色归属；未绑定或皮肤站不可用时为空。 */
    Optional<String> resolveOwnerId(String playerId, String playerName);
}
