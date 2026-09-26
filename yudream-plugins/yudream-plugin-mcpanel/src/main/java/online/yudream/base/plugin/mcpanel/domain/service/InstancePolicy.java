package online.yudream.base.plugin.mcpanel.domain.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 实例规格校验（面板侧镜像节点侧规则，防止不合规载荷下发）。 */
public final class InstancePolicy {

    private static final Pattern ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9-]{0,63}$");
    private static final Pattern ENV_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    private static final List<String> KINDS = List.of("vanilla", "paper", "purpur", "folia", "fabric",
            "forge", "neoforge", "quilt", "velocity", "bungee", "bedrock", "generic");

    private InstancePolicy() {
    }

    public static void validate(McpanelInstance spec) {
        if (spec.id() == null || !ID.matcher(spec.id()).matches()) {
            throw McpanelBusinessException.invalid("实例 ID 格式无效");
        }
        if (spec.nodeId() == null || !ID.matcher(spec.nodeId()).matches()) {
            throw McpanelBusinessException.invalid("节点 ID 无效");
        }
        if (spec.name() == null || spec.name().isBlank() || spec.name().length() > 128) {
            throw McpanelBusinessException.invalid("实例名称长度应为 1-128");
        }
        if (spec.p2pWhitelist() != null) {
            if (spec.p2pWhitelist().size() > 50) {
                throw McpanelBusinessException.invalid("P2P 白名单最多 50 个用户");
            }
            for (String userId : spec.p2pWhitelist()) {
                if (userId == null || !userId.trim().matches("\\d{1,20}")) {
                    throw McpanelBusinessException.invalid("P2P 白名单只接受用户 ID（数字）：" + userId);
                }
            }
        }
        if (spec.kind() == null || !KINDS.contains(spec.kind())) {
            throw McpanelBusinessException.invalid("服务端类型不支持");
        }
        if (spec.image() == null || spec.image().isBlank() || spec.image().length() > 255) {
            throw McpanelBusinessException.invalid("镜像不能为空且不超过 255 字符");
        }
        if (spec.command() == null || spec.command().isEmpty() || spec.command().size() > 64) {
            throw McpanelBusinessException.invalid("启动命令必填且参数最多 64 项");
        }
        if (spec.memoryMb() < 64 || spec.memoryMb() > 1_048_576
                || spec.cpuMillis() < 100 || spec.cpuMillis() > 256_000
                || spec.diskMb() < 64 || spec.diskMb() > 104_857_600) {
            throw McpanelBusinessException.invalid("资源规格越界");
        }
        if (spec.env() != null && spec.env().size() > 64) {
            throw McpanelBusinessException.invalid("环境变量最多 64 项");
        }
        for (Map.Entry<String, String> entry : spec.env().entrySet()) {
            if (!ENV_KEY.matcher(entry.getKey()).matches() || entry.getValue() == null
                    || entry.getValue().length() > 4096) {
                throw McpanelBusinessException.invalid("环境变量格式无效：" + entry.getKey());
            }
        }
        if (spec.ports() != null && spec.ports().size() > 16) {
            throw McpanelBusinessException.invalid("每实例最多 16 个端口");
        }
        if (spec.config() != null && spec.config().size() > 128) {
            throw McpanelBusinessException.invalid("配置项最多 128 项");
        }
        if (spec.domainSlug() != null && !spec.domainSlug().isBlank()
                && !spec.domainSlug().matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")) {
            throw McpanelBusinessException.invalid("域名 slug 只允许小写字母数字与短横线");
        }
    }
}
