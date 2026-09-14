package online.yudream.base.plugin.launcher.domain.aggregate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LauncherPackTest {

    @Test
    void withRecommendedVersionKeepsIdentityFields() {
        LauncherPack pack = LauncherPack.builder()
                .id("demo")
                .name("演示整合包")
                .description("说明")
                .icon("i-ri:box-3-line")
                .retainedVersionIds(List.of("1.0.0"))
                .createdAt(1000L)
                .updatedAt(1000L)
                .build();

        LauncherPack next = pack.withRecommendedVersion("1.1.0");

        assertEquals("demo", next.id());
        assertEquals("演示整合包", next.name());
        assertEquals("说明", next.description());
        assertEquals("i-ri:box-3-line", next.icon());
        assertEquals("1.1.0", next.recommendedVersionId());
        assertEquals(List.of("1.0.0"), next.retainedVersionIds());
        assertEquals(1000L, next.createdAt());
    }

    @Test
    void retainVersionCopiesExistingMetadata() {
        LauncherPack pack = LauncherPack.builder()
                .id("demo")
                .name("演示整合包")
                .recommendedVersionId("1.0.0")
                .retainedVersionIds(List.of("1.0.0"))
                .createdAt(1000L)
                .updatedAt(1000L)
                .build();

        LauncherPack next = pack.retainVersion("1.1.0", 10);

        assertEquals("demo", next.id());
        assertEquals("演示整合包", next.name());
        assertEquals("1.0.0", next.recommendedVersionId());
        assertEquals(List.of("1.0.0", "1.1.0"), next.retainedVersionIds());
    }

    @Test
    void blankIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> LauncherPack.builder().id(" ").name("x").build());
    }
}
