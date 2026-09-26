package online.yudream.base.plugin.activityproof.domain.valobj;

import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityBindingType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高级自定义计分：公式解析/求值与绑定校验。
 * 场景取自管理端真实用法：多服时长加权 + 答题得分加分。
 */
class ScoreFormulaTest {

    private static final Set<String> KEYS = Set.of("a", "b", "c");

    @Test
    void evaluatesWeightedServerMinutesAndQuizScore() {
        // 0.5*服务器a时长(分钟)/30 + 0.5*服务器b时长(分钟)/30 + 答题c答对题数*0.2
        ScoreFormula formula = ScoreFormula.parse("0.5*a/30 + 0.5*b/30 + c*0.2", KEYS);
        double score = formula.evaluate(Map.of("a", 60.0, "b", 30.0, "c", 8.0));
        assertEquals(0.5 * 60 / 30 + 0.5 * 30 / 30 + 8 * 0.2, score, 1e-9);
        assertEquals(Set.of("a", "b", "c"), formula.variables());
    }

    @Test
    void supportsParenthesesNegationAndCaseInsensitiveVariables() {
        ScoreFormula formula = ScoreFormula.parse("(a + B) * -2 + 1.5", KEYS);
        assertEquals((10 + 4) * -2 + 1.5, formula.evaluate(Map.of("a", 10.0, "b", 4.0, "c", 0.0)), 1e-9);
    }

    @Test
    void rejectsEmptyUnknownAndIllegalInput() {
        assertThrows(IllegalArgumentException.class, () -> ScoreFormula.parse("   ", KEYS));
        // 未定义变量
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> ScoreFormula.parse("a + d", KEYS));
        assertTrue(unknown.getMessage().contains("d"));
        // 中文等字符不允许进公式
        assertThrows(IllegalArgumentException.class, () -> ScoreFormula.parse("0.5*服务器a时长/30", KEYS));
        // 非法数字、缺括号、多余内容、悬空运算符
        assertThrows(IllegalArgumentException.class, () -> ScoreFormula.parse("1.2.3 + a", KEYS));
        assertThrows(IllegalArgumentException.class, () -> ScoreFormula.parse("(a + 1", KEYS));
        assertThrows(IllegalArgumentException.class, () -> ScoreFormula.parse("a + 1)", KEYS));
        assertThrows(IllegalArgumentException.class, () -> ScoreFormula.parse("a +", KEYS));
        // 变量集为空时不允许出现任何字母
        assertThrows(IllegalArgumentException.class, () -> ScoreFormula.parse("a", Set.of()));
    }

    @Test
    void rejectsOverlongFormula() {
        String longFormula = "a+" .repeat(200) + "a";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ScoreFormula.parse(longFormula, KEYS));
        assertTrue(error.getMessage().contains("过长"));
    }

    @Test
    void divisionByZeroFailsAtEvaluateOnly() {
        ScoreFormula formula = ScoreFormula.parse("a / b", KEYS);
        assertEquals(2.0, formula.evaluate(Map.of("a", 4.0, "b", 2.0)), 1e-9);
        assertThrows(ArithmeticException.class, () -> formula.evaluate(Map.of("a", 4.0, "b", 0.0)));
    }

    @Test
    void advancedBindingKeepsParamsExpressionAndInterval() {
        ActivityBinding binding = ActivityBinding.advanced(
                List.of(
                        new ActivityBindingParam("a", "服A在线时长（分钟）", ActivityBindingType.PLAYTIME, "server-a", "", true, "", ""),
                        new ActivityBindingParam("b", "活动答题得分（答对题数）", ActivityBindingType.QUIZ, "", "", false, "", "")
                ),
                "0.5*a/30 + b*0.2",
                30,
                null
        );
        assertTrue(binding.isAdvanced());
        assertFalse(binding.autoJoin());
        assertEquals(30, binding.minScore());
        assertEquals(null, binding.maxScore());
        // 表达式在绑定构造时即完成语法与未知变量校验
        assertThrows(IllegalArgumentException.class,
                () -> ActivityBinding.advanced(List.of(
                        new ActivityBindingParam("a", "x", ActivityBindingType.PLAYTIME, "server-a", "", false, "", "")),
                        "a + b", 0, null));
    }

    @Test
    void advancedBindingValidation() {
        List<ActivityBindingParam> params = List.of(
                new ActivityBindingParam("a", "x", ActivityBindingType.PLAYTIME, "server-a", "", false, "", ""));
        // 参数为空、超上限、区间颠倒
        assertThrows(IllegalArgumentException.class, () -> ActivityBinding.advanced(List.of(), "a", 0, null));
        assertThrows(IllegalArgumentException.class, () -> ActivityBinding.advanced(
                List.of(
                        new ActivityBindingParam("a", "", ActivityBindingType.PLAYTIME, "s", "", false, "", ""),
                        new ActivityBindingParam("b", "", ActivityBindingType.PLAYTIME, "s", "", false, "", ""),
                        new ActivityBindingParam("c", "", ActivityBindingType.PLAYTIME, "s", "", false, "", ""),
                        new ActivityBindingParam("d", "", ActivityBindingType.PLAYTIME, "s", "", false, "", ""),
                        new ActivityBindingParam("e", "", ActivityBindingType.PLAYTIME, "s", "", false, "", ""),
                        new ActivityBindingParam("f", "", ActivityBindingType.PLAYTIME, "s", "", false, "", ""),
                        new ActivityBindingParam("g", "", ActivityBindingType.PLAYTIME, "s", "", false, "", ""),
                        new ActivityBindingParam("h", "", ActivityBindingType.PLAYTIME, "s", "", false, "", ""),
                        new ActivityBindingParam("i", "", ActivityBindingType.PLAYTIME, "s", "", false, "", "")),
                "a", 0, null));
        assertThrows(IllegalArgumentException.class, () -> ActivityBinding.advanced(params, "a", 10, 5.0));
        // 负分数线
        assertThrows(IllegalArgumentException.class, () -> ActivityBinding.advanced(params, "a", -1, null));
        // 非高级绑定的计分字段收敛为缺省，互不影响
        ActivityBinding playtime = ActivityBinding.playtime("server-a", 60, false, true);
        assertTrue(playtime.params().isEmpty());
        assertEquals(0, playtime.minScore());
    }

    @Test
    void paramNormalizesKeyAndClearsIrrelevantFields() {
        ActivityBindingParam param = new ActivityBindingParam(" A ", "x", ActivityBindingType.FORM, "should-clear", "sub", true, "form-1", "");
        assertEquals("a", param.key());
        assertTrue(param.isForm());
        assertEquals("", param.serverId());
        assertEquals("", param.subServer());
        assertFalse(param.includeAfk());
        // 时长参数必须选服务器，表单参数必须选表单
        assertThrows(IllegalArgumentException.class,
                () -> new ActivityBindingParam("a", "x", ActivityBindingType.PLAYTIME, "", "", false, "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ActivityBindingParam("a", "x", ActivityBindingType.FORM, "", "", false, "", ""));
    }
}
