package online.yudream.base.plugin.feedback.application.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import online.yudream.base.plugin.feedback.application.FeedbackNotFoundException;
import online.yudream.base.plugin.feedback.application.assembler.FeedbackAssembler;
import online.yudream.base.plugin.feedback.application.cmd.FeedbackSubmitCmd;
import online.yudream.base.plugin.feedback.application.dto.AssignableUsersDTO;
import online.yudream.base.plugin.feedback.application.dto.FeedbackDTO;
import online.yudream.base.plugin.feedback.application.dto.FeedbackPageDTO;
import online.yudream.base.plugin.feedback.application.dto.FeedbackStatsDTO;
import online.yudream.base.plugin.feedback.application.dto.FeedbackUserOptionDTO;
import online.yudream.base.plugin.feedback.domain.aggregate.Feedback;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackCommentRole;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackSeverity;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackStatus;
import online.yudream.base.plugin.feedback.domain.repo.FeedbackRepository;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackQuery;
import online.yudream.base.plugin.feedback.infrastructure.Ids;
import online.yudream.base.plugin.spi.system.user.PluginUserOption;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.spi.system.user.PluginUserService;

/**
 * 问题反馈用例编排。
 *
 * <p>用户端与管理端用例严格分离：用户端所有读写都先按「反馈 ID + 当前登录者」定位数据，
 * 管理端才做跨用户查询与受理。管理权限不会扩大用户端的数据范围。
 *
 * <p>管理端的所有更新动作都会先把操作者登记为处理人（见 {@link Feedback#claim(String, long)}），
 * 因此一条反馈天然支持多人协作：谁回复过、谁流转过状态，谁就出现在处理人名单里。
 *
 * <p>受理类动作（官方回复、内部备注、调整严重程度、调整处理人）还会把「待处理」自动推进为
 * 「处理中」，避免已经受理却仍停在待处理；显式的状态流转不参与联动，见
 * {@link #autoStartProcessing(Feedback, String, long)}。
 */
public class FeedbackAppService {

    private final FeedbackRepository repository;
    private final PluginUserService users;
    private final FeedbackAssembler assembler = new FeedbackAssembler();

    public FeedbackAppService(FeedbackRepository repository, PluginUserService users) {
        this.repository = repository;
        this.users = users;
    }

    // ------------------------------------------------------------------ 用户端

    /** 提交反馈：归属强制取当前登录者，状态固定为待处理。 */
    public FeedbackDTO submit(String authorId, FeedbackSubmitCmd cmd) {
        long now = System.currentTimeMillis();
        Feedback feedback = Feedback.create(Ids.next(), authorId, cmd.title(), cmd.description(),
                cmd.type(), cmd.severity(), cmd.attachments(), now);
        return dto(repository.save(feedback), authorId, false);
    }

    /** 我的反馈：忽略客户端可能传入的作者筛选，无条件收敛到当前登录者。 */
    public FeedbackPageDTO<FeedbackDTO> listMyFeedbacks(String authorId, FeedbackQuery query) {
        FeedbackQuery scoped = query.authorId(authorId);
        List<FeedbackDTO> records = repository.findByQuery(scoped).stream()
                .map(feedback -> dto(feedback, authorId, false))
                .toList();
        return new FeedbackPageDTO<>(records, repository.countByQuery(scoped), scoped.page(), scoped.size());
    }

    public FeedbackDTO myFeedback(String authorId, String feedbackId) {
        return dto(requireMine(feedbackId, authorId), authorId, false);
    }

    /** 我的反馈统计：口径与列表一致，全部基于本人数据。 */
    public FeedbackStatsDTO myStats(String authorId) {
        return stats(feedback -> feedback.ownedBy(authorId));
    }

    /** 修改自己的反馈内容；仅未完结时可改。 */
    public FeedbackDTO updateMyFeedback(String authorId, String feedbackId, FeedbackSubmitCmd cmd) {
        Feedback feedback = requireMine(feedbackId, authorId);
        feedback.updateContent(cmd.title(), cmd.description(), cmd.type(), cmd.severity(),
                cmd.attachments(), System.currentTimeMillis());
        return dto(repository.save(feedback), authorId, false);
    }

