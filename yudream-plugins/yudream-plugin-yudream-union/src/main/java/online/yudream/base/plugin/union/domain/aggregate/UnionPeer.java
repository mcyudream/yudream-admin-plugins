package online.yudream.base.plugin.union.domain.aggregate;

/**
 * 受信站点目录条目（纯点对点信任；TOFU 固定 TLS 指纹与联合签名公钥）。
 *
 * @param id                对端 siteCode（文档主键）
 * @param siteName          对端展示名
 * @param origin            对端 https origin
 * @param apiRoot           对端联合发现文档地址（协议 §3.1）
 * @param textureBaseUrl    对端 texture 基地址（合并进本站 skinDomains）
 * @param publicKeyPem      对端联合签名主公钥（PEM）
 * @param backupPublicKeyPem 对端备公钥（轮换宽限期验签用，可为空）
 * @param tlsCertSha256     TOFU 固定的对端 TLS 叶子证书指纹
 * @param peerSecretSha256  注册时下发给对端的推送密钥摘要（对端回推封禁时校验）
 * @param status            trusted | blocked
 * @param addedBy           操作人
 * @param addedAt           注册时间（epoch 毫秒）
 * @param note              备注
 * @param namePrefix        对端联合名前缀（协议 §3.1 可选；空 = 对端未启用名字空间）
 * @param banCursor         已从该站拉取的封禁账本游标
 * @param authlibPublicKeyPem 对端 authlib 元数据的材质签名公钥（PEM，联合公钥信任集发布用；
 *                            0.5.0 起注册/刷新时经同一 TOFU 通道抓取，旧条目为空直到刷新）
 */
public record UnionPeer(
        String id,
        String siteName,
        String origin,
        String apiRoot,
        String textureBaseUrl,
        String publicKeyPem,
        String backupPublicKeyPem,
        String tlsCertSha256,
        String peerSecretSha256,
        String status,
        String addedBy,
        long addedAt,
        String note,
        String namePrefix,
        long banCursor,
        String authlibPublicKeyPem
) {

    public static final String STATUS_TRUSTED = "trusted";
    public static final String STATUS_BLOCKED = "blocked";

    public boolean trusted() {
        return STATUS_TRUSTED.equals(status);
    }
}
