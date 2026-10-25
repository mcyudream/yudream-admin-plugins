package online.yudream.base.plugin.authlib.application.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthlibServerNameTest {

    @Test
    void overrideWinsOverSiteSettings() {
        assertEquals("我的皮肤站", AuthlibAppService.resolveServerName(
                Optional.of("我的皮肤站"), Optional.of("站点描述"), Optional.of("站点名称")));
    }

    @Test
    void blankOverrideFallsBackToSiteDescription() {
        assertEquals("站点描述", AuthlibAppService.resolveServerName(
                Optional.empty(), Optional.of("站点描述"), Optional.of("站点名称")));
    }

    @Test
    void missingDescriptionFallsBackToSiteName() {
        assertEquals("站点名称", AuthlibAppService.resolveServerName(
                Optional.empty(), Optional.empty(), Optional.of("站点名称")));
    }

    @Test
    void blankValuesAreSkippedInChain() {
        assertEquals("站点名称", AuthlibAppService.resolveServerName(
                Optional.of("   "), Optional.of(""), Optional.of("站点名称")));
    }

    @Test
    void allMissingFallsBackToNeutralName() {
        assertEquals("Minecraft", AuthlibAppService.resolveServerName(
                Optional.empty(), Optional.empty(), Optional.empty()));
    }
}
