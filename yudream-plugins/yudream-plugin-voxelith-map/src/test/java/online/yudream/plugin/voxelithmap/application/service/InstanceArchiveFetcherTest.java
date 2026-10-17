package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.plugin.voxelithmap.domain.port.InstanceFilePort;
import online.yudream.plugin.voxelithmap.domain.value.InstanceDirEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打包产物分块拉回的三条关键语义：自适应分块不丢数据、字节数不符必须失败、取消立即生效。
 *
 * <p>这三条都属于「错了也看不出来」的类型——少读一段会得到一个能建图但内容残缺的存档，
 * 多容忍一次不完整就会把半截包写进存储。因此用替身把节点/面板的行为（返回量小于请求量、
 * 大小未知、提前结束）逐个复刻出来。</p>
 */
class InstanceArchiveFetcherTest {

    private static final long MAX_BYTES = 64L * 1024 * 1024;

    /** 逐块供数的替身：单次最多返回 {@code maxReturn} 字节，模拟节点侧的返回上限。 */
    private static final class FakePort implements InstanceFilePort {

        private final byte[] payload;
        private final int maxReturn;
        private final List<Integer> requested = new ArrayList<>();
        /** 读到第几次时开始报告"已取消"（0 = 从不）。 */
        private int cancelAfterReads;
        private int reads;

        FakePort(byte[] payload, int maxReturn) {
            this.payload = payload;
            this.maxReturn = Math.max(1, maxReturn);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<InstanceDirEntry> listDir(String instanceId, String path) {
            return List.of();
        }

        @Override
        public long packToZip(String instanceId, List<String> paths, String dest) {
            return payload.length;
        }

        @Override
        public byte[] readArchiveRange(String instanceId, String path, long offset, int length) {
            reads++;
            requested.add(length);
            if (offset >= payload.length) {
                return new byte[0];
            }
            int want = (int) Math.min(Math.min(length, maxReturn), payload.length - offset);
            byte[] chunk = new byte[want];
            System.arraycopy(payload, (int) offset, chunk, 0, want);
            return chunk;
        }

        @Override
        public void deleteFile(String instanceId, String path) {
        }
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    @Test
    void adaptsChunkSizeWhenPanelReturnsLessThanRequested(@TempDir Path dir) throws IOException {
        byte[] expected = payload(5_000);
        FakePort port = new FakePort(expected, 700);
        Path target = dir.resolve("world.zip");

        long received = new InstanceArchiveFetcher(port, 4_000, MAX_BYTES)
                .fetch("inst-1", "voxelith-import.zip", target, expected.length, () -> false, null);

        assertEquals(expected.length, received);
        assertArrayEquals(expected, Files.readAllBytes(target));
        // 首次请求 4000，撞到上限后收敛到 700：后续请求不应再超过实际返回量
        assertEquals(4_000, port.requested.get(0).intValue());
        assertEquals(700, port.requested.get(1).intValue());
        assertTrue(port.requested.stream().skip(1).allMatch(size -> size <= 700),
                "撞到节点上限后应把窗口拆细，而不是继续按 4000 请求：" + port.requested);
    }

    @Test
    void probesSequentiallyWhenSizeIsUnknown(@TempDir Path dir) throws IOException {
        byte[] expected = payload(1_500);
        FakePort port = new FakePort(expected, 600);
        Path target = dir.resolve("world.zip");

        long received = new InstanceArchiveFetcher(port, 600, MAX_BYTES)
                .fetch("inst-1", "pkg.zip", target, 0, () -> false, null);

        assertEquals(expected.length, received);
        assertArrayEquals(expected, Files.readAllBytes(target));
    }

    @Test
    void failsWhenReceivedBytesDoNotMatchReportedSize(@TempDir Path dir) {
        // 面板报 1000 字节，实际只能吐 900：宁可不建图，也不能把半截存档写进存储
        FakePort port = new FakePort(payload(900), 400);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                new InstanceArchiveFetcher(port, 600, MAX_BYTES)
                        .fetch("inst-1", "pkg.zip", dir.resolve("w.zip"), 1_000, () -> false, null));
        assertTrue(error.getMessage().contains("不完整"), error.getMessage());
    }

