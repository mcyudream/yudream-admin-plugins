package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 大文件分片上传任务：节点会话建立、分片顺序转发、状态机与取消清理。 */
class UploadTaskServiceTest {

    private static final String SHA = "a".repeat(64);

    /** 记录型 fake：file.upload.begin 返回固定 uploadId；chunk 从第 failFromChunk 次起抛错。 */
    private static final class FakeNodeCall implements UploadTaskService.NodeUploadCall {
        record Call(String scopeKey, String instanceId, String method, Map<String, Object> payload) {
        }

        final List<Call> calls = new ArrayList<>();
        int failFromChunk = Integer.MAX_VALUE;
        int chunkCount;

        List<Call> of(String method) {
            return calls.stream().filter(call -> call.method().equals(method)).toList();
        }

        @Override
        public Map<String, Object> call(String scopeKey, String instanceId, String method, Map<String, Object> payload) {
            calls.add(new Call(scopeKey, instanceId, method, payload));
            if ("file.upload.chunk".equals(method) && ++chunkCount >= failFromChunk) {
                throw new IllegalStateException("节点断开");
            }
            return switch (method) {
                case "file.upload.begin" -> new HashMap<>(Map.of("uploadId", "node-upload-1"));
                case "file.upload.chunk" -> new HashMap<>(Map.of("accepted", true));
                default -> new HashMap<>();
            };
        }
    }

    private final FakeNodeCall node = new FakeNodeCall();
    private final UploadTaskService service = new UploadTaskService(node);

    private Map<String, Object> begin() {
        return service.begin("user:1", "inst-1", "server.jar", 250_000L, SHA, "server.jar");
    }

    private String taskId() {
        return String.valueOf(service.viewOf("inst-1").get(0).get("taskId"));
    }

    @Test
    void beginCreatesTaskAndNodeSession() {
        Map<String, Object> task = begin();
        assertEquals("running", task.get("state"));
        assertEquals(0L, task.get("uploaded"));
        assertEquals(250_000L, task.get("size"));
        assertEquals(1, node.of("file.upload.begin").size());
        assertEquals(SHA, node.of("file.upload.begin").get(0).payload().get("sha256"));
    }

    @Test
    void beginRejectsInvalidShaOrSize() {
        assertThrows(McpanelBusinessException.class,
                () -> service.begin("user:1", "inst-1", "a.jar", 100, "short", null));
        assertThrows(McpanelBusinessException.class,
                () -> service.begin("user:1", "inst-1", "a.jar", 3L * 1024 * 1024 * 1024, SHA, null));
        assertThrows(McpanelBusinessException.class,
                () -> service.begin("user:1", "inst-1", "", 100, SHA, null));
        assertTrue(node.of("file.upload.begin").isEmpty());
    }

    @Test
    void chunkForwardsFramesAndTracksProgress() {
        begin();
        byte[] data = new byte[250_000];
        Arrays.fill(data, (byte) 0x61);
        Map<String, Object> result = service.chunk("user:1", taskId(), 0, data);
        assertEquals(250_000L, result.get("uploaded"));
        // 250KB 拆 96KB 帧 = 3 帧，offset 与全文件偏移一致；末帧 base64 长度对应剩余字节
        List<FakeNodeCall.Call> chunks = node.of("file.upload.chunk");
        assertEquals(3, chunks.size());
        assertEquals(0L, chunks.get(0).payload().get("offset"));
        assertEquals(96 * 1024L, chunks.get(1).payload().get("offset"));
        assertEquals(2 * 96 * 1024L, chunks.get(2).payload().get("offset"));
        byte[] lastFrame = Base64.getDecoder().decode((String) chunks.get(2).payload().get("content"));
        assertEquals(250_000 - 2 * 96 * 1024, lastFrame.length);
    }

    @Test
    void chunkRejectsOffsetMismatch() {
        begin();
        String taskId = taskId();
        service.chunk("user:1", taskId, 0, new byte[1000]);
        assertThrows(McpanelBusinessException.class,
                () -> service.chunk("user:1", taskId, 500, new byte[1000]));
    }

    @Test
    void chunkFailureFailsTaskAndAbortsSession() {
        // 100KB 分片 = 2 帧；第 3 帧起失败 → 第一片成功、第二片失败
        node.failFromChunk = 3;
        begin();
        String taskId = taskId();
        service.chunk("user:1", taskId, 0, new byte[100_000]);
        assertThrows(RuntimeException.class,
                () -> service.chunk("user:1", taskId, 100_000, new byte[100_000]));
        assertEquals("failed", service.viewOf("inst-1").get(0).get("state"));
        assertEquals(1, node.of("file.upload.abort").size());
        assertThrows(McpanelBusinessException.class,
                () -> service.chunk("user:1", taskId, 200_000, new byte[50_000]));
    }

    @Test
    void commitMarksDoneAndCannotRecommit() {
        begin();
        String taskId = taskId();
        service.chunk("user:1", taskId, 0, new byte[1000]);
        Map<String, Object> result = service.commit("user:1", taskId);
        assertEquals("done", result.get("state"));
        assertThrows(McpanelBusinessException.class, () -> service.commit("user:1", taskId));
    }

    @Test
    void cancelAbortsNodeSession() {
        begin();
        String taskId = taskId();
        Map<String, Object> result = service.cancel("user:1", taskId);
        assertEquals("canceled", result.get("state"));
        assertEquals(1, node.of("file.upload.abort").size());
    }

    @Test
    void viewOfOnlyReturnsSameInstanceAndEvicts() {
        begin();
        service.begin("user:1", "inst-2", "other.jar", 100, SHA, null);
        assertEquals(1, service.viewOf("inst-1").size());
        service.evictInstance("inst-1");
        assertTrue(service.viewOf("inst-1").isEmpty());
        assertEquals(1, service.viewOf("inst-2").size());
    }
}