    /** 追加说明：用户端评论固定为用户角色，不接受内部备注标记。 */
    public FeedbackDTO commentMyFeedback(String authorId, String feedbackId, String content) {
        Feedback feedback = requireMine(feedbackId, authorId);
        feedback.comment(Ids.next(), authorId, FeedbackCommentRole.USER, content, false,
                System.currentTimeMillis());
        return dto(repository.save(feedback), authorId, false);
    }

    /**
     * 提交人归档：管理员给出结论（已解决 / 不予处理）后才可以归档，归档为终态。
     *
     * <p>这是「用户接受结论」的动作，不做任何附带的自动流转；未归档的会在
     * {@link Feedback#ARCHIVE_GRACE_DAYS} 天后由定时任务自动归档。
     */
    public FeedbackDTO archiveMyFeedback(String authorId, String feedbackId) {
        Feedback feedback = requireMine(feedbackId, authorId);
        feedback.archive(authorId, "", System.currentTimeMillis());
        return dto(repository.save(feedback), authorId, false);
    }

    /** 撤回反馈：只在未完结时允许，已受理出结论的反馈保留作为记录。 */
    public void withdrawMyFeedback(String authorId, String feedbackId) {
        Feedback feedback = requireMine(feedbackId, authorId);
        if (!feedback.open()) {
            throw new IllegalArgumentException("反馈已完结，无法撤回；如仍需处理请重新打开或追加说明");
        }
        repository.delete(feedback);
    }

    // ------------------------------------------------------------------ 管理端

    public FeedbackPageDTO<FeedbackDTO> adminPageFeedbacks(FeedbackQuery query) {
        List<FeedbackDTO> records = repository.findByQuery(query).stream()
                .map(feedback -> dto(feedback, null, true))
                .toList();
        return new FeedbackPageDTO<>(records, repository.countByQuery(query), query.page(), query.size());
    }

    public FeedbackDTO adminFeedback(String feedbackId) {
        return dto(requireExisting(feedbackId), null, true);
    }

    public FeedbackStatsDTO adminStats() {
        return stats(feedback -> true);
    }

    /** 官方回复：以管理员角色写入公开评论，用户端可见。 */
    public FeedbackDTO adminReply(String feedbackId, String operatorId, String content) {
        Feedback feedback = requireExisting(feedbackId);
        long now = System.currentTimeMillis();
        feedback.claim(operatorId, now);
        autoStartProcessing(feedback, operatorId, now);
        feedback.comment(Ids.next(), operatorId, FeedbackCommentRole.ADMIN, content, false, now);
        return dto(repository.save(feedback), null, true);
    }

    /** 内部备注：只有管理员能看到，不进入用户端响应，也不触发用户侧的状态变化。 */
    public FeedbackDTO adminNote(String feedbackId, String operatorId, String content) {
        Feedback feedback = requireExisting(feedbackId);
        long now = System.currentTimeMillis();
        feedback.claim(operatorId, now);
        autoStartProcessing(feedback, operatorId, now);
        feedback.comment(Ids.next(), operatorId, FeedbackCommentRole.ADMIN, content, true, now);
        return dto(repository.save(feedback), null, true);
    }

    /** 状态流转；note 作为结论说明写入留痕，便于事后回溯处理依据。 */
    public FeedbackDTO adminChangeStatus(String feedbackId, String operatorId, String status, String note) {
        Feedback feedback = requireExisting(feedbackId);
        long now = System.currentTimeMillis();
        feedback.claim(operatorId, now);
        feedback.changeStatus(FeedbackStatus.of(status), operatorId, note, now);
        return dto(repository.save(feedback), null, true);
    }

    /**
     * 整体替换处理人名单。
     *
     * <p>处理人必须来自宿主账号目录，禁止手输内部 ID；这里不做「自动加入操作者」——
     * 该动作本身就是处理人的显式维护，若再自动加入会导致无法把自己移出名单。
     */
    /**
     * 管理端快捷归档：详情页的「流转为已归档」搬到列表上，留痕记为归档而不是状态变更。
     *
     * <p>与提交人归档不同，这里不要求先给出结论；已归档的反馈再归档会由领域层报错。
     */
    public FeedbackDTO adminArchiveFeedback(String feedbackId, String operatorId) {
        Feedback feedback = requireExisting(feedbackId);
        feedback.archiveByAdmin(operatorId, System.currentTimeMillis());
        return dto(repository.save(feedback), null, true);
    }

