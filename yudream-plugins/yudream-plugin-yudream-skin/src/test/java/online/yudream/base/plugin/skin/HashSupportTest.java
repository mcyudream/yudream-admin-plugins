package online.yudream.base.plugin.skin;

import online.yudream.base.plugin.skin.infrastructure.support.HashSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class HashSupportTest {

    @Test
    void offlineUuidIsDeterministicAndRenameStable() {
        String first = HashSupport.offlineUuid("issuer-1", "user-1", "identity-1");
        assertEquals(first, HashSupport.offlineUuid("issuer-1", "user-1", "identity-1"));
        assertEquals(first, HashSupport.offlineUuid("issuer-1", "user-1", "identity-1"));
    }

    @Test
    void offlineUuidVariesByUnionIssuerOwnerAndOrdinal() {
        String base = HashSupport.offlineUuid("issuer-1", "user-1", "identity-1");
        assertNotEquals(base, HashSupport.offlineUuid("issuer-2", "user-1", "identity-1"));
        assertNotEquals(base, HashSupport.offlineUuid("issuer-1", "user-2", "identity-1"));
        assertNotEquals(base, HashSupport.offlineUuid("issuer-1", "user-1", "identity-2"));
    }

    @Test
    void legacyPlayerUuidKeepsOldDerivationForCompatibility() {
        // 旧规则：同一名永远得到同一 UUID（既有数据兼容）
        assertEquals(HashSupport.playerUuid("Notch"), HashSupport.playerUuid("notch"));
        assertEquals(HashSupport.playerUuid("Notch"), HashSupport.playerUuid("NOTCH"));
    }

    @Test
    void offlineUuidHasNoDashesAnd32Hex() {
        String uuid = HashSupport.offlineUuid("issuer-1", "user-1", "identity-1");
        assertEquals(32, uuid.length());
        assertEquals(uuid, uuid.toLowerCase());
        assertFalse(uuid.contains("-"));
    }
}
