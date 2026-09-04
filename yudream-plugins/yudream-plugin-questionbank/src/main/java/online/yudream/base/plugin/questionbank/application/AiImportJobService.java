package online.yudream.base.plugin.questionbank.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import online.yudream.base.plugin.questionbank.application.QuestionService.ImportResult;
import online.yudream.base.plugin.questionbank.infrastructure.CreateQuestionAiTool;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolResult;

/**
 * AI 一键导入任务：后台线程发起一次<strong>流式</strong> AI 对话（SPI 2.16.0 chatStream），
 * 模型每识别出一道题就调用「创建一道题目」插件工具逐题原子落库（WRITE 工具经
 * grantedWriteToolNames 显式授权 + 题库管理权限双重闸门）；模型输出增量、每题成败
 * 都通过 SSE 事件流实时推送前端。
 * 模型不支持/未执行工具调用时兜底：对最终输出做容错 JSON 解析后逐题入库。
 * 单飞行：同一时间只允许一个导入任务，避免 AI 并发调用互相拖慢。
 * 线程池与心跳调度随插件 disable 释放（bootstrap 注册 onDispose）。
 */
public final class AiImportJobService {
    private static final Logger LOGGER = Logger.getLogger(AiImportJobService.class.getName());
    private static final int MAX_TEXT_LENGTH = 20000;
    private static final int MAX_QUESTIONS = 50;
    private static final long AI_TIMEOUT_MINUTES = 10;
    private static final long HEARTBEAT_SECONDS = 15;
    private static final int MAX_RETAINED_JOBS = 20;

    private static final String SYSTEM_PROMPT = "你是题库录入助手。通读用户给出的文本，把其中每一道题"
            + "（可能有多道、题型混合）识别出来，并逐题调用工具 questionbank.create_question 写入题库，"
            + "不要输出题目 JSON，不要批量合并，一道题调用一次。字段约定：\n"
            + "- type：SINGLE（单选）/ MULTIPLE（多选）/ TRUE_FALSE（判断）/ FILL（填空）/ SHORT（简答）\n"
            + "- content：仅题干本身，Markdown 格式，保留原文中的图片链接；严禁把选项列表（如 A. xx B. xx）写进题干\n"
            + "- options：选择题选项内容数组（不含 A/B/C 标号）；其余题型省略\n"
            + "- answer：单选题正确选项字母（如 B）；判断题 TRUE 或 FALSE\n"
            + "- answers：多选题正确选项字母数组\n"
            + "- blanks：填空题每空可接受答案的二维数组（如 [[\"答案1\",\"等价答案\"]]），空数与题干中的空对应\n"
            + "- referenceAnswer：简答题参考答案（Markdown）\n"
            + "- analysis：解析（Markdown，没有可省略）；difficulty：难度 1~5，拿不准填 3；tags：建议标签\n"
            + "原文未给出答案或解析时，你必须先自行解答，再带完整答案调用工具。尽量保留题目原始表述。"
            + "全部题目处理完后，用一句话汇报成功与失败数量。";

