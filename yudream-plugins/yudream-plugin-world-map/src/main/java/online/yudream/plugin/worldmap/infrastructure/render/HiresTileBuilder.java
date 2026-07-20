package online.yudream.plugin.worldmap.infrastructure.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

/**
 * 单个 hires tile 的数据累积器：按需扩容的扁平数组 + CONTRACT §4 JSON 序列化（gzip）。
 * 不透明面片与半透明面片（水等）分段累积，分别输出（前端分两个 mesh 渲染）。
 * float 保留 4 位小数并去尾零以减小体积。
 */
final class HiresTileBuilder {

    private final Section opaque = new Section();
    private final Section translucent = new Section();

    boolean isEmpty() {
        return opaque.isEmpty() && translucent.isEmpty();
    }

    /**
     * 追加一个面片：4 顶点 + 2 三角形索引。
     *
     * @param pos12       世界绝对坐标（4 顶点 xyz）
     * @param uv8         atlas 空间 uv（4 顶点）
     * @param r,g,b       顶点染色（整面同色）
     * @param ao4         逐顶点 AO 系数
     * @param bl4         逐顶点方块光 0..15
     * @param sl4         逐顶点天空光 0..15
     * @param translucent 是否半透明面（水等）
     */
    void addQuad(float[] pos12, float[] uv8, float r, float g, float b,
                 float[] ao4, float[] bl4, float[] sl4, boolean translucent) {
        (translucent ? this.translucent : opaque).addQuad(pos12, uv8, r, g, b, ao4, bl4, sl4);
    }

    /** 序列化为 CONTRACT §4 的 gzip JSON。 */
    byte[] toGzipJson(int tx, int tz) {
        StringBuilder sb = new StringBuilder(256 + (opaque.vertexCount + translucent.vertexCount) * 48);
        sb.append("{\"x\":").append(tx).append(",\"z\":").append(tz);
        opaque.appendTo(sb);
        if (!translucent.isEmpty()) {
            StringBuilder tsb = new StringBuilder(translucent.vertexCount * 48);
            translucent.appendTo(tsb);
            // 子对象内首字段去掉前导逗号
            sb.append(",\"translucent\":{").append(tsb.substring(1)).append('}');
        }
        sb.append('}');
        byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 4 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("hires tile gzip 压缩失败", e);
        }
        return bos.toByteArray();
    }

    /** 单段（不透明/半透明）几何累积与序列化。 */
    private static final class Section {
        private float[] positions = new float[4096];
        private int posCount;
        private float[] uvs = new float[2048];
        private int uvCount;
        private float[] colors = new float[4096];
        private int colorCount;
        private float[] ao = new float[1024];
        private int aoCount;
        private float[] blockLight = new float[1024];
        private float[] skyLight = new float[1024];
        private int lightCount;
        private int[] indices = new int[4096];
        private int indexCount;
        private int vertexCount;

        boolean isEmpty() {
            return vertexCount == 0;
        }

        void addQuad(float[] pos12, float[] uv8, float r, float g, float b,
                     float[] ao4, float[] bl4, float[] sl4) {
            int base = vertexCount;
            for (int i = 0; i < 4; i++) {
                addPosition(pos12[i * 3], pos12[i * 3 + 1], pos12[i * 3 + 2]);
                addUv(uv8[i * 2], uv8[i * 2 + 1]);
                addColor(r, g, b);
                addLight(ao4[i], bl4[i], sl4[i]);
            }
            addIndex(base);
            addIndex(base + 1);
            addIndex(base + 2);
            addIndex(base);
            addIndex(base + 2);
            addIndex(base + 3);
            vertexCount += 4;
        }

        /** 追加字段到 JSON 对象内（不含花括号）。首段无前导逗号，调用方保证顺序。 */
        void appendTo(StringBuilder sb) {
            appendFloatArray(sb, "positions", positions, posCount);
            appendIntArray(sb, "indices", indices, indexCount);
            appendFloatArray(sb, "uvs", uvs, uvCount);
            appendFloatArray(sb, "colors", colors, colorCount);
            appendFloatArray(sb, "ao", ao, aoCount);
            appendFloatArray(sb, "blocklight", blockLight, lightCount);
            appendFloatArray(sb, "skylight", skyLight, lightCount);
        }

        private void addPosition(float x, float y, float z) {
            if (posCount + 3 > positions.length) {
                positions = Arrays.copyOf(positions, positions.length * 2);
            }
            positions[posCount++] = x;
            positions[posCount++] = y;
            positions[posCount++] = z;
        }

        private void addUv(float u, float v) {
            if (uvCount + 2 > uvs.length) {
                uvs = Arrays.copyOf(uvs, uvs.length * 2);
            }
            uvs[uvCount++] = u;
            uvs[uvCount++] = v;
        }

        private void addColor(float r, float g, float b) {
            if (colorCount + 3 > colors.length) {
                colors = Arrays.copyOf(colors, colors.length * 2);
            }
            colors[colorCount++] = r;
            colors[colorCount++] = g;
            colors[colorCount++] = b;
        }

        private void addLight(float a, float bl, float sl) {
            if (aoCount + 1 > ao.length) {
                ao = Arrays.copyOf(ao, ao.length * 2);
                blockLight = Arrays.copyOf(blockLight, blockLight.length * 2);
                skyLight = Arrays.copyOf(skyLight, skyLight.length * 2);
            }
            ao[aoCount++] = a;
            blockLight[lightCount] = bl;
            skyLight[lightCount] = sl;
            lightCount++;
        }

        private void addIndex(int i) {
            if (indexCount + 1 > indices.length) {
                indices = Arrays.copyOf(indices, indices.length * 2);
            }
            indices[indexCount++] = i;
        }
    }

    private static void appendFloatArray(StringBuilder sb, String name, float[] arr, int n) {
        sb.append(",\"").append(name).append("\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendFloat(sb, arr[i]);
        }
        sb.append(']');
    }

    private static void appendIntArray(StringBuilder sb, String name, int[] arr, int n) {
        sb.append(",\"").append(name).append("\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arr[i]);
        }
        sb.append(']');
    }

    /** float 保留 4 位小数；整数值直接输出整数形式。 */
    private static void appendFloat(StringBuilder sb, float v) {
        float r = Math.round(v * 10_000f) / 10_000f;
        if (r == (int) r && Math.abs(r) < 16_777_216f) {
            sb.append((int) r);
        } else {
            sb.append(Float.toString(r));
        }
    }
}
