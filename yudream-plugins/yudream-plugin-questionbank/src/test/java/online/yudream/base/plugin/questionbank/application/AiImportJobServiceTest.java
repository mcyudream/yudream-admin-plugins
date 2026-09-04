package online.yudream.base.plugin.questionbank.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import online.yudream.base.plugin.questionbank.infrastructure.CategoryRepository;
import online.yudream.base.plugin.questionbank.infrastructure.CreateQuestionAiTool;
import online.yudream.base.plugin.questionbank.infrastructure.FakeDocumentStore;
import online.yudream.base.plugin.questionbank.infrastructure.FakeFramework;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.questionbank.infrastructure.QuestionRepository;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolCall;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AI 导入任务流：流式增量、工具逐题落库事件、无工具调用兜底解析、单飞行限制与历史回放。 */
class AiImportJobServiceTest {
    private FakeFramework framework;
    private QuestionService questionService;
    private CreateQuestionAiTool tool;
    private AiImportJobService jobs;

    @BeforeEach
    void setUp() {
        framework = new FakeFramework();
        FakeDocumentStore store = new FakeDocumentStore();
        QuestionRepository questions = new QuestionRepository(store);
        CategoryRepository categories = new CategoryRepository(store);
        CategoryService categoryService = new CategoryService(categories, questions);
        JsonSupport json = new JsonSupport(new ObjectMapper());
        questionService = new QuestionService(questions, categoryService, json, framework);
        tool = new CreateQuestionAiTool(questionService, json);
        jobs = new AiImportJobService(framework, new SettingsService(new FakeDocumentStore()),
                new AiImportService(new SettingsService(new FakeDocumentStore()), framework, json), questionService);
    }

    @AfterEach
    void tearDown() throws Exception {
        jobs.shutdownHook().close();
    }

    @Test
    void streamsDeltasAndPerQuestionToolEvents() {
        framework.setAi(new ToolCallingAi(tool, List.of(
                Map.of("type", "SINGLE", "content", "1+1=?", "options", List.of("1", "2", "3"), "answer", "B"),
                Map.of("type", "SINGLE", "content", "缺选项的坏题", "answer", "A"),
                Map.of("type", "SHORT", "content", "简述光合作用", "referenceAnswer", "利用光能"))));
        String jobId = jobs.start("第一道\n\n第二道\n\n第三道", "1", "管理员");
        List<Map<String, Object>> events = awaitEvents(jobId);

        List<Map<String, Object>> questionEvents = events.stream()
                .filter(event -> "question".equals(event.get("type"))).toList();
        assertEquals(3, questionEvents.size());
        assertEquals(Boolean.TRUE, questionEvents.get(0).get("ok"));
        assertEquals(Boolean.FALSE, questionEvents.get(1).get("ok"));
        assertNotNull(questionEvents.get(1).get("reason"));
        assertEquals(Boolean.TRUE, questionEvents.get(2).get("ok"));

        Map<String, Object> done = events.stream().filter(event -> "done".equals(event.get("type"))).findFirst().orElseThrow();
        assertEquals(2, done.get("imported"));
        assertEquals(1, done.get("failed"));
        assertEquals(3, done.get("total"));
        assertTrue(events.stream().anyMatch(event -> "log".equals(event.get("type"))));
        assertTrue(events.stream().anyMatch(event -> "delta".equals(event.get("type"))));
    }

    @Test
    void fallsBackToContentParsingWhenModelCallsNoTool() {
        framework.setAi(new StubAi("""
                [
                  {"type":"SINGLE","content":"2+2=?","options":["3","4"],"answer":"B"},
                  {"type":"SHORT","content":"简述蒸发","referenceAnswer":"液态变气态"}
                ]
                """));
        String jobId = jobs.start("题目内容", "1", "管理员");
        List<Map<String, Object>> events = awaitEvents(jobId);

        assertTrue(events.stream().anyMatch(event -> "log".equals(event.get("type"))
                && String.valueOf(event.get("message")).contains("兜底")));
        List<Map<String, Object>> questionEvents = events.stream()
                .filter(event -> "question".equals(event.get("type"))).toList();
        assertEquals(2, questionEvents.size());
        Map<String, Object> done = events.stream().filter(event -> "done".equals(event.get("type"))).findFirst().orElseThrow();
        assertEquals(2, done.get("imported"));
        assertEquals(0, done.get("failed"));
    }

