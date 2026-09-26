package online.yudream.base.plugin.mcpanel.application.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 安装任务跟踪：登记、进度轮询终态、DTO 载荷与实例清理。 */
class InstallTaskTrackerTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tasksOf(InstallTaskTracker tracker, String instanceId) {
        Map<String, Object> view = tracker.viewOf(instanceId);
        assertNotNull(view, "应有跟踪条目");
        return (List<Map<String, Object>>) view.get("tasks");
    }

    @Test
    void beginViewAndPollToDone() {
        AtomicInteger polls = new AtomicInteger();
        boolean[] half = {false};
        InstallTaskTracker tracker = new InstallTaskTracker((nodeId, method, payload) -> {
            assertEquals("task.get", method);
            polls.incrementAndGet();
            if (!half[0]) {
                half[0] = true;
                return Map.of("state", "running", "progress", 0.4d);
            }
            return Map.of("state", "done", "progress", 1d);
        }, null);

        tracker.begin("node-1", "inst-1", "task-9", "server.jar");
        List<Map<String, Object>> tasks = tasksOf(tracker, "inst-1");
        assertEquals(1, tasks.size());
        assertEquals("running", tasks.get(0).get("state"));
        assertEquals("server.jar", tasks.get(0).get("fileName"));

        tracker.pollOnce();
        assertEquals(0.4d, tasksOf(tracker, "inst-1").get(0).get("progress"));
        tracker.pollOnce();
        List<Map<String, Object>> done = tasksOf(tracker, "inst-1");
        assertEquals("done", done.get(0).get("state"));
        assertEquals(1.0d, done.get(0).get("progress"));
        assertTrue(polls.get() >= 2);
        tracker.close();
    }

    @Test
    void failedTaskCarriesErrorAndDropClears() {
        InstallTaskTracker tracker = new InstallTaskTracker(
                (nodeId, method, payload) -> Map.of("state", "failed", "progress", 0.2d, "error", "下载返回 404"),
                null);
        tracker.begin("node-1", "inst-2", "task-x", "core.jar");
        tracker.pollOnce();
        List<Map<String, Object>> tasks = tasksOf(tracker, "inst-2");
        assertEquals("failed", tasks.get(0).get("state"));
        assertEquals("下载返回 404", tasks.get(0).get("error"));
        tracker.drop("inst-2");
        assertNull(tracker.viewOf("inst-2"));
        tracker.close();
    }

    @Test
    void unknownInstanceAndBlankTaskIdAreIgnored() {
        InstallTaskTracker tracker = new InstallTaskTracker((nodeId, method, payload) -> Map.of(), null);
        assertNull(tracker.viewOf("nope"));
        tracker.begin("node-1", "inst-3", "", "server.jar");
        assertNull(tracker.viewOf("inst-3"));
        tracker.close();
    }

    @Test
    void taskLostOnNodeFailsImmediately() {
        InstallTaskTracker tracker = new InstallTaskTracker((nodeId, method, payload) -> {
            throw new online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException(
                    "instance.notFound", "目标不存在");
        }, null);
        tracker.begin("node-1", "inst-4", "task-lost", "server.jar");
        tracker.pollOnce();
        List<Map<String, Object>> tasks = tasksOf(tracker, "inst-4");
        assertEquals("failed", tasks.get(0).get("state"));
        assertTrue(String.valueOf(tasks.get(0).get("error")).contains("任务已丢失"));
        tracker.close();
    }

    @Test
    void nodeUnreachableEventuallyFails() {
        int old = InstallTaskTracker.MAX_POLL_FAILURES;
        InstallTaskTracker.MAX_POLL_FAILURES = 3;
        try {
            AtomicInteger polls = new AtomicInteger();
            InstallTaskTracker tracker = new InstallTaskTracker((nodeId, method, payload) -> {
                polls.incrementAndGet();
                throw new RuntimeException("node.offline: 节点未在线");
            }, null);
            tracker.begin("node-1", "inst-5", "task-offline", "server.jar");
            tracker.pollOnce();
            tracker.pollOnce();
            assertEquals("running", tasksOf(tracker, "inst-5").get(0).get("state"));
            tracker.pollOnce();
            List<Map<String, Object>> tasks = tasksOf(tracker, "inst-5");
            assertEquals("failed", tasks.get(0).get("state"));
            assertTrue(String.valueOf(tasks.get(0).get("error")).contains("不可达"));
            assertEquals(3, polls.get());
            tracker.close();
        }
        finally {
            InstallTaskTracker.MAX_POLL_FAILURES = old;
        }
    }

    @Test
    void stalledTaskEventuallyFails() {
        long old = InstallTaskTracker.STALL_MS;
        InstallTaskTracker.STALL_MS = -1; // 强制任何 running 轮询都判停滞
        try {
            InstallTaskTracker tracker = new InstallTaskTracker(
                    (nodeId, method, payload) -> Map.of("state", "running", "progress", 0.04d), null);
            tracker.begin("node-1", "inst-6", "task-stall", "162 个文件");
            tracker.pollOnce();
            List<Map<String, Object>> tasks = tasksOf(tracker, "inst-6");
            assertEquals("failed", tasks.get(0).get("state"));
            assertTrue(String.valueOf(tasks.get(0).get("error")).contains("无进展"));
            tracker.close();
        }
        finally {
            InstallTaskTracker.STALL_MS = old;
        }
    }

    @Test
    void progressChangeKeepsTaskAlive() {
        long old = InstallTaskTracker.STALL_MS;
        InstallTaskTracker.STALL_MS = 60_000L;
        try {
            AtomicInteger polls = new AtomicInteger();
            InstallTaskTracker tracker = new InstallTaskTracker((nodeId, method, payload) ->
                    Map.of("state", "running", "progress", 0.1d + polls.incrementAndGet() * 0.1d), null);
            tracker.begin("node-1", "inst-7", "task-alive", "server.jar");
            tracker.pollOnce();
            tracker.pollOnce();
            assertEquals("running", tasksOf(tracker, "inst-7").get(0).get("state"));
            tracker.close();
        }
        finally {
            InstallTaskTracker.STALL_MS = old;
        }
    }
}
