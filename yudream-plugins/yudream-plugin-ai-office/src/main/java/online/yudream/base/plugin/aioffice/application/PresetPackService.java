package online.yudream.base.plugin.aioffice.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeCategory;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeDocType;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficePackage;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficePrompt;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSchema;
import online.yudream.base.plugin.aioffice.domain.aggregate.OfficeSettings;
import online.yudream.base.plugin.aioffice.domain.repo.OfficeRepository;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 预设包导入/导出：预设只是站点配置的打包载体，导入即复制为站点数据，插件升级不覆盖站点定制。 */
public class PresetPackService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OfficeRepository repository;
    private final ConfigService config;

    public PresetPackService(OfficeRepository repository, ConfigService config) {
        this.repository = repository;
        this.config = config;
    }

    /** 内置预设清单（classpath presets/*.json）。 */
    public List<Map<String, Object>> builtins() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : List.of("club-month-end-pack")) {
            try (InputStream input = getClass().getResourceAsStream("/presets/" + name + ".json")) {
                if (input == null) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(input);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", node.path("code").asText(name));
                item.put("name", node.path("name").asText(name));
                item.put("description", node.path("description").asText(""));
                item.put("wordTemplates", MAPPER.convertValue(node.path("wordTemplates"), List.class));
                list.add(item);
            } catch (Exception ignored) {
                // 预设资源缺失时跳过，不影响插件运行
            }
        }
        return list;
    }

    /** 导入内置预设。overwrite=true 时同编码配置被覆盖，否则跳过。 */
    public Map<String, Object> importBuiltin(String code, boolean overwrite) {
        try (InputStream input = getClass().getResourceAsStream("/presets/" + code + ".json")) {
            if (input == null) {
                throw new IllegalArgumentException("内置预设不存在：" + code);
            }
            return importJson(MAPPER.readTree(input), overwrite);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("预设读取失败：" + e.getMessage(), e);
        }
    }

    /** 从 JSON 内容导入（管理员上传的自定义预设）。 */
    public Map<String, Object> importContent(String json, boolean overwrite) {
        try {
            return importJson(MAPPER.readTree(json == null || json.isBlank() ? "{}" : json), overwrite);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("预设 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    /** 导出当前站点配置为预设 JSON（供站点间复制成例）。 */
    public Map<String, Object> exportCurrent() {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("code", "site-export");
        pack.put("name", "站点导出配置");
        pack.put("description", "由站点配置导出的预设包");
        OfficeSettings settings = repository.settings();
        Map<String, Object> settingsNode = new LinkedHashMap<>();
        settingsNode.put("profileSchemaCode", settings.profileSchemaCode());
        settingsNode.put("styleGuide", settings.styleGuide());
        pack.put("settings", settingsNode);
        pack.put("schemas", config.schemas());
        pack.put("prompts", config.prompts());
        pack.put("categories", config.categories());
        pack.put("docTypes", config.docTypes());
        pack.put("packages", config.packages());
        return pack;
    }

    private Map<String, Object> importJson(JsonNode root, boolean overwrite) {
        int[] counters = {0, 0};
        for (JsonNode node : root.path("schemas")) {
            OfficeSchema parsed = new OfficeSchema(null, node.path("code").asText(),
                    node.path("name").asText(), false, fields(node.path("fields")), System.currentTimeMillis());
            Optional<OfficeSchema> existing = repository.schemaByCode(parsed.code());
            if (existing.isPresent() && !overwrite) {
                counters[1]++;
                continue;
            }
            OfficeSchema schema = existing.isPresent()
                    ? new OfficeSchema(existing.get().id(), parsed.code(), parsed.name(), false,
                            parsed.fields(), System.currentTimeMillis())
                    : parsed;
            repository.saveSchema(schema);
            counters[0]++;
        }
        for (JsonNode node : root.path("prompts")) {
            OfficePrompt parsed = OfficePrompt.create(node.path("code").asText(), node.path("name").asText(),
                    node.path("system").asText(""), node.path("user").asText());
            Optional<OfficePrompt> existing = repository.promptByCode(parsed.code());
            if (existing.isPresent() && !overwrite) {
                counters[1]++;
                continue;
            }
            OfficePrompt prompt = existing.isPresent()
                    ? new OfficePrompt(existing.get().id(), parsed.code(), parsed.name(), parsed.system(),
                            parsed.user(), System.currentTimeMillis())
                    : parsed;
            repository.savePrompt(prompt);
            counters[0]++;
        }
        for (JsonNode node : root.path("categories")) {
            List<String> fields = new ArrayList<>();
            node.path("fields").forEach(field -> fields.add(field.asText()));
            OfficeCategory parsed = OfficeCategory.create(node.path("direction").asText(),
                    node.path("code").asText(), node.path("name").asText(), fields, node.path("sort").asInt(0));
            Optional<OfficeCategory> existing = repository.categories().stream()
                    .filter(item -> item.direction().equals(parsed.direction())
                            && item.code().equals(parsed.code()))
                    .findFirst();
            if (existing.isPresent() && !overwrite) {
                counters[1]++;
                continue;
            }
            OfficeCategory category = existing.isPresent()
                    ? new OfficeCategory(existing.get().id(), parsed.direction(), parsed.code(), parsed.name(),
                            parsed.fields(), parsed.sort(), true)
                    : parsed;
            repository.saveCategory(category);
            counters[0]++;
        }
        for (JsonNode node : root.path("docTypes")) {
            OfficeDocType parsed = parseDocType(node);
            Optional<OfficeDocType> existing = repository.docTypeByCode(parsed.code());
            if (existing.isPresent() && !overwrite) {
                counters[1]++;
                continue;
            }
            OfficeDocType docType = existing.isPresent()
                    ? new OfficeDocType(existing.get().id(), parsed.code(), parsed.name(), parsed.stage(),
                            parsed.wordTemplateCode(), parsed.schemaCodes(), parsed.mappings(),
                            parsed.fileNamePattern(), true, System.currentTimeMillis())
                    : parsed;
            repository.saveDocType(docType);
            counters[0]++;
        }
        for (JsonNode node : root.path("packages")) {
            List<OfficePackage.Item> items = new ArrayList<>();
            node.path("items").forEach(item -> items.add(new OfficePackage.Item(
                    item.path("docTypeCode").asText(), item.path("target").asText("PER_MONTH"),
                    item.path("subDir").asText(""))));
            OfficePackage parsed = OfficePackage.create(node.path("code").asText(), node.path("name").asText(),
                    node.path("dirPattern").asText(""), items);
            Optional<OfficePackage> existing = repository.packageByCode(parsed.code());
            if (existing.isPresent() && !overwrite) {
                counters[1]++;
                continue;
            }
            OfficePackage aPackage = existing.isPresent()
                    ? new OfficePackage(existing.get().id(), parsed.code(), parsed.name(), parsed.dirPattern(),
                            parsed.items(), true, System.currentTimeMillis())
                    : parsed;
            repository.savePackage(aPackage);
            counters[0]++;
        }
        JsonNode settings = root.path("settings");
        if (settings.isObject()) {
            OfficeSettings current = repository.settings();
            repository.saveSettings(current.withMeta(
                    settings.path("profileSchemaCode").asText(current.profileSchemaCode()),
                    current.financeCycle(), current.currency(),
                    settings.path("styleGuide").asText(current.styleGuide()),
                    current.aiProviderCode(), current.aiModelCode()));
        }
        String presetCode = root.path("code").asText("");
        if (!presetCode.isBlank()) {
            OfficeSettings current = repository.settings();
            repository.saveSettings(new OfficeSettings(current.profileSchemaCode(), current.profile(),
                    current.financeCycle(), current.currency(), current.styleGuide(), current.aiProviderCode(),
                    current.aiModelCode(), presetCode, System.currentTimeMillis()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", counters[0]);
        result.put("skipped", counters[1]);
        result.put("preset", root.path("name").asText(presetCode));
        return result;
    }

    private OfficeDocType parseDocType(JsonNode node) {
        List<OfficeDocType.Mapping> mappings = new ArrayList<>();
        node.path("mappings").forEach(mapping -> {
            Map<String, String> itemMapping = new LinkedHashMap<>();
            mapping.path("itemMapping").fields()
                    .forEachRemaining(field -> itemMapping.put(field.getKey(), field.getValue().asText()));
            mappings.add(new OfficeDocType.Mapping(mapping.path("var").asText(),
                    mapping.path("source").asText(), mapping.path("transform").asText(""), itemMapping));
        });
        List<String> schemaCodes = new ArrayList<>();
        node.path("schemaCodes").forEach(code -> schemaCodes.add(code.asText()));
        return OfficeDocType.create(node.path("code").asText(), node.path("name").asText(),
                node.path("stage").asText("general"), node.path("wordTemplateCode").asText(""),
                schemaCodes, mappings, node.path("fileNamePattern").asText(""));
    }

    private List<OfficeSchema.FieldDef> fields(JsonNode array) {
        List<OfficeSchema.FieldDef> fields = new ArrayList<>();
        for (JsonNode node : array) {
            List<String> options = new ArrayList<>();
            node.path("options").forEach(option -> options.add(option.asText()));
            fields.add(new OfficeSchema.FieldDef(node.path("key").asText(), node.path("label").asText(),
                    node.path("type").asText("text"), node.path("required").asBoolean(false), options));
        }
        return fields;
    }
}
