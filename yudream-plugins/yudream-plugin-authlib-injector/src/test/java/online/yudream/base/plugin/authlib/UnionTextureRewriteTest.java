package online.yudream.base.plugin.authlib;

import online.yudream.base.plugin.authlib.infrastructure.launcher.UnionTextureRewrite;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnionTextureRewriteTest {

    private static final String LOCAL_BASE = "https://www.swustmc.cn/api/plugins/authlib-injector/texture/union";
    private static final String PEER_BASE = "https://hall.mc.taru.xj.cn/api/plugins/yudream-skin/textures";

    private static String textureValue(String skinUrl) {
        String payload = "{\"timestamp\":1,\"profileId\":\"abc\",\"profileName\":\"Godot\",\"isPublic\":true,"
                + "\"textures\":{\"SKIN\":{\"url\":\"" + skinUrl + "\"}}}";
        return Base64.getEncoder().encodeToString(payload.getBytes());
    }

    @Test
    void rewritesTrustedHostUrlsToLocalProxy() {
        String value = textureValue(PEER_BASE + "/caa06e210f006d1088308b332ae13");

        String rewritten = UnionTextureRewrite.rewrite(value, List.of("hall.mc.taru.xj.cn"), LOCAL_BASE);

        String decoded = new String(Base64.getDecoder().decode(rewritten));
        assertTrue(decoded.contains(LOCAL_BASE + "/caa06e210f006d1088308b332ae13"));
        assertFalse(decoded.contains(PEER_BASE));
    }

    @Test
    void keepsOriginalWhenHostNotTrusted() {
        String value = textureValue(PEER_BASE + "/hash123");

        assertNull(UnionTextureRewrite.rewrite(value, List.of("other.host"), LOCAL_BASE));
    }

    @Test
    void returnsNullForMissingInputs() {
        assertNull(UnionTextureRewrite.rewrite(null, List.of("h"), LOCAL_BASE));
        assertNull(UnionTextureRewrite.rewrite("", List.of("h"), LOCAL_BASE));
        assertNull(UnionTextureRewrite.rewrite("not-base64!!", List.of("h"), LOCAL_BASE));
    }

    @Test
    void rewritesCapeAlongsideSkin() {
        String payload = "{\"timestamp\":1,\"profileId\":\"abc\",\"profileName\":\"Godot\",\"isPublic\":true,"
                + "\"textures\":{"
                + "\"SKIN\":{\"url\":\"" + PEER_BASE + "/skin-hash\"},"
                + "\"CAPE\":{\"url\":\"" + PEER_BASE + "/cape-hash\"}}}";
        String value = Base64.getEncoder().encodeToString(payload.getBytes());

        String rewritten = UnionTextureRewrite.rewrite(value, List.of("hall.mc.taru.xj.cn"), LOCAL_BASE);

        String decoded = new String(Base64.getDecoder().decode(rewritten));
        assertTrue(decoded.contains(LOCAL_BASE + "/skin-hash"));
        assertTrue(decoded.contains(LOCAL_BASE + "/cape-hash"));
    }
}
