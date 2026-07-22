package online.yudream.plugin.webcard.application;

import com.fasterxml.jackson.databind.JsonNode;
import online.yudream.plugin.webcard.interfaces.JsonSupport;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWorkflowResourceTest {

    @Test
    void conversationalWorkflowDoesNotContainStructuredUnderstandingNodes() throws Exception {
        JsonNode workflow = workflow("/agents/web-card-studio.json");

        assertEquals(List.of("start", "llm", "end"), kinds(workflow));
        assertFalse(workflow.toString().contains("outputSchema"));
    }

    @Test
    void proposalCompilerUsesExtractNodeWithCompleteWorkspaceSchema() throws Exception {
        JsonNode workflow = workflow("/agents/web-card-plan-compiler.json");
        JsonNode compile = workflow.path("nodes").get(1).path("data");

        assertEquals("extract", compile.path("kind").asText());
        JsonNode schema = compile.path("outputSchema");
        assertTrue(schema.isObject());
        assertTrue(schema.path("required").toString().contains("site"));
        assertTrue(schema.path("required").toString().contains("rules"));
        assertTrue(schema.path("required").toString().contains("template"));
        assertEquals(1, schema.path("properties").path("rules").path("properties")
                .path("fields").path("minItems").asInt());
        String prompt = compile.path("prompt").asText();
        assertTrue(prompt.contains("KEY_VALUE_LIST"));
        assertTrue(prompt.contains("LINK_LIST"));
        assertTrue(prompt.contains("TABLE"));
        assertTrue(prompt.contains("sections"));
        assertTrue(prompt.contains("支持版本"));
    }

    private JsonNode workflow(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return JsonSupport.MAPPER.readTree(input);
        }
    }

    private List<String> kinds(JsonNode workflow) {
        List<String> values = new ArrayList<>();
        workflow.path("nodes").forEach(node -> values.add(node.path("data").path("kind").asText()));
        return values;
    }
}
