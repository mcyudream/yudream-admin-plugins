package online.yudream.plugin.webcard.interfaces;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebCardAgentEndpointContractTest {
    @Test
    void exposesStreamingAndEditableProposalEndpoints() {
        assertTrue(has("agentMessageStream"));
        assertTrue(has("agentMessageEvents"));
        assertTrue(has("updateProposal"));
        assertTrue(has("deleteSession"));
        assertTrue(has("deleteTemplate"));
    }

    private boolean has(String name) {
        for (Method method : WebCardAdminController.class.getDeclaredMethods()) if (method.getName().equals(name)) return true;
        return false;
    }
}
