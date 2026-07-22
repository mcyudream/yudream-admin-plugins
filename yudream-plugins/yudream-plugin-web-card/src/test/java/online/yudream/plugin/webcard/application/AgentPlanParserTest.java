package online.yudream.plugin.webcard.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentPlanParserTest {
    @Test
    void extractsJsonFromFencedAndProseResponse() {
        Map<String, Object> plan = AgentPlanParser.parse("方案如下：```json\n{\"summary\":\"x\",\"site\":{\"hosts\":[\"example.com\"]},\"rules\":{},\"template\":{}}\n```", false);
        assertEquals("x", plan.get("summary"));
    }

    @Test
    void removesJobWhenCrawlIntentIsAbsent() {
        Map<String, Object> plan = AgentPlanParser.parse("{\"summary\":\"x\",\"site\":{\"hosts\":[\"example.com\"]},\"rules\":{},\"template\":{},\"binding\":{\"channelId\":\"1\"},\"job\":{\"sourceUrl\":\"https://x\"},\"publish\":true}", false);
        assertFalse(plan.containsKey("job"));
        assertNull(plan.get("binding"));
        assertEquals(false, plan.get("publish"));
    }

    @Test
    void invalidProposalBecomesWarningWithoutThrowing() {
        AgentPlanParser.Result result = AgentPlanParser.tryParse("I cannot build that", false);
        assertTrue(result.plan().isEmpty());
        assertTrue(result.warning().isPresent());
    }

    @Test
    void requiresPositiveCrawlOrScheduleIntent() {
        assertFalse(AgentPlanParser.hasExplicitCrawlIntent("自动采集不需要，只处理群里发送的网址"));
        assertFalse(AgentPlanParser.hasExplicitCrawlIntent("帮我做一个网站卡片"));
        assertFalse(AgentPlanParser.hasExplicitCrawlIntent("抓取这个页面的信息生成渲染规则，但不要投递"));
        assertTrue(AgentPlanParser.hasExplicitCrawlIntent("每隔 30 分钟抓取一次 RSS"));
    }

    @Test
    void requiresIndependentExplicitBindingIntent() {
        assertFalse(AgentPlanParser.hasExplicitBindingIntent("抓取页面生成规则，但不投递"));
        assertFalse(AgentPlanParser.hasExplicitBindingIntent("每隔 30 分钟采集一次"));
        assertTrue(AgentPlanParser.hasExplicitBindingIntent("定时任务完成后推送到群里"));

        String content = "{\"summary\":\"x\",\"site\":{\"hosts\":[\"example.com\"]},\"rules\":{},\"template\":{},\"binding\":{\"channelId\":\"1\"},\"job\":{\"sourceUrl\":\"https://x\"}}";
        Map<String, Object> plan = AgentPlanParser.parse(content, true, false);
        assertTrue(plan.containsKey("job"));
        assertNull(plan.get("binding"));
    }

    @Test
    void rejectsPlanWithoutUsableHost() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                AgentPlanParser.parse("{\"site\":{\"hosts\":[]},\"rules\":{},\"template\":{}}", false));
        assertTrue(error.getMessage().contains("域名"));
    }
}
