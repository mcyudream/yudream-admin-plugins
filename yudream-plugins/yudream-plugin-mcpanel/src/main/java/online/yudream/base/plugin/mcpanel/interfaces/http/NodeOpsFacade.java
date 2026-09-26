package online.yudream.base.plugin.mcpanel.interfaces.http;

import online.yudream.base.plugin.mcpanel.application.service.FileCharsetCodec;
import online.yudream.base.plugin.mcpanel.application.port.NodeControlPlane;
import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException;
import online.yudream.base.plugin.spi.http.PluginSseStream;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 节点级操作应用服务：终端 / 节点文件 / 节点 SFTP 的节点协议代理。
 * 数据范围：节点记录必须存在且已启用；事件流按 nodeId 隔离。
 *
 * 错误映射（人话优先，与实例 facade 同一口径）：
 * - node.offline → 502「节点未在线」；node.timeout → 504「节点响应超时」；
 * - node.capability → 409「节点版本不支持」；其余节点业务拒绝 → 409/500 原话透传；
 * - 请求失败一律直接返回，不做任何自动重试（写请求重试可能造成重复副作用）。
 * 审计：SFTP 开关等节点级操作接入共享窄 AuditRecorder（tenantId 恒空 = 平台直属）。
 */
public class NodeOpsFacade {

    private static final long CALL_TIMEOUT_MS = 32_000L;
    private static final long TERMINAL_TIMEOUT_MS = 60_000L;

    private final McpanelNodeRepository nodeRepository;
    private final NodeControlPlane controlPlane;
    private final EventBusRef eventBusRef;
    private final McpanelInstanceAppService.AuditRecorder audit;

    public NodeOpsFacade(McpanelNodeRepository nodeRepository,
                         NodeControlPlane controlPlane,
                         EventBusRef eventBusRef) {
        this(nodeRepository, controlPlane, eventBusRef, null);
    }

    public NodeOpsFacade(McpanelNodeRepository nodeRepository,
                                 NodeControlPlane controlPlane,
                                 EventBusRef eventBusRef,
                                 McpanelInstanceAppService.AuditRecorder audit) {
        this.nodeRepository = nodeRepository;
        this.controlPlane = controlPlane;
        this.eventBusRef = eventBusRef;
        this.audit = audit;
    }

    /** 事件总线引用（避免循环依赖的窄接口）。 */
    public interface EventBusRef {
        PluginSseStream open(String nodeId, String eventType, String matchKey, String matchValue);
    }

    public Map<String, Object> terminalOpen(String actor, String nodeId, Map<String, Object> body) {
        requireEnabledNode(nodeId);
        Map<String, Object> payload = new LinkedHashMap<>(body);
        return call(nodeId, "node.terminal.open", payload, TERMINAL_TIMEOUT_MS);
    }

    public Map<String, Object> terminalInput(String actor, String nodeId, Map<String, Object> body) {
        requireEnabledNode(nodeId);
        return call(nodeId, "node.terminal.input", body);
    }

    public Map<String, Object> terminalClose(String actor, String nodeId, Map<String, Object> body) {
        requireEnabledNode(nodeId);
        return call(nodeId, "node.terminal.close", body);
    }

    /**
     * 真实系统命令补全（节点 0.4.0+ 的 node.term.complete）：按输入词扫描
     * 节点 PATH 下可执行文件名。旧节点返回 node.capability → 409，由前端
     * 静默降级为仅历史/内置候选，因此不设长超时，快速失败。
     */
    public Map<String, Object> terminalComplete(String nodeId, Map<String, Object> body) {
        requireEnabledNode(nodeId);
        return call(nodeId, "node.term.complete", body, 8_000L);
    }

    /** 节点终端输出 SSE：事件 node.terminal.output，payload.terminalId 过滤。 */
    public PluginSseStream terminalEvents(String nodeId, String terminalId) {
        requireEnabledNode(nodeId);
        if (terminalId == null || terminalId.isBlank()) {
            // 终端 open 的回执 terminalId 是流过滤键：缺失时直接 400，不向节点发空过滤订阅。
            throw new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(
                    "invalid-request", 400, "缺少 terminalId：请先打开节点终端获取");
        }
        return eventBusRef.open(nodeId, "node.terminal.output", "terminalId", terminalId);
    }

