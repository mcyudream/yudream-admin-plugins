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
            assertTrue(graph.path("nodes").toString().contains("\"kind\":\"condition\""));
            assertTrue(graph.path("nodes").toString().contains("\"kind\":\"template\""));
            assertTrue(graph.path("edges").toString().contains("\"sourceHandle\":\"true\""));
            assertTrue(graph.path("edges").toString().contains("\"sourceHandle\":\"false\""));
        }
    }
}
