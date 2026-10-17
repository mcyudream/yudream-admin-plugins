package online.yudream.plugin.voxelithmap.infrastructure.world;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把「边车解不开」的存档压缩包规整成它能解的。
 *
 * <p>背景：边车是**未修改**的上游 {@code voxelith-server.jar}，它解包存档用的是
 * {@link java.util.zip.ZipInputStream}。JDK 在 {@code readLOC()} 里对「置了数据描述符
 * （general purpose bit 3）但不是 DEFLATED」的条目直接抛
 * {@code ZipException("only DEFLATED entries can have EXT descriptor")}；
 * 上游把这条例外包成 HTTP 400 返回，管理页看到的就是「生成预览底图失败」。</p>
 *
 * <p>触发它的是**第三方压缩工具**：这类实现边走边写，先把本地头发出去（CRC 与长度留空），
 * 写完再补 16 字节数据描述符；若它对某条目选了「仅存储」（不压缩），产出的就是
 * 「STORED + 数据描述符」。实测一份 26MB 的存档包：134 个条目<b>全部</b>是这种形态，
 * 于是边车一个存档都解不开（而 Windows 资源管理器、部分 7-Zip / libarchive 组合都会这么写）。</p>
 *
 * <p>为什么由插件来修：上游仓只读，且这条路必须把原包交给边车
 * （{@code POST /api/uploads/archive}，解包与编目都在它那边）。插件自己的解包器
 * {@link WorldArchiveExtractor} 走中央目录（{@code ZipFile}），本来就不受这个坑影响——
 * 也就是说「同样的包，渲染能跑而预览打不开」，只有预览需要这一步。</p>
 *
 * <p>规整代价刻意压到最低：探测只做一次顺序读、不解压；重写时 STORED 条目按中央目录里
 * 已知的长度与 CRC <b>原样直传</b>，不二次压缩，DEFLATED 条目保持流式压缩。</p>
 */
public final class WorldArchiveNormalizer {

    /** 本地文件头签名 {@code PK\x03\x04}。 */
    private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;
    /** 数据描述符标志位（general purpose bit 3）。 */
    private static final int FLAG_DATA_DESCRIPTOR = 0x0008;
    /** 读一个本地头需要的最小字节数：签名 4 + 标志 2 + 压缩方法 2 + 余量 2。 */
    private static final int HEADER_PROBE = 10;
    /** 标志位在本地头里的偏移（签名 4 + 版本 2 之后）。 */
    private static final int OFFSET_FLAG = 6;
    /** 压缩方法在本地头里的偏移。 */
    private static final int OFFSET_METHOD = 8;

    private WorldArchiveNormalizer() {
    }

    /**
     * 这个包边车能直接解吗——不能才需要 {@link #repairInto}。
     *
     * <p>只看本地头（不看中央目录）：JDK 抛异常的依据就是本地头里的标志位与压缩方法，
     * 而 {@link ZipEntry} 并不暴露标志位，所以直接顺序扫签名。反正是读文件，
     * 顺便还能覆盖「包里有 STORED+描述符的条目，但它不在第一个」这种情况。</p>
     *
     * @return {@code true} = 需要规整
     */
    public static boolean needsRepair(Path archive) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(archive), 1 << 16)) {
            byte[] buffer = new byte[1 << 16];
            // 条目可能跨缓冲区边界，末尾留 HEADER_PROBE-1 字节拼到下一块开头
            int carry = 0;
            while (true) {
                int read = input.read(buffer, carry, buffer.length - carry);
                if (read <= 0) {
                    break;
                }
                int end = carry + read;
                for (int offset = 0; offset + HEADER_PROBE <= end; offset++) {
                    if (isStoredWithDataDescriptor(buffer, offset)) {
                        return true;
                    }
                }
                carry = Math.min(HEADER_PROBE - 1, end);
                System.arraycopy(buffer, end - carry, buffer, 0, carry);
            }
        }
        return false;
    }

    /**
     * 重写一份边车读得懂的等价包：条目名、内容、目录结构与时间戳不变，
     * 只是本地头不再出现「STORED + 数据描述符」。
     *
     * @param archive     原包
     * @param destination 输出路径（写 {@code .part} 再改名，避免半个包被当成成品）
     * @return {@code destination}
     */
    public static Path repairInto(Path archive, Path destination) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        Files.createDirectories(target.getParent());
        byte[] buffer = new byte[1 << 16];
        try (ZipFile source = new ZipFile(archive.toFile(), entryNameCharset());
             ZipOutputStream out = new ZipOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(temporary), 1 << 16),
                     StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                copyEntry(source, entries.nextElement(), out, buffer);
            }
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /**
     * 条目名的解码字符集，与上游边车同一口径：中文 Windows 的资源管理器按 ANSI 码页（GBK）
     * 写名字且不置 UTF-8 标志位，新式工具写 UTF-8 并置标志位。{@link ZipFile} 对置了标志位的
     * 条目一律按 UTF-8 解（与这里的 charset 无关），其余才用它——所以配系统码页最稳。
     *
     * <p>写出去的一律是 UTF-8（{@link ZipOutputStream} 会置 UTF-8 标志位），
     * 因此边车按什么本机码页读都还原得回来。</p>
     */
    static Charset entryNameCharset() {
        return Charset.forName(System.getProperty("sun.jnu.encoding", "GBK"), StandardCharsets.UTF_8);
    }

    private static void copyEntry(ZipFile source, ZipEntry entry, ZipOutputStream out, byte[] buffer)
            throws IOException {
        ZipEntry target = new ZipEntry(entry.getName());
        if (entry.getTime() != -1) {
            target.setTime(entry.getTime());
        }
        if (entry.isDirectory()) {
            // 零字节目录条目显式按 STORED 写：走 DEFLATED 会又落进「描述符」那条路
            target.setMethod(ZipEntry.STORED);
            target.setSize(0);
            target.setCompressedSize(0);
            target.setCrc(0);
            out.putNextEntry(target);
            out.closeEntry();
            return;
        }
        long size = entry.getSize();
        if (entry.getMethod() == ZipEntry.STORED && size >= 0 && entry.getCrc() >= 0) {
            // 中央目录里的长度与 CRC 是准的：内容原样直传，不解压也不二次压缩
            target.setMethod(ZipEntry.STORED);
            target.setSize(size);
            target.setCompressedSize(size);
            target.setCrc(entry.getCrc());
        } else {
            target.setMethod(ZipEntry.DEFLATED);
        }
        out.putNextEntry(target);
        try (InputStream input = source.getInputStream(entry)) {
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        out.closeEntry();
    }

    /** 这个偏移处是不是一个「STORED + 数据描述符」的本地头。 */
    private static boolean isStoredWithDataDescriptor(byte[] buffer, int offset) {
        if (littleEndian32(buffer, offset) != LOCAL_HEADER_SIGNATURE) {
            return false;
        }
        int flag = littleEndian16(buffer, offset + OFFSET_FLAG);
        int method = littleEndian16(buffer, offset + OFFSET_METHOD);
        return (flag & FLAG_DATA_DESCRIPTOR) != 0 && method == ZipEntry.STORED;
    }

    /** 本地头里的整数字段是小端。 */
    private static int littleEndian16(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8);
    }

    private static int littleEndian32(byte[] buffer, int offset) {
        return littleEndian16(buffer, offset) | (littleEndian16(buffer, offset + 2) << 16);
    }
}