    @Test
    void failsWhenPanelReturnsNothing(@TempDir Path dir) {
        FakePort port = new FakePort(new byte[0], 400);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                new InstanceArchiveFetcher(port, 600, MAX_BYTES)
                        .fetch("inst-1", "pkg.zip", dir.resolve("w.zip"), 0, () -> false, null));
        assertTrue(error.getMessage().contains("为空"), error.getMessage());
    }

    @Test
    void stopsAsSoonAsCancelSignalIsSet(@TempDir Path dir) throws IOException {
        FakePort port = new FakePort(payload(10_000), 500);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<Long> progress = new ArrayList<>();
        Path target = dir.resolve("w.zip");

        assertThrows(InstanceArchiveFetcher.CancelledException.class, () ->
                new InstanceArchiveFetcher(port, 500, MAX_BYTES).fetch(
                        "inst-1", "pkg.zip", target, 10_000,
                        cancelled::get,
                        (received, total) -> {
                            progress.add(received);
                            // 拉回第一块就取消：取消必须在分块之间立刻生效，而不是等整包拉完
                            cancelled.set(true);
                        }));

        assertEquals(1, progress.size(), "取消后不应继续拉取：" + progress);
        assertTrue(Files.size(target) < 10_000, "取消后只应留下已拉回的部分，由调用方清理");
    }

    @Test
    void failsWhenZipIsTruncatedEvenWithoutReportedSize(@TempDir Path dir) throws IOException {
        // 面板报不出大小时只能顺序探测到空块为止：这时少读一截也「看起来正常」，
        // 必须靠 zip 形态兜底（缺尾部中央目录记录 = 被截断），否则渲染解包时才炸
        byte[] complete = zipBytes("mods/a.jar");
        byte[] truncated = java.util.Arrays.copyOf(complete, complete.length - 40);
        Path target = dir.resolve("mods.zip");

        FakePort port = new FakePort(truncated, 4_000);
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                new InstanceArchiveFetcher(port, 4_000, MAX_BYTES)
                        .fetch("inst-1", "mods.zip", target, 0, () -> false, null));
        assertTrue(error.getMessage().contains("缺少 zip 尾部记录"), error.getMessage());

        // 完整包必须通过
        FakePort fine = new FakePort(complete, 4_000);
        long received = new InstanceArchiveFetcher(fine, 4_000, MAX_BYTES)
                .fetch("inst-1", "mods.zip", dir.resolve("ok.zip"), 0, () -> false, null);
        assertEquals(complete.length, received);
    }

    @Test
    void skipsZipCheckForNonZipPayload(@TempDir Path dir) throws IOException {
        // 非 zip 载荷不做形态校验，别把别的产物形态误判成损坏
        byte[] plain = "this is not a zip at all".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FakePort port = new FakePort(plain, 4_000);

        long received = new InstanceArchiveFetcher(port, 4_000, MAX_BYTES)
                .fetch("inst-1", "pkg.bin", dir.resolve("plain.bin"), 0, () -> false, null);

        assertEquals(plain.length, received);
    }

    /** 造一个最小的合法 zip（单条目），用于完整性判定的正反例。 */
    private static byte[] zipBytes(String entryName) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
            zip.write(new byte[256]);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void failsWhenArchiveExceedsSizeCap(@TempDir Path dir) {
        FakePort port = new FakePort(payload(3_000), 1_000);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                new InstanceArchiveFetcher(port, 1_000, 2_000)
                        .fetch("inst-1", "pkg.zip", dir.resolve("w.zip"), 3_000, () -> false, null));
        assertTrue(error.getMessage().contains("上限"), error.getMessage());
    }
}
