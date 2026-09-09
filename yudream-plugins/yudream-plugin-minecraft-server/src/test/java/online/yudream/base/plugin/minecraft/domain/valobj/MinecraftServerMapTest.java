package online.yudream.base.plugin.minecraft.domain.valobj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftServerMapTest {

    @Test
    void storedFileRequiresFileIdAndObjectKey() {
        MinecraftServerMap map = MinecraftServerMap.storedFile("file-1", "servers/1/map.zip", "", false);
        assertEquals("file-1", map.fileId());
        assertEquals("servers/1/map.zip", map.objectKey());
        assertEquals("file-1.zip", map.originalName());
        assertFalse(map.external());
        assertEquals("", map.externalUrl());
    }

    @Test
    void storedFileRejectsBlankFileId() {
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftServerMap.storedFile(" ", "servers/1/map.zip", "world.zip", false));
    }

    @Test
    void storedFileRejectsBlankObjectKey() {
        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftServerMap("file-1", "", "world.zip", false, ""));
    }

    @Test
    void storedFileRejectsExternalUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftServerMap("file-1", "servers/1/map.zip", "world.zip", false, "https://pan.example/s/abc"));
    }

    @Test
    void externalLinkNormalizesAndDefaultsName() {
        MinecraftServerMap map = MinecraftServerMap.externalLink(" https://pan.example/s/abc ", "", true);
        assertTrue(map.external());
        assertEquals("https://pan.example/s/abc", map.externalUrl());
        assertEquals("网盘下载", map.originalName());
        assertEquals("", map.fileId());
        assertEquals("", map.objectKey());
        assertTrue(map.publicAccess());
    }

    @Test
    void externalLinkRejectsMissingBothSources() {
        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftServerMap("", "", "world.zip", false, ""));
    }

    @Test
    void externalLinkRejectsJavascriptAndFileSchemes() {
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftServerMap.externalLink("javascript:alert(1)", "x", false));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftServerMap.externalLink("file:///etc/passwd", "x", false));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftServerMap.externalLink("data:text/plain,hi", "x", false));
    }

    @Test
    void externalLinkRejectsHostlessHttp() {
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftServerMap.externalLink("https:///no-host", "x", false));
    }

    @Test
    void withPublicAccessPreservesExternalUrl() {
        MinecraftServerMap map = MinecraftServerMap.externalLink("https://pan.example/s/abc", "S1 存档", false)
                .withPublicAccess(true);
        assertTrue(map.publicAccess());
        assertEquals("https://pan.example/s/abc", map.externalUrl());
        assertEquals("S1 存档", map.originalName());
    }
}
