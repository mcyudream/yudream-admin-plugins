package online.yudream.base.plugin.questionbank.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.infrastructure.QuestionRepository;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.document.PluginRenderedDocument;

/**
 * 组卷用例：随机抽题、按 id 集合取题、内置 docx 模板渲染导出。
 * 组卷不持久化（即组即下载）；模板位于插件 JAR 的 templates/compose-paper.docx，
 * 通过宿主 wordTemplates() 渲染，宿主关闭 Word 模板能力时明确报错。
 */
public final class ComposeService {
    private static final String TEMPLATE_RESOURCE = "/templates/compose-paper.docx";
    private static final int MAX_DRAW_COUNT = 100;
    private static final int MAX_EXPORT_COUNT = 200;

    private final QuestionRepository questions;
    private final FrameworkServices framework;

    public ComposeService(QuestionRepository questions, FrameworkServices framework) {
        this.questions = questions;
        this.framework = framework;
    }

    /** 按规则随机抽题（仅启用题）。空条件不参与过滤。 */
    public List<Question> draw(String categoryId, List<String> tags, List<String> types,
                               List<Integer> difficulties, int count) {
        if (count < 1 || count > MAX_DRAW_COUNT) {
            throw new IllegalArgumentException("抽题数量必须在 1~" + MAX_DRAW_COUNT + " 之间");
        }
        List<Question> pool = new ArrayList<>(QuestionPool.filter(
                questions.listAll().stream().filter(Question::enabled).toList(),
                categoryId, tags, types, difficulties));
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("没有符合条件的启用题目，请调整抽题规则");
        }
        Collections.shuffle(pool, ThreadLocalRandom.current());
        return pool.subList(0, Math.min(count, pool.size()));
    }

    /** 按给定 id 顺序取题（手选组卷）。缺失或停用题目被忽略并在返回数量上体现。 */
    public List<Question> byIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请先选择题目");
        }
        if (ids.size() > MAX_EXPORT_COUNT) {
            throw new IllegalArgumentException("单次最多导出 " + MAX_EXPORT_COUNT + " 道题");
        }
        Map<String, Question> byId = new LinkedHashMap<>();
        for (Question question : questions.listAll()) {
            byId.put(question.id(), question);
        }
        List<Question> result = new ArrayList<>();
        for (String id : ids) {
            Question question = byId.get(id);
            if (question != null && question.enabled() && result.stream().noneMatch(item -> item.id().equals(id))) {
                result.add(question);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("所选题目不存在或已停用");
        }
        return result;
    }

    /** 用内置模板渲染 Word 试卷。withAnswers=false 时答案段落整段移除（空集合清空循环块）。 */
    public PluginRenderedDocument exportWord(String title, String description,
                                             List<Question> questions, boolean withAnswers) {
        if (!framework.wordTemplates().enabled()) {
            throw new IllegalStateException("宿主 Word 模板能力未启用，无法导出 Word");
        }
        String safeTitle = title == null || title.isBlank() ? "试题卷" : title.trim();
        Map<String, Object> data = new HashMap<>();
        data.put("title", safeTitle);
        data.put("description", description == null ? "" : MarkdownPlainText.toSingleLine(description));
        data.put("count", questions.size());
        data.put("answerHeading", withAnswers ? "答案与解析" : "");
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> answers = new ArrayList<>();
        int number = 1;
        for (Question question : questions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("number", number);
            item.put("typeLabel", question.type().label());
            item.put("stem", MarkdownPlainText.toSingleLine(question.content()));
            List<Map<String, Object>> options = new ArrayList<>();
            if (question.type().isChoice() && question.options() != null) {
                for (int i = 0; i < question.options().size(); i++) {
                    Map<String, Object> option = new LinkedHashMap<>();
                    option.put("key", Question.optionKey(i));
                    option.put("text", question.options().get(i));
                    options.add(option);
                }
            }
            item.put("options", options);
            items.add(item);
            if (withAnswers) {
                Map<String, Object> answerItem = new LinkedHashMap<>();
                answerItem.put("number", number);
                answerItem.put("answer", answerText(question));
                String analysis = question.analysis() == null ? "" : MarkdownPlainText.toSingleLine(question.analysis());
                answerItem.put("analysis", analysis.isEmpty() ? "" : "解析：" + analysis);
                answers.add(answerItem);
            }
            number++;
        }
        data.put("questions", items);
        data.put("answers", answers);
        return framework.wordTemplates().render(templateBytes(), data);
    }

    /** 各题型的纯文本答案（单行）。 */
    public static String answerText(Question question) {
        return switch (question.type()) {
            case SINGLE -> question.answer() == null ? "" : question.answer();
            case TRUE_FALSE -> "TRUE".equals(question.answer()) ? "对" : "错";
            case MULTIPLE -> String.join("、", question.answers() == null ? List.<String>of() : question.answers());
            case FILL -> {
                List<List<String>> blanks = question.blanks() == null ? List.<List<String>>of() : question.blanks();
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < blanks.size(); i++) {
                    parts.add("第" + (i + 1) + "空：" + String.join(" / ", blanks.get(i)));
                }
                yield String.join("；", parts);
            }
            case SHORT -> question.referenceAnswer() == null ? "" : MarkdownPlainText.toSingleLine(question.referenceAnswer());
        };
    }

    private byte[] templateBytes() {
        try (InputStream inputStream = ComposeService.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("插件内置 Word 模板缺失：" + TEMPLATE_RESOURCE);
            }
            return inputStream.readAllBytes();
        }
        catch (IOException e) {
            throw new IllegalStateException("读取内置 Word 模板失败：" + e.getMessage());
        }
    }
}
