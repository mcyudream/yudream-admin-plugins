package online.yudream.base.plugin.union.support;

import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.union.infrastructure.PeerHttpClient;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 测试支撑：内存文档库 + 最小 PluginContext/FrameworkServices 桩（JDK Proxy）。 */
public final class UnionTestSupport {

    private UnionTestSupport() {
    }

    /** 桩配置：按需提供扩展实现、密钥库与宿主 setting。 */
    public interface TestConfig {
        default List<Object> extensionsFor(Class<?> extensionPoint) {
            return List.of();
        }

        default PluginSecretStore secretStore() {
            return new InMemorySecretStore();
        }

        default String setting(String key) {
            return null;
        }
    }

    public static PluginContext context(TestConfig config, PluginDocumentStore documentStore) {
        Object proxy = Proxy.newProxyInstance(UnionTestSupport.class.getClassLoader(),
                new Class<?>[]{PluginContext.class}, (unused, method, args) -> {
                    String name = method.getName();
                    return switch (name) {
                        case "extensions" -> config.extensionsFor((Class<?>) args[0]);
                        case "framework" -> framework(config, documentStore);
                        case "secrets" -> config.secretStore();
                        case "pluginCode" -> "yudream-union";
                        default -> throw new UnsupportedOperationException("测试桩未实现：" + name);
                    };
                });
        return (PluginContext) proxy;
    }

    private static FrameworkServices framework(TestConfig config, PluginDocumentStore documentStore) {
        Object proxy = Proxy.newProxyInstance(UnionTestSupport.class.getClassLoader(),
                new Class<?>[]{FrameworkServices.class}, (unused, method, args) -> {
                    String name = method.getName();
                    return switch (name) {
                        case "setting" -> Optional.ofNullable(config.setting((String) args[0]));
                        case "documents" -> documentStore;
                        default -> throw new UnsupportedOperationException("测试桩未实现：" + name);
                    };
                });
        return (FrameworkServices) proxy;
    }

    /** 内存 SecretStore：测试中验证联合私钥不回落普通文档库。 */
    public static final class InMemorySecretStore implements PluginSecretStore {
        private final Map<String, byte[]> values = new ConcurrentHashMap<>();

        @Override
        public void put(String key, byte[] secret) {
            values.put(key, secret == null ? new byte[0] : secret.clone());
        }

        @Override
        public Optional<byte[]> get(String key) {
            return Optional.ofNullable(values.get(key)).map(byte[]::clone);
        }

        @Override
        public boolean delete(String key) {
            return values.remove(key) != null;
        }
    }

    /** 固定响应的出站客户端桩（url → body，可注入响应变换模拟篡改）。 */
    public static final class FakePeerHttpClient extends PeerHttpClient {
        public final Map<String, String> responses = new ConcurrentHashMap<>();
        /** POST 预置响应：值可带 "NNN:" 前缀指定状态码（如 "401:{...}"），缺省 200。 */
        public final Map<String, String> postResponses = new ConcurrentHashMap<>();
        public final Map<String, String> postedBodies = new ConcurrentHashMap<>();
        public java.util.function.UnaryOperator<String> mutate = body -> body;

        public FakePeerHttpClient() {
            super();
        }

        @Override
        public PeerResponse get(String url, Optional<String> pinnedCertSha256) {
            String body = responses.get(url);
            if (body == null) {
                throw new PeerHttpClient.PeerHttpException("无预置响应：" + url);
            }
            return new PeerResponse(mutate.apply(body), "f".repeat(64));
        }

        @Override
        public PeerJsonResponse post(String url, Object jsonBody, Optional<String> pinnedCertSha256) {
            String body = postResponses.get(url);
            if (body == null) {
                throw new PeerHttpClient.PeerHttpException("无预置响应：" + url);
            }
            postedBodies.put(url, String.valueOf(jsonBody));
            int status = 200;
            if (body.length() > 4 && body.charAt(3) == ':') {
                status = Integer.parseInt(body.substring(0, 3));
                body = body.substring(4);
            }
            return new PeerJsonResponse(status, mutate.apply(body), "f".repeat(64));
        }
    }

    public static final class InMemoryDocumentStore implements PluginDocumentStore {

        public final Map<String, Map<String, Map<String, Object>>> collections = new ConcurrentHashMap<>();

        private Map<String, Map<String, Object>> collection(String name) {
            return collections.computeIfAbsent(name, key -> new LinkedHashMap<>());
        }

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            Map<String, Object> copy = new LinkedHashMap<>(document);
            copy.put("id", id);
            collection(collection).put(id, copy);
            return copy;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            Map<String, Object> document = collection(collection).get(id);
            return document == null ? Optional.empty() : Optional.of(new LinkedHashMap<>(document));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            return page(new ArrayList<>(collection(collection).values()), page, size);
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Map<String, Object> document : collection(collection).values()) {
                if (value.equals(document.get(field))) {
                    matches.add(document);
                }
            }
            return page(matches, page, size);
        }

        @Override
        public long count(String collection) {
            return collection(collection).size();
        }

        @Override
        public boolean updateIfFieldAtMost(String collection, String id, String field, long maximum,
                                           Map<String, Object> document) {
            Map<String, Object> existing = collection(collection).get(id);
            if (existing == null || !(existing.get(field) instanceof Number number) || number.longValue() > maximum) {
                return false;
            }
            existing.putAll(document);
            return true;
        }

        @Override
        public void delete(String collection, String id) {
            collection(collection).remove(id);
        }

        private List<Map<String, Object>> page(List<Map<String, Object>> records, int page, int size) {
            int from = Math.min((page - 1) * size, records.size());
            int to = Math.min(from + size, records.size());
            return new ArrayList<>(records.subList(from, to));
        }
    }
}
