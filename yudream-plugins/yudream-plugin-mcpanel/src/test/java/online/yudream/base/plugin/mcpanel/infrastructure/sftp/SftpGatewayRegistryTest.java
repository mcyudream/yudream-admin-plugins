package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 别名注册表：签发唯一性、双格式认证、过期拒绝、按实例撤销。 */
class SftpGatewayRegistryTest {

    private static long now = 1_000_000L;

    private SftpGatewayRegistry newRegistry() {
        return new SftpGatewayRegistry(() -> now);
    }

    private SftpGatewayRegistry.Minted mint(SftpGatewayRegistry registry, String instanceId) {
        return registry.mint(instanceId, "实例-" + instanceId, "node-1", "节点A",
                "10.0.0.8", 2200, "ftp-user", "ftp-pass", now + 600_000L);
    }

    @Test
    void mintProducesUniqueAliasAndAuthenticatesWithPrefixedUsername() {
        SftpGatewayRegistry registry = newRegistry();
        SftpGatewayRegistry.Minted first = mint(registry, "inst-1");
        SftpGatewayRegistry.Minted second = mint(registry, "inst-2");

        assertNotEquals(first.alias(), second.alias());
        assertEquals(SftpGatewayRegistry.USERNAME_PREFIX + first.alias(), first.username());
        assertEquals(6, first.alias().length());
        assertTrue(first.alias().matches("[abcdefghjkmnpqrstuvwxyz23456789]+"));

        Optional<SftpGatewayEntry> entry = registry.authenticate(first.username(), first.password());
        assertTrue(entry.isPresent());
        assertEquals("inst-1", entry.get().instanceId());
        assertEquals("10.0.0.8", entry.get().dialHost());
        assertEquals(2200, entry.get().dialPort());
        // 节点侧凭据由网关代持，供回源拨号，而不是透传给客户端的那份。
        assertEquals("ftp-user", entry.get().nodeUser());
        assertEquals("ftp-pass", entry.get().nodePassword());
    }

    @Test
    void authenticateAcceptsRawInstanceIdWithoutPrefix() {
        SftpGatewayRegistry registry = newRegistry();
        SftpGatewayRegistry.Minted minted = mint(registry, "inst-1");

        Optional<SftpGatewayEntry> entry = registry.authenticate("inst-1", minted.password());
        assertTrue(entry.isPresent());
    }

    @Test
    void authenticateRejectsWrongPasswordUnknownAliasAndBlankInput() {
        SftpGatewayRegistry registry = newRegistry();
        SftpGatewayRegistry.Minted minted = mint(registry, "inst-1");

        assertTrue(registry.authenticate(minted.username(), "wrong-password").isEmpty());
        assertTrue(registry.authenticate("mc-zzzzzz", minted.password()).isEmpty());
        assertTrue(registry.authenticate("", minted.password()).isEmpty());
        assertTrue(registry.authenticate(minted.username(), "").isEmpty());
        assertTrue(registry.authenticate(null, null).isEmpty());
    }

    @Test
    void expiredEntryIsRejectedAndPurged() {
        SftpGatewayRegistry registry = newRegistry();
        SftpGatewayRegistry.Minted minted = mint(registry, "inst-1");

        now += 600_001L;
        assertTrue(registry.authenticate(minted.username(), minted.password()).isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    void dropByInstanceRevokesEveryAliasOfThatInstance() {
        SftpGatewayRegistry registry = newRegistry();
        SftpGatewayRegistry.Minted first = mint(registry, "inst-1");
        SftpGatewayRegistry.Minted second = mint(registry, "inst-1");
        SftpGatewayRegistry.Minted other = mint(registry, "inst-2");

        assertEquals(2, registry.dropByInstance("inst-1"));
        assertTrue(registry.authenticate(first.username(), first.password()).isEmpty());
        assertTrue(registry.authenticate(second.username(), second.password()).isEmpty());
        assertTrue(registry.authenticate(other.username(), other.password()).isPresent());
    }

    @Test
    void closeAllClearsEverything() {
        SftpGatewayRegistry registry = newRegistry();
        mint(registry, "inst-1");
        mint(registry, "inst-2");

        assertEquals(2, registry.closeAll());
        assertEquals(0, registry.size());
    }
}
