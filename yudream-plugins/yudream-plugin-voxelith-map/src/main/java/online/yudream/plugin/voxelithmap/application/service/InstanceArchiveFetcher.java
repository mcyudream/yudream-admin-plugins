package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.plugin.voxelithmap.domain.port.InstanceFilePort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * 把面板实例里的打包产物分块拉回本地暂存文件。
 *
 * <p>边拉边写、内存只留单块缓冲：GB 级存档整包读进堆里会把插件进程打爆。三处刻意设计：</p>
 * <ol>
 *   <li><b>自适应分块</b>：节点/面板的单次返回上限可能小于请求量，返回量小于请求量即认为撞到上限，
 *       把后续请求长度收敛到实际返回量——只是把窗口拆细，不会截断数据。</li>
 *   <li><b>完整性校验</b>：面板给的字节数可信时（{@code total > 0}），实收与期望不符直接失败，
 *       宁可报错也不能把半截存档拿去建图（建图后存档与记录不一致，只能重导）。</li>
 *   <li><b>可取消</b>：取消信号在分块之间检查，抛出 {@link CancelledException} 由调用方收敛为
 *       「已取消」并清理暂存文件。</li>
 * </ol>
 *
 * <p>独立成类是为了能单测：这三条都属于「错了也看不出来」的逻辑（少读一段、多算一截），
 * 必须在没有真实面板实例的情况下用替身覆盖。</p>
 */
public final class InstanceArchiveFetcher {

    /** 取消信号触发的异常：调用方据此把任务落为「已取消」而不是「失败」。 */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("导入已取消");
        }
    }

    /** 进度回调：已拉回字节数 + 面板给出的总字节数（未知为 0）。 */
    public interface Progress {
        void onProgress(long received, long total);
    }

    private final InstanceFilePort port;
    private final int chunkBytes;
    private final long maxBytes;

    public InstanceArchiveFetcher(InstanceFilePort port, int chunkBytes, long maxBytes) {
        this.port = port;
        this.chunkBytes = Math.max(1, chunkBytes);
        this.maxBytes = maxBytes > 0 ? maxBytes : Long.MAX_VALUE;
    }

    /**
     * 拉回整包写到 {@code target}（覆盖写）。
     *
     * @param instanceId 实例 id
     * @param panelPath  面板侧打包产物路径
     * @param target     本地暂存文件
     * @param total      面板给出的总字节数；{@code <= 0} 表示未知（顺序探测到空块为止）
     * @param cancelled  取消信号
     * @param progress   进度回调（可能被高频调用，调用方自行节流落库）
     * @return 实际写入的字节数
     * @throws IOException        写盘失败
     * @throws CancelledException  取消信号触发
     */
    public long fetch(String instanceId, String panelPath, Path target, long total,
                      BooleanSupplier cancelled, Progress progress) throws IOException {
        long received = 0;
        int chunk = chunkBytes;
        try (OutputStream output = Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new CancelledException();
                }
                int want = total > 0
                        ? (int) Math.max(1, Math.min(chunk, total - received))
                        : chunk;
                byte[] bytes = port.readArchiveRange(instanceId, panelPath, received, want);
                if (bytes == null || bytes.length == 0) {
                    break;
                }
                if (bytes.length < want) {
                    chunk = Math.max(1, Math.min(chunk, bytes.length));
                }
                output.write(bytes);
                received += bytes.length;
                if (received > maxBytes) {
                    throw new IllegalStateException("打包产物超过上限（" + maxBytes + " 字节）");
                }
                if (progress != null) {
                    progress.onProgress(received, total);
                }
            }
        }
        if (received == 0) {
            throw new IllegalStateException("面板返回的打包产物为空");
        }
        if (total > 0 && received != total) {
            throw new IllegalStateException(
                    "打包产物不完整（期望 " + total + " 字节，实收 " + received + " 字节），请重试");
        }
        requireCompleteZip(target, received);
        if (progress != null) {
            progress.onProgress(received, total);
        }
        return received;
    }

    /** zip 本地文件头与中央目录结束记录（EOCD）的签名。 */
    private static final byte[] ZIP_LOCAL_HEAD = {0x50, 0x4B, 0x03, 0x04};

    private static final byte[] ZIP_EOCD = {0x50, 0x4B, 0x05, 0x06};

    /** EOCD 之前最多还有 64KiB 的 zip 注释，扫描窗口按它取。 */
    private static final int ZIP_TAIL_WINDOW = 66_000;

    /**
     * 兜底校验：产物是不是一个**完整的 zip**。
     *
     * <p>面板给不出产物大小时（{@code total <= 0}）只能顺序探测到空块为止，无法按字节数核对；
     * 这时一次提前结束的读就会得到「看起来正常、其实被截断」的包，直到渲染阶段解包才炸
     * （解包器报 {@code 模组包读取失败: Unexpected end of ZLIB input stream}）。判据取两处：
     * 文件头是 zip 本地头，且尾部窗口里能找到中央目录结束记录——缺了它必然是被截断的。</p>
     *
     * <p>非 zip 载荷（头部不是 PK）不做这项校验，避免把别的产物形态误判为损坏。</p>
     */
    static void requireCompleteZip(Path file, long size) throws IOException {
        if (size < ZIP_LOCAL_HEAD.length + 4) {
            return;
        }
        byte[] head = new byte[ZIP_LOCAL_HEAD.length];
        try (InputStream input = Files.newInputStream(file)) {
            if (input.readNBytes(head, 0, head.length) < head.length || !Arrays.equals(head, ZIP_LOCAL_HEAD)) {
                return;
            }
        }
        int window = (int) Math.min(size, ZIP_TAIL_WINDOW);
        byte[] tail = new byte[window];
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(file)) {
            channel.position(size - window);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(tail);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // 读满窗口即可
            }
        }
        for (int i = tail.length - ZIP_EOCD.length; i >= 0; i--) {
            boolean matched = true;
            for (int k = 0; k < ZIP_EOCD.length; k++) {
                if (tail[i + k] != ZIP_EOCD[k]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return;
            }
        }
        throw new IllegalStateException("打包产物不完整（缺少 zip 尾部记录）：传输可能被截断，请重试");
    }
}
