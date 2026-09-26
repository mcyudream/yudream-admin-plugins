package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 实例配置文件结构化读写（对标 MCSM ServerConfig）。
 * 经既有 files read/write 代理节点文件通道。
 *
 * 0.13.0 起按扩展名适配格式：
 * - .properties：server.properties 平面 key=value（原有行为不变）；
 * - .yml/.yaml：BungeeCord config.yml 等嵌套 YAML，点路径扁平化读写；
 * - .toml：Velocity velocity.toml，点路径扁平化读写（TOML 标量前置重排）；
 * - 其余（json/txt）：仅原文模式，结构化属性返回空。
 * 结构化保存对 YAML/TOML 为全量重写：注释会丢失，需要保留注释请用原文模式（UI 已提示）。
 */
public class ServerConfigService {

    public interface InstanceFiles {

        Map<String, Object> read(String scopeKey, String instanceId, Map<String, Object> args);

        Map<String, Object> write(String scopeKey, String instanceId, Map<String, Object> args);
    }

    public static final String DEFAULT_PATH = "server.properties";

    private final InstanceFiles files;

    public ServerConfigService(InstanceFiles files) {
        this.files = files;
    }

    /**
     * PROXY protocol 开关状态（单端口入口配套）：不支持的类型/文件不存在/键缺失都如实回报。
     */
    public Map<String, Object> proxyProtocolStatus(String scopeKey, String instanceId, String kind) {
        Map<String, Object> result = new LinkedHashMap<>();
        ProxyProtocolToggle.Target target = ProxyProtocolToggle.targetOf(kind);
        result.put("kind", kind == null ? "" : kind);
        result.put("supported", target != null);
        if (target == null) {
            result.put("reason", "该实例类型不需要 PROXY protocol（仅 Paper 系 / Velocity / BungeeCord 支持）");
            result.put("enabled", false);
            return result;
        }
        result.put("path", target.path());
        result.put("key", target.section() == null ? target.key() : target.section() + "." + target.key());
        String content = readText(scopeKey, instanceId, target.path());
        if (content == null) {
            result.put("fileExists", false);
            result.put("enabled", false);
            result.put("reason", "实例里还没有 " + target.path() + "：启动一次实例生成默认配置后再开");
            return result;
        }
        result.put("fileExists", true);
        Boolean enabled = ProxyProtocolToggle.current(content, target);
        result.put("enabled", Boolean.TRUE.equals(enabled));
        if (enabled == null) {
            result.put("reason", "配置里没有 " + target.key() + " 键：请启动一次实例生成默认配置，或手动添加");
        }
        return result;
    }

    /**
     * PROXY protocol 一键开关：只翻转已存在的键并保留其它内容（注释/缩进不动）；
     * 键缺失或类型不支持时返回可读错误，不做任何写入。
     */
    public Map<String, Object> setProxyProtocol(String scopeKey, String instanceId, String kind, boolean enabled) {
        ProxyProtocolToggle.Target target = ProxyProtocolToggle.targetOf(kind);
        if (target == null) {
            throw McpanelBusinessException.invalid(
                    "该实例类型不需要 PROXY protocol（仅 Paper 系 / Velocity / BungeeCord 支持）");
        }
        String content = readText(scopeKey, instanceId, target.path());
        if (content == null) {
            throw McpanelBusinessException.invalid("实例里还没有 " + target.path()
                    + "：先启动一次实例生成默认配置，再开启 PROXY protocol");
        }
        String updated;
        try {
            updated = ProxyProtocolToggle.apply(content, target, enabled);
        }
        catch (IllegalArgumentException error) {
            throw McpanelBusinessException.invalid(error.getMessage());
        }
        if (!updated.equals(content)) {
            files.write(scopeKey, instanceId, Map.of("path", target.path(),
                    "content", Base64.getEncoder().encodeToString(updated.getBytes(StandardCharsets.UTF_8)),
                    "encoding", "base64"));
        }
        Map<String, Object> result = proxyProtocolStatus(scopeKey, instanceId, kind);
        result.put("changed", !updated.equals(content));
        result.put("restartHint", enabled
                ? "已开启：重启实例后生效（运行中的实例不会热加载该配置）"
                : "已关闭：重启实例后生效");
        return result;
    }

