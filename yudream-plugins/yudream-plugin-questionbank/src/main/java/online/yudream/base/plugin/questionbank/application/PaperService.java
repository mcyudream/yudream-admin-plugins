package online.yudream.base.plugin.questionbank.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import online.yudream.base.plugin.questionbank.domain.Paper;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.infrastructure.Ids;
import online.yudream.base.plugin.questionbank.infrastructure.PaperRepository;
import online.yudream.base.plugin.questionbank.infrastructure.QuestionRepository;

/**
 * 题单用例：管理端 CRUD/发布/归档，用户端已发布列表。
 * RULE 模式每次作答或打印都按规则现场随机抽题；MANUAL 模式固定选题顺序，跳过已删除/停用题目。
 */
public final class PaperService {
    private static final int MAX_PAPER_COUNT = 100;

    private final PaperRepository papers;
    private final QuestionRepository questions;

    public PaperService(PaperRepository papers, QuestionRepository questions) {
        this.papers = papers;
        this.questions = questions;
    }

    public PageResult<Paper> list(String keyword, String status, int page, int size) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<Paper> filtered = papers.listAll().stream()
                .filter(paper -> status == null || status.isBlank() || paper.status().equalsIgnoreCase(status))
                .filter(paper -> normalizedKeyword.isEmpty()
                        || paper.name().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                        || (paper.description() != null
                                && paper.description().toLowerCase(Locale.ROOT).contains(normalizedKeyword)))
                .toList();
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return PageResult.of(filtered.subList(from, to), filtered.size());
    }

    /** 用户端仅可见已发布题单。 */
    public List<Paper> listPublished() {
        return papers.listAll().stream().filter(Paper::published).toList();
    }

    public Paper require(String paperId) {
        return papers.findById(paperId).orElseThrow(() -> new NotFoundException("题单不存在"));
    }

    public Paper requirePublished(String paperId) {
        Paper paper = require(paperId);
        if (!paper.published()) {
            throw new IllegalArgumentException("题单未发布，暂不可作答");
        }
        return paper;
    }

    public Paper create(String adminId, String adminName, PaperPayload payload) {
        long now = System.currentTimeMillis();
        Paper paper = assemble(Ids.newId(), adminId, adminName, payload, null, now, now);
        papers.save(paper);
        return paper;
    }

    public Paper update(String paperId, PaperPayload payload) {
        Paper existing = require(paperId);
        Paper paper = assemble(existing.id(), existing.createdBy(), existing.createdByName(), payload,
                existing, existing.createdAt(), System.currentTimeMillis());
        papers.save(paper);
        return paper;
    }

    public void delete(String paperId) {
        require(paperId);
        papers.delete(paperId);
    }

    /**
     * 按题单模式抽题：RULE 现场随机抽 count 道（每次调用结果不同）；
     * MANUAL 按 questionIds 顺序取题，已删除或停用的题目自动跳过。
     */
    public List<Question> draw(Paper paper) {
        List<Question> enabled = questions.listAll().stream().filter(Question::enabled).toList();
        if (paper.ruleMode()) {
            List<Question> pool = new ArrayList<>(QuestionPool.filter(enabled, paper.categoryId(),
                    paper.tags(), paper.types(), paper.difficulties()));
            if (pool.isEmpty()) {
                throw new IllegalArgumentException("题单规则没有命中任何启用题目，请调整抽题规则");
            }
            Collections.shuffle(pool, new Random());
            return List.copyOf(pool.subList(0, Math.min(paper.count(), pool.size())));
        }
        List<Question> resolved = new ArrayList<>();
        for (String questionId : paper.questionIds()) {
            Optional<Question> question = questions.findById(questionId).filter(Question::enabled);
            question.ifPresent(resolved::add);
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("题单所选题目均已删除或停用，请重新选题");
        }
        return List.copyOf(resolved);
    }

    private Paper assemble(String id, String adminId, String adminName, PaperPayload payload,
            Paper existing, long createdAt, long updatedAt) {
        String name = payload.name() == null ? "" : payload.name().trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("题单名称不能为空");
        }
        String mode = payload.mode() == null || payload.mode().isBlank()
                ? Paper.MODE_RULE : payload.mode().trim().toUpperCase(Locale.ROOT);
        if (!Paper.MODE_RULE.equals(mode) && !Paper.MODE_MANUAL.equals(mode)) {
            throw new IllegalArgumentException("不支持的组题模式：" + mode);
        }
        String subjectiveMode = payload.subjectiveMode() == null || payload.subjectiveMode().isBlank()
                ? Paper.SUBJECTIVE_SELF : payload.subjectiveMode().trim().toUpperCase(Locale.ROOT);
        if (!Paper.SUBJECTIVE_SELF.equals(subjectiveMode) && !Paper.SUBJECTIVE_REVIEW.equals(subjectiveMode)
                && !Paper.SUBJECTIVE_AI.equals(subjectiveMode)) {
            throw new IllegalArgumentException("不支持的主观题评分方式：" + subjectiveMode);
        }
        String status = payload.status() == null || payload.status().isBlank()
                ? (existing == null ? Paper.STATUS_DRAFT : existing.status())
                : payload.status().trim().toUpperCase(Locale.ROOT);
        if (!Paper.STATUS_DRAFT.equals(status) && !Paper.STATUS_PUBLISHED.equals(status)
                && !Paper.STATUS_ARCHIVED.equals(status)) {
            throw new IllegalArgumentException("不支持的题单状态：" + status);
        }
        int count = payload.count() == null ? 10 : payload.count();
        if (count < 1 || count > MAX_PAPER_COUNT) {
            throw new IllegalArgumentException("抽题数量必须在 1~" + MAX_PAPER_COUNT + " 之间");
        }
        List<String> questionIds = payload.questionIds() == null ? List.of()
                : payload.questionIds().stream().filter(item -> item != null && !item.isBlank()).toList();
        if (Paper.MODE_MANUAL.equals(mode) && questionIds.isEmpty()) {
            throw new IllegalArgumentException("手动选题模式至少需要选择一道题目");
        }
        return new Paper(
                id,
                name,
                payload.description(),
                mode,
                blankToNull(payload.categoryId()),
                payload.tags() == null ? List.of() : List.copyOf(payload.tags()),
                payload.types() == null ? List.of() : List.copyOf(payload.types()),
                payload.difficulties() == null ? List.of() : List.copyOf(payload.difficulties()),
                count,
                questionIds,
                subjectiveMode,
                status,
                adminId,
                adminName,
                createdAt,
                updatedAt
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
