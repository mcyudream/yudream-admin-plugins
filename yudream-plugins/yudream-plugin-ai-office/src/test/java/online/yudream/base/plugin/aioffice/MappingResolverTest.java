package online.yudream.base.plugin.aioffice;

import online.yudream.base.plugin.aioffice.domain.service.MappingResolver;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingResolverTest {

    private Map<String, Object> context() {
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("title", "周年活动");
        activity.put("activityStart", 0L);
        Map<String, Object> org = new LinkedHashMap<>();
        org.put("name", "测试社团");
        org.put("committee", List.of("张三", "李四"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "奖品");
        row.put("amountCents", 12800L);
        Map<String, Object> finance = new LinkedHashMap<>();
        finance.put("expenseTotal", 12800L);
        finance.put("expenses", List.of(row));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("activity", activity);
        ctx.put("target", activity);
        ctx.put("org", org);
        ctx.put("finance", finance);
        return ctx;
    }

    @Test
    void resolveDotPath() {
        assertEquals("周年活动", MappingResolver.resolve("ctx:target.title", context(), Map.of()));
        assertEquals("测试社团", MappingResolver.resolve("ctx:org.name", context(), Map.of()));
        assertEquals("", MappingResolver.resolve("ctx:org.missing", context(), Map.of()));
    }

    @Test
    void resolveTextAndAi() {
        assertEquals("常量", MappingResolver.resolve("text:常量", context(), Map.of()));
        assertEquals("草稿内容", MappingResolver.resolve("ai:body", context(), Map.of("body", "草稿内容")));
        assertEquals("", MappingResolver.resolve("ai:missing", context(), Map.of()));
    }

    @Test
    void resolveList() {
        Object value = MappingResolver.resolve("list:finance.expenses", context(), Map.of());
        assertTrue(value instanceof List<?> list && list.size() == 1);
    }

    @Test
    void transforms() {
        long epoch = new Date(2026 - 1900, 9, 23).getTime();
        assertEquals("2026年10月23日",
                MappingResolver.applyTransform("date:yyyy年MM月dd日", epoch));
        assertEquals("128", MappingResolver.applyTransform("money", 12800L));
        assertEquals("128.50", MappingResolver.applyTransform("money", 12850L));
        assertEquals("张三、李四", MappingResolver.applyTransform("join:、", List.of("张三", "李四")));
    }

    @Test
    void buildRenderDataWithItems() {
        OfficeDocTypeHolder holder = new OfficeDocTypeHolder();
        Map<String, Object> data = MappingResolver.buildRenderData(holder.mappings(), context(), Map.of());
        assertEquals("周年活动", data.get("activityName"));
        assertEquals("128", data.get("expenseTotal"));
        assertTrue(data.get("expenseRows") instanceof List<?> rows && rows.size() == 1);
        assertEquals("奖品", ((Map<?, ?>) ((List<?>) data.get("expenseRows")).get(0)).get("title"));
    }

    @Test
    void interpolateReplacesDotPathsAndDraft() {
        String result = MappingResolver.interpolate("活动「{target.title}」由{org.name}主办，{aiNote}",
                context(), Map.of("aiNote", "已核"));
        assertEquals("活动「周年活动」由测试社团主办，已核", result);
    }

    @Test
    void moneyTextFormatting() {
        assertEquals("0", MappingResolver.moneyText(0));
        assertEquals("12.05", MappingResolver.moneyText(1205));
    }

    /** 仅用于承载映射定义的测试壳。 */
    static final class OfficeDocTypeHolder {
        List<online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType.Mapping> mappings() {
            Map<String, String> itemMapping = new LinkedHashMap<>();
            itemMapping.put("title", "item.title");
            itemMapping.put("amount", "item.amountCents");
            return List.of(
                    new online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType.Mapping(
                            "activityName", "ctx:target.title", "", Map.of()),
                    new online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType.Mapping(
                            "expenseTotal", "ctx:finance.expenseTotal", "money", Map.of()),
                    new online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType.Mapping(
                            "expenseRows", "list:finance.expenses", "", itemMapping));
        }
    }
}
