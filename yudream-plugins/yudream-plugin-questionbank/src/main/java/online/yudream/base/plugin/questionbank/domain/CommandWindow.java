package online.yudream.base.plugin.questionbank.domain;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 群指令的开放时间段：一天内的 {@code [start, end)}。
 *
 * <p>{@code start} 晚于 {@code end} 表示跨零点（如 22:00-02:00 表示当天 22:00 到次日 02:00）；
 * 起止相同视为无效——它既不是零点宽也不是整天，写出来只会让人以为开了却什么都没开放。
 */
public record CommandWindow(LocalTime start, LocalTime end) {

    public CommandWindow {
        if (start == null || end == null) {
            throw new IllegalArgumentException("时间段的开始与结束不能为空");
        }
        if (start.equals(end)) {
            throw new IllegalArgumentException("时间段的开始与结束不能相同");
        }
    }

    /**
     * 解析一对 {@code HH:mm} 文本；任一侧格式不对、越界或起止相同都返回 null。
     *
     * <p>调用方按「这一项无效」处理（保存时丢弃、读取时忽略），不因为一条写坏的时间段把整个设置
     * 变成不可用。
     */
    public static CommandWindow parse(String start, String end) {
        LocalTime from = parseTime(start);
        LocalTime to = parseTime(end);
        if (from == null || to == null || from.equals(to)) {
            return null;
        }
        return new CommandWindow(from, to);
    }

    /** 把文本列表里的无效项丢掉；空列表表示不限制。 */
    public static List<CommandWindow> parseAll(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<CommandWindow> windows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            CommandWindow window = parse(DocValues.str(row, "start"), DocValues.str(row, "end"));
            if (window != null) {
                windows.add(window);
            }
        }
        return List.copyOf(windows);
    }

    /** 现在是否落在任一时间段内；列表为空表示不限制，调用方应先判断这个前提。 */
    public static boolean containsAny(List<CommandWindow> windows, LocalTime time) {
        if (windows == null || windows.isEmpty() || time == null) {
            return false;
        }
        for (CommandWindow window : windows) {
            if (window.contains(time)) {
                return true;
            }
        }
        return false;
    }

    /** 用于群内提示的时间段列表文本，如 {@code 12:00-13:30、19:00-21:00}。 */
    public static String describe(List<CommandWindow> windows) {
        if (windows == null || windows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CommandWindow window : windows) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(window.text());
        }
        return sb.toString();
    }

    /** 是否落在本时间段内；跨零点的时间段按 {@code [start, 24:00) ∪ [00:00, end)} 判定。 */
    public boolean contains(LocalTime time) {
        if (time == null) {
            return false;
        }
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }

    /** 展示文本；跨零点的时间段显式标注「次日」。 */
    public String text() {
        return text(start) + "-" + (start.isBefore(end) ? "" : "次日 ") + text(end);
    }

    /** 落库与回显用的 {start, end} 文本。 */
    public Map<String, Object> toMap() {
        return Map.of("start", text(start), "end", text(end));
    }

    private static LocalTime parseTime(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return LocalTime.of(hour, minute);
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }
}
