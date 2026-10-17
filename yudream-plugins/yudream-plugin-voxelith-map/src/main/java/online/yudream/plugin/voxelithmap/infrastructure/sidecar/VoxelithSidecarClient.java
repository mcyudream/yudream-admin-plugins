package online.yudream.plugin.voxelithmap.infrastructure.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 边车的 HTTP 客户端。
 *
 * <p>只用 Voxelith 已发布的 REST 契约（见其 {@code UploadController}/`MapController`），
 * 不猜任何内部结构：</p>
 * <ul>
 *   <li>{@code POST /api/uploads/archive}（multipart 字段 {@code file}）—— 上传存档 zip，
 *       Voxelith 自己负责解包、跳过外壳目录、按系统码页处理中文名；包的形态若被它的
 *       解包器拒收（见 {@code WorldArchiveNormalizer}），得由调用方先规整；</li>
 *   <li>{@code POST /api/uploads/local}（JSON {@code {path,name}}）—— 注册已在盘上的存档目录，
 *       省掉一次大文件搬运；</li>
 *   <li>{@code POST|GET /api/uploads/{id}/preview} —— 预览是异步的，POST 触发、GET 轮询；</li>
 *   <li>{@code GET /api/uploads/{id}/preview.png}、{@code /window.png} —— 底图与框选窗口图。</li>
 * </ul>
 */
public final class VoxelithSidecarClient {

    /** 存档上传可能很大，读超时给足；连接超时保持短，便于快速失败。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofHours(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final VoxelithSidecar sidecar;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public VoxelithSidecarClient(VoxelithSidecar sidecar, ObjectMapper objectMapper) {
        this.sidecar = sidecar;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // 只用 HTTP/1.1：Voxelith 跑 Spring Boot 内嵌 Tomcat，避免协商开销
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** 边车是否可用。 */
    public boolean available() {
        return sidecar.ensureRunning();
    }

    /**
     * 上传存档 zip（多部分表单流式发送，不把 GB 级文件读进内存）。
     *
     * @param archive 本地 zip 路径
     * @param name    展示名；可为 {@code null}
     */
    public Upload uploadArchive(Path archive, String name) throws IOException {
        return uploadArchive(archive, name, archive.getFileName().toString());
    }

    /**
     * 上传存档 zip，并指定报给边车的文件名。
     *
     * <p>文件名在这里不是装饰：边车按 {@code uniqueId(stripExtension(fileName))} 推导存档 id，
     * 所以拿本地临时名（例如规整后的 {@code world-normalized.zip}）报上去会改掉 id。
     * 「内容换过、本地文件名也换过」的调用方要用这个重载把原名报回去。</p>
     *
     * @param fileName 报给边车的文件名；{@code null}/空则用 {@code archive} 的文件名
     */
    public Upload uploadArchive(Path archive, String name, String fileName) throws IOException {
        requireAvailable();
        if (!Files.isRegularFile(archive)) {
            throw new IOException("存档文件不存在: " + archive);
        }
        String boundary = "----yudreamVoxelithMap" + Long.toHexString(System.nanoTime());
        String fieldName = "file";
        String filename = fileName == null || fileName.isBlank()
                ? archive.getFileName().toString() : fileName;
        String prologue = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/zip\r\n\r\n";
        String epilogue = "\r\n--" + boundary + "--\r\n";
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(prologue, StandardCharsets.UTF_8),
                HttpRequest.BodyPublishers.ofFile(archive),
                HttpRequest.BodyPublishers.ofString(epilogue, StandardCharsets.UTF_8));
        URI uri = sidecar.baseUri().resolve("/api/uploads/archive"
                + (name == null || name.isBlank() ? "" : "?name=" + encode(name)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(UPLOAD_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body)
                .build();
        return Upload.from(call(request));
    }

    /** 注册已在本地磁盘上的存档目录（目录需含 level.dat）。 */
    public Upload registerLocalWorld(Path worldDir, String name) throws IOException {
        requireAvailable();
        String payload = objectMapper.writeValueAsString(
                java.util.Map.of("path", worldDir.toAbsolutePath().toString(),
                        "name", name == null ? worldDir.getFileName().toString() : name));
        HttpRequest request = HttpRequest.newBuilder(sidecar.baseUri().resolve("/api/uploads/local"))
                .timeout(READ_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return Upload.from(call(request));
    }

    /** 列出已编目的存档。 */
    public List<Upload> uploads() throws IOException {
        requireAvailable();
        JsonNode root = call(HttpRequest.newBuilder(sidecar.baseUri().resolve("/api/uploads"))
                .timeout(READ_TIMEOUT).GET().build());
        List<Upload> result = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                result.add(Upload.from(node));
            }
        }
        return List.copyOf(result);
    }

