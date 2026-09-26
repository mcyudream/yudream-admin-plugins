package online.yudream.base.plugin.mcpanel.acceptance;

import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内存 SecretStore（测试/验收 fixture，无 JUnit 依赖）。
 */
public class InMemorySecretStore implements PluginSecretStore {

    private final Map<String, byte[]> secrets = new HashMap<>();

    @Override
    public synchronized void put(String key, byte[] value) {
        secrets.put(key, value == null ? new byte[0] : value.clone());
    }

    @Override
    public synchronized Optional<byte[]> get(String key) {
        byte[] value = secrets.get(key);
        return Optional.ofNullable(value).map(bytes -> bytes.clone());
    }

    @Override
    public synchronized boolean delete(String key) {
        return secrets.remove(key) != null;
    }
}
