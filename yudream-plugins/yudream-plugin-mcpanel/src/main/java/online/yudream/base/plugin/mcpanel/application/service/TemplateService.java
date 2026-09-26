package online.yudream.base.plugin.mcpanel.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 模板服务（M4）：镜像模板与服务端模板 CRUD（文档存储）。
 * 服务端模板持有安装源、启动命令形态、配置重写与注入开关；
 * 面板按模板展开实例规格（image/command/config 注入 env 在实例创建时合并）。
 */
public class TemplateService {

    private static final String COLLECTION = "mcpanel_templates";
    private static final int PAGE_SIZE = 200;
    private static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");
    private static final List<String> SERVER_KINDS = List.of("vanilla", "paper", "purpur", "folia",
            "fabric", "forge", "neoforge", "quilt", "velocity", "bungee", "bedrock", "generic");

    private final PluginDocumentStore documents;
    private final ObjectMapper mapper;

    public TemplateService(PluginDocumentStore documents, ObjectMapper mapper) {
        this.documents = documents;
        this.mapper = mapper;
    }

    public Map<String, Object> page(int page, int size, String kind, String keyword) {
        List<Map<String, Object>> all = new ArrayList<>();
        int p = 1;
        List<Map<String, Object>> batch;
        do {
            batch = documents.findAll(COLLECTION, p, PAGE_SIZE);
            all.addAll(batch);
            p++;
        } while (batch.size() == PAGE_SIZE);
        List<Map<String, Object>> filtered = all.stream()
                .filter(t -> kind == null || kind.isBlank() || kind.equals(t.get("kind")))
                .filter(t -> keyword == null || keyword.isBlank()
                        || String.valueOf(t.get("key")).contains(keyword)
                        || String.valueOf(t.getOrDefault("name", "")).contains(keyword))
                .sorted((a, b) -> String.valueOf(a.get("key")).compareTo(String.valueOf(b.get("key"))))
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

    public Map<String, Object> save(Map<String, Object> template) {
        String key = String.valueOf(template.get("key"));
        String kind = String.valueOf(template.get("kind"));
        if (!KEY.matcher(key).matches()) {
            throw McpanelBusinessException.invalid("模板 key 只允许小写字母数字与短横线");
        }
        if (!SERVER_KINDS.contains(kind)) {
            throw McpanelBusinessException.invalid("服务端类型不支持：" + kind);
        }
        String name = String.valueOf(template.get("name"));
        if (name.isBlank() || name.length() > 128) {
            throw McpanelBusinessException.invalid("模板名称长度应为 1-128");
        }
        if ("image".equals(kind)) {
            String image = String.valueOf(template.get("image"));
            if (image.isBlank() || image.length() > 255) {
                throw McpanelBusinessException.invalid("镜像不能为空");
            }
        } else {
            Object installer = template.get("installer");
            if (!(installer instanceof Map<?, ?> installerMap)
                    || String.valueOf(installerMap.get("type")).isBlank()) {
                throw McpanelBusinessException.invalid("服务端模板需要 installer（下载源/构建 API）");
            }
            Object startup = template.get("startup");
            if (!(startup instanceof Map<?, ?>)) {
                throw McpanelBusinessException.invalid("服务端模板需要 startup（command 或 jarGlob+jvmOpts）");
            }
        }
        template.put("updatedAt", System.currentTimeMillis());
        documents.save(COLLECTION, key, template);
        return template;
    }

    public Map<String, Object> get(String key) {
        return documents.findById(COLLECTION, key)
                .orElseThrow(() -> McpanelBusinessException.notFound("模板不存在"));
    }

    public void delete(String key) {
        documents.delete(COLLECTION, key);
    }

    /** 按模板展开实例创建载荷的缺省项（image/command/env/configRewrites/注入）。 */
    public Map<String, Object> expand(String templateKey, String mcVersion) {
        Map<String, Object> template = get(templateKey);
        Map<String, Object> spec = new LinkedHashMap<>();
        if ("image".equals(String.valueOf(template.get("kind")))) {
            spec.put("image", template.get("image"));
            spec.put("command", template.getOrDefault("command", List.of("sleep", "infinity")));
        } else {
            Map<?, ?> installer = (Map<?, ?>) template.get("installer");
            String image = String.valueOf(template.getOrDefault("image", "eclipse-temurin:21-jre"));
            spec.put("image", image);
            Map<?, ?> startup = (Map<?, ?>) template.get("startup");
            if (startup.get("command") instanceof List<?> command) {
                spec.put("command", command);
            } else {
                String jarGlob = startup.get("jarGlob") == null ? "server.jar" : String.valueOf(startup.get("jarGlob"));
                String jvmOpts = startup.get("jvmOpts") == null ? "-Xms512M -Xmx1024M" : String.valueOf(startup.get("jvmOpts"));
                List<String> command = new ArrayList<>();
                command.addAll(List.of(jvmOpts.trim().split("\\s+")));
                command.add("-jar");
                command.add(jarGlob);
                command.add("nogui");
                spec.put("command", command);
            }
            spec.put("installer", installer);
            spec.put("inject", template.getOrDefault("inject", Map.of()));
            spec.put("configRewrites", template.getOrDefault("configRewrites", List.of()));
        }
        spec.put("kind", template.get("kind"));
        spec.put("mcVersion", mcVersion == null ? template.get("mcVersion") : mcVersion);
        spec.put("env", template.getOrDefault("env", Map.of()));
        return spec;
    }
}
