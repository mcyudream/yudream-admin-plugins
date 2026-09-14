package online.yudream.base.plugin.launcher.application.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackDownloadHostTest {

    @Test
    void allowsDefaultHostsAndSubdomains() {
        assertTrue(PackAppService.isAllowedDownloadHost("cdn.modrinth.com"));
        assertTrue(PackAppService.isAllowedDownloadHost("assets.cdn.modrinth.com"));
        assertTrue(PackAppService.isAllowedDownloadHost("raw.githubusercontent.com"));
        assertFalse(PackAppService.isAllowedDownloadHost("cdn-raw.modrinth.com"));
        assertFalse(PackAppService.isAllowedDownloadHost("evil.example"));
        assertFalse(PackAppService.isAllowedDownloadHost(""));
    }

    @Test
    void rejectsHttpAndUnknownHosts() {
        PackAppService service = new PackAppService(null, null);
        assertDoesNotThrow(() -> service.validateDownloads(List.of("cdn.modrinth.com")));
        assertDoesNotThrow(() -> service.validateDownloads(List.of("https://cdn.modrinth.com/path")));
        assertThrows(IllegalArgumentException.class, () -> service.validateDownloads(List.of("http://cdn.modrinth.com/x")));
        assertThrows(IllegalArgumentException.class, () -> service.validateDownloads(List.of("evil.example")));
    }
}
