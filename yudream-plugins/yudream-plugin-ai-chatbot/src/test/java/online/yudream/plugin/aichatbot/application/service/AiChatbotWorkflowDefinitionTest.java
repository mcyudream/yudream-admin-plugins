package online.yudream.plugin.aichatbot.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatbotWorkflowDefinitionTest {

    @Test
    void declaresIntentConditionRefusalAndReplyOrchestration() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("agents/group-chatbot.json")) {
            var graph = new ObjectMapper().readTree(input);

            assertEquals(7, graph.path("nodes").size());
            assertTrue(graph.path("nodes").toString().contains("\"kind\":\"understand\""));
            // 意图节点必须容忍模型输出非严格 JSON（strictJson=false），避免整条群回复因解析失败而报错
            graph.path("nodes").forEach(node -> {
                if ("understand".equals(node.path("data").path("kind").asText())) {
                    assertTrue(node.path("data").has("strictJson") && !node.path("data").path("strictJson").asBoolean(),
                            "understand 节点必须显式声明 strictJson=false");
                }
            });
            assertTrue(graph.path("nodes").toString().contains("\"kind\":\"condition\""));
            assertTrue(graph.path("nodes").toString().contains("\"kind\":\"template\""));
            assertTrue(graph.path("edges").toString().contains("\"sourceHandle\":\"true\""));
            assertTrue(graph.path("edges").toString().contains("\"sourceHandle\":\"false\""));
        }
    }
}