    public FeedbackDTO adminAssign(String feedbackId, String operatorId, List<String> assigneeIds) {
        Feedback feedback = requireExisting(feedbackId);
        List<String> requested = distinct(assigneeIds);
        if (requested.size() > Feedback.MAX_ASSIGNEE_COUNT) {
            throw new IllegalArgumentException("单条反馈最多只能有 " + Feedback.MAX_ASSIGNEE_COUNT + " 个处理人");
        }
        List<String> names = new ArrayList<>();
        for (String assigneeId : requested) {
            names.add(requireUser(assigneeId));
        }
        long now = System.currentTimeMillis();
        autoStartProcessing(feedback, operatorId, now);
        feedback.replaceAssignees(requested, names, operatorId, now);
        return dto(repository.save(feedback), null, true);
    }

    public FeedbackDTO adminUpdateSeverity(String feedbackId, String operatorId, String severity) {
        Feedback feedback = requireExisting(feedbackId);
        long now = System.currentTimeMillis();
        feedback.claim(operatorId, now);
        autoStartProcessing(feedback, operatorId, now);
        feedback.changeSeverity(FeedbackSeverity.of(severity), operatorId, now);
        return dto(repository.save(feedback), null, true);
    }

    public void adminDeleteFeedback(String feedbackId) {
        repository.delete(requireExisting(feedbackId));
    }

    // ------------------------------------------------------------------ 人员

    public List<FeedbackUserOptionDTO> searchUsers(String keyword, int page, int size) {
        if (users == null) {
            return List.of();
        }
        String trimmed = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return users.searchUsers(trimmed, null, page, size).stream()
                .map(this::toUserOption)
                .toList();
    }

    /**
     * 「可指派处理人」候选：来自宿主 SPI 的账号目录。
     *
     * <p>宿主 SPI 只提供「判断当前调用者有没有某个权限」，没有「按权限反查用户」的能力；
     * 调用宿主内部 HTTP 接口反查会绕过插件契约，因此这里只按关键词列出账号目录成员。
     * 受此限制，候选不再按反馈管理权限预筛，管理员指派前需自行确认所选成员可访问反馈管理页。
     */
    public AssignableUsersDTO searchAssignableUsers(String keyword, int page, int size) {
        if (users == null) {
            return new AssignableUsersDTO(List.of(), false, "宿主账号目录不可用，无法选择处理人");
        }
        String trimmed = keyword == null || keyword.isBlank() ? null : keyword.trim();
        List<FeedbackUserOptionDTO> records = users.searchUsers(trimmed, null, page, size).stream()
                .map(this::toUserOption)
                .toList();
        return new AssignableUsersDTO(records, true,
                "候选来自宿主账号目录；受宿主 SPI 能力限制，未按反馈管理权限预筛，请确认所选成员可访问反馈管理页");
    }

