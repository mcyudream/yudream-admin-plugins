package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整合包分片上传回归：顺序分片组装、commit 完整性校验（size+sha256）、
 * 摘要与旧 inspect 同形状（token 可被 modpack-apply 取回）、取消/乱序拒绝、超限拦截。
 */
class ModpackUploadServiceTest {

    private final ModpackUploadService service = new ModpackUploadService(new ModpackService(null, "test")::inspect);

    @AfterEach
    void tearDown() {
        service.close();
    }

    // ---------- 夹具 ----------

    private static byte[] mrpackZip() throws Exception {
        String index = """
                {
                  "formatVersion": 1,
                  "game": "minecraft",
                  "versionId": "1.0.0",
                  "name": "测试整合包",
                  "files": [
                    {"path": "mods/a.jar", "hashes": {"sha512": "abc"}, "size": 10,
                     "downloads": ["https://cdn.modrinth.com/data/aaaa/versions/x/a.jar"],
                     "env": {"client": "required", "server": "required"}}
                  ],
                  "dependencies": {"minecraft": "1.21.1", "fabric-loader": "0.16.9"}
                }
                """;
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("modrinth.index.json", index);
        entries.put("overrides/config/server.toml", "a=1");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    // ---------- 用例 ----------

    @Test
    void chunkedUploadAssemblesAndCommitReturnsInspectSummary() throws Exception {
        byte[] zip = mrpackZip();
        Map<String, Object> begin = service.begin("test.mrpack", zip.length, sha256Hex(zip));
        String taskId = String.valueOf(begin.get("taskId"));
        assertEquals(0L, begin.get("uploaded"));
        assertNotNull(begin.get("chunkSize"));

        int half = zip.length / 2;
        Map<String, Object> first = service.chunk(taskId, 0, java.util.Arrays.copyOfRange(zip, 0, half));
        assertEquals(half, ((Number) first.get("uploaded")).intValue());
        Map<String, Object> second = service.chunk(taskId, half,
                java.util.Arrays.copyOfRange(zip, half, zip.length));
        assertEquals(zip.length, ((Number) second.get("uploaded")).intValue());

        Map<String, Object> summary = service.commit(taskId);
        assertEquals("test.mrpack", summary.get("fileName"));
        assertEquals("测试整合包", summary.get("name"));
        assertEquals("1.21.1", summary.get("mcVersion"));
        assertNotNull(summary.get("token"), "commit 应产出 modpack-apply 使用的 token");
        // token 可取回识别结果（一次性）。
        assertNotNull(ModpackInspectStore.take(String.valueOf(summary.get("token"))));
        assertEquals("done", service.cancel(taskId).get("state"), "终态任务再次操作幂等返回视图");
    }

    @Test
    void outOfOrderChunkIsRejected() throws Exception {
        byte[] zip = mrpackZip();
        String taskId = String.valueOf(service.begin("a.zip", zip.length, sha256Hex(zip)).get("taskId"));
        service.chunk(taskId, 0, java.util.Arrays.copyOfRange(zip, 0, 10));
        assertThrows(McpanelBusinessException.class,
                () -> service.chunk(taskId, 5, java.util.Arrays.copyOfRange(zip, 10, 20)));
        // 顺序分片仍可继续完成
        Map<String, Object> view = service.chunk(taskId, 10, java.util.Arrays.copyOfRange(zip, 10, zip.length));
        assertEquals(zip.length, ((Number) view.get("uploaded")).intValue());
    }

    @Test
    void commitRejectsSha256MismatchAndFailsTask() throws Exception {
        byte[] zip = mrpackZip();
        String wrongSha = sha256Hex("other".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(sha256Hex(zip), wrongSha);
        String taskId = String.valueOf(service.begin("a.zip", zip.length, wrongSha).get("taskId"));
        service.chunk(taskId, 0, zip);
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.commit(taskId));
        assertTrue(String.valueOf(error.getMessage()).contains("sha256"), "应报 sha256 校验失败");
        assertThrows(McpanelBusinessException.class,
                () -> service.chunk(taskId, zip.length, new byte[1]), "失败任务的分片应被拒绝");
    }

    @Test
    void cancelReleasesTaskAndRejectsFurtherChunks() throws Exception {
        byte[] zip = mrpackZip();
        String taskId = String.valueOf(service.begin("a.zip", zip.length, sha256Hex(zip)).get("taskId"));
        assertEquals("canceled", service.cancel(taskId).get("state"));
        assertThrows(McpanelBusinessException.class, () -> service.chunk(taskId, 0, zip));
    }

    @Test
    void beginValidatesSizeAndSha256() {
        assertThrows(McpanelBusinessException.class,
                () -> service.begin("big.zip", 65L * 1024 * 1024, "a".repeat(64)));
        assertThrows(McpanelBusinessException.class,
                () -> service.begin("a.zip", 1024, "nothex"));
        assertThrows(McpanelBusinessException.class,
                () -> service.begin("a.zip", 0, "a".repeat(64)));
    }

    @Test
    void incompleteCommitFailsWithoutInspecting() throws Exception {
        byte[] zip = mrpackZip();
        String taskId = String.valueOf(service.begin("a.zip", zip.length + 1, sha256Hex(zip)).get("taskId"));
        service.chunk(taskId, 0, zip);
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.commit(taskId));
        assertTrue(String.valueOf(error.getMessage()).contains("不完整"));
    }
}