    @Test
    void singleFlightAndValidation() {
        assertThrows(IllegalArgumentException.class, () -> jobs.start("  ", "1", "管理员"));
        assertThrows(IllegalArgumentException.class, () -> jobs.start("x".repeat(20001), "1", "管理员"));
        framework.setAi(new BlockingAi());
        String jobId = jobs.start("题目内容", "1", "管理员");
        assertNotNull(jobId);
        assertThrows(IllegalStateException.class, () -> jobs.start("另一批题目", "1", "管理员"));
    }

    @Test
    void unrecognizableContentDegradesToWarnLogAndCompletes() {
        framework.setAi(new StubAi("完全不是 JSON"));
        String jobId = jobs.start("无法解析的内容", "1", "管理员");
        List<Map<String, Object>> events = awaitEvents(jobId);
        assertTrue(events.stream().anyMatch(event -> "log".equals(event.get("type"))
                && String.valueOf(event.get("message")).contains("未能从内容中识别出有效题目")));
        Map<String, Object> done = events.stream().filter(event -> "done".equals(event.get("type"))).findFirst().orElseThrow();
        assertEquals(0, done.get("imported"));
        assertEquals(0, done.get("total"));
    }

    @Test
    void unknownJobStreamIsNull() {
        assertEquals(null, jobs.stream("missing"));
    }

    /** 订阅事件流并等待任务结束，返回完整事件历史（含回放语义）。 */
    private List<Map<String, Object>> awaitEvents(String jobId) {
        PluginSseStream stream = jobs.stream(jobId);
        assertNotNull(stream);
        List<Map<String, Object>> events = new ArrayList<>();
        boolean[] completed = {false};
        stream.subscribe(new PluginSseStream.Subscriber() {
            @Override
            @SuppressWarnings("unchecked")
            public void send(String event, Object data) {
                if (data instanceof Map<?, ?> map) {
                    events.add((Map<String, Object>) map);
                }
            }

            @Override
            public void complete() {
                completed[0] = true;
            }

            @Override
            public void error(Throwable throwable) {
                completed[0] = true;
            }
        });
        long deadline = System.currentTimeMillis() + 15000;
        while (!completed[0] && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(completed[0], "导入任务未在 15 秒内完成");
        return events;
    }

    /** 模拟宿主流式实现：推送增量，并像模型一样逐题调用 create_question 工具后回调 onTool。 */
    private static class ToolCallingAi extends StubAi {
        private final CreateQuestionAiTool tool;
        private final List<Map<String, Object>> questions;

        ToolCallingAi(CreateQuestionAiTool tool, List<Map<String, Object>> questions) {
            super("全部题目处理完毕");
            this.tool = tool;
            this.questions = questions;
        }

        @Override
        public java.util.concurrent.CompletionStage<PluginAiChatResponse> chatStream(PluginAiChatRequest request,
                Consumer<String> onDelta, Consumer<PluginAiToolResult> onTool) {
            onDelta.accept("正在识别第 1 道题…");
            List<PluginAiToolResult> results = new ArrayList<>();
            for (Map<String, Object> question : questions) {
                PluginAiToolResult result = tool.execute(request.executionContext(),
                        new PluginAiToolCall(CreateQuestionAiTool.TOOL_NAME, question));
                results.add(result);
                onTool.accept(result);
            }
            onDelta.accept("汇报：完成。");
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new PluginAiChatResponse("完成", results));
        }
    }

    private static class StubAi implements online.yudream.base.plugin.spi.system.ai.PluginAiService {
        private final String output;

        StubAi(String output) {
            this.output = output;
        }

        @Override
        public java.util.concurrent.CompletionStage<PluginAiChatResponse> chat(PluginAiChatRequest request) {
            return java.util.concurrent.CompletableFuture.completedFuture(new PluginAiChatResponse(output, List.of()));
        }

        @Override
        public List<online.yudream.base.plugin.spi.system.ai.PluginAiToolDescriptor> tools() {
            return List.of();
        }

        @Override
        public List<online.yudream.base.plugin.spi.system.ai.PluginAiProviderOption> providers() {
            return List.of();
        }

        @Override
        public List<online.yudream.base.plugin.spi.system.ai.PluginAiAgentOption> agents() {
            return List.of();
        }

        @Override
        public java.util.concurrent.CompletionStage<PluginAiChatResponse> runAgent(String agentCode, PluginAiChatRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    /** 永不返回的 AI，用于单飞行测试（任务一直占用 worker）。 */
    private static final class BlockingAi extends StubAi {
        BlockingAi() {
            super("");
        }

        @Override
        public java.util.concurrent.CompletionStage<PluginAiChatResponse> chat(PluginAiChatRequest request) {
            return new java.util.concurrent.CompletableFuture<>();
        }
    }
}
