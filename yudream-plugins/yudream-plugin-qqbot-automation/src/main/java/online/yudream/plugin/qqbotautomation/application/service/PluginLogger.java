package online.yudream.plugin.qqbotautomation.application.service;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 插件日志统一走 JUL，宿主 jul-to-slf4j 会收进系统日志。
 * logger 名含 qqbotautomation，系统日志模块筛选为「QQ 群自动化」。
 */
final class PluginLogger {
    static final String JOIN = "入群验证";
    static final String MEDIA = "媒体解析";
    static final String RISK = "风险监测";

    private static final String PREFIX = "[QQ 群自动化]";
    private final Logger logger;

    private PluginLogger(Class<?> type) {
        this.logger = Logger.getLogger(type.getName());
    }

    static PluginLogger of(Class<?> type) {
        return new PluginLogger(type);
    }

    void info(String category, String message) {
        logger.info(format(category, message));
    }

    void warn(String category, String message) {
        logger.warning(format(category, message));
    }

    void warn(String category, String message, Throwable error) {
        logger.log(Level.WARNING, format(category, message), error);
    }

    void error(String category, String message, Throwable error) {
        logger.log(Level.SEVERE, format(category, message), error);
    }

    private static String format(String category, String message) {
        return PREFIX + " [" + category + "] " + message;
    }
}
