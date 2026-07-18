package online.yudream.plugin.worldmap.domain.aggregate;

import online.yudream.plugin.worldmap.domain.enumerate.MapState;
import online.yudream.plugin.worldmap.domain.enumerate.TaskState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderTaskTest {

    @Test
    void cancellingRunningTaskAndMapRecordsTerminalState() {
        RenderTask task = new RenderTask("task-1", "map-1");
        task.start(12);
        task.cancel("cancelled by administrator");

        MapInstance map = new MapInstance("map-1", "Example", "overworld");
        map.markRendering();
        map.markCancelled("cancelled by administrator");

        assertEquals(TaskState.CANCELLED, task.getState());
        assertEquals("cancelled by administrator", task.getError());
        assertEquals(MapState.CANCELLED, map.getState());
        assertEquals("cancelled by administrator", map.getMessage());
    }
}
