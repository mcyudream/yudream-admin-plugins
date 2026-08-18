package online.yudream.plugin.aichatbot.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatbotWorkflowDefinitionTest {

    @Test
    void declaresSafetyIntentHelpBranchWikiToolAndReplyOrchestration() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("agents/group-chatbot.json")) {
            var graph = new ObjectMapper().readTree(input);
            String nodes = graph.path("nodes").toString();

            assertEquals(14, graph.path("nodes").size());
            assertTrue(nodes.contains("\"kind\":\"understand\""));
            assertTrue(nodes.contains("\"kind\":\"condition\""));
            assertTrue(nodes.contains("\"kind\":\"template\""));
            // 求助分支：安全意图之后接求助判定，命中进入 wiki.search 工具节点（编排内检索，插件配置不再承担）
            assertTrue(nodes.contains("\"toolCode\":\"wiki.search\""));
            assertTrue(nodes.contains("intent.route == 'help'"));
            // 工具分支：服务器状态、QQ 状态与历史等实时数据查询必须走工具调用，不得落入 wiki 检索
            assertTrue(nodes.contains("intent.route == 'tool'"));
            assertTrue(nodes.contains("\"toolMode\":\"AUTO\""));
            // 兜底分支：wiki 无命中时回落普通群聊回复，而不是强行 wiki 作答
            assertTrue(nodes.contains("wikiResult.count != null && wikiResult.count > 0"));
            // 意图节点必须容忍模型输出非严格 JSON（strictJson=false），避免整条群回复因解析失败而报错
            graph.path("nodes").forEach(node -> {
                if ("understand".equals(node.path("data").path("kind").asText())) {
                    assertTrue(node.path("data").has("strictJson") && !node.path("data").path("strictJson").asBoolean(),
                            "understand 节点必须显式声明 strictJson=false");
                }
            });
            assertTrue(graph.path("edges").toString().contains("\"sourceHandle\":\"true\""));
            assertTrue(graph.path("edges").toString().contains("\"sourceHandle\":\"false\""));
        }
    }
}
