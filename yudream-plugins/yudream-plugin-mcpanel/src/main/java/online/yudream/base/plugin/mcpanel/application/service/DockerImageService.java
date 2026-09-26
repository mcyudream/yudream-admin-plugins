package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Docker 镜像目录：按 **Java 运行时版本** 命名（OpenJDK 8/11/17/21/25），
 * 与 MC 核心（Paper/Fabric…）无关——核心是服务端软件，镜像是 JVM 运行环境。
 * 内置华为云 SWR JRE 源；管理员可增删改标签。
 */
public class DockerImageService {

    private static final String COLLECTION = "mcpanel_docker_images";
    private static final int PAGE_SIZE = 200;
    private static final Pattern ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9-]{0,63}$");

    /** 出厂内置：Java 版本 → JRE 镜像（华为云 SWR ddn-k8s，国内拉取友好）。 */
    private static final String[][] BUILTIN = {
            {"java8", "Java 8（JRE）",
                    "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/adoptopenjdk/openjdk8:latest", "8"},
            {"java11", "Java 11（JRE）",
                    "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/adoptopenjdk/openjdk11:debian", "11"},
            {"java17", "Java 17（JRE）",
                    "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/thingsboard/openjdk17:bookworm-slim", "17"},
            {"java21", "Java 21（JRE）",
                    "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/khipu/openjdk21-alpine:latest", "21"},
            {"java25", "Java 25（JRE）",
                    "swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/niceos/openjdk25:latest", "25"},
    };

    private final PluginDocumentStore documents;

    public DockerImageService(PluginDocumentStore documents) {
        this.documents = documents;
    }

    public Map<String, Object> page(int page, int size, String keyword) {
        List<Map<String, Object>> filtered = loadAll().stream()
                .filter(item -> keyword == null || keyword.isBlank()
                        || contains(item.get("name"), keyword)
                        || contains(item.get("primaryImage"), keyword)
                        || tagsText(item).toLowerCase().contains(keyword.toLowerCase()))
                .sorted((a, b) -> String.valueOf(a.getOrDefault("name", ""))
                        .compareToIgnoreCase(String.valueOf(b.getOrDefault("name", ""))))
                .toList();
        int pageNo = Math.max(1, page);
        int pageSize = size < 1 ? 10 : Math.min(size, 100);
        int from = Math.min((pageNo - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", filtered.subList(from, to));
        result.put("total", filtered.size());
        result.put("page", pageNo);
        result.put("size", pageSize);
        return result;
    }

    /** 创建向导选项：名称 + 标签列表 + 主镜像。 */
    public List<Map<String, Object>> options() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : loadAll()) {
            if (Boolean.FALSE.equals(item.get("enabled"))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.get("id"));
            row.put("name", item.get("name"));
            row.put("primaryImage", item.get("primaryImage"));
            row.put("tags", tagsOf(item));
            row.put("javaVersion", item.getOrDefault("javaVersion", ""));
            row.put("builtin", Boolean.TRUE.equals(item.get("builtin")));
            row.put("note", item.getOrDefault("note", ""));
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> save(Map<String, Object> input) {
        String name = String.valueOf(input.getOrDefault("name", "")).trim();
        if (name.isBlank() || name.length() > 128) {
            throw McpanelBusinessException.invalid("内部名称长度应为 1-128");
        }
        List<String> tags = normalizeTags(input.get("tags"));
        if (tags.isEmpty()) {
            throw McpanelBusinessException.invalid("至少填写一个镜像标签（可拉取地址）");
        }
        String primary = String.valueOf(input.getOrDefault("primaryImage", "")).trim();
        if (primary.isBlank() || !tags.contains(primary)) {
            primary = tags.get(0);
        }
        String id = String.valueOf(input.getOrDefault("id", "")).trim();
        if (id.isBlank()) {
            id = "img-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        } else if (!ID.matcher(id).matches()) {
            throw McpanelBusinessException.invalid("镜像 ID 格式无效");
        }
        boolean builtin = Boolean.TRUE.equals(input.get("builtin"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("name", name);
        record.put("tags", tags);
        record.put("primaryImage", primary);
        record.put("javaVersion", String.valueOf(input.getOrDefault("javaVersion", "")).trim());
        record.put("note", String.valueOf(input.getOrDefault("note", "")).trim());
        record.put("enabled", !Boolean.FALSE.equals(input.get("enabled")));
        record.put("builtin", builtin);
        record.put("updatedAt", System.currentTimeMillis());
        documents.save(COLLECTION, id, record);
        return record;
    }

    public Map<String, Object> get(String id) {
        if (id == null || id.isBlank()) {
            throw McpanelBusinessException.invalid("镜像目录项 ID 不能为空");
        }
        return documents.findById(COLLECTION, id)
                .orElseThrow(() -> McpanelBusinessException.notFound("镜像目录项不存在：" + id));
    }

    public void delete(String id) {
        if (id == null || id.isBlank()) {
            throw McpanelBusinessException.invalid("镜像目录项 ID 不能为空");
        }
        documents.delete(COLLECTION, id);
    }

    public void seedDefaultsIfEmpty() {
        List<Map<String, Object>> all = loadAll();
        Map<String, Boolean> present = new LinkedHashMap<>();
        for (Map<String, Object> item : all) {
            present.put(String.valueOf(item.get("id")), true);
        }
        for (String[] row : BUILTIN) {
            if (Boolean.TRUE.equals(present.get(row[0]))) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", row[0]);
            record.put("name", row[1]);
            record.put("tags", List.of(row[2]));
            record.put("primaryImage", row[2]);
            record.put("javaVersion", row[3]);
            record.put("note", "出厂内置 Java 运行时（JRE），按 Java 版本选择，与服务端核心无关");
            record.put("enabled", true);
            record.put("builtin", true);
            record.put("updatedAt", System.currentTimeMillis());
            documents.save(COLLECTION, row[0], record);
        }
        // 旧版（label/kind/mcVersion/image）目录：规范化字段名，避免前端空白单元格
        for (Map<String, Object> legacy : all) {
            if (legacy.containsKey("name") && legacy.containsKey("primaryImage")) {
                continue;
            }
            normalizeLegacy(legacy);
            documents.save(COLLECTION, String.valueOf(legacy.get("id")), legacy);
        }
    }

    /** 0.3.x 目录项 → 0.4 schema：label→name，image→primaryImage/tags，kind/mcVersion 不再映射核心。 */
    @SuppressWarnings("unchecked")
    private static void normalizeLegacy(Map<String, Object> item) {
        Object id = item.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            return;
        }
        String name = firstNonBlank(item.get("name"), item.get("label"), String.valueOf(id));
        item.put("name", name);
        List<String> tags = tagsOf(item);
        if (tags.isEmpty()) {
            Object image = item.get("image");
            if (image != null && !String.valueOf(image).isBlank()) {
                tags = List.of(String.valueOf(image));
            }
        }
        item.put("tags", tags);
        String primary = firstNonBlank(item.get("primaryImage"), item.get("image"),
                tags.isEmpty() ? "" : tags.get(0));
        item.put("primaryImage", primary);
        String javaVersion = firstNonBlank(item.get("javaVersion"), extractJavaVersion(name));
        item.put("javaVersion", javaVersion);
        if (!item.containsKey("enabled")) {
            item.put("enabled", true);
        }
        if (!item.containsKey("builtin")) {
            item.put("builtin", false);
        }
        item.remove("kind");
        item.remove("mcVersion");
        item.remove("image");
        item.remove("label");
        item.put("updatedAt", System.currentTimeMillis());
    }

    private static String extractJavaVersion(String name) {
        if (name == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)java\\s*(\\d{1,2})|openjdk\\s*(\\d{1,2})|jdk\\s*(\\d{1,2})|jre\\s*(\\d{1,2})")
                .matcher(name);
        if (matcher.find()) {
            for (int i = 1; i <= 4; i++) {
                if (matcher.group(i) != null) {
                    return matcher.group(i);
                }
            }
        }
        return "";
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static List<String> tagsOf(Map<String, Object> item) {
        Object tags = item.get("tags");
        if (tags instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        if (tags != null && !String.valueOf(tags).isBlank()) {
            return List.of(String.valueOf(tags));
        }
        Object legacy = item.get("image");
        return legacy == null || String.valueOf(legacy).isBlank()
                ? List.of() : List.of(String.valueOf(legacy));
    }

    private static List<String> normalizeTags(Object tags) {
        List<String> out = new ArrayList<>();
        if (tags instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    String value = String.valueOf(item).trim();
                    if (value.length() <= 255 && !out.contains(value)) {
                        out.add(value);
                    }
                }
            }
        } else if (tags != null && !String.valueOf(tags).isBlank()) {
            for (String part : String.valueOf(tags).split("[,\\n]")) {
                String value = part.trim();
                if (!value.isEmpty() && value.length() <= 255 && !out.contains(value)) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private static String tagsText(Map<String, Object> item) {
        return String.join(" ", tagsOf(item));
    }

    private List<Map<String, Object>> loadAll() {
        List<Map<String, Object>> all = new ArrayList<>();
        int p = 1;
        List<Map<String, Object>> batch;
        do {
            batch = documents.findAll(COLLECTION, p, PAGE_SIZE);
            all.addAll(batch);
            p++;
        } while (batch.size() == PAGE_SIZE);
        // 读取时也做兼容：未落库迁移的旧字段在响应里补齐，避免前端空白
        for (Map<String, Object> item : all) {
            if (!item.containsKey("name") || !item.containsKey("primaryImage") || !item.containsKey("tags")) {
                normalizeLegacy(item);
            }
        }
        return all;
    }

    private static boolean contains(Object value, String keyword) {
        return value != null && String.valueOf(value).toLowerCase().contains(keyword.toLowerCase());
    }
}
