package online.yudream.base.plugin.mcwiki.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McVersionInfo;

public final class MojangClient {
    private static final URI MANIFEST = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private final HttpClient http;
    private final ObjectMapper mapper;
    public MojangClient(HttpClient http, ObjectMapper mapper) { this.http = Objects.requireNonNull(http); this.mapper = Objects.requireNonNull(mapper); }
    public List<McVersionInfo> manifest() {
        try {
            HttpRequest request = HttpRequest.newBuilder(MANIFEST).timeout(Duration.ofSeconds(30)).GET().build();
            JsonNode root = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofByteArray()).body());
            List<McVersionInfo> result = new ArrayList<>();
            String latest = root.path("latest").path("release").asText("");
            for (JsonNode node : root.path("versions")) result.add(new McVersionInfo(node.path("id").asText(), node.path("type").asText(), node.path("releaseTime").asText(null), node.path("url").asText(null), latest.equals(node.path("id").asText())));
            return List.copyOf(result);
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("无法获取 Mojang 版本清单", ex);
        }
    }
    public byte[] download(String url) {
        try { return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body(); }
        catch (IOException | InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("无法下载 Minecraft 客户端资源", ex); }
    }
    public JsonNode versionDetails(String url) {
        try { return mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body()); }
        catch (IOException | InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("无法获取 Minecraft 版本详情", ex); }
    }
}