    public Map<String, Object> nodeFile(String nodeId, String operation, Map<String, Object> body) {
        requireEnabledNode(nodeId);
        Map<String, Object> payload = new LinkedHashMap<>(body);
        if ("write".equals(operation)) {
            FileCharsetCodec.applyCharset(payload);
        }
        return call(nodeId, "node.file." + operation, payload);
    }

    public Map<String, Object> nodeFtpOpen(String actor, String nodeId, Map<String, Object> body) {
        var node = requireEnabledNode(nodeId);
        Map<String, Object> payload = new LinkedHashMap<>(body);
        Map<String, Object> result = call(nodeId, "ftp.open.node", payload, TERMINAL_TIMEOUT_MS);
        // 节点载荷可能反序列化为不可变 Map：包一层再补默认 host。
        Map<String, Object> response = new LinkedHashMap<>(result);
        response.putIfAbsent("host", node.advertisedSftpHost());
        audit(actor, "node.ftp.open", nodeId, String.valueOf(body.get("root")));
        return response;
    }

    public Map<String, Object> nodeFtpClose(String actor, String nodeId, Map<String, Object> body) {
        requireEnabledNode(nodeId);
        Map<String, Object> result = call(nodeId, "ftp.close.node", body);
        audit(actor, "node.ftp.close", nodeId, String.valueOf(body.get("root")));
        return result;
    }

    private online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode requireEnabledNode(String nodeId) {
        var node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(
                        "node.notFound", 404, "节点不存在"));
        if (!node.enabled() || !node.enrolled()) {
            throw new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(
                    "node-not-ready", 409, "节点未启用或未完成注册");
        }
        return node;
    }

    private Map<String, Object> call(String nodeId, String method, Map<String, Object> payload) {
        return call(nodeId, method, payload, CALL_TIMEOUT_MS);
    }

    private Map<String, Object> call(String nodeId, String method, Map<String, Object> payload, long timeoutMs) {
        CompletableFuture<Map<String, Object>> future = controlPlane.call(nodeId, method, payload, timeoutMs);
        try {
            return future.get(timeoutMs + 5_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(
                    "internal.error", 500, "节点调用被中断");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            // orTimeout 触发时 CompletionException 的 cause 是 TimeoutException：
            // 必须先于通用 NodeCallException/兜底分支识别，否则被误报为「调用失败」。
            if (cause instanceof TimeoutException) {
                throw new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(
                        "node.timeout", 504, "节点响应超时：" + method + "（请检查节点负载或网络）");
            }
            if (cause instanceof NodeCallException nodeCall) {
                throw translateNodeCall(method, nodeCall);
            }
            throw new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(
                    "node.unreachable", 502, "节点调用失败：" + method + "（连接中断或节点异常）");
        } catch (TimeoutException error) {
            throw new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(
                    "node.timeout", 504, "节点响应超时：" + method + "（请检查节点负载或网络）");
        }
    }

    /** 节点业务错误 → 人话 + 语义化 HTTP 状态。 */
    private static online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException translateNodeCall(
            String method, NodeCallException nodeCall) {
        String code = nodeCall.code() == null || nodeCall.code().isBlank() ? "node.error" : nodeCall.code();
        String rawMessage = nodeCall.getMessage() == null || nodeCall.getMessage().isBlank()
                ? code : nodeCall.getMessage();
        switch (code) {
            case "node.offline" -> {
                return new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(code, 502,
                        "节点未在线：" + method + " 无法下发（请确认节点连接状态后重试）");
            }
            case "node.timeout" -> {
                return new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(code, 504,
                        "节点响应超时：" + method + "（请检查节点负载或网络）");
            }
            case "node.capability" -> {
                return new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(code, 409,
                        "节点版本不支持该操作：" + method + "（请升级节点 agent）");
            }
            default -> {
                // 其余业务拒绝：透传节点原话（节点侧已脱敏），节点级错误码按 409/500 分档。
                int status = code.startsWith("node.") ? 409 : 500;
                return new online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException(code, status,
                        rawMessage);
            }
        }
    }

    /** 审计（共享窄 recorder；节点级操作租户恒为平台直属）。recorder 未装配时静默跳过。 */
    private void audit(String actor, String action, String nodeId, String detail) {
        McpanelInstanceAppService.AuditRecorder recorder = audit;
        if (recorder == null) {
            return;
        }
        try {
            recorder.record(actor, action, "node", nodeId, detail, "");
        } catch (RuntimeException ignored) {
            // 审计失败不影响主流程
        }
    }
}