    /** 删除边车侧的存档（连同它的预览缓存）。 */
    public void deleteUpload(String uploadId) throws IOException {
        requireAvailable();
        call(HttpRequest.newBuilder(sidecar.baseUri()
                        .resolve("/api/uploads/" + encode(uploadId)))
                .timeout(READ_TIMEOUT).DELETE().build());
    }

    /** 触发预览生成（幂等：已在跑或已就绪会直接返回当前状态）。 */
    public PreviewStatus requestPreview(String uploadId, String dimension) throws IOException {
        requireAvailable();
        return PreviewStatus.from(call(HttpRequest.newBuilder(sidecar.baseUri()
                        .resolve("/api/uploads/" + encode(uploadId) + "/preview?dimension=" + encode(dimension)))
                .timeout(READ_TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build()));
    }

    /** 查询预览状态。 */
    public PreviewStatus previewStatus(String uploadId, String dimension) throws IOException {
        requireAvailable();
        return PreviewStatus.from(call(HttpRequest.newBuilder(sidecar.baseUri()
                        .resolve("/api/uploads/" + encode(uploadId) + "/preview?dimension=" + encode(dimension)))
                .timeout(READ_TIMEOUT).GET().build()));
    }

    /**
     * 取预览底图 PNG。
     *
     * @param step 每像素方块数（2 的幂）；{@code null} = 最细一级
     * @return PNG 字节；未就绪时返回空
     */
    public Optional<byte[]> previewPng(String uploadId, String dimension, Integer step) throws IOException {
        requireAvailable();
        String path = "/api/uploads/" + encode(uploadId) + "/preview.png?dimension=" + encode(dimension)
                + (step == null ? "" : "&step=" + step);
        return binary(HttpRequest.newBuilder(sidecar.baseUri().resolve(path))
                .timeout(READ_TIMEOUT).GET().build());
    }

    /**
     * 取框选窗口图（同步渲染 + 磁盘缓存，同一窗口第二次直接命中）。
     *
     * @param size 窗口像素边长，取值 [16,1024]
     */
    public Optional<byte[]> windowPng(String uploadId, String dimension, int minX, int minZ, int size, int step)
            throws IOException {
        requireAvailable();
        String path = "/api/uploads/" + encode(uploadId) + "/window.png?dimension=" + encode(dimension)
                + "&minX=" + minX + "&minZ=" + minZ + "&size=" + size + "&step=" + step;
        return binary(HttpRequest.newBuilder(sidecar.baseUri().resolve(path))
                .timeout(Duration.ofMinutes(5)).GET().build());
    }

    /** 边车发现的渲染默认值（client jar 候选、models 文件、默认区块上限）。 */
    public JsonNode renderDefaults() throws IOException {
        requireAvailable();
        return call(HttpRequest.newBuilder(sidecar.baseUri().resolve("/api/render/defaults"))
                .timeout(READ_TIMEOUT).GET().build());
    }

    private void requireAvailable() throws IOException {
        if (!sidecar.ensureRunning()) {
            throw new IOException("Voxelith 边车不可用"
                    + (sidecar.lastError() == null ? "" : "：" + sidecar.lastError()));
        }
    }

    private JsonNode call(HttpRequest request) throws IOException {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("调用边车被中断", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("边车返回 HTTP " + response.statusCode() + "：" + summarize(response.body()));
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new IOException("边车响应不是合法 JSON：" + summarize(body), e);
        }
    }

    private Optional<byte[]> binary(HttpRequest request) throws IOException {
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("调用边车被中断", e);
        }
        // Voxelith 在预览未就绪时返回 404 空体——这是「还没好」，不是错误
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("边车返回 HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        return body == null || body.length == 0 ? Optional.empty() : Optional.of(body);
    }

    private static String summarize(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 边车编目的存档。
     *
     * @param id          存档 id（Voxelith 生成，形如 {@code world-11b92}）
     * @param name        展示名
     * @param worldDir    解出的存档目录（本地路径）
     * @param versionName 存档版本名（{@code level.dat} 里的 Version.Name）
     * @param dataVersion 存档 data version
     * @param dimensions  可用维度 id 列表
     */
    public record Upload(String id, String name, String worldDir, String versionName, int dataVersion,
                         List<String> dimensions) {

        static Upload from(JsonNode node) {
            List<String> dimensions = new ArrayList<>();
            for (JsonNode item : node.path("dimensions")) {
                dimensions.add(item.asText());
            }
            return new Upload(
                    node.path("id").asText(""),
                    node.path("name").asText(""),
                    node.path("worldDir").asText(""),
                    node.path("versionName").asText(""),
                    node.path("dataVersion").asInt(0),
                    List.copyOf(dimensions));
        }

        public String displayVersion() {
            if (versionName == null || versionName.isBlank()) {
                return dataVersion > 0 ? "data " + dataVersion : "未知版本";
            }
            return versionName;
        }
    }

    /**
     * 预览状态。
     *
     * @param state   {@code IDLE}/{@code RUNNING}/{@code READY}/{@code FAILED}
     * @param done    已解析 region 数
     * @param total   region 总数
     * @param message 状态文本
     * @param meta    地理信息（就绪后才有）
     */
    public record PreviewStatus(String state, int done, int total, String message, PreviewMeta meta) {

        static PreviewStatus from(JsonNode node) {
            JsonNode metaNode = node.path("meta");
            return new PreviewStatus(
                    node.path("state").asText("IDLE"),
                    node.path("done").asInt(0),
                    node.path("total").asInt(0),
                    node.path("message").asText(""),
                    metaNode.isMissingNode() || metaNode.isNull() ? null : PreviewMeta.from(metaNode));
        }

        public boolean ready() {
            return "READY".equals(state);
        }

        public boolean failed() {
            return "FAILED".equals(state);
        }

        public boolean running() {
            return "RUNNING".equals(state);
        }

        /** 进度百分比（无法计算时返回 -1）。 */
        public int percent() {
            if (ready()) {
                return 100;
            }
            if (total <= 0) {
                return running() ? 0 : -1;
            }
            return Math.min(99, (int) Math.round(100.0 * done / total));
        }
    }

    /**
     * 预览地理信息（与 Voxelith {@code PreviewService.PreviewMeta} 字段一一对应）。
     *
     * <p>前端框选后，用它把「画布像素」换算成方块范围：</p>
     * <pre>
     * blockX = originX + pixelX * step
     * blockZ = originZ + pixelY * step
     * </pre>
     *
     * @param version       缓存 schema 版本（{@code 3}）
     * @param dimension     维度 id
     * @param originX       左上角像素对应的方块 X
     * @param originZ       左上角像素对应的方块 Z
     * @param step          每像素方块数（2 的幂）
     * @param width         像素宽
     * @param depth         像素深
     * @param blockMinX     有内容区块的方块包围盒（含端点）
     * @param blockMinZ     同上
     * @param blockMaxX     同上
     * @param blockMaxZ     同上
     * @param regionCount   region 数
     * @param chunkCount    区块数
     * @param minSurfaceY   抽样列地表最低高度（{@code Integer.MIN_VALUE} = 无数据）
     * @param maxSurfaceY   抽样列地表最高高度
     * @param levels        各层金字塔的 {@code {step,width,depth}}
     */
    public record PreviewMeta(int version, String dimension, int originX, int originZ, int step, int width,
                              int depth, int blockMinX, int blockMinZ, int blockMaxX, int blockMaxZ,
                              long regionCount, long chunkCount, int minSurfaceY, int maxSurfaceY,
                              List<Level> levels) {

        static PreviewMeta from(JsonNode node) {
            List<Level> levels = new ArrayList<>();
            for (JsonNode item : node.path("levels")) {
                levels.add(new Level(item.path("step").asInt(1), item.path("width").asInt(0),
                        item.path("depth").asInt(0)));
            }
            return new PreviewMeta(
                    node.path("version").asInt(0),
                    node.path("dimension").asText(""),
                    node.path("originX").asInt(0),
                    node.path("originZ").asInt(0),
                    Math.max(1, node.path("step").asInt(1)),
                    node.path("width").asInt(0),
                    node.path("depth").asInt(0),
                    node.path("blockMinX").asInt(0),
                    node.path("blockMinZ").asInt(0),
                    node.path("blockMaxX").asInt(0),
                    node.path("blockMaxZ").asInt(0),
                    node.path("regionCount").asLong(0),
                    node.path("chunkCount").asLong(0),
                    node.path("minSurfaceY").asInt(Integer.MIN_VALUE),
                    node.path("maxSurfaceY").asInt(Integer.MIN_VALUE),
                    List.copyOf(levels));
        }

        /**
         * 建议的最低渲染高度。
         *
         * <p>Voxelith 的取值口径：{@code minSurfaceY - 16} 并按 section（16 格）对齐。
         * 存档高度差异极大，写死的默认值会把整片几何裁掉，所以框选流程必须回填这个值。</p>
         *
         * @return 建议值；无地表数据时返回空
         */
        public Optional<Integer> suggestedMinY() {
            if (minSurfaceY == Integer.MIN_VALUE) {
                return Optional.empty();
            }
            int aligned = Math.floorDiv(minSurfaceY - 16, 16) * 16;
            return Optional.of(aligned);
        }

        /** 是否有地表高度信息。 */
        public boolean hasSurfaceData() {
            return minSurfaceY != Integer.MIN_VALUE;
        }

        /** 每层金字塔的尺寸。 */
        public record Level(int step, int width, int depth) {
        }
    }
}
