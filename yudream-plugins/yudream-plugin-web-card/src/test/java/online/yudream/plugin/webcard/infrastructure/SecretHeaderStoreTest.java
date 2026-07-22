package online.yudream.plugin.webcard.infrastructure;

import online.yudream.base.plugin.spi.system.secret.PluginSecretStore;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class SecretHeaderStoreTest {
    @Test void retainsBlankValuesReplacesValuesAndDeletesMissingNames() {
        MemorySecrets secrets=new MemorySecrets();SecretHeaderStore store=new SecretHeaderStore(secrets);
        String ref=store.save(null,Map.of("Authorization","old","Cookie","session=1"));
        store.save(ref,Map.of("Authorization","","X-API-Key","new"));
        assertEquals(Map.of("Authorization","old","X-API-Key","new"),store.read(ref));
    }
    private static final class MemorySecrets implements PluginSecretStore{private final Map<String,byte[]> values=new HashMap<>();public void put(String key,byte[] secret){values.put(key,secret.clone());}public Optional<byte[]> get(String key){return Optional.ofNullable(values.get(key)).map(byte[]::clone);}public boolean delete(String key){return values.remove(key)!=null;}}
}
