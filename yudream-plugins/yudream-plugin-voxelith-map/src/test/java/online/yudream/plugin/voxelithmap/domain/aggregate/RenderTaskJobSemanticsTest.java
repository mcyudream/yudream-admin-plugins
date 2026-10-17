package online.yudream.plugin.voxelithmap.domain.aggregate;

import online.yudream.plugin.voxelithmap.domain.enumerate.TaskKind;
import online.yudream.plugin.voxelithmap.domain.enumerate.TaskState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长任务（导入/同步/删除）在任务聚合上的语义。
 *
 * <p>渲染与作业共用同一个聚合，所以这里重点守住两件容易改错的事：作业类任务没有渲染代
 * （误调 {@code generation()} 必须立刻失败而不是拼出一个假代），以及删除任务的取消语义
 * （删除是一次不可中断的存储回收，跑起来之后不该再声称能取消）。</p>
 */
class RenderTaskJobSemanticsTest {

    @Test
    void importJobHasNoGeneration() {
        RenderTask task = RenderTask.createJob("t-1", TaskKind.IMPORT, "生存服");
        assertEquals(TaskKind.IMPORT, task.kind());
        assertEquals("生存服", task.subject());
        assertTrue(task.mapId().isBlank(), "导入在建图之前还没有地图");
        assertThrows(IllegalStateException.class, task::generation,
                "作业类任务没有渲染代，误用必须立刻失败");
    }

    @Test
    void syncJobCarriesMapIdForLinking() {
        RenderTask task = RenderTask.createJob("t-2", TaskKind.SYNC, "我的世界", "map-9");
        assertEquals("map-9", task.mapId());
        assertEquals("我的世界", task.subject());
    }

    /** 删除跑起来之后不可取消：否则会标成「已取消」而产物其实已经删掉。 */
    @Test
    void deleteJobIsOnlyCancellableWhileQueued() {
        RenderTask task = RenderTask.createJob("t-3", TaskKind.DELETE, "待删地图");
        assertTrue(task.canCancel(), "排队阶段允许取消");
        task.start();
        assertEquals(TaskState.RUNNING, task.state());
        assertTrue(!task.canCancel(), "删除执行中不允许取消");
    }

    /** 渲染任务仍可在执行中取消（子进程会被优雅终止）。 */
    @Test
    void renderJobStaysCancellableWhileRunning() {
        RenderTask task = RenderTask.createJob("t-4", TaskKind.IMPORT, "实例");
        task.start();
        assertTrue(task.canCancel());
    }

    /** 失败文案按类型区分：导入任务不该显示「渲染失败」。 */
    @Test
    void failureMessageFollowsTaskKind() {
        RenderTask task = RenderTask.createJob("t-5", TaskKind.SYNC, "我的世界", "map-1");
        task.start();
        task.fail("来源实例已删除");
        assertEquals(TaskState.FAILED, task.state());
        assertEquals("存档同步失败", task.message());
        assertEquals("来源实例已删除", task.error());
    }
}
