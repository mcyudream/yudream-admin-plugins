package online.yudream.base.plugin.feedback.infrastructure;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 时间格式化助手：统一服务端时区与展示格式，避免前端各算一套。 */
public final class TimeSupport {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private TimeSupport() {
    }

    public static String formatDateTime(Long millis) {
        return millis == null || millis <= 0L ? "" : DATE_TIME.format(toLocalDateTime(millis));
    }

    private static LocalDateTime toLocalDateTime(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}
