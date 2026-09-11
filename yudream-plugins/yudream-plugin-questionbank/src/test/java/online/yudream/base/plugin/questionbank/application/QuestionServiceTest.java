package online.yudream.base.plugin.questionbank.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.domain.QuestionCategory;
import online.yudream.base.plugin.questionbank.infrastructure.CategoryRepository;
import online.yudream.base.plugin.questionbank.infrastructure.FakeDocumentStore;
import online.yudream.base.plugin.questionbank.infrastructure.FakeFramework;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.questionbank.infrastructure.PaperRepository;
import online.yudream.base.plugin.questionbank.infrastructure.QuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 题目用例：校验规范化、筛选分页、导入失败条目隔离、分类引用保护。 */
class QuestionServiceTest {
    private FakeDocumentStore store;
    private QuestionService questionService;
    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        store = new FakeDocumentStore();
        QuestionRepository questions = new QuestionRepository(store);
        CategoryRepository categories = new CategoryRepository(store);
        categoryService = new CategoryService(categories, questions, new PaperRepository(store));
        questionService = new QuestionService(questions, categoryService,
                new JsonSupport(new ObjectMapper()), new FakeFramework());
    }

    private QuestionPayload singlePayload() {
        return new QuestionPayload("SINGLE", null, null, List.of("java"), "1+1=?",
                List.of("1", "2", "3"), "b", List.of(), List.of(), null, null, null, null);
    }

    @Test
    void createNormalizesAnswerKeyAndTags() {
        Question created = questionService.create(singlePayload(), "1", "管理员");
        assertEquals("B", created.answer());
        assertEquals(3, created.difficulty());
        assertEquals(Question.STATUS_ENABLED, created.status());
        assertEquals(1, questionService.query(new QuestionQuery(null, null, null, null, null, null, 1, 20)).total());
    }

    @Test
    void createRejectsOutOfRangeAnswer() {
        QuestionPayload bad = new QuestionPayload("SINGLE", null, null, List.of(), "题干",
                List.of("甲", "乙"), "D", List.of(), List.of(), null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> questionService.create(bad, "1", null));
    }

    @Test
    void trueFalseAcceptsChineseAliases() {
        QuestionPayload payload = new QuestionPayload("TRUE_FALSE", null, null, List.of(), "地球是圆的",
                List.of(), "对", List.of(), List.of(), null, null, null, null);
        Question created = questionService.create(payload, "1", null);
        assertEquals("TRUE", created.answer());
    }

    @Test
    void fillRequiresAcceptedAnswerPerBlank() {
        QuestionPayload bad = new QuestionPayload("FILL", null, null, List.of(), "填空",
                List.of(), null, List.of(), List.of(List.of("答案"), List.of()), null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> questionService.create(bad, "1", null));
    }

    @Test
    void queryFiltersByTagAndKeyword() {
        questionService.create(singlePayload(), "1", null);
        questionService.create(new QuestionPayload("SINGLE", null, null, List.of("sql"), "数据库是什么",
                List.of("甲", "乙"), "A", List.of(), List.of(), null, null, 5, null), "1", null);
        assertEquals(1, questionService.query(new QuestionQuery(null, null, "sql", null, null, null, 1, 20)).total());
        assertEquals(1, questionService.query(new QuestionQuery("数据库", null, null, null, null, null, 1, 20)).total());
        assertEquals(1, questionService.query(new QuestionQuery(null, null, null, null, 5, null, 1, 20)).total());
        assertEquals(2, questionService.query(new QuestionQuery(null, null, null, null, null, null, 1, 20)).total());
    }

    @Test
    void importIsolatesFailuresAndCreatesCategoryByName() {
        QuestionPayload ok = new QuestionPayload("SINGLE", null, "编程", List.of(), "题干A",
                List.of("甲", "乙"), "A", List.of(), List.of(), null, null, null, null);
        QuestionPayload bad = new QuestionPayload("SINGLE", null, null, List.of(), "",
                List.of("甲", "乙"), "A", List.of(), List.of(), null, null, null, null);
        QuestionService.ImportResult result = questionService.importQuestions(List.of(ok, bad), "1", null);
        assertEquals(1, result.imported());
        assertEquals(1, result.failures().size());
        assertEquals(2, result.failures().get(0).index());
        List<?> categories = store.findAll("qb_categories", 1, 200);
        assertEquals(1, categories.size());
        assertEquals("编程", QuestionCategory.fromDoc((java.util.Map<String, Object>) categories.get(0)).name());
    }

    @Test
    void categoryDeleteBlockedWhenReferenced() {
        String categoryId = categoryService.create("编程", 0).id();
        questionService.create(new QuestionPayload("SINGLE", categoryId, null, List.of(), "题干",
                List.of("甲", "乙"), "A", List.of(), List.of(), null, null, null, null), "1", null);
        assertThrows(IllegalArgumentException.class, () -> categoryService.delete(categoryId));
    }

    @Test
    void exportJsonRestrictsToSelectedIds() {
        Question first = questionService.create(new QuestionPayload("SINGLE", null, null,
                List.of(), "勾选的题", List.of("甲", "乙"), "A", List.of(), List.of(), null, null, null, null), "1", null);
        questionService.create(new QuestionPayload("SINGLE", null, null,
                List.of(), "未勾选的题", List.of("甲", "乙"), "A", List.of(), List.of(), null, null, null, null), "1", null);
        String exported = questionService.exportJson(new QuestionQuery(null, null, null, null, null, null, 1, 20, List.of(first.id())));
        assertTrue(exported.contains("勾选的题"));
        assertFalse(exported.contains("未勾选的题"));
    }

    @Test
    void exportJsonRoundTripsThroughImport() {
        Question created = questionService.create(new QuestionPayload("MULTIPLE", null, "综合",
                List.of("t1"), "多选题干", List.of("甲", "乙", "丙"), null, List.of("a", "c"),
                List.of(), null, "解析", 4, null), "1", "管理员");
        String exported = questionService.exportJson(new QuestionQuery(null, null, null, null, null, null, 1, 20));
        assertTrue(exported.contains("多选题干"));

        // 全新环境导入导出结果
        store = new FakeDocumentStore();
        QuestionRepository questions = new QuestionRepository(store);
        CategoryRepository categories = new CategoryRepository(store);
        categoryService = new CategoryService(categories, questions, new PaperRepository(store));
        questionService = new QuestionService(questions, categoryService,
                new JsonSupport(new ObjectMapper()), new FakeFramework());
        try {
            com.fasterxml.jackson.databind.JsonNode root = new ObjectMapper().readTree(exported);
            List<QuestionPayload> payloads = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : root.get("questions")) {
                payloads.add(new ObjectMapper().treeToValue(node, QuestionPayload.class));
            }
            QuestionService.ImportResult result = questionService.importQuestions(payloads, "2", null);
            assertEquals(1, result.imported());
            assertTrue(result.failures().isEmpty());
            Question imported = questionService.query(new QuestionQuery(null, null, null, null, null, null, 1, 20)).records().get(0);
            assertEquals(List.of("A", "C"), imported.answers());
            assertEquals(created.content(), imported.content());
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void randomDrawSameSeedIsReproducible() {
        for (int i = 0; i < 10; i++) {
            questionService.create(new QuestionPayload("SINGLE", null, null, List.of("t"),
                    "题干" + i, List.of("甲", "乙"), "A", List.of(), List.of(), null, null, null, null), "1", null);
        }
        QuestionService.DrawResult first = questionService.randomDraw(null, List.of(), List.of(), null, 42L, 5);
        QuestionService.DrawResult second = questionService.randomDraw(null, List.of(), List.of(), null, 42L, 5);
        assertEquals(42L, first.seed());
        assertEquals(10, first.total());
        assertEquals(5, first.questions().size());
        assertEquals(first.questions().stream().map(Question::id).toList(),
                second.questions().stream().map(Question::id).toList());
    }

    @Test
    void randomDrawGeneratesSeedWhenAbsent() {
        questionService.create(singlePayload(), "1", null);
        QuestionService.DrawResult result = questionService.randomDraw(null, List.of(), List.of(), null, null, 1);
        assertEquals(1, result.questions().size());
        // 回传的 seed 可再次复现同一道题
        QuestionService.DrawResult replay = questionService.randomDraw(null, List.of(), List.of(), null, result.seed(), 1);
        assertEquals(result.questions().get(0).id(), replay.questions().get(0).id());
    }

    @Test
    void randomDrawOnlyEnabledAndFiltersByConditions() {
        String categoryId = categoryService.create("编程", 0).id();
        questionService.create(new QuestionPayload("SINGLE", categoryId, null, List.of("java"),
                "启用题", List.of("甲", "乙"), "A", List.of(), List.of(), null, null, 2, null), "1", null);
        questionService.create(new QuestionPayload("SINGLE", categoryId, null, List.of("java"),
                "停用题", List.of("甲", "乙"), "A", List.of(), List.of(), null, null, 2, "DISABLED"), "1", null);
        questionService.create(new QuestionPayload("TRUE_FALSE", null, null, List.of("sql"),
                "其他题", List.of(), "对", List.of(), List.of(), null, null, 5, null), "1", null);

        QuestionService.DrawResult result = questionService.randomDraw(
                categoryId, List.of("java"), List.of("single"), 2, 7L, 10);
        assertEquals(1, result.total());
        assertEquals("启用题", result.questions().get(0).content());
        // count 超过池大小时返回整个池
        assertEquals(1, result.questions().size());
    }

    @Test
    void randomDrawRejectsBadCountAndEmptyPool() {
        questionService.create(singlePayload(), "1", null);
        assertThrows(IllegalArgumentException.class,
                () -> questionService.randomDraw(null, List.of(), List.of(), null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> questionService.randomDraw(null, List.of(), List.of(), null, null, 51));
        assertThrows(IllegalArgumentException.class,
                () -> questionService.randomDraw(null, List.of("不存在的标签"), List.of(), null, null, 1));
    }
}
