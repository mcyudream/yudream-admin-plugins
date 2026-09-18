package online.yudream.base.plugin.questionbank.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 群指令开放时间段：解析容错、边界归属、跨零点与提示文本。 */
class CommandWindowTest {

    @Test
    void parsesTwoDigitAndSingleDigitHours() {
        CommandWindow window = CommandWindow.parse("9:05", "22:00");
        assertEquals(LocalTime.of(9, 5), window.start());
        assertEquals(LocalTime.of(22, 0), window.end());
        assertEquals("09:05-22:00", window.text());
    }

    @Test
    void rejectsInvalidInput() {
        assertNull(CommandWindow.parse(null, "22:00"));
        assertNull(CommandWindow.parse("9:05", ""));
        // 位数不严格：09:5 按 09:05 处理（界面始终给出 HH:mm，这里只保证不产出非法时间）
        assertEquals(LocalTime.of(9, 5), CommandWindow.parse("09:5", "22:00").start());
        assertNull(CommandWindow.parse("09:5x", "22:00"));
        assertNull(CommandWindow.parse("-1:00", "22:00"));
        assertNull(CommandWindow.parse("24:00", "22:00"));
        assertNull(CommandWindow.parse("09:60", "22:00"));
        assertNull(CommandWindow.parse("abc", "22:00"));
        // 起止相同既不是零点宽也不是整天，写出来只会让人以为开放了却什么都没开放
        assertNull(CommandWindow.parse("09:00", "09:00"));
    }

    @Test
    void containsIsHalfOpenOnBothEnds() {
        CommandWindow window = CommandWindow.parse("19:00", "21:00");
        assertFalse(window.contains(LocalTime.of(18, 59)));
        assertTrue(window.contains(LocalTime.of(19, 0)));
        assertTrue(window.contains(LocalTime.of(20, 59)));
        assertFalse(window.contains(LocalTime.of(21, 0)));
        assertFalse(window.contains(null));
    }

    @Test
    void windowCrossingMidnightSpansBothSidesOfTheDay() {
        CommandWindow window = CommandWindow.parse("22:00", "02:00");
        assertEquals("22:00-次日 02:00", window.text());
        assertTrue(window.contains(LocalTime.of(23, 30)));
        assertTrue(window.contains(LocalTime.of(0, 30)));
        assertFalse(window.contains(LocalTime.of(12, 0)));
        assertTrue(window.contains(LocalTime.of(22, 0)));
        assertFalse(window.contains(LocalTime.of(2, 0)));
    }

    @Test
    void containsAnyMatchesWhicheverWindowApplies() {
        List<CommandWindow> windows = CommandWindow.parseAll(List.of(
                Map.of("start", "12:00", "end", "13:30"),
                Map.of("start", "19:00", "end", "21:00")));
        assertEquals(2, windows.size());
        assertTrue(CommandWindow.containsAny(windows, LocalTime.of(12, 30)));
        assertTrue(CommandWindow.containsAny(windows, LocalTime.of(20, 0)));
        assertFalse(CommandWindow.containsAny(windows, LocalTime.of(15, 0)));
        assertFalse(CommandWindow.containsAny(List.of(), LocalTime.of(15, 0)));
    }

    @Test
    void parseAllDropsBrokenRowsInsteadOfFailingTheWholeSetting() {
        List<CommandWindow> windows = CommandWindow.parseAll(List.of(
                Map.of("start", "19:00", "end", "21:00"),
                Map.of("start", "08:00"),
                Map.of("start", "24:00", "end", "23:00"),
                Map.of()));
        assertEquals(1, windows.size());
        assertEquals("19:00-21:00", windows.get(0).text());
        assertEquals(List.of(), CommandWindow.parseAll(null));
    }

    @Test
    void describeJoinsWindowsForTheGroupReply() {
        List<CommandWindow> windows = CommandWindow.parseAll(List.of(
                Map.of("start", "12:00", "end", "13:30"),
                Map.of("start", "22:00", "end", "02:00")));
        assertEquals("12:00-13:30、22:00-次日 02:00", CommandWindow.describe(windows));
        assertEquals("", CommandWindow.describe(List.of()));
    }
}
