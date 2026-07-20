package online.yudream.plugin.worldmap.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdminMapControllerTest {

    @Test
    void registersTaskListAndCancellationOnSeparateMethods() throws Exception {
        Method tasks = AdminMapController.class.getDeclaredMethod("tasks", PluginHttpRequest.class);
        Method cancel = AdminMapController.class.getDeclaredMethod("cancelTask", PluginHttpRequest.class);
        Method events = AdminMapController.class.getDeclaredMethod("taskEvents", PluginHttpRequest.class);

        PluginHttpEndpoint tasksEndpoint = tasks.getAnnotation(PluginHttpEndpoint.class);
        PluginHttpEndpoint cancelEndpoint = cancel.getAnnotation(PluginHttpEndpoint.class);
        PluginHttpEndpoint eventsEndpoint = events.getAnnotation(PluginHttpEndpoint.class);

        assertNotNull(tasksEndpoint);
        assertNotNull(cancelEndpoint);
        assertNotNull(eventsEndpoint);
        assertEquals("GET", tasksEndpoint.method());
        assertEquals("/admin/tasks", tasksEndpoint.path());
        assertEquals("POST", cancelEndpoint.method());
        assertEquals("/admin/tasks/{taskId}/cancel", cancelEndpoint.path());
        assertEquals("GET", eventsEndpoint.method());
        assertEquals("/admin/tasks/events", eventsEndpoint.path());
    }
}
