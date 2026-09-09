package online.yudream.base.plugin.minecraft.infrastructure.repository;

import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerMap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftServerMapDocumentTest {

    @Test
    void readsLegacyZipWithoutExternalUrl() throws Exception {
        MinecraftServerMap map = invokeToMap(Map.of(
                "fileId", "file-1",
                "objectKey", "servers/1/map.zip",
                "originalName", "file-1.zip",
                "publicAccess", true
        ));
        assertEquals("file-1", map.fileId());
        assertEquals("servers/1/map.zip", map.objectKey());
        assertFalse(map.external());
        assertTrue(map.publicAccess());
    }

    @Test
    void readsExternalLinkWithoutFileId() throws Exception {
        MinecraftServerMap map = invokeToMap(Map.of(
                "originalName", "S1 存档",
                "publicAccess", false,
                "externalUrl", "https://pan.example/s/abc"
        ));
        assertTrue(map.external());
        assertEquals("https://pan.example/s/abc", map.externalUrl());
        assertEquals("", map.fileId());
        assertEquals("", map.objectKey());
    }

    @Test
    void ignoresEmptyMapDocument() throws Exception {
        assertNull(invokeToMap(Map.of("fileId", "", "objectKey", "", "externalUrl", "")));
    }

    private MinecraftServerMap invokeToMap(Map<String, Object> document) throws Exception {
        Method method = MinecraftServerDocumentRepository.class.getDeclaredMethod("toMap", Object.class);
        method.setAccessible(true);
        return (MinecraftServerMap) method.invoke(new MinecraftServerDocumentRepository(null), document);
    }
}
