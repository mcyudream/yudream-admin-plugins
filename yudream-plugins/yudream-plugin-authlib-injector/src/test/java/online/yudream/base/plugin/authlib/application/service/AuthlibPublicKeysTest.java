package online.yudream.base.plugin.authlib.application.service;

import online.yudream.base.plugin.authlib.api.PluginFederationGateway;
import online.yudream.base.plugin.authlib.infrastructure.repository.AuthlibRepository;
import online.yudream.base.plugin.authlib.infrastructure.service.AuthlibCryptoService;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthlibPublicKeysTest {

    /** 只服务 settings/keypair 读写的内存文档存储。 */
    private static final class InMemoryDocuments implements PluginDocumentStore {
        private final Map<String, Map<String, Object>> store = new LinkedHashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> saved = new LinkedHashMap<>(document);
            saved.put("id", id);
            store.put(collection + "/" + id, saved);
            return saved;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(store.get(collection + "/" + id));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            return List.of();
        }

        @Override
        public long count(String collection) {
            return store.size();
        }

        @Override
        public void delete(String collection, String id) {
            store.remove(collection + "/" + id);
        }
    }

    @Test
    void publishesSiteKeyInBothPurposesWithoutUnion() {
        AuthlibCryptoService crypto = new AuthlibCryptoService(new AuthlibRepository(new InMemoryDocuments()));
        AuthlibAppService service = new AuthlibAppService(null, null, crypto, null);

        Map<?, ?> body = (Map<?, ?>) service.publicKeys();
        assertEquals(Set.of("profilePropertyKeys", "playerCertificateKeys"), body.keySet());
        List<?> propertyKeys = (List<?>) body.get("profilePropertyKeys");
        List<?> certificateKeys = (List<?>) body.get("playerCertificateKeys");
        assertEquals(1, propertyKeys.size());
        assertEquals(propertyKeys, certificateKeys);
        assertEquals(crypto.keyPair().publicKey(), ((Map<?, ?>) propertyKeys.get(0)).get("publicKey"));
    }

    @Test
    void mergesPeerPinnedKeysDedupesAndSkipsUnparseable() throws Exception {
        AuthlibCryptoService crypto = new AuthlibCryptoService(new AuthlibRepository(new InMemoryDocuments()));
        String peerPem = pemOf(generateRsa(2048));
        String undersizedPem = pemOf(generateRsa(1024));
        PluginFederationGateway gateway = new PluginFederationGateway() {
            @Override
            public Optional<RemoteJoinProfile> verifyRemoteJoin(String username, String serverId) {
                return Optional.empty();
            }

            @Override
            public List<String> trustedTextureHosts() {
                return List.of();
            }

            @Override
            public List<String> trustedPeerSigningKeysPem() {
                return List.of(peerPem, "not-a-pem", undersizedPem, peerPem);
            }
        };
        AuthlibAppService service = new AuthlibAppService(null, null, crypto, gateway);

        List<?> propertyKeys = (List<?>) ((Map<?, ?>) service.publicKeys()).get("profilePropertyKeys");
        assertEquals(2, propertyKeys.size());
        assertEquals(crypto.keyPair().publicKey(), ((Map<?, ?>) propertyKeys.get(0)).get("publicKey"));
        assertEquals(crypto.publicKeyDerBase64(peerPem), ((Map<?, ?>) propertyKeys.get(1)).get("publicKey"));
    }

    @Test
    void derBase64AcceptsRsaWithinClientBoundsOnly() throws Exception {
        AuthlibCryptoService crypto = new AuthlibCryptoService(new AuthlibRepository(new InMemoryDocuments()));

        KeyPair keyPair = generateRsa(2048);
        assertEquals(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                crypto.publicKeyDerBase64(pemOf(keyPair)));

        assertNull(crypto.publicKeyDerBase64(null));
        assertNull(crypto.publicKeyDerBase64(""));
        assertNull(crypto.publicKeyDerBase64("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"));
        assertNull(crypto.publicKeyDerBase64(pemOf(generateRsa(1024))));
    }

    private static KeyPair generateRsa(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String pemOf(KeyPair keyPair) {
        String base64 = Base64.getMimeEncoder(76, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }
}
