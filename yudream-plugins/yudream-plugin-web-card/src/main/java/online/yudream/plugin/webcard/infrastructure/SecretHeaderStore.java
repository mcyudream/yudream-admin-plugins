package online.yudream.plugin.webcard.infrastructure;

import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import online.yudream.plugin.webcard.interfaces.JsonSupport;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SecretHeaderStore {
    private final PluginSecretStore secrets;
    public SecretHeaderStore(PluginSecretStore secrets) { this.secrets = secrets; }
    public String save(String currentRef, Map<String, String> headers) {
        String ref = currentRef == null || currentRef.isBlank() ? "headers:" + java.util.UUID.randomUUID() : currentRef;
        Map<String, String> safe = new LinkedHashMap<>();
        Map<String, String> existing = read(ref);
        if (headers != null) headers.forEach((name, value) -> {
            String normalized = validateName(name);
            if (value != null && !value.isBlank()) safe.put(normalized, value.trim());
            else if (existing.containsKey(normalized)) safe.put(normalized, existing.get(normalized));
        });
        if (safe.isEmpty()) secrets.delete(ref); else secrets.put(ref, JsonSupport.bytes(safe));
        return ref;
    }
    public Map<String, String> read(String ref) {
        if (ref == null || ref.isBlank()) return Map.of();
        return secrets.get(ref).map(JsonSupport::stringMap).map(Map::copyOf).orElse(Map.of());
    }
    public void delete(String ref) { if (ref != null && !ref.isBlank()) secrets.delete(ref); }
    public static Map<String, String> masked(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers != null) headers.keySet().forEach(name -> result.put(name, "********"));
        return result;
    }
    private static String validateName(String name) {
        if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) throw new IllegalArgumentException("Header 名称无效");
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (java.util.Set.of("host", "content-length", "connection", "transfer-encoding", "upgrade", "proxy-authorization").contains(lower)) throw new IllegalArgumentException("不允许配置该 Header");
        return name;
    }
}
