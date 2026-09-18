package online.yudream.base.plugin.questionbank.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.questionbank.domain.CommandWindow;
import online.yudream.base.plugin.questionbank.infrastructure.FakeDocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 群指令开放时间的读写与判定；开关/空时间段都不限制，坏数据只丢那一项。 */
class SettingsServiceCommandWindowTest {
    private static final LocalTime IN_WINDOW = LocalTime.of(20, 0);
    private static final LocalTime OUTSIDE_WINDOW = LocalTime.of(9, 0);

    private SettingsService settings;

    @BeforeEach
    void setUp() {
        settings = new SettingsService(new FakeDocumentStore());
    }

    @Test
    void defaultIsUnrestricted() {
        assertFalse(settings.qqCommandWindowEnabled());
        assertEquals(List.of(), settings.qqCommandWindows());
        assertTrue(settings.qqCommandWindowOpen(OUTSIDE_WINDOW));
    }

    @Test
    void enablingWithoutWindowsStaysUnrestricted() {
        settings.update(null, null, null, null, null, null, null, true, List.of());
        assertTrue(settings.qqCommandWindowEnabled());
        assertEquals(List.of(), settings.qqCommandWindows());
        assertTrue(settings.qqCommandWindowOpen(OUTSIDE_WINDOW));
    }

    @Test
    void openOnlyInsideTheConfiguredWindows() {
        settings.update(null, null, null, null, null, null, null, true,
                List.of(Map.of("start", "19:00", "end", "21:00")));
        assertEquals(1, settings.qqCommandWindows().size());
        assertTrue(settings.qqCommandWindowOpen(IN_WINDOW));
        assertFalse(settings.qqCommandWindowOpen(OUTSIDE_WINDOW));
    }

    @Test
    void switchingTheRestrictionOffIgnoresTheWindows() {
        settings.update(null, null, null, null, null, null, null, true,
                List.of(Map.of("start", "19:00", "end", "21:00")));
        settings.update(null, null, null, null, null, null, null, false, null);
        assertFalse(settings.qqCommandWindowEnabled());
        assertTrue(settings.qqCommandWindowOpen(OUTSIDE_WINDOW));
    }

    @Test
    void invalidRowsAreDroppedOnSaveAndRead() {
        settings.update(null, null, null, null, null, null, null, true, List.of(
                Map.of("start", "19:00", "end", "21:00"),
                Map.of("start", "08:00"),
                Map.of("start", "21:00", "end", "21:00"),
                Map.of("start", "23:00", "end", "25:00")));
        assertEquals(List.of("19:00-21:00"),
                settings.qqCommandWindows().stream().map(CommandWindow::text).toList());
    }

    @Test
    void viewExposesTheFlagAndNormalizedWindows() {
        settings.update(null, null, null, null, null, null, null, true,
                List.of(Map.of("start", "7:30", "end", "8:00")));
        Map<String, Object> view = settings.settingsView();
        assertEquals(true, view.get("qqCommandWindowEnabled"));
        assertEquals(List.of(Map.of("start", "07:30", "end", "08:00")), view.get("qqCommandWindows"));
    }

    @Test
    void nullFieldsKeepTheExistingWindows() {
        settings.update(null, null, null, null, null, null, null, true,
                List.of(Map.of("start", "19:00", "end", "21:00")));
        settings.update(false, null, null, null, null, null, null, null, null);
        assertEquals(1, settings.qqCommandWindows().size());
        assertFalse(settings.practiceEnabled());
    }
}
