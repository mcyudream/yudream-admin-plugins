package online.yudream.plugin.voxelithmap.infrastructure.world;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 从存档 {@code level.dat} 里读「存档版本名」（NBT {@code Data.Name}，如 {@code 1.21.8}）。
 *
 * <p>引擎按「资源包文件名 → 存档版本」判定采集版本，其中存档版本就来自这个字段——
 * 这里用同源的判据，只为在客户端 jar 缺失/无版本文件名时仍能确定采集与下载版本，
 * 不是另起炉灶猜版本。</p>
 *
 * <p>实现是启发式字节扫描而非完整 NBT 解析：level.dat 的结构高度稳定，
 * {@code TAG_String("Name")}（0x08 00 04 'N' 'a' 'm' 'e' + 2 字节长度 + UTF-8 值）
 * 在整份 NBT 里唯一且靠前，扫到第一个能匹配 MC 版本号形态的值即认定成功；
 * 解不出返回 {@code null}，调用方必须能接受 null（退回引擎自动判定）。</p>
 */
public final class LevelDatReader {

    /** MC 版本号形态（1.21.8 / 1.20.1 / 25w14a 之类）。 */
    private static final Pattern VERSION_VALUE = Pattern.compile("^\\d+\\.\\d+(\\.\\d+)?([-_.]\\w+)?$");

    /** TAG_String 头：类型 0x08 + 名长 0x0004 + "Name"。 */
    private static final byte[] NAME_STRING_HEADER = {
            0x08, 0x00, 0x04, 'N', 'a', 'm', 'e',
    };

    private LevelDatReader() {
    }

    /** 从已解压的 NBT 字节读版本名（导入链路直接读 zip 内的 level.dat 时用）。 */
    public static String readVersionName(byte[] nbt) {
        return scanVersionName(nbt);
    }

    /** 读取存档版本名；文件缺失、解压失败、解析不出都返回 {@code null}。 */
    public static String readVersionName(Path levelDat) {
        if (levelDat == null || !Files.isRegularFile(levelDat)) {
            return null;
        }
        byte[] nbt;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(levelDat))) {
            nbt = gunzipOrRaw(raw);
        } catch (IOException e) {
            return null;
        }
        return scanVersionName(nbt);
    }

    /** level.dat 是 gzip 压缩的 NBT；老版本可能不压缩，解不开就按原始字节处理。 */
    private static byte[] gunzipOrRaw(InputStream raw) throws IOException {
        raw.mark(2);
        int first = raw.read();
        int second = raw.read();
        raw.reset();
        if (first == 0x1f && second == (0x8b & 0xff)) {
            try (GZIPInputStream gzip = new GZIPInputStream(raw)) {
                return readAll(gzip);
            }
        }
        return readAll(raw);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String scanVersionName(byte[] nbt) {
        int index = indexOf(nbt, NAME_STRING_HEADER, 0);
        while (index >= 0) {
            int valueLengthAt = index + NAME_STRING_HEADER.length;
            if (valueLengthAt + 2 <= nbt.length) {
                int length = ((nbt[valueLengthAt] & 0xff) << 8) | (nbt[valueLengthAt + 1] & 0xff);
                int valueAt = valueLengthAt + 2;
                if (length > 0 && length <= 64 && valueAt + length <= nbt.length) {
                    String value = new String(nbt, valueAt, length, StandardCharsets.UTF_8);
                    if (VERSION_VALUE.matcher(value).matches()) {
                        return value;
                    }
                }
            }
            index = indexOf(nbt, NAME_STRING_HEADER, index + 1);
        }
        return null;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            boolean matched = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return i;
            }
        }
        return -1;
    }

    /** 仅供日志与测试对比用：规范化版本显示。 */
    public static String normalize(String version) {
        return version == null ? null : version.trim().toLowerCase(Locale.ROOT);
    }
}
