package online.yudream.base.plugin.authlib.api;

import java.util.List;
import java.util.Optional;

/**
 * 联邦 join 验签网关（authlib 自有中性契约，屏蔽 yudream-union 类型）。
 *
 * <p>由 {@code UnionGatewayBridge} 实现（yudream-union 可用时注册装配）：
 * {@code hasJoined} 本地未命中时走受信站签名断言（YD 联合认证协议 v1 §3.2）。
 * 返回的 texture value 为受信站原文，调用方必须用**本站 Yggdrasil 密钥重签**后下发，
 * 且远程角色不携带 uploadableTextures 属性。</p>
 */
public interface PluginFederationGateway {

    /** 联合验证一次跨站 join；未命中/多站冲突/封禁/协议异常返回空。 */
    Optional<RemoteJoinProfile> verifyRemoteJoin(String username, String serverId);

    /** 受信站 texture 主机（合并进本站元数据 skinDomains）。 */
    List<String> trustedTextureHosts();

    /** 受信站已钉扎的材质签名公钥（PEM 列表；union 未启用、版本过旧或调用失败时为空）。 */
    default List<String> trustedPeerSigningKeysPem() {
        return List.of();
    }

    /** 已验证的远程角色（texture value 为远程站 base64 原文，URL 指向远程站）。 */
    record RemoteJoinProfile(String uuid, String name, String textureValue) {
    }
}
