package online.yudream.base.plugin.eduverify.application.chsi;

import java.util.Map;

/**
 * 学信网在线验证码核验端口。实现与官方报告页抓取/解析解耦。
 */
public interface ChsiVerifier {

    ChsiVerifyResult verify(String vcode, ChsiParseConfig config);

    /**
     * kind: PASSED / FAILED / UNAVAILABLE
     */
    record ChsiVerifyResult(String kind, String realName, String schoolName, String detail, String message) {

        public static ChsiVerifyResult passed(String realName, String schoolName, String detail) {
            return new ChsiVerifyResult("PASSED", realName, schoolName, detail, null);
        }

        public static ChsiVerifyResult unavailable(String message) {
            return new ChsiVerifyResult("UNAVAILABLE", null, null, null, message);
        }

        public static ChsiVerifyResult failed(String message) {
            return new ChsiVerifyResult("FAILED", null, null, null, message);
        }
    }

    record ChsiParseConfig(String urlTemplate, Map<String, String> selectors) {
        public ChsiParseConfig {
            urlTemplate = urlTemplate == null || urlTemplate.isBlank()
                    ? ChsiReportParser.DEFAULT_URL_TEMPLATE
                    : urlTemplate.trim();
            selectors = selectors == null || selectors.isEmpty()
                    ? ChsiReportParser.defaultSelectors()
                    : Map.copyOf(selectors);
        }
    }
}