    private final FrameworkServices framework;
    private final SettingsService settings;
    private final AiImportService aiImportService;
    private final QuestionService questionService;
    private final Map<String, ImportJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "questionbank-ai-import");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "questionbank-ai-import-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public AiImportJobService(FrameworkServices framework, SettingsService settings,
                              AiImportService aiImportService, QuestionService questionService) {
        this.framework = framework;
        this.settings = settings;
        this.aiImportService = aiImportService;
        this.questionService = questionService;
        heartbeat.scheduleAtFixedRate(this::beat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** 供 bootstrap 在插件 disable 时释放线程资源。 */
    public AutoCloseable shutdownHook() {
        return () -> {
            worker.shutdownNow();
            heartbeat.shutdownNow();
            for (ImportJob job : jobs.values()) {
                job.bus.complete();
            }
            jobs.clear();
        };
    }

    /** 启动导入任务，返回任务 ID；校验失败或已有任务进行中时抛业务异常。 */
    public String start(String text, String operatorId, String operatorName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("请粘贴或上传题目内容");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("内容过长（最多 " + MAX_TEXT_LENGTH + " 字），请分批导入");
        }
        for (ImportJob job : jobs.values()) {
            if (!job.bus.isCompleted()) {
                throw new IllegalStateException("已有 AI 导入任务进行中，请等待其完成");
            }
        }
        evictRetained();
        ImportJob job = new ImportJob(UUID.randomUUID().toString().replace("-", ""), operatorId, operatorName);
        jobs.put(job.id, job);
        String content = text;
        worker.submit(() -> run(job, content));
        return job.id;
    }

    /** 任务事件流；任务不存在返回 null。已完成任务支持历史回放（前端断线重连/后打开也能看到完整日志）。 */
    public PluginSseStream stream(String jobId) {
        ImportJob job = jobs.get(jobId);
        return job == null ? null : job.bus;
    }

    private void run(ImportJob job, String text) {
        try {
            PluginAiService ai = aiOrNull();
            if (ai == null) {
                throw new IllegalStateException("平台 AI 能力不可用，请先在平台配置 AI 供应商");
            }
            job.bus.emit("log", Map.of("message", "开始 AI 流式识别（共 " + text.length() + " 字）…"));
            PluginAiExecutionContext context = new PluginAiExecutionContext(
                    parseUserId(job.operatorId), null, null, null, null, CreateQuestionAiTool.TRIGGER, null,
                    List.of(), List.of(CreateQuestionAiTool.TOOL_NAME), List.of(CreateQuestionAiTool.TOOL_NAME));
            PluginAiChatResponse response = await(ai.chatStream(new PluginAiChatRequest(
                            SYSTEM_PROMPT, text, settings.aiProviderCode(), settings.aiModelCode(),
                            List.of(), context, true),
                    delta -> job.bus.emitLive("delta", Map.of("text", delta)),
                    toolResult -> onToolResult(job, toolResult)));
            if (job.toolCalls.get() == 0) {
                fallbackParse(job, response == null ? null : response.content());
            }
            job.bus.emit("done", Map.of(
                    "imported", job.imported.get(),
                    "failed", job.failed.get(),
                    "total", job.imported.get() + job.failed.get()));
        }
        catch (Throwable e) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [题库] AI 导入任务异常", e);
            job.bus.emit("log", Map.of("level", "ERROR", "message", "导入任务异常终止：" + e.getMessage()));
            job.bus.emit("done", Map.of("imported", job.imported.get(), "failed", job.failed.get(),
                    "total", job.imported.get() + job.failed.get(), "error", true));
        }
        finally {
            job.bus.complete();
        }
    }

    /** 工具回调即逐题入库结果：payload 由 CreateQuestionAiTool 给出 ok/summary/reason。 */
    private void onToolResult(ImportJob job, PluginAiToolResult toolResult) {
        int seq = job.toolCalls.incrementAndGet();
        Map<String, Object> payload = toolResult == null ? Map.of() : toolResult.payload();
        boolean ok = Boolean.TRUE.equals(payload.get("ok"));
        String summary = String.valueOf(payload.getOrDefault("summary", ""));
        if (ok) {
            job.imported.incrementAndGet();
            job.bus.emit("question", Map.of("seq", seq, "ok", true, "summary", summary));
        }
        else {
            job.failed.incrementAndGet();
            job.bus.emit("question", Map.of("seq", seq, "ok", false, "summary", summary,
                    "reason", String.valueOf(payload.getOrDefault("reason",
                            toolResult == null ? "未知错误" : toolResult.message()))));
        }
    }

    /** 模型没有调用工具（不支持工具调用或答非所问）时，对最终输出做容错解析再逐题入库。 */
    private void fallbackParse(ImportJob job, String content) {
        job.bus.emit("log", Map.of("level", "WARN", "message", "模型未调用创建工具，尝试从输出文本兜底解析…"));
        List<QuestionPayload> drafts;
        try {
            drafts = content == null || content.isBlank() ? List.of() : aiImportService.parseContent(content);
        }
        catch (RuntimeException e) {
            drafts = List.of();
        }
        if (drafts.isEmpty()) {
            job.bus.emit("log", Map.of("level", "WARN", "message", "AI 未能从内容中识别出有效题目，请检查文本或重试"));
            return;
        }
        job.bus.emit("log", Map.of("message", "兜底识别出 " + drafts.size() + " 道题，开始逐题入库"));
        int seq = 0;
        for (QuestionPayload draft : drafts) {
            if (seq >= MAX_QUESTIONS) {
                break;
            }
            seq++;
            importOne(job, draft, seq);
        }
    }

    /** 兜底路径的逐题落库：复用 QuestionService 的全量校验，单题失败不影响后续题目。 */
    private void importOne(ImportJob job, QuestionPayload draft, int seq) {
        String summary = summarize(draft.content());
        try {
            ImportResult result = questionService.importQuestions(List.of(draft), job.operatorId, job.operatorName);
            if (result.failures().isEmpty()) {
                job.imported.incrementAndGet();
                job.bus.emit("question", Map.of("seq", seq, "ok", true, "summary", summary));
            }
            else {
                job.failed.incrementAndGet();
                job.bus.emit("question", Map.of("seq", seq, "ok", false, "summary", summary,
                        "reason", result.failures().get(0).reason()));
            }
        }
        catch (RuntimeException e) {
            job.failed.incrementAndGet();
            job.bus.emit("question", Map.of("seq", seq, "ok", false, "summary", summary, "reason", e.getMessage()));
        }
    }

    private PluginAiService aiOrNull() {
        try {
            return framework.ai();
        }
        catch (Throwable e) {
            return null;
        }
    }

    private static Long parseUserId(String operatorId) {
        try {
            return operatorId == null ? null : Long.valueOf(operatorId);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** 同步等待流式调用结束；宿主实现不支持阻塞等待时直接报业务错误。 */
    private PluginAiChatResponse await(CompletionStage<PluginAiChatResponse> stage) {
        java.util.concurrent.CompletableFuture<PluginAiChatResponse> future;
        try {
            future = stage.toCompletableFuture();
        }
        catch (UnsupportedOperationException e) {
            throw new IllegalStateException("当前 AI 实现不支持流式调用，请联系平台管理员升级平台");
        }
        try {
            return future.get(AI_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        }
        catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("AI 响应超时，请重试");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 调用被中断，请重试");
        }
        catch (java.util.concurrent.ExecutionException e) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [题库] AI 流式导入调用失败", e);
            throw new IllegalStateException("AI 调用失败：" +
                    (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
        }
    }

    private void beat() {
        try {
            for (ImportJob job : jobs.values()) {
                if (!job.bus.isCompleted()) {
                    job.bus.emit("heartbeat", Map.of("ts", System.currentTimeMillis()));
                }
            }
        }
        catch (Throwable e) {
            LOGGER.log(Level.FINE, "[YuDreamAdmin] [题库] AI 导入心跳异常", e);
        }
    }

    /** 只保留最近 MAX_RETAINED_JOBS 个已完成任务，防止管理端反复导入撑爆内存。 */
    private void evictRetained() {
        if (jobs.size() < MAX_RETAINED_JOBS) {
            return;
        }
        jobs.values().removeIf(job -> job.bus.isCompleted());
    }

    private static String summarize(String content) {
        if (content == null) {
            return "";
        }
        String text = content.replaceAll("!\\[[^]]*]\\([^)]*\\)", "[图片]")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("[#>*`_~-]+", "")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    private static final class ImportJob {
        private final String id;
        private final String operatorId;
        private final String operatorName;
        private final ImportEventBus bus = new ImportEventBus();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicInteger imported = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();

        private ImportJob(String id, String operatorId, String operatorName) {
            this.id = id;
            this.operatorId = operatorId;
            this.operatorName = operatorName;
        }
    }

    /** SSE 事件总线：日志/逐题/汇总事件保留历史供订阅时回放；delta 增量不入历史（量大且有时效）。 */
    static final class ImportEventBus implements PluginSseStream {
        private static final int MAX_HISTORY = 500;

        private final List<Map<String, Object>> history = new ArrayList<>();
        private final List<Subscriber> subscribers = new ArrayList<>();
        private boolean completed;

        @Override
        public synchronized void subscribe(Subscriber subscriber) {
            for (Map<String, Object> event : history) {
                subscriber.send(String.valueOf(event.get("type")), event);
            }
            if (completed) {
                subscriber.complete();
            }
            else {
                subscribers.add(subscriber);
            }
        }

        @Override
        public synchronized void unsubscribe(Subscriber subscriber) {
            subscribers.remove(subscriber);
        }

        public void emit(String type, Map<String, Object> payload) {
            emit(type, payload, true);
        }

        /** 实时增量事件：只推给当前订阅者，不进历史（晚到的订阅者靠日志与汇总补齐状态）。 */
        public void emitLive(String type, Map<String, Object> payload) {
            emit(type, payload, false);
        }

        private synchronized void emit(String type, Map<String, Object> payload, boolean retain) {
            if (completed) {
                return;
            }
            Map<String, Object> event = new java.util.LinkedHashMap<>(payload);
            event.put("type", type);
            if (retain) {
                history.add(event);
                while (history.size() > MAX_HISTORY) {
                    history.remove(0);
                }
            }
            for (Subscriber subscriber : List.copyOf(subscribers)) {
                try {
                    subscriber.send(type, event);
                }
                catch (RuntimeException e) {
                    subscribers.remove(subscriber);
                }
            }
        }

        public synchronized void complete() {
            if (completed) {
                return;
            }
            completed = true;
            for (Subscriber subscriber : List.copyOf(subscribers)) {
                subscriber.complete();
            }
            subscribers.clear();
        }

        public synchronized boolean isCompleted() {
            return completed;
        }
    }
}
