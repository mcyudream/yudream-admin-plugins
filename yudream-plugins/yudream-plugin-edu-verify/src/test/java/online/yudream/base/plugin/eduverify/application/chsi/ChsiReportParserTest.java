package online.yudream.base.plugin.eduverify.application.chsi;

import online.yudream.base.plugin.eduverify.application.chsi.ChsiVerifier.ChsiParseConfig;
import online.yudream.base.plugin.eduverify.application.chsi.ChsiVerifier.ChsiVerifyResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChsiReportParserTest {

    @Test
    void parsesTableReportWithDefaultSelectors() {
        String html = """
                <html><head><title>教育部学籍在线验证报告_中国高等教育学生信息网（学信网）</title></head>
                <body>
                <h1>教育部学籍在线验证报告</h1>
                <table>
                  <tr><td>姓名</td><td>郭金龙</td><td>性别</td><td>男</td></tr>
                  <tr><td>学校名称</td><td>西南科技大学</td><td>层次</td><td>本科</td></tr>
                  <tr><td>专业</td><td>信息安全</td><td>学籍状态</td><td>在籍（注册学籍）</td></tr>
                  <tr><td>在线验证码</td><td>APEVUKH9C8SSGS5D</td></tr>
                </table>
                </body></html>
                """;
        ChsiVerifyResult result = ChsiReportParser.parse(html, "APEVUKH9C8SSGS5D",
                new ChsiParseConfig(ChsiReportParser.DEFAULT_URL_TEMPLATE, ChsiReportParser.defaultSelectors()));
        assertEquals("PASSED", result.kind());
        assertEquals("郭金龙", result.realName());
        assertEquals("西南科技大学", result.schoolName());
        assertTrue(result.detail().contains("在籍"));
    }

    @Test
    void fallsBackToSiblingLabelsWhenSelectorsMiss() {
        String html = """
                <html><body>
                <div>教育部学籍在线验证报告</div>
                <div>姓名</div><div>张三</div>
                <div>学校名称</div><div>某某大学</div>
                <div>在线验证码</div><div>ABCDEFGH12345678</div>
                </body></html>
                """;
        ChsiVerifyResult result = ChsiReportParser.parse(html, "ABCDEFGH12345678",
                new ChsiParseConfig(null, Map.of("realName", "#missing")));
        assertEquals("PASSED", result.kind());
        assertEquals("张三", result.realName());
        assertEquals("某某大学", result.schoolName());
    }

    @Test
    void rejectsInvalidReport() {
        ChsiVerifyResult result = ChsiReportParser.parse("<html><body>验证码无效或报告不存在</body></html>",
                "APEVUKH9C8SSGS5D", new ChsiParseConfig(null, Map.of()));
        assertEquals("FAILED", result.kind());
    }
}
