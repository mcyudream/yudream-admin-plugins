package online.yudream.base.plugin.union.api;

import java.util.List;
import java.util.Optional;

/**
 * 联合认证对外服务（A 侧验签入口）。
 *
 * <p>供 authlib-injector 消费（provided 依赖 + softdepend 本插件）：其
 * {@code hasJoined} 本地未命中时调用 {@link #verifyRemoteJoin}，向受信站
 * 并发查询签名断言并完成验签/封禁检查。返回的 texture value 为**受信站原文**，
 * 调用方必须用**本站 Yggdrasil 密钥重签**后才能下发（信任根 = 本站
 * signaturePublickey），且远程角色不得携带 uploadableTextures 属性。</p>
 */
public interface UnionFederationService {

    /**
     * 联合验证一次跨站 join。
     *
     * @param username 服务器上报的角色名（原始大小写）
     * @param serverId 服务器 serverHash（与 Yggdrasil join 同值）
     * @return 唯一命中的远程角色；未命中、多站冲突、封禁或协议异常时为空
     */
    Optional<FederatedProfile> verifyRemoteJoin(String username, String serverId);

    /** 受信站 texture 主机列表（合并进本站元数据 skinDomains）。 */
    List<String> trustedTextureHosts();

    /** 规范化角色名是否命中本站或受信站的 ACTIVE 联合封禁。 */
    boolean isNameBanned(String name);

    /** 本站联合签名 issuerKeyId（公钥 SHA-256 前 16 字节 hex，离线 UUID 派生的命名空间输入）。 */
    String issuerKeyId();

    /**
     * 本站联合身份（站点身份页配置的 siteCode/站点名/对外 origin）。
     *
     * <p>作为站点身份的单一事实源供其他插件读取（如 mc-interop 出口）；
     * 管理员尚未配置 siteCode 时为空。</p>
     */
    Optional<SiteIdentity> siteIdentity();

    /**
     * 本站联合身份。
     *
     * @param code   siteCode（跨站断言受众，启用后不可变更）
     * @param name   站点展示名
     * @param origin 对外 origin（由宿主 app.web-url / app.base-url 推导）
     */
    record SiteIdentity(String code, String name, String origin) {
    }

    /**
     * 已验证的远程角色。
     *
     * @param uuid         远程角色 UUID（无连字符）
     * @param name         远程角色名
     * @param textureValue 远程站 textures 属性 value（base64 原文，URL 指向远程站）
     */
    record FederatedProfile(String uuid, String name, String textureValue) {
    }
}