    public List<FeedbackUserOptionDTO> usersByIds(List<String> userIds) {
        if (users == null || userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<FeedbackUserOptionDTO> result = new ArrayList<>();
        for (String userId : distinct(userIds)) {
            Long id = parseLongOrNull(userId);
            if (id == null) {
                continue;
            }
            users.findById(id).map(this::toUserOption).ifPresent(result::add);
        }
        return result;
    }

    // ------------------------------------------------------------------ 内部

    private FeedbackDTO dto(Feedback feedback, String viewerId, boolean viewerAdmin) {
        return assembler.toDTO(feedback, this::displayName, viewerId, viewerAdmin);
    }

    private FeedbackStatsDTO stats(Predicate<Feedback> scope) {
        List<Feedback> scoped = repository.findAll().stream().filter(scope).toList();
        long archived = count(scoped, FeedbackStatus.ARCHIVED);
        long pending = count(scoped, FeedbackStatus.PENDING);
        long processing = count(scoped, FeedbackStatus.PROCESSING);
        return new FeedbackStatsDTO(
                // 与列表同一口径：归档单独成卡，不再重复计入「全部」
                scoped.size() - archived,
                pending,
                processing,
                pending + processing,
                count(scoped, FeedbackStatus.RESOLVED),
                count(scoped, FeedbackStatus.REJECTED),
                archived
        );
    }

    private static long count(List<Feedback> feedbacks, FeedbackStatus status) {
        return feedbacks.stream().filter(feedback -> feedback.status() == status).count();
    }

    /**
     * 管理员受理动作的默认副作用：待处理的反馈一旦被管理员经手就进入「处理中」。
     *
     * <p>显式的状态流转不参与联动——那是管理员对状态的直接意图，若也自动流转，
     * 「把反馈退回待处理」会被立刻翻回处理中。
     */
    private void autoStartProcessing(Feedback feedback, String operatorId, long now) {
        if (feedback.status() != FeedbackStatus.PENDING) {
            return;
        }
        feedback.changeStatus(FeedbackStatus.PROCESSING, operatorId, "管理员受理自动流转", now);
    }

    /**
     * 自动归档：已给出结论、且超过宽限期仍未主动归档的反馈由系统收口。
     *
     * <p>由插件定时任务调用，单轮扫描全量反馈；只有命中才写库与留痕，
     * 因此反馈量不大时开销可以忽略。
     *
     * @return 本轮归档的条数
     */
    public int autoArchiveExpired(long now) {
        int archived = 0;
        for (Feedback feedback : repository.findAll()) {
            if (!feedback.archiveExpired(now)) {
                continue;
            }
            feedback.archive(Feedback.SYSTEM_ACTOR,
                    "超过 " + Feedback.ARCHIVE_GRACE_DAYS + " 天未归档，系统自动归档", now);
            repository.save(feedback);
            archived++;
        }
        return archived;
    }

    /** 用户端按「反馈 ID + 归属人」定位，他人反馈一律按不存在处理，不泄露存在性。 */
    private Feedback requireMine(String feedbackId, String authorId) {
        return repository.findByIdAndAuthorId(feedbackId, authorId)
                .orElseThrow(() -> new FeedbackNotFoundException("反馈不存在或已被撤回"));
    }

    private Feedback requireExisting(String feedbackId) {
        return repository.findById(feedbackId)
                .orElseThrow(() -> new FeedbackNotFoundException("反馈不存在或已被删除"));
    }

    /** 校验成员存在并返回展示名，供处理人留痕文案使用。 */
    private String requireUser(String userId) {
        if (users == null) {
            throw new IllegalStateException("人员目录当前不可用，请稍后重试");
        }
        Long id = parseLongOrNull(userId);
        if (id == null) {
            throw new IllegalArgumentException("所选成员不存在，请重新选择");
        }
        return users.findById(id)
                .map(user -> user.nickname() == null || user.nickname().isBlank()
                        ? user.username() : user.nickname())
                .orElseThrow(() -> new IllegalArgumentException("所选成员不存在，请重新选择"));
    }

    private FeedbackUserOptionDTO toUserOption(PluginUserOption user) {
        return new FeedbackUserOptionDTO(stringOrEmpty(user.id()), user.username(), user.nickname(),
                user.email(), user.avatar(), user.status());
    }

    private FeedbackUserOptionDTO toUserOption(PluginUserProfile user) {
        return new FeedbackUserOptionDTO(String.valueOf(user.id()), user.username(), user.nickname(),
                user.email(), user.avatar(), user.status());
    }

    /** 展示名优先昵称，其次用户名；目录不可用时回退为空串，由前端按 ID 兜底展示。 */
    private String displayName(String userId) {
        if (users == null || userId == null || userId.isBlank()) {
            return "";
        }
        Long id = parseLongOrNull(userId);
        if (id == null) {
            return "";
        }
        return users.findById(id)
                .map(user -> user.nickname() == null || user.nickname().isBlank()
                        ? user.username() : user.nickname())
                .orElse("");
    }

    private static List<String> distinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            String item = value == null ? "" : value.trim();
            if (!item.isEmpty()) {
                cleaned.add(item);
            }
        }
        return new ArrayList<>(cleaned);
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
