package online.yudream.plugin.voxelithmap.infrastructure.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 原版客户端 jar 下载器：地图未绑定 client jar 时，按存档版本自动补上 packs[0]。
 *
 * <p>采集与渲染都需要一个原版 client jar 作资源包栈底（packs[0]），否则引擎只能
 * 静态解析（模型来源会显示「缺 packs[0] 或 worker classpath，无法采集」）。
 * 下载源依次尝试：</p>
 * <ol>
 *   <li>Mojang 官方 launchermeta 元数据 → 版本 JSON → {@code downloads.client.url}；</li>
 *   <li>BMCLAPI 镜像（{@code /version/{v}/client}），官方源不可达时的国内回退。</li>
 * </ol>
 * 下载产物按版本缓存在运行根的 provision 目录，命中即不重复下载。
 */
public final class VanillaClientJarDownloader {

    private static final String MOJANG_MANIFEST =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String BMCLAPI_CLIENT = "https://bmclapi2.bangbang93.com/version/";

    /** 客户端 jar 最小体积：低于此值视为下载不完整，按失败处理。 */
    private static final long MIN_JAR_BYTES = 1024 * 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 下载指定版本的原版客户端 jar。
     *
     * @param version   存档版本名（如 {@code 1.21.8}）
     * @param targetDir 缓存目录（产物固定名 {@code client-<version>.jar}，命中即返回）
     * @param log       进度日志
     * @return 下载/缓存命中的 jar 路径
     * @throws IOException 网络、版本不存在或产物不完整
     */
    public Path download(String version, Path targetDir, Consumer<String> log) throws IOException {
        if (version == null || version.isBlank()) {
            throw new IOException("版本号为空，无法下载原版客户端 jar");
        }
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve("client-" + version + ".jar");
        if (Files.isRegularFile(target) && fileSize(target) >= MIN_JAR_BYTES) {
            log.accept("[voxelith-map] 命中原版客户端 jar 缓存: " + target.getFileName());
            return target;
        }
        IOException lastFailure;
        try {
            return downloadFromMojang(version, target, log);
        } catch (IOException e) {
            lastFailure = e;
            log.accept("[voxelith-map] 官方源下载失败（" + e.getMessage() + "），尝试 BMCLAPI 镜像");
        }
        try {
            return downloadDirect(URI.create(BMCLAPI_CLIENT + version + "/client"), target, version, log);
        } catch (IOException e) {
            IOException merged = new IOException("官方源: " + lastFailure.getMessage()
                    + "；BMCLAPI 镜像: " + e.getMessage());
            merged.addSuppressed(e);
            throw merged;
        }
    }

    private Path downloadFromMojang(String version, Path target, Consumer<String> log) throws IOException {
        log.accept("[voxelith-map] 从官方源解析客户端 jar 地址（" + version + "）");
        String versionJsonUrl = findMojangVersionUrl(version);
        JsonNode versionJson = getJson(URI.create(versionJsonUrl));
        String clientUrl = versionJson.path("downloads").path("client").path("url").asText("");
        if (clientUrl.isBlank()) {
            throw new IOException("版本元数据里没有 client 下载地址");
        }
        return downloadDirect(URI.create(clientUrl), target, version, log);
    }

    private String findMojangVersionUrl(String version) throws IOException {
        JsonNode manifest = getJson(URI.create(MOJANG_MANIFEST));
        for (JsonNode entry : manifest.path("versions")) {
            if (version.equals(entry.path("id").asText())) {
                String url = entry.path("url").asText("");
                if (!url.isBlank()) {
                    return url;
                }
            }
        }
        throw new IOException("官方版本清单里没有 " + version);
    }

    private Path downloadDirect(URI uri, Path target, String version, Consumer<String> log) throws IOException {
        log.accept("[voxelith-map] 下载原版客户端 jar（" + version + "）: " + uri);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "yudream-voxelith-map-plugin")
                .GET()
                .build();
        try {
            HttpResponse<Path> response = client.send(request,
                    HttpResponse.BodyHandlers.ofFile(Files.createTempFile("client-jar-", ".download"),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            Path downloaded = response.body();
            if (fileSize(downloaded) < MIN_JAR_BYTES) {
                throw new IOException("下载内容过小（" + fileSize(downloaded) + " 字节），不是有效的客户端 jar");
            }
            Files.move(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
            log.accept("[voxelith-map] 原版客户端 jar 就绪: " + target.getFileName()
                    + "（" + fileSize(target) / 1024 / 1024 + " MiB）");
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", e);
        }
    }

    private JsonNode getJson(URI uri) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "yudream-voxelith-map-plugin")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        try (InputStream body = response.body()) {
            return objectMapper.readTree(body);
        }
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }
}
