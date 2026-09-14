package online.yudream.base.plugin.authlib.api;

import java.util.List;

/**
 * 站点系统用户对外（启动器等）的 yggdrasil 会话签发服务。
 * <p>
 * 供 launcher-adapter 等 trusted caller（已经过站点身份认证）调用，把站点身份"提升"为
 * 玩家在 ygg 服务器上的可用 session；信任根 = 调用方已持有有效 OAuth/API Key 凭证并通过
 * 宿主 SecurityPrincipalSupport 解析出 userId，authlib-injector 只负责按 userId 签发。
 *
 * <p>该服务仅暴露免密码签发能力，不复用 authenticate(password) 的鉴权路径，
 * 避免把站点密码校验流程开放给插件间调用方。
 */
public interface PluginAuthService {

    /**
     * 为指定站点用户签发一条新的 ygg 会话。
     *
     * @param userId                站点用户主键（不可为空）
     * @param clientToken           客户端令牌（为空时自动生成）
     * @param requestedProfileName  期望选中的角色名；为空则取第一可用角色
     * @return 新的会话快照
     * @throws IllegalArgumentException userId 为空 / 该用户无角色
     */
    IssuedSession issueSession(String userId, String clientToken, String requestedProfileName);

    /** 列出该用户可用的角色（用于多角色挑选 UI）。 */
    List<PluginAuthProfile> listProfiles(String userId);

    /**
     * 会话签发结果。
     *
     * @param userId            站点用户 ID
     * @param username          ygg 角色名（已选中）
     * @param profileId         ygg 角色 UUID（无连字符）
     * @param accessToken       ygg 访问令牌（Minecraft 客户端用）
     * @param clientToken       ygg 客户端令牌（启动器持久化）
     * @param availableProfiles 该用户全部可选角色（id+name）
     */
    record IssuedSession(
            String userId,
            String username,
            String profileId,
            String accessToken,
            String clientToken,
            List<PluginAuthProfile> availableProfiles
    ) {
    }
}