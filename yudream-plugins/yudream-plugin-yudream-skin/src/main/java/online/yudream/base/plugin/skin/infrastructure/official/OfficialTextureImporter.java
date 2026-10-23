package online.yudream.base.plugin.skin.infrastructure.official;

import online.yudream.base.plugin.skin.domain.aggregate.SkinTexture;
import online.yudream.base.plugin.skin.infrastructure.repository.YuDreamSkinRepository;
import online.yudream.base.plugin.skin.infrastructure.support.HashSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * 正版皮肤导入器：只接受 Mojang 官方 {@code textures.minecraft.net} 的 HTTPS PNG，
 * 下载后以 SHA-256 内容寻址进入本站皮肤库。Authlib 输出仍指向本站公开纹理端点，
 * 所以 textures 属性只需由本站签名，不依赖 Mojang 签名/跨域白名单。
 */
public class OfficialTextureImporter {

    private static final String OFFICIAL_TEXTURE_HOST = "textures.minecraft.net";
    private static final long MAX_TEXTURE_BYTES = 1_048_576L;

    private final YuDreamSkinRepository repository;
    private final HttpClient client;

    public OfficialTextureImporter(YuDreamSkinRepository repository) {
        this(repository, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    OfficialTextureImporter(YuDreamSkinRepository repository, HttpClient client) {
        this.repository = repository;
        this.client = client;
    }

    /** 导入失败只返回空，正版身份绑定/续签仍可继续；下次续签会重试。 */
    public Optional<String> importOfficialSkin(String skinUrl, String model, String ownerId, String playerName) {
        if (!isOfficialTextureUrl(skinUrl)) {
            return Optional.empty();
        }
        try {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(skinUrl))
                            .timeout(Duration.ofSeconds(10))
                            .header("Accept", "image/png")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_TEXTURE_BYTES || !isPng(bytes)) {
                return Optional.empty();
            }
            String hash = HashSupport.sha256(bytes);
            if (repository.findTextureByHash(hash).isEmpty()) {
                String objectKey = repository.saveTextureFile(hash, bytes, "image/png");
                repository.saveTexture(new SkinTexture(
                        hash,
                        "正版皮肤：" + playerName,
                        "skin",
                        "slim".equalsIgnoreCase(model) ? "slim" : "default",
                        "image/png",
                        (long) bytes.length,
                        ownerId,
                        true,
                        objectKey,
                        null,
                        System.currentTimeMillis()
                ));
            }
            return Optional.of(hash);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static boolean isOfficialTextureUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && OFFICIAL_TEXTURE_HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && bytes[0] == (byte) 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a;
    }
}