    /** 读取文本内容；文件不存在返回 null（节点 read 对缺失文件会报错，这里按不存在处理）。 */
    private String readText(String scopeKey, String instanceId, String path) {
        Map<String, Object> raw;
        try {
            raw = files.read(scopeKey, instanceId, Map.of("path", path));
        }
        catch (RuntimeException error) {
            return null;
        }
        Object encoded = raw == null ? null : raw.get("content");
        if (encoded == null || String.valueOf(encoded).isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(String.valueOf(encoded)), StandardCharsets.UTF_8);
        }
        catch (RuntimeException error) {
            return String.valueOf(encoded);
        }
    }

    public Map<String, Object> view(String scopeKey, String instanceId, String path) {
        String target = path == null || path.isBlank() ? DEFAULT_PATH : path.trim();
        Map<String, Object> raw;
        try {
            raw = files.read(scopeKey, instanceId, Map.of("path", target));
        }
        catch (McpanelBusinessException error) {
            if (McpanelBusinessException.CODE_INSTANCE_NOT_FOUND.equals(error.code())) {
                // 节点上还没有该实例的数据（创建未完成/从未启动）：按空配置视图处理，
                // 前端展示「暂无可编辑的配置文件」空态引导，而不是把 409 报到页面上。
                raw = Map.of();
            }
            else {
                throw error;
            }
        }
        String content = "";
        Object encoded = raw.get("content");
        if (encoded != null && !String.valueOf(encoded).isBlank()) {
            try {
                content = new String(Base64.getDecoder().decode(String.valueOf(encoded)), StandardCharsets.UTF_8);
            } catch (RuntimeException error) {
                content = String.valueOf(encoded);
            }
        }
        StructuredConfigCodec.Format format = StructuredConfigCodec.formatOf(target);
        Map<String, String> properties = new TreeMap<>();
        String parseError = null;
        if (content.isBlank()) {
            // 尚未生成：结构化面板允许空文件（保存时按编辑键生成）。
        }
        else if (format == StructuredConfigCodec.Format.PROPERTIES) {
            properties.putAll(parse(content));
        }
        else if (format != StructuredConfigCodec.Format.RAW) {
            try {
                properties.putAll(StructuredConfigCodec.flatten(StructuredConfigCodec.parseTree(content, format)));
            } catch (IOException | RuntimeException | LinkageError error) {
                // 解析失败或解析库缺失（dev 目录加载不含 shade 依赖）：原文仍可编辑，结构化面板显式提示。
                parseError = structuredUnavailable(error);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", target);
        result.put("exists", !content.isBlank() || properties.isEmpty() == false || raw.get("content") != null);
        result.put("content", content);
        result.put("properties", properties);
        result.put("format", format.name().toLowerCase(java.util.Locale.ROOT));
        if (parseError != null) {
            result.put("parseError", parseError);
        }
        return result;
    }

    public Map<String, Object> save(String scopeKey, String instanceId, String path,
                                    Map<String, String> properties, String rawContent, boolean preferRaw) {
        String target = path == null || path.isBlank() ? DEFAULT_PATH : path.trim();
        StructuredConfigCodec.Format format = StructuredConfigCodec.formatOf(target);
        String content;
        if (preferRaw) {
            content = rawContent == null ? "" : rawContent;
        }
        else if (format == StructuredConfigCodec.Format.PROPERTIES) {
            Map<String, Object> current = view(scopeKey, instanceId, target);
            @SuppressWarnings("unchecked")
            Map<String, String> merged = new TreeMap<>((Map<String, String>) current.get("properties"));
            if (properties != null) {
                properties.forEach((key, value) -> {
                    if (key != null && !key.isBlank()) {
                        merged.put(key.trim(), value == null ? "" : value);
                    }
                });
            }
            content = serialize(merged);
        }
        else if (format != StructuredConfigCodec.Format.RAW) {
            Map<String, Object> current = view(scopeKey, instanceId, target);
            if (current.get("parseError") != null) {
                throw McpanelBusinessException.invalid(
                        "当前 " + target + " 无法解析（" + current.get("parseError") + "），请使用原文模式");
            }
            Map<String, Object> tree;
            Map<String, String> written;
            try {
                // view 返回的是展平视图：结构化保存需基于原始树做点路径合并，
                // 否则嵌套表（[servers] 等）会被展平键整体覆盖。
                tree = StructuredConfigCodec.parseTree(String.valueOf(current.get("content")), format);
                if (properties != null) {
                    StructuredConfigCodec.applyEdits(tree, properties);
                }
                content = StructuredConfigCodec.writeTree(tree, format);
                written = StructuredConfigCodec.flatten(
                        StructuredConfigCodec.parseTree(content, format));
            } catch (IOException | RuntimeException | LinkageError error) {
                throw McpanelBusinessException.invalid(structuredUnavailable(error));
            }
            String encodedOut = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
            files.write(scopeKey, instanceId, Map.of("path", target, "content", encodedOut, "encoding", "base64"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", target);
            result.put("saved", true);
            result.put("content", content);
            result.put("properties", new TreeMap<>(written));
            return result;
        }
        else {
            throw McpanelBusinessException.invalid("该文件类型仅支持原文模式保存");
        }
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        files.write(scopeKey, instanceId, Map.of("path", target, "content", encoded, "encoding", "base64"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", target);
        result.put("saved", true);
        result.put("content", content);
        result.put("properties", new TreeMap<>(parse(content)));
        return result;
    }

    static Map<String, String> parse(String content) {
        Map<String, String> map = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return map;
        }
        for (String line : content.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            map.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1));
        }
        return map;
    }

    /** 结构化不可用的统一文案：区分「缺解析库（dev 模式）」与「文件本身解析失败」。 */
    private static String structuredUnavailable(Throwable error) {
        if (error instanceof LinkageError
                || (error.getCause() instanceof LinkageError)
                || String.valueOf(error).contains("NoClassDefFoundError")
                || String.valueOf(error).contains("TomlFactory")
                || String.valueOf(error).contains("snakeyaml")) {
            return "面板缺少 YAML/TOML 解析库（开发目录加载模式不含内置依赖），请使用打包后的插件 JAR 部署，或切换原文模式";
        }
        return "配置解析失败（" + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : "：" + error.getMessage()) + "），请使用原文模式";
    }

    static String serialize(Map<String, String> map) {
        StringBuilder builder = new StringBuilder();
        map.forEach((key, value) -> builder.append(key).append('=').append(value == null ? "" : value).append('\n'));
        return builder.toString();
    }
}
