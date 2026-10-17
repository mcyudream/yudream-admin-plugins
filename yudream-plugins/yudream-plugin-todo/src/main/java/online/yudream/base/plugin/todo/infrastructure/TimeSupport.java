package online.yudream.base.plugin.todo.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 时间格式化助手：统一服务端时区与展示格式，避免前端各算一套。 */
public final class TimeSupport {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private TimeSupport() {
    }

    public static String formatDateTime(Long millis) {
        return millis == null || millis <= 0L ? "" : DATE_TIME.format(toLocalDateTime(millis));
    }

    public static String formatDate(Long millis) {
        return millis == null || millis <= 0L ? "" : DATE.format(toLocalDateTime(millis).toLocalDate());
    }

    /** 自然日起点（含）。 */
    public static long dayStart(long millis) {
        return LocalDate.from(toLocalDateTime(millis)).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
    }

    /** 自然日终点（不含）。 */
    public static long dayEnd(long millis) {
        return dayStart(millis) + 24L * 60L * 60L * 1000L;
    }

    /** 未来 N 天后的自然日终点（不含），用于「即将到期」。 */
    public static long dayEndPlusDays(long millis, int days) {
        return dayStart(millis) + (days + 1L) * DAY_MILLIS;
    }

    /** 以自然日为单位的偏移，用于按天铺开日程区间。 */
    public static long dayStartPlusDays(long millis, int days) {
        return dayStart(millis) + days * DAY_MILLIS;
    }

    private static LocalDateTime toLocalDateTime(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}
