package online.yudream.plugin.worldmap.domain.aggregate;

import online.yudream.plugin.worldmap.domain.enumerate.RenderPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderTaskPhaseTest {

    @Test
    void phaseProgressIsMonotonicAndCannotReachCompleteBeforePublish() {
        RenderTask task = new RenderTask("task-1", "map-1");

        task.advancePhase(RenderPhase.HIRES, 50, "rendering hires tiles");
        int hiresProgress = task.getProgressPercent();
        task.advancePhase(RenderPhase.LOWRES, 0, "rendering lowres tiles");

        assertEquals(RenderPhase.LOWRES, task.getPhase());
        assertTrue(task.getProgressPercent() >= hiresProgress);
        assertTrue(task.getProgressPercent() < 100);
    }

    @Test
    void publishCompletionMarksTaskAtOneHundredPercent() {
        RenderTask task = new RenderTask("task-1", "map-1");

        task.advancePhase(RenderPhase.PUBLISH, 100, "publishing generation");
        task.succeed();

        assertEquals(100, task.getProgressPercent());
    }

    @Test
    void phaseCannotMoveBackwardAfterLaterWorkStarted() {
        RenderTask task = new RenderTask("task-1", "map-1");

        task.advancePhase(RenderPhase.LOWRES, 20, "rendering overview tiles");
        task.advancePhase(RenderPhase.HIRES, 100, "stale hires update");

        assertEquals(RenderPhase.LOWRES, task.getPhase());
    }
}
