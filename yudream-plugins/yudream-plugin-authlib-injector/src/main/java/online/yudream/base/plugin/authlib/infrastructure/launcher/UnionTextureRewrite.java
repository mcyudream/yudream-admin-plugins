package online.yudream.base.plugin.authlib.infrastructure.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import online.yudream.base.plugin.authlib.infrastructure.support.JsonSupport;

import java.util.Base64;
import java.util.List;

/**
 * 联合玩家材质改写：把受信站 texture URL 改写为本站代理地址（/texture/union/{hash}）。
 *
 * <p>动机：材质签名信任根是「玩家认证站」的公钥——服务器用本站密钥重签联合玩家的材质后，
 * 来自其他认证站的客户端仍会验签失败而拒绝渲染。把 URL 收敛到本站代理后，客户端的
 * 域名白名单（元数据 skinDomains 已合并受信站主机）与材质获取都统一走本站。</p>
 */
public final class UnionTextureRewrite {

    private static final String PEER_TEXTURE_MARKER = "/api/plugins/yudream-skin/textures/";

    private UnionTextureRewrite() {
    }

    /**
     * @param textureValue     远程站材质 base64 原文（URL 指向远程站）
     * @param trustedHosts     受信站 texture 主机（来自 union 联邦网关）
     * @param localTextureBase 本站材质基址（以 /textures 结尾，与 textureBaseUrl 同口径）
     * @return 改写后的 base64；无可改写 URL 或解析失败返回 null（调用方回落远程原文）。
     */
    public static String rewrite(String textureValue, List<String> trustedHosts, String localTextureBase) {
        if (textureValue == null || textureValue.isBlank()
                || trustedHosts == null || trustedHosts.isEmpty()
                || localTextureBase == null || localTextureBase.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JsonSupport.read(new String(Base64.getDecoder().decode(textureValue), java.nio.charset.StandardCharsets.UTF_8), JsonNode.class);
            JsonNode textures = root.get("textures");
            if (textures == null || !textures.isObject()) {
                return null;
            }
            boolean rewritten = false;
            var fields = textures.fields();
            while (fields.hasNext()) {
                var texture = fields.next().getValue();
                String url = texture.path("url").asText("");
                String host = hostOf(url);
                if (host == null || !trustedHosts.contains(host)) {
                    continue;
                }
                int marker = url.indexOf(PEER_TEXTURE_MARKER);
                if (marker < 0) {
                    continue;
                }
                String hash = url.substring(marker + PEER_TEXTURE_MARKER.length());
                ((ObjectNode) texture).put("url",
                        stripTrailingSlash(localTextureBase) + "/texture/union/" + hash);
                rewritten = true;
            }
            if (!rewritten) {
                return null;
            }
            return Base64.getEncoder().encodeToString(JsonSupport.write(root).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
