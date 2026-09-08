package online.yudream.base.plugin.activityproof.application.service;

import online.yudream.base.plugin.activityproof.application.cmd.ActivityBindingCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityParticipantAddCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityProofExportCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityProofMappingSaveCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityProofSettingsSaveCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityProofStampedPdfUploadCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityProofTemplateSelectCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivitySaveCmd;
import online.yudream.base.plugin.activityproof.application.cmd.ActivityTemplateMembersSaveCmd;
import online.yudream.base.plugin.activityproof.application.dto.ActivityBindingDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityDeptOptionDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityFormOptionDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityParticipantAdminDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityProofDependencyDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityProofDownloadDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityProofExportDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityProofMappingDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityProofPageDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityProofServerDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityProofSettingsDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityProofStatusDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityProofTemplateDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityQqConnectionDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityQqGroupDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityTemplateMembersDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityUserOptionDTO;
import online.yudream.base.plugin.activityproof.application.dto.ActivityVerifyResultDTO;
import online.yudream.base.plugin.activityproof.application.dto.MyParticipationDTO;
import online.yudream.base.plugin.activityproof.application.dto.ServerParticipantSyncResultDTO;
import online.yudream.base.plugin.activityproof.application.dto.UserActivityDTO;
import online.yudream.base.plugin.activityproof.application.dto.UserRequirementDTO;
import online.yudream.base.plugin.activityproof.domain.aggregate.Activity;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityParticipation;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityProofExportRecord;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityProofParticipantSnapshot;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityProofSettings;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityProofTemplateMembers;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityQuizAttempt;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityQuizConfig;
import online.yudream.base.plugin.activityproof.domain.aggregate.PlayerStudentMapping;
import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityBindingType;
import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityDeptMode;
import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityStatus;
import online.yudream.base.plugin.activityproof.domain.enumerate.ParticipationSource;
import online.yudream.base.plugin.activityproof.domain.enumerate.VerifyStatus;
import online.yudream.base.plugin.activityproof.domain.repo.ActivityProofRepository;
import online.yudream.base.plugin.activityproof.domain.valobj.ActivityBinding;
import online.yudream.base.plugin.activityproof.infrastructure.support.SoftDependencyServices;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftActivePlayer;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftOnlineWindow;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftServer;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftService;
import online.yudream.base.plugin.skin.api.PluginSkinService;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.document.PluginRenderedDocument;
import online.yudream.base.plugin.spi.system.document.PluginWordTemplateSummary;
import online.yudream.base.plugin.spi.system.form.PluginFormService;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingConnection;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingService;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.user.PluginDeptOption;
import online.yudream.base.plugin.spi.system.user.PluginUserDept;
import online.yudream.base.plugin.spi.system.user.PluginUserOption;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.studentinfo.api.PluginStudentInfoProfile;
import online.yudream.base.plugin.studentinfo.api.PluginStudentInfoService;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ActivityProofAppService {

    private static final String STUDENT_INFO_PLUGIN = "yudream-student-info";
    private static final String DEFAULT_TEMPLATE_CODE = "minecraft_activity_proof_v1";
    private static final String DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String DEFAULT_QQ_MESSAGE_TEMPLATE =
            "【新活动】{title}\n{summary}\n报名时间：{signupTime}\n活动时间：{activityTime}";
    // 官方 QQ 连接按 markdown 渲染：默认模板带版式；管理员自定义模板原样使用（自定义内容须自行保证是合法 markdown）
    private static final String DEFAULT_QQ_MARKDOWN_TEMPLATE =
            "# 📢 新活动发布\n\n**{title}**\n\n{summary}\n\n> 🕐 报名时间：{signupTime}\n> 🗓️ 活动时间：{activityTime}";
    private static final int SCAN_PAGE_SIZE = 200;
    private static final int MAX_SCAN_SIZE = 1000;
    private static final int QQ_ACTIVITY_LIST_LIMIT = 5;

    private final ActivityProofRepository repository;
    private final PluginFileStore files;
    private final FrameworkServices framework;
    private final PluginContext pluginContext;
    private final ActivityQuizService quizService;

    public ActivityProofAppService(ActivityProofRepository repository, PluginFileStore files, FrameworkServices framework,
                                   PluginContext pluginContext, ActivityQuizService quizService) {
        this.repository = repository;
        this.files = files;
        this.framework = framework;
        this.pluginContext = pluginContext;
        this.quizService = quizService;
    }

    // ---------------------------------------------------------------- status

    public ActivityProofStatusDTO status() {
        return new ActivityProofStatusDTO(dependencies(), toDTO(currentSettings()));
    }

    public ActivityProofDependencyDTO dependencies() {
        return new ActivityProofDependencyDTO(
                minecraftService().isPresent(),
                studentInfoService().isPresent(),
                wordTemplateEnabled(),
                formEnabled(),
                skinService().isPresent(),
                quizService.quizAvailable()
        );
    }

    // ---------------------------------------------------------------- settings & templates

    public ActivityProofSettingsDTO settings() {
        return toDTO(currentSettings());
    }

    public ActivityProofSettingsDTO saveSettings(ActivityProofSettingsSaveCmd cmd) {
        long now = System.currentTimeMillis();
        ActivityProofSettings settings = repository.settings()
                .withDefaults(cmd.defaultActivityName(), cmd.defaultCollege(), cmd.defaultIssuer(), now)
                .withQqNotify(Boolean.TRUE.equals(cmd.qqNotifyEnabled()), cmd.qqConnectionId(), cmd.qqGroupIds(),
                        cmd.qqMessageTemplate(), Boolean.TRUE.equals(cmd.qqSignupButtonEnabled()),
                        cmd.qqSignupButtonLabel(), now);
        if (hasText(cmd.templateId())) {
            settings = withTemplate(settings, cmd.templateId());
        } else {
            settings = refreshTemplate(settings);
        }
        return toDTO(repository.saveSettings(settings));
    }

    public List<ActivityProofTemplateDTO> templates(String keyword, int page, int size) {
        if (!wordTemplateEnabled()) {
            return List.of();
        }
        return framework.wordTemplates().templates(keyword, safePage(page), safeSize(size)).stream()
                .map(this::toDTO)
                .toList();
    }

    public ActivityProofSettingsDTO selectTemplate(ActivityProofTemplateSelectCmd cmd) {
        ActivityProofSettings settings = withTemplate(repository.settings(), cmd.templateId());
        return toDTO(repository.saveSettings(settings));
    }

    public List<ActivityProofServerDTO> servers() {
        return minecraftService().map(service -> service.minecraftServers(true).stream()
                        .map(server -> new ActivityProofServerDTO(server.id(), server.name(), server.enabled(),
                                server.currentSeasonName(), server.currentSeasonStartedAt()))
                        .toList())
                .orElse(List.of());
    }

    // ---------------------------------------------------------------- option selectors

    public List<ActivityDeptOptionDTO> departmentOptions(String keyword) {
        if (framework == null || framework.users() == null) {
            return List.of();
        }
        List<ActivityDeptOptionDTO> result = new ArrayList<>();
        for (PluginDeptOption root : framework.users().listDepartments(hasText(keyword) ? keyword.trim() : null)) {
            flattenDept(root, "", result);
        }
        return result;
    }

    public List<ActivityFormOptionDTO> formOptions(String keyword, int page, int size) {
        PluginFormService forms = formService();
        if (forms == null || !forms.enabled()) {
            return List.of();
        }
        return forms.publishedForms(hasText(keyword) ? keyword.trim() : null, safePage(page), safeSize(size)).stream()
                .map(form -> new ActivityFormOptionDTO(form.code(), form.name(), form.description(), form.publishedAt()))
                .toList();
    }

    public ActivityProofPageDTO<ActivityUserOptionDTO> userOptions(String keyword, int page, int size) {
        if (framework == null || framework.users() == null) {
            return new ActivityProofPageDTO<>(List.of(), 0);
        }
        int safePage = safePage(page);
        int safeSize = Math.min(safeSize(size), 50);
        // SPI 无用户总数统计，多取一条探测是否还有下一页
        List<PluginUserOption> probe = framework.users().searchUsers(hasText(keyword) ? keyword.trim() : null, null, safePage, safeSize + 1);
        boolean hasMore = probe.size() > safeSize;
        List<ActivityUserOptionDTO> records = probe.stream().limit(safeSize)
                .map(option -> new ActivityUserOptionDTO(text(option.id()), text(option.username()), text(option.nickname()),
                        option.deptNames() == null ? List.of() : option.deptNames()))
                .toList();
        long total = hasMore ? (long) safePage * safeSize + 1 : (long) (safePage - 1) * safeSize + records.size();
        return new ActivityProofPageDTO<>(records, total);
    }

    private void flattenDept(PluginDeptOption node, String parentLabel, List<ActivityDeptOptionDTO> output) {
        if (node == null) {
            return;
        }
        String name = text(node.name());
        String label = parentLabel.isBlank() ? name : parentLabel + " / " + name;
        output.add(new ActivityDeptOptionDTO(text(node.id()), name, label, text(node.parentId())));
        for (PluginDeptOption child : node.children() == null ? List.<PluginDeptOption>of() : node.children()) {
            flattenDept(child, label, output);
        }
    }

    // ---------------------------------------------------------------- admin: activities

    public ActivityProofPageDTO<ActivityDTO> adminActivities(String keyword, String status, int page, int size) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        List<ActivityDTO> records = repository.activities(keyword, status, safePage, safeSize).stream()
                .map(this::toDTO)
                .toList();
        return new ActivityProofPageDTO<>(records, repository.countActivities(keyword, status));
    }

    public ActivityDTO adminActivity(String id) {
        return toDTO(requireActivity(id));
    }

    public ActivityDTO createActivity(ActivitySaveCmd cmd, String operatorUserId) {
        Activity activity = Activity.create(
                requireText(cmd.title(), "活动标题不能为空"),
                cmd.summary(), cmd.description(), cmd.coverUrl(),
                epoch(cmd.signupStart()), epoch(cmd.signupEnd()), epoch(cmd.activityStart()), epoch(cmd.activityEnd()),
                ActivityDeptMode.of(cmd.deptMode()),
                cmd.allowedDeptIds() == null ? List.of() : cmd.allowedDeptIds(),
                toBindings(cmd.bindings()),
                text(operatorUserId)
        );
        return toDTO(repository.saveActivity(activity));
    }

    public ActivityDTO updateActivity(ActivitySaveCmd cmd) {
        Activity activity = requireActivity(cmd.id());
        Activity updated = activity.update(
                requireText(cmd.title(), "活动标题不能为空"),
                cmd.summary(), cmd.description(), cmd.coverUrl(),
                epoch(cmd.signupStart()), epoch(cmd.signupEnd()), epoch(cmd.activityStart()), epoch(cmd.activityEnd()),
                ActivityDeptMode.of(cmd.deptMode()),
                cmd.allowedDeptIds() == null ? List.of() : cmd.allowedDeptIds(),
                toBindings(cmd.bindings())
        );
        return toDTO(repository.saveActivity(updated));
    }

    public ActivityDTO publishActivity(String id) {
        Activity activity = requireActivity(id).publish();
        Activity saved = repository.saveActivity(activity);
        notifyQqGroups(saved);
        return toDTO(saved);
    }

    public ActivityDTO closeActivity(String id) {
        Activity activity = requireActivity(id).close();
        return toDTO(repository.saveActivity(activity));
    }

    public void deleteActivity(String id) {
        Activity activity = requireActivity(id);
        if (activity.status() != ActivityStatus.DRAFT) {
            throw new IllegalArgumentException("只有草稿状态的活动可以删除；已发布活动请先结束");
        }
        repository.deleteActivity(activity.id());
    }

    private List<ActivityBinding> toBindings(List<ActivityBindingCmd> cmds) {
        if (cmds == null) {
            return List.of();
        }
        List<ActivityBinding> bindings = new ArrayList<>();
        for (ActivityBindingCmd cmd : cmds) {
            if (cmd == null) {
                continue;
            }
            ActivityBindingType type = ActivityBindingType.of(cmd.type());
            if (type == null) {
                throw new IllegalArgumentException("未知核验方式：" + cmd.type());
            }
            if (type == ActivityBindingType.PLAYTIME) {
                bindings.add(ActivityBinding.playtime(cmd.serverId(),
                        cmd.minOnlineMinutes() == null ? 0 : cmd.minOnlineMinutes(),
                        Boolean.TRUE.equals(cmd.includeAfk()),
                        Boolean.TRUE.equals(cmd.autoJoin())));
            } else if (type == ActivityBindingType.QUIZ) {
                if (bindings.stream().anyMatch(ActivityBinding::isQuiz)) {
                    throw new IllegalArgumentException("答题核验方式至多添加一个");
                }
                bindings.add(ActivityBinding.quiz());
            } else {
                bindings.add(ActivityBinding.form(cmd.formCode(), formName(cmd.formCode())));
            }
        }
        return bindings;
    }

    private String formName(String formCode) {
        if (!hasText(formCode)) {
            return "";
        }
        PluginFormService forms = formService();
        if (forms == null || !forms.enabled()) {
            return "";
        }
        try {
            return forms.formByCode(formCode.trim()).map(form -> text(form.name())).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- admin: participants & verification

    public ActivityProofPageDTO<ActivityParticipantAdminDTO> adminParticipants(String activityId, int page, int size) {
        Activity activity = requireActivity(activityId);
        List<ActivityParticipation> all = allParticipations(activity.id());
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        List<ActivityParticipantAdminDTO> records = all.stream()
                .skip((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .map(participation -> toAdminParticipant(activity, participation))
                .toList();
        return new ActivityProofPageDTO<>(records, all.size());
    }

    public ActivityParticipantAdminDTO verifyParticipant(String activityId, String userId) {
        Activity activity = requireActivity(activityId);
        ActivityParticipation participation = requireParticipation(activity.id(), requireText(userId, "用户不能为空"));
        ActivityParticipation saved = repository.saveParticipation(verify(activity, participation));
        return toAdminParticipant(activity, saved);
    }

    public ActivityVerifyResultDTO verifyAllParticipants(String activityId) {
        Activity activity = requireActivity(activityId);
        autoSyncServerParticipantsQuietly(activity);
        long passed = 0;
        long failed = 0;
        List<ActivityParticipation> joined = allParticipations(activity.id()).stream()
                .filter(ActivityParticipation::isJoined)
                .toList();
        for (ActivityParticipation participation : joined) {
            ActivityParticipation verified = repository.saveParticipation(verify(activity, participation));
            if (verified.verifyStatus() == VerifyStatus.PASSED) {
                passed++;
            } else {
                failed++;
            }
        }
        return new ActivityVerifyResultDTO(activity.id(), joined.size(), passed, failed);
    }

    public ActivityParticipantAdminDTO addParticipant(ActivityParticipantAddCmd cmd) {
        Activity activity = requireActivity(cmd.activityId());
        String userId = requireText(cmd.userId(), "请选择要添加的用户");
        Long numericId = parseUserId(userId);
        if (numericId == null || framework == null || framework.users() == null
                || framework.users().findById(numericId).isEmpty()) {
            throw new IllegalArgumentException("用户不存在，请从用户列表中选择");
        }
        ActivityParticipation existing = repository.participation(activity.id(), userId).orElse(null);
        if (existing != null && existing.isJoined()) {
            throw new IllegalArgumentException("该用户已在参与名单中");
        }
        boolean passed = cmd.passed() == null || cmd.passed();
        ActivityParticipation participation = existing == null
                ? ActivityParticipation.create(activity.id(), userId, false, ParticipationSource.MANUAL)
                : existing.rejoin(false, ParticipationSource.MANUAL);
        if (passed) {
            participation = participation.withVerification(VerifyStatus.PASSED,
                    hasText(cmd.note()) ? cmd.note().trim() : "管理员手动添加");
        } else {
            participation = verify(activity, participation);
        }
        ActivityParticipation saved = repository.saveParticipation(participation);
        return toAdminParticipant(activity, saved);
    }

    public void removeParticipant(String activityId, String userId) {
        Activity activity = requireActivity(activityId);
        ActivityParticipation participation = requireParticipation(activity.id(), requireText(userId, "用户不能为空"));
        repository.deleteParticipation(participation.id());
        if (participation.source() == ParticipationSource.AUTO) {
            repository.addAutoJoinExclusion(activity.id(), participation.userId());
        }
    }

    // ---------------------------------------------------------------- admin: server auto-join sync

    public ServerParticipantSyncResultDTO syncServerParticipants(String activityId) {
        Activity activity = requireActivity(activityId);
        List<ActivityBinding> autoJoinBindings = activity.bindings().stream()
                .filter(binding -> binding.isPlaytime() && binding.autoJoin())
                .toList();
        if (autoJoinBindings.isEmpty()) {
            throw new IllegalArgumentException("该活动未开启「加入服务器自动参与活动」的时长检测绑定");
        }
        if (activity.activityStart() <= 0 || activity.activityEnd() <= activity.activityStart()) {
            throw new IllegalArgumentException("活动起止时间未配置，无法同步服务器玩家");
        }
        PluginMinecraftService minecraft = minecraftService()
                .orElseThrow(() -> new IllegalArgumentException("Minecraft 服务器插件未启用，无法同步服务器玩家"));
        long scanned = 0;
        long added = 0;
        long skippedExisting = 0;
        long skippedExcluded = 0;
        long unresolved = 0;
        long verifiedPassed = 0;
        long verifiedFailed = 0;
        Set<String> seenUserIds = new LinkedHashSet<>();
        for (ActivityBinding binding : autoJoinBindings) {
            List<PluginMinecraftActivePlayer> players;
            try {
                players = minecraft.minecraftActivePlayers(binding.serverId(), activity.activityStart(), activity.activityEnd());
            } catch (LinkageError e) {
                // 宿主仍运行旧版 minecraft-server（接口缺少 minecraftActivePlayers）时是 Error 而非 RuntimeException
                throw new IllegalArgumentException("Minecraft 服务器插件版本过低，无法同步上线玩家，请升级至 1.3.0 及以上后重试");
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("查询服务器「" + serverName(binding.serverId()) + "」上线玩家失败：" + e.getMessage());
            }
            if (players == null) {
                continue;
            }
            for (PluginMinecraftActivePlayer player : players) {
                scanned++;
                String userId = resolveUserIdForPlayer(binding.serverId(), player.playerId(), player.playerName());
                if (!hasText(userId) || !seenUserIds.add(userId)) {
                    if (!hasText(userId)) {
                        unresolved++;
                    }
                    continue;
                }
                if (repository.participation(activity.id(), userId).isPresent()) {
                    skippedExisting++;
                    continue;
                }
                if (repository.autoJoinExcluded(activity.id(), userId)) {
                    skippedExcluded++;
                    continue;
                }
                ActivityParticipation participation = ActivityParticipation.create(activity.id(), userId, false, ParticipationSource.AUTO);
                ActivityParticipation verified = repository.saveParticipation(
                        verify(activity, participation).withSource(ParticipationSource.AUTO));
                added++;
                if (verified.verifyStatus() == VerifyStatus.PASSED) {
                    verifiedPassed++;
                } else if (verified.verifyStatus() == VerifyStatus.FAILED) {
                    verifiedFailed++;
                }
            }
        }
        return new ServerParticipantSyncResultDTO(activity.id(), scanned, scanned - unresolved, added,
                skippedExisting, skippedExcluded, unresolved, verifiedPassed, verifiedFailed);
    }

    /**
     * 活动含 autoJoin 绑定时先静默同步服务器玩家；同步失败不阻断后续核验/导出主流程。
     */
    private void autoSyncServerParticipantsQuietly(Activity activity) {
        boolean autoJoinBound = activity.bindings().stream()
                .anyMatch(binding -> binding.isPlaytime() && binding.autoJoin());
        if (!autoJoinBound) {
            return;
        }
        try {
            syncServerParticipants(activity.id());
        } catch (RuntimeException ignored) {
            // 自动同步失败不阻断核验/导出，管理员仍可通过「同步服务器玩家」按钮手动重试
        }
    }

    /**
     * 服务器玩家 → 平台用户反解：先走本插件玩家-学号映射查学号再反查用户，再走皮肤站 UUID 档案的归属用户。
     */
    private String resolveUserIdForPlayer(String serverId, String playerId, String playerName) {
        PlayerStudentMapping mapping = hasText(serverId) && hasText(playerId)
                ? repository.mapping(serverId, playerId).orElse(null)
                : null;
        if (mapping != null && hasText(mapping.studentNo())) {
            String userId = studentInfoService()
                    .flatMap(service -> service.findStudentInfoByStudentNo(mapping.studentNo().trim()))
                    .map(PluginStudentInfoProfile::userId)
                    .orElse("");
            if (hasText(userId)) {
                return userId.trim();
            }
        }
        String normalizedUuid = normalizeUuid(playerId);
        if (!normalizedUuid.isBlank()) {
            String ownerId = skinService()
                    .flatMap(service -> service.findProfileByUuid(normalizedUuid))
                    .map(profile -> profile.ownerId())
                    .orElse("");
            if (hasText(ownerId)) {
                return ownerId.trim();
            }
        }
        // 部分档案可能以玩家名建档，兜底再按名字查一次皮肤站
        if (hasText(playerName)) {
            String ownerId = skinService()
                    .flatMap(service -> service.findProfileByName(playerName.trim()))
                    .map(profile -> profile.ownerId())
                    .orElse("");
            if (hasText(ownerId)) {
                return ownerId.trim();
            }
        }
        return "";
    }

    private String normalizeUuid(String playerId) {
        return playerId == null ? "" : playerId.trim().replace("-", "").toLowerCase(Locale.ROOT);
    }

    public ActivityTemplateMembersDTO templateMembers(String templateId) {
        Long id = requireTemplateId(templateId);
        ActivityProofTemplateMembers members = repository.templateMembers(id)
                .orElseGet(() -> ActivityProofTemplateMembers.empty(id));
        return new ActivityTemplateMembersDTO(stringId(id), resolveUserOptions(members.userIds()),
                members.updatedAt(), members.updatedBy());
    }

    public ActivityTemplateMembersDTO saveTemplateMembers(ActivityTemplateMembersSaveCmd cmd, String operatorUserId) {
        Long id = requireTemplateId(cmd.templateId());
        List<String> userIds = cmd.userIds() == null ? List.of() : cmd.userIds().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        for (String userId : userIds) {
            Long numericId = parseUserId(userId);
            if (numericId == null || framework == null || framework.users() == null
                    || framework.users().findById(numericId).isEmpty()) {
                throw new IllegalArgumentException("用户不存在：" + userId);
            }
        }
        ActivityProofTemplateMembers members = repository.templateMembers(id)
                .orElseGet(() -> ActivityProofTemplateMembers.empty(id))
                .withUserIds(userIds, text(operatorUserId));
        repository.saveTemplateMembers(members);
        return templateMembers(cmd.templateId());
    }

    public List<ActivityQqConnectionDTO> qqConnections() {
        PluginMessagingService messaging = messagingService();
        if (messaging == null) {
            return List.of();
        }
        try {
            return messaging.connections().stream()
                    .map(connection -> new ActivityQqConnectionDTO(text(connection.id()), text(connection.name()), text(connection.platform())))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public List<ActivityQqGroupDTO> qqGroups(String connectionId) {
        PluginMessagingService messaging = messagingService();
        if (messaging == null || !hasText(connectionId)) {
            return List.of();
        }
        try {
            return messaging.groups(connectionId.trim()).stream()
                    .map(group -> new ActivityQqGroupDTO(text(group.id()), text(group.name())))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<ActivityUserOptionDTO> resolveUserOptions(List<String> userIds) {
        List<ActivityUserOptionDTO> result = new ArrayList<>();
        for (String userId : userIds) {
            PluginUserProfile profile = Optional.ofNullable(parseUserId(userId))
                    .flatMap(value -> framework.users().findById(value))
                    .orElse(null);
            result.add(profile == null
                    ? new ActivityUserOptionDTO(userId, userId, "（用户已删除）", List.of())
                    : new ActivityUserOptionDTO(userId, text(profile.username()), text(profile.nickname()), List.of()));
        }
        return result;
    }

    private void notifyQqGroups(Activity activity) {
        ActivityProofSettings settings;
        try {
            settings = currentSettings();
        } catch (RuntimeException e) {
            return;
        }
        if (settings == null || !settings.qqNotifyReady()) {
            return;
        }
        PluginMessagingService messaging = messagingService();
        if (messaging == null) {
            return;
        }
        String message = qqNotifyText(settings.qqMessageTemplate(), activity);
        if (message.isBlank()) {
            return;
        }
        boolean official = officialConnection(messaging, settings.qqConnectionId());
        // 官方 QQ 机器人走 markdown 卡片 + 报名按钮（指令按钮点击直接发出 /报名）；Milky 保持原纯文本，协议行为不变
        PluginMessageContent content = official
                ? new PluginMessageContent(PluginMessageContent.Type.MARKDOWN,
                        qqNotifyMarkdown(settings.qqMessageTemplate(), activity), null, Map.of(),
                        signupButtons(settings, activity))
                : new PluginMessageContent(PluginMessageContent.Type.TEXT, message, null, Map.of());
        for (String groupId : settings.qqGroupIds()) {
            try {
                messaging.sendToChannel(settings.qqConnectionId(), groupId, content);
            } catch (RuntimeException ignored) {
                // 群通知失败不阻断活动发布
            }
        }
    }

    private boolean officialConnection(PluginMessagingService messaging, String connectionId) {
        try {
            return messaging.connections().stream()
                    .filter(connection -> connection.id().equals(connectionId))
                    .map(PluginMessagingConnection::protocol)
                    .anyMatch("official"::equals);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<PluginMessageContent.Button> signupButtons(ActivityProofSettings settings, Activity activity) {
        if (!settings.qqSignupButtonEnabled() || !activity.signupOpen(System.currentTimeMillis())) {
            return List.of();
        }
        return List.of(PluginMessageContent.Button.command("signup-" + activity.id(),
                settings.effectiveSignupButtonLabel(), "/报名 " + activity.id()));
    }

    private String qqNotifyMarkdown(String messageTemplate, Activity activity) {
        if (hasText(messageTemplate)) {
            return qqNotifyText(messageTemplate, activity);
        }
        return DEFAULT_QQ_MARKDOWN_TEMPLATE
                .replace("{title}", text(activity.title()))
                .replace("{summary}", text(activity.summary()))
                .replace("{signupTime}", timeRangeText(activity.signupStart(), activity.signupEnd()))
                .replace("{activityTime}", timeRangeText(activity.activityStart(), activity.activityEnd()))
                .trim();
    }

    // ---------------------------------------------------------------- QQ signup command

    /** QQ 群指令 / 报名按钮入口：/报名 {活动ID}，复用 joinActivity 的完整校验链并被动回复中文结果。 */
    public void signupFromQq(PluginEvent event, List<String> arguments, Long userId) {
        if (userId == null) {
            replyQq(event, "当前 QQ 未绑定系统账号，请先完成绑定后再报名。");
            return;
        }
        if (arguments == null || arguments.isEmpty() || !hasText(arguments.getFirst())) {
            replyQq(event, "用法：/报名 活动ID（也可以直接点击活动通知下方的报名按钮）");
            return;
        }
        String activityId = arguments.getFirst().trim();
        try {
            UserActivityDTO joined = joinActivity(activityId, String.valueOf(userId));
            replyQq(event, "✅ 报名成功：" + joined.title()
                    + "\n活动时间：" + timeRangeText(joined.activityStart(), joined.activityEnd())
                    + (joined.requirements().isEmpty() ? "" : "\n记得完成核验要求后再导出活动证明哦"));
        } catch (IllegalArgumentException e) {
            replyQq(event, "❌ 报名失败：" + e.getMessage());
        } catch (RuntimeException e) {
            replyQq(event, "❌ 报名失败：活动不存在或已删除");
        }
    }

    // ---------------------------------------------------------------- QQ activity list command

    /** QQ 群指令 /活动列表：展示未结束的已发布活动（最多 5 条，按活动开始时间升序），官方连接附一键报名按钮。 */
    public void listActivitiesFromQq(PluginEvent event) {
        PluginMessagingService messaging = messagingService();
        if (messaging == null || event == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Activity> activities = repository.activities(null, ActivityStatus.PUBLISHED.name(), 1, SCAN_PAGE_SIZE).stream()
                .filter(activity -> !activity.activityEnded(now))
                .sorted(Comparator.comparingLong(ActivityProofAppService::activitySortTime))
                .limit(QQ_ACTIVITY_LIST_LIMIT)
                .toList();
        if (activities.isEmpty()) {
            replyQq(event, "当前没有进行中的活动，敬请期待～");
            return;
        }
        // 官方 QQ 机器人发 markdown 列表 + 每个报名中的活动一个指令按钮；Milky 等其他协议发纯文本并提示 /报名 指令
        PluginMessageContent content = officialConnection(messaging, event.connectionId())
                ? new PluginMessageContent(PluginMessageContent.Type.MARKDOWN, qqActivityListMarkdown(activities, now), null,
                        qqReplyReferrer(event), qqActivityListButtons(activities, now))
                : new PluginMessageContent(PluginMessageContent.Type.TEXT, qqActivityListText(activities, now), null,
                        qqReplyReferrer(event));
        try {
            messaging.send(new PluginMessageRequest(event.connectionId(), event.platform(), event.selfId(), event.channelId(), content));
        } catch (RuntimeException ignored) {
            // 列表回复失败无需兜底
        }
    }

    private String qqActivityListMarkdown(List<Activity> activities, long now) {
        StringBuilder builder = new StringBuilder("# 📋 活动列表\n");
        int index = 1;
        for (Activity activity : activities) {
            builder.append("\n**").append(index++).append(". ").append(text(activity.title())).append("**\n");
            builder.append("> 🕐 报名时间：").append(timeRangeText(activity.signupStart(), activity.signupEnd()))
                    .append("（").append(signupStatusText(activity, now)).append("）\n");
            builder.append("> 🗓️ 活动时间：").append(timeRangeText(activity.activityStart(), activity.activityEnd())).append('\n');
        }
        builder.append("\n点击对应按钮即可一键报名");
        return builder.toString().trim();
    }

    private String qqActivityListText(List<Activity> activities, long now) {
        StringBuilder builder = new StringBuilder("【活动列表】");
        int index = 1;
        for (Activity activity : activities) {
            builder.append('\n').append(index++).append(". ").append(text(activity.title()));
            builder.append("\n报名时间：").append(timeRangeText(activity.signupStart(), activity.signupEnd()))
                    .append("（").append(signupStatusText(activity, now)).append("）");
            builder.append("\n活动时间：").append(timeRangeText(activity.activityStart(), activity.activityEnd()));
            if (activity.signupOpen(now)) {
                builder.append("\n报名指令：/报名 ").append(activity.id());
            }
        }
        return builder.toString();
    }

    /** 与发布通知共用「报名按钮」开关；列表里多个活动并排，按钮标签带活动名前缀便于区分。 */
    private List<PluginMessageContent.Button> qqActivityListButtons(List<Activity> activities, long now) {
        ActivityProofSettings settings;
        try {
            settings = currentSettings();
        } catch (RuntimeException e) {
            settings = null;
        }
        if (settings == null || !settings.qqSignupButtonEnabled()) {
            return List.of();
        }
        List<PluginMessageContent.Button> buttons = new ArrayList<>();
        for (Activity activity : activities) {
            if (!activity.signupOpen(now)) {
                continue;
            }
            buttons.add(PluginMessageContent.Button.command("signup-" + activity.id(),
                    qqListButtonLabel(activity.title()), "/报名 " + activity.id()));
        }
        return buttons;
    }

    // 官方按钮标签长度有限，截断活动名保证标签可读
    private String qqListButtonLabel(String title) {
        String trimmed = text(title);
        return "报名·" + (trimmed.length() <= 7 ? trimmed : trimmed.substring(0, 7));
    }

    private String signupStatusText(Activity activity, long now) {
        if (activity.signupOpen(now)) {
            return "报名中";
        }
        if (activity.signupStart() > 0 && now < activity.signupStart()) {
            return "报名未开始";
        }
        return "报名已截止";
    }

    // 未配置开始时间的活动排在最后
    private static long activitySortTime(Activity activity) {
        return activity.activityStart() > 0 ? activity.activityStart() : Long.MAX_VALUE;
    }

    private void replyQq(PluginEvent event, String text) {
        PluginMessagingService messaging = messagingService();
        if (messaging == null || event == null) {
            return;
        }
        try {
            messaging.send(new PluginMessageRequest(event.connectionId(), event.platform(), event.selfId(), event.channelId(),
                    new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null, qqReplyReferrer(event))));
        } catch (RuntimeException ignored) {
            // 回复失败不影响报名结果
        }
    }

    /** 复制官方被动回复所需的 msg_id/event_id/message_scene/interaction_id，使回复落在原消息会话上。 */
    private Map<String, Object> qqReplyReferrer(PluginEvent event) {
        Map<String, Object> referrer = new LinkedHashMap<>();
        if (event.nativeData() instanceof Map<?, ?> data) {
            for (String key : List.of("message_scene", "event_id", "msg_id", "interaction_id")) {
                Object value = data.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    referrer.put(key, String.valueOf(value));
                }
            }
        }
        if (event.messageId() != null && !event.messageId().isBlank()) {
            referrer.putIfAbsent("message_id", event.messageId());
            referrer.putIfAbsent("msg_id", event.messageId());
        }
        return referrer;
    }

    private String qqNotifyText(String messageTemplate, Activity activity) {
        String template = hasText(messageTemplate) ? messageTemplate.trim() : DEFAULT_QQ_MESSAGE_TEMPLATE;
        return template
                .replace("{title}", text(activity.title()))
                .replace("{summary}", text(activity.summary()))
                .replace("{signupTime}", timeRangeText(activity.signupStart(), activity.signupEnd()))
                .replace("{activityTime}", timeRangeText(activity.activityStart(), activity.activityEnd()))
                .trim();
    }

    private String timeRangeText(long start, long end) {
        if (start <= 0) {
            return "待定";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
        String startText = formatter.format(Instant.ofEpochMilli(start));
        if (end <= start) {
            return startText;
        }
        return startText + " 至 " + formatter.format(Instant.ofEpochMilli(end));
    }

    private PluginMessagingService messagingService() {
        if (framework == null) {
            return null;
        }
        try {
            return framework.messaging();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- user: square & participation

    public ActivityProofPageDTO<UserActivityDTO> userActivities(String userId, int page, int size) {
        String safeUserId = requireText(userId, "请先登录");
        Set<String> myDeptIds = myDeptIds(safeUserId);
        Map<String, String> deptNames = deptNameMap();
        List<Activity> visible = allActivities().stream()
                .filter(activity -> activity.status() == ActivityStatus.PUBLISHED || activity.status() == ActivityStatus.CLOSED)
                .toList();
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        List<UserActivityDTO> records = visible.stream()
                .skip((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .map(activity -> toUserDTO(activity, safeUserId, myDeptIds, deptNames, false))
                .toList();
        return new ActivityProofPageDTO<>(records, visible.size());
    }

    public UserActivityDTO userActivity(String id, String userId) {
        String safeUserId = requireText(userId, "请先登录");
        Activity activity = requireActivity(id);
        if (activity.status() == ActivityStatus.DRAFT) {
            throw new IllegalArgumentException("活动不存在或未发布");
        }
        return toUserDTO(activity, safeUserId, myDeptIds(safeUserId), deptNameMap(), true);
    }

    public UserActivityDTO joinActivity(String id, String userId) {
        String safeUserId = requireText(userId, "请先登录");
        Activity activity = requireActivity(id);
        if (activity.status() != ActivityStatus.PUBLISHED) {
            throw new IllegalArgumentException(activity.status() == ActivityStatus.CLOSED ? "活动已结束，无法参与" : "活动未开放报名");
        }
        if (activity.activityEnded(System.currentTimeMillis())) {
            throw new IllegalArgumentException("活动已结束，无法参与");
        }
        if (!activity.signupOpen(System.currentTimeMillis())) {
            throw new IllegalArgumentException(signupClosedReason(activity, System.currentTimeMillis()) + "，无法参与");
        }
        if (!activity.allowsDepartments(myDeptIds(safeUserId))) {
            throw new IllegalArgumentException("该活动仅限指定部门成员参与");
        }
        ActivityParticipation existing = repository.participation(activity.id(), safeUserId).orElse(null);
        if (existing != null && existing.isJoined()) {
            throw new IllegalArgumentException("你已参与该活动");
        }
        boolean autoPassed = activity.bindings().isEmpty();
        ActivityParticipation participation = existing == null
                ? ActivityParticipation.create(activity.id(), safeUserId, autoPassed, ParticipationSource.SELF)
                : existing.rejoin(autoPassed, ParticipationSource.SELF);
        repository.saveParticipation(participation);
        return userActivity(activity.id(), safeUserId);
    }

    public UserActivityDTO cancelActivity(String id, String userId) {
        String safeUserId = requireText(userId, "请先登录");
        Activity activity = requireActivity(id);
        if (activity.status() != ActivityStatus.PUBLISHED) {
            throw new IllegalArgumentException("活动已结束，无法取消参与");
        }
        if (activity.activityEnded(System.currentTimeMillis())) {
            throw new IllegalArgumentException("活动已结束，无法取消参与");
        }
        ActivityParticipation participation = repository.participation(activity.id(), safeUserId)
                .orElseThrow(() -> new IllegalArgumentException("你还没有参与该活动"));
        if (!participation.isJoined()) {
            throw new IllegalArgumentException("你已取消参与该活动");
        }
        repository.saveParticipation(participation.cancel());
        return userActivity(activity.id(), safeUserId);
    }

    public ActivityProofPageDTO<MyParticipationDTO> myParticipations(String userId, int page, int size) {
        String safeUserId = requireText(userId, "请先登录");
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        List<MyParticipationDTO> records = repository.participationsByUser(safeUserId, safePage, safeSize).stream()
                .map(this::toMyDTO)
                .toList();
        return new ActivityProofPageDTO<>(records, repository.countParticipationsByUser(safeUserId));
    }

    public MyParticipationDTO verifyMyParticipation(String activityId, String userId) {
        String safeUserId = requireText(userId, "请先登录");
        Activity activity = requireActivity(activityId);
        ActivityParticipation participation = requireParticipation(activity.id(), safeUserId);
        if (!participation.isJoined()) {
            throw new IllegalArgumentException("请先参与该活动再核验");
        }
        ActivityParticipation saved = repository.saveParticipation(verify(activity, participation));
        return toMyDTO(saved);
    }

    // ---------------------------------------------------------------- verification engine

    private ActivityParticipation verify(Activity activity, ActivityParticipation participation) {
        if (!participation.isJoined()) {
            return participation.withVerification(VerifyStatus.FAILED, "已取消参与，无法核验");
        }
        if (activity.bindings().isEmpty()) {
            return participation.withVerification(VerifyStatus.PASSED, "参与活动即达标");
        }
        List<String> notes = new ArrayList<>();
        for (ActivityBinding binding : activity.bindings()) {
            VerifyOutcome outcome = switch (binding.type()) {
                case PLAYTIME -> verifyPlaytime(activity, binding, participation.userId());
                case FORM -> verifyForm(activity, binding, participation.userId());
                case QUIZ -> verifyQuiz(activity, participation.userId());
            };
            if (outcome.passed()) {
                return participation.withVerification(VerifyStatus.PASSED, outcome.note());
            }
            notes.add(outcome.note());
        }
        return participation.withVerification(VerifyStatus.FAILED, String.join("；", notes));
    }

    private VerifyOutcome verifyPlaytime(Activity activity, ActivityBinding binding, String userId) {
        Optional<PluginMinecraftService> service = minecraftService();
        if (service.isEmpty()) {
            return new VerifyOutcome(false, "Minecraft 服务器插件未启用，无法核验时长");
        }
        List<ResolvedPlayer> players = resolvePlayers(binding.serverId(), userId);
        if (players.isEmpty()) {
            return new VerifyOutcome(false, "未找到你在服务器「" + serverName(binding.serverId()) + "」的玩家映射，请联系管理员维护映射");
        }
        VerifyOutcome lastMiss = null;
        for (ResolvedPlayer player : players) {
            Optional<PluginMinecraftOnlineWindow> window = lookupOnlineWindow(
                    service.get(), binding.serverId(), player, activity.activityStart(), activity.activityEnd());
            if (window.isEmpty()) {
                lastMiss = new VerifyOutcome(false, "未查询到玩家「" + player.display() + "」在活动时段的在线记录");
                continue;
            }
            long effectiveMillis = binding.includeAfk() ? window.get().onlineMillis() : window.get().effectiveOnlineMillis();
            String metric = binding.includeAfk() ? "在线" : "有效在线";
            String displayName = hasText(window.get().playerName()) ? window.get().playerName() : player.display();
            String note = "玩家「" + displayName + "」活动时段" + metric + " "
                    + (effectiveMillis / 60_000L) + "/" + binding.minOnlineMinutes() + " 分钟";
            return new VerifyOutcome(effectiveMillis >= binding.minOnlineMinutes() * 60_000L, note);
        }
        return lastMiss != null
                ? lastMiss
                : new VerifyOutcome(false, "未查询到在活动时段的在线记录");
    }

    private VerifyOutcome verifyForm(Activity activity, ActivityBinding binding, String userId) {
        PluginFormService forms = formService();
        String formLabel = hasText(binding.formName()) ? binding.formName() : binding.formCode();
        if (forms == null || !forms.enabled()) {
            return new VerifyOutcome(false, "动态表单能力未启用，无法核验表单「" + formLabel + "」");
        }
        Long submitterId = parseUserId(userId);
        if (submitterId == null) {
            return new VerifyOutcome(false, "用户身份无效，无法核验表单");
        }
        long from = activity.signupStart() > 0 ? activity.signupStart() : activity.activityStart();
        boolean submitted;
        try {
            submitted = forms.submittedBy(binding.formCode(), submitterId, from, activity.activityEnd());
        } catch (RuntimeException e) {
            return new VerifyOutcome(false, "表单「" + formLabel + "」核验失败：" + e.getMessage());
        }
        return submitted
                ? new VerifyOutcome(true, "已提交表单「" + formLabel + "」")
                : new VerifyOutcome(false, "未在活动周期内提交表单「" + formLabel + "」");
    }

    private VerifyOutcome verifyQuiz(Activity activity, String userId) {
        ActivityQuizConfig config = repository.quizConfig(activity.id()).orElse(null);
        if (config == null || !config.enabled()) {
            return new VerifyOutcome(false, "活动未开启答题环节，无法核验答题");
        }
        ActivityQuizAttempt attempt = repository.quizAttempt(activity.id(), userId).orElse(null);
        if (attempt == null) {
            return new VerifyOutcome(false, "尚未参与活动答题");
        }
        // 核验前拉取题库侧最新结果，避免用户答完未回到活动页导致达标状态未同步
        attempt = quizService.syncQuizResult(activity, config, attempt, userId);
        return attempt.passed()
                ? new VerifyOutcome(true, "活动答题达标（答对 ≥ " + config.passCorrect() + " 题）")
                : new VerifyOutcome(false, "活动答题未达标（需答对至少 " + config.passCorrect() + " 题）");
    }

    private record VerifyOutcome(boolean passed, String note) {
    }

    private record ResolvedPlayer(String playerId, String playerName) {
        String display() {
            return playerName != null && !playerName.isBlank() ? playerName : playerId;
        }
    }

    private ResolvedPlayer resolvePlayer(String serverId, String userId) {
        List<ResolvedPlayer> players = resolvePlayers(serverId, userId);
        return players.isEmpty() ? null : players.getFirst();
    }

    /**
     * 平台用户 → 服务器玩家：与 {@link #resolveUserIdForPlayer} 互为反解。
     * 先走本插件玩家-学号映射，再走皮肤站归属角色；同一用户多名角色都保留，核验时按在线记录择一。
     */
    private List<ResolvedPlayer> resolvePlayers(String serverId, String userId) {
        LinkedHashMap<String, ResolvedPlayer> candidates = new LinkedHashMap<>();
        if (!hasText(serverId) || !hasText(userId)) {
            return List.of();
        }
        List<PlayerStudentMapping> mappings = allMappings(serverId);
        String studentNo = studentInfoService()
                .flatMap(service -> service.findStudentInfoByUserId(userId))
                .map(PluginStudentInfoProfile::studentNo)
                .orElse("");
        if (hasText(studentNo)) {
            String key = normalizeKey(studentNo);
            for (PlayerStudentMapping mapping : mappings) {
                if (normalizeKey(mapping.studentNo()).equals(key)) {
                    addResolvedPlayer(candidates, mapping.playerId(), mapping.playerName());
                }
            }
        }
        String username = Optional.ofNullable(parseUserId(userId))
                .flatMap(id -> framework.users().findById(id))
                .map(PluginUserProfile::username)
                .orElse("");
        if (hasText(username)) {
            String key = normalizeKey(username);
            for (PlayerStudentMapping mapping : mappings) {
                if (normalizeKey(mapping.playerId()).equals(key) || normalizeKey(mapping.playerName()).equals(key)) {
                    addResolvedPlayer(candidates, mapping.playerId(), mapping.playerName());
                }
            }
        }
        skinService().ifPresent(service -> {
            for (var profile : service.findProfilesByOwner(userId.trim())) {
                String uuid = profile.uuid() == null ? "" : profile.uuid().trim();
                String name = profile.name() == null ? "" : profile.name().trim();
                if (!hasText(uuid) && !hasText(name)) {
                    continue;
                }
                boolean mapped = false;
                for (String candidateId : playerIdCandidates(uuid)) {
                    PlayerStudentMapping mapping = repository.mapping(serverId, candidateId).orElse(null);
                    if (mapping != null) {
                        addResolvedPlayer(candidates, mapping.playerId(), mapping.playerName());
                        mapped = true;
                        break;
                    }
                }
                if (!mapped) {
                    addResolvedPlayer(candidates, hasText(uuid) ? uuid : name, name);
                }
            }
        });
        return List.copyOf(candidates.values());
    }

    private void addResolvedPlayer(Map<String, ResolvedPlayer> candidates, String playerId, String playerName) {
        if (!hasText(playerId) && !hasText(playerName)) {
            return;
        }
        String id = hasText(playerId) ? playerId.trim() : "";
        String name = hasText(playerName) ? playerName.trim() : "";
        String normalizedUuid = normalizeUuid(id);
        String key = !normalizedUuid.isBlank() ? normalizedUuid
                : (hasText(id) ? normalizeKey(id) : "name:" + normalizeKey(name));
        candidates.putIfAbsent(key, new ResolvedPlayer(id, name));
    }

    private Optional<PluginMinecraftOnlineWindow> lookupOnlineWindow(
            PluginMinecraftService service, String serverId, ResolvedPlayer player, long windowStart, long windowEnd) {
        if (player == null) {
            return Optional.empty();
        }
        for (String candidateId : playerIdCandidates(player.playerId())) {
            Optional<PluginMinecraftOnlineWindow> window = service.minecraftOnlineWindow(
                    serverId, candidateId, windowStart, windowEnd);
            if (window.isPresent()) {
                return window;
            }
        }
        return Optional.empty();
    }

    private List<String> playerIdCandidates(String playerId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (!hasText(playerId)) {
            return List.of();
        }
        String raw = playerId.trim();
        ids.add(raw);
        String stripped = normalizeUuid(raw);
        if (!stripped.isBlank()) {
            ids.add(stripped);
            String dashed = dashedUuid(stripped);
            if (hasText(dashed)) {
                ids.add(dashed);
            }
        }
        return List.copyOf(ids);
    }

    private String dashedUuid(String stripped) {
        if (stripped == null || stripped.length() != 32) {
            return "";
        }
        for (int i = 0; i < stripped.length(); i++) {
            if (Character.digit(stripped.charAt(i), 16) < 0) {
                return "";
            }
        }
        return stripped.substring(0, 8) + "-" + stripped.substring(8, 12) + "-"
                + stripped.substring(12, 16) + "-" + stripped.substring(16, 20) + "-" + stripped.substring(20);
    }

    // ---------------------------------------------------------------- mappings

    public ActivityProofPageDTO<ActivityProofMappingDTO> mappings(String serverId, int page, int size) {
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        List<ActivityProofMappingDTO> records = repository.mappings(serverId, safePage, safeSize).stream()
                .map(this::toDTO)
                .toList();
        return new ActivityProofPageDTO<>(records, repository.countMappings(serverId));
    }

    public ActivityProofMappingDTO saveMapping(ActivityProofMappingSaveCmd cmd) {
        String serverId = requireText(cmd.serverId(), "服务器不能为空");
        String playerId = requireText(cmd.playerId(), "玩家 ID 不能为空");
        requireText(cmd.studentNo(), "学号不能为空");
        PlayerStudentMapping mapping = repository.mapping(serverId, playerId)
                .map(existing -> existing.update(cmd.playerName(), cmd.studentNo()))
                .orElseGet(() -> PlayerStudentMapping.create(serverId, playerId, cmd.playerName(), cmd.studentNo()));
        return toDTO(repository.saveMapping(mapping));
    }

    public void deleteMapping(String id) {
        repository.deleteMapping(requireText(id, "映射 ID 不能为空"));
    }

    // ---------------------------------------------------------------- export

    public ActivityProofExportDTO export(ActivityProofExportCmd cmd, String operatorUserId) {
        ActivityProofSettings settings = currentSettings();
        if (!settings.hasTemplate()) {
            throw new IllegalArgumentException("请选择 Word 模板");
        }
        if (!wordTemplateEnabled()) {
            throw new IllegalArgumentException("Word 模板能力未启用，请先在能力管理中启用 document-template");
        }
        Activity activity = requireActivity(cmd.activityId());
        autoSyncServerParticipantsQuietly(activity);
        Set<String> selected = cmd.selectedUserIds() == null ? Set.of() : cmd.selectedUserIds().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String serverId = activity.firstPlaytimeServerId();
        List<ActivityProofParticipantSnapshot> snapshots = new ArrayList<>();
        for (ActivityParticipation participation : allParticipations(activity.id())) {
            if (!participation.isJoined() || participation.verifyStatus() != VerifyStatus.PASSED) {
                continue;
            }
            if (!selected.isEmpty() && !selected.contains(participation.userId())) {
                continue;
            }
            snapshots.add(toSnapshot(participation.userId(), serverId));
        }
        mergeTemplateMemberSnapshots(settings, serverId, snapshots);
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("没有已核验通过的参与者，请先完成参与核验");
        }
        PluginMinecraftServer server = hasText(serverId)
                ? minecraftService().flatMap(service -> service.minecraftServer(serverId)).orElse(null)
                : null;
        Map<String, Object> data = buildTemplateData(cmd, activity, server, settings, snapshots);
        PluginRenderedDocument rendered = framework.wordTemplates().render(settings.templateId(), data);
        String recordId = UUID.randomUUID().toString();
        String filename = outputFilename(activity.title(), recordId);
        String objectKey = files.put("exports/" + recordId + ".docx",
                new ByteArrayInputStream(rendered.content()), rendered.content().length,
                rendered.contentType() == null ? DOCX_CONTENT_TYPE : rendered.contentType());
        long unmatchedCount = snapshots.stream().filter(snapshot -> snapshot.studentNo().isBlank()).count();
        ActivityProofExportRecord record = ActivityProofExportRecord.create(activity.id(),
                server == null ? "" : server.id(), server == null ? "" : server.name(), activity.title(),
                objectKey, filename, snapshots.size(), (int) unmatchedCount, operatorUserId, snapshots);
        return toDTO(repository.saveExportRecord(record));
    }

    private ActivityProofParticipantSnapshot toSnapshot(String userId, String serverId) {
        PluginStudentInfoProfile profile = studentInfoService()
                .flatMap(service -> service.findStudentInfoByUserId(userId))
                .orElse(null);
        ResolvedPlayer player = resolvePlayer(serverId, userId);
        return new ActivityProofParticipantSnapshot(
                userId,
                profile == null ? "" : profile.studentName(),
                profile == null ? "" : profile.studentNo(),
                profile == null ? "" : profile.className(),
                profile == null ? "" : profile.college(),
                player == null ? "" : player.playerId(),
                player == null ? "" : player.playerName()
        );
    }

    private void mergeTemplateMemberSnapshots(ActivityProofSettings settings, String serverId,
                                              List<ActivityProofParticipantSnapshot> snapshots) {
        if (!settings.hasTemplate()) {
            return;
        }
        List<String> memberIds = repository.templateMembers(settings.templateId())
                .map(ActivityProofTemplateMembers::userIds)
                .orElse(List.of());
        if (memberIds.isEmpty()) {
            return;
        }
        Set<String> existing = snapshots.stream()
                .map(ActivityProofParticipantSnapshot::userId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String userId : memberIds) {
            if (existing.add(userId)) {
                snapshots.add(toSnapshot(userId, serverId));
            }
        }
    }

    private Map<String, Object> buildTemplateData(ActivityProofExportCmd cmd, Activity activity, PluginMinecraftServer server,
                                                  ActivityProofSettings settings, List<ActivityProofParticipantSnapshot> snapshots) {
        String college = firstText(cmd.college(), settings.defaultCollege(), "计算机科学与技术学院");
        String issueDate = firstText(cmd.issueDate(), todayText());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("participantTableAppend", true);
        data.put("proofNo", firstText(cmd.proofNo(), defaultProofNo()));
        data.put("activityName", activity.title());
        data.put("activityDate", activityDateText(activity, issueDate));
        data.put("college", college);
        data.put("collegeName", college);
        data.put("issuer", firstText(cmd.issuer(), settings.defaultIssuer()));
        data.put("issueDate", issueDate);
        data.put("serverId", server == null ? "" : server.id());
        data.put("serverName", server == null ? "" : server.name());
        data.put("currentSeasonName", server == null ? "" : server.currentSeasonName());
        data.put("participantCount", snapshots.size());
        List<Map<String, Object>> participantData = new ArrayList<>();
        for (int index = 0; index < snapshots.size(); index++) {
            participantData.add(templateData(snapshots.get(index), index + 1));
        }
        data.put("participants", participantData);
        data.put("participantRows", participantRows(participantData));
        return data;
    }

    private Map<String, Object> templateData(ActivityProofParticipantSnapshot snapshot, int index) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("index", index);
        data.put("userId", snapshot.userId());
        data.put("name", snapshot.studentName().isBlank() ? snapshot.playerName() : snapshot.studentName());
        data.put("studentName", snapshot.studentName());
        data.put("studentNo", snapshot.studentNo());
        data.put("className", snapshot.className());
        data.put("college", snapshot.college());
        data.put("playerId", snapshot.playerId());
        data.put("playerName", snapshot.playerName());
        data.put("matched", !snapshot.studentNo().isBlank());
        return data;
    }

    private String activityDateText(Activity activity, String fallback) {
        if (activity.activityStart() <= 0) {
            return fallback;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年M月d日").withZone(ZoneId.systemDefault());
        String start = formatter.format(Instant.ofEpochMilli(activity.activityStart()));
        if (activity.activityEnd() <= activity.activityStart()) {
            return start;
        }
        String end = formatter.format(Instant.ofEpochMilli(activity.activityEnd()));
        return start.equals(end) ? start : start + " 至 " + end;
    }

    private List<Map<String, Object>> participantRows(List<Map<String, Object>> participants) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < participants.size(); index += 2) {
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, Object> left = participants.get(index);
            Map<String, Object> right = index + 1 < participants.size() ? participants.get(index + 1) : Map.of();
            row.put("left", left);
            row.put("right", right);
            row.put("l", compactParticipant(left));
            row.put("r", compactParticipant(right));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> compactParticipant(Map<String, Object> participant) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", participant.getOrDefault("name", ""));
        value.put("class", participant.getOrDefault("className", ""));
        value.put("no", participant.getOrDefault("studentNo", ""));
        return value;
    }

    // ---------------------------------------------------------------- export records & stamped pdf

    public ActivityProofPageDTO<ActivityProofExportDTO> exportRecords(int page, int size) {
        List<ActivityProofExportDTO> records = repository.exportRecords(safePage(page), safeSize(size)).stream()
                .map(this::toDTO)
                .toList();
        return new ActivityProofPageDTO<>(records, repository.countExportRecords());
    }

    public ActivityProofPageDTO<ActivityProofExportDTO> myStampedExportRecords(String userId, int page, int size) {
        String safeUserId = requireText(userId, "请先登录");
        String studentNo = studentInfoService()
                .flatMap(service -> service.findStudentInfoByUserId(safeUserId))
                .map(PluginStudentInfoProfile::studentNo)
                .orElse("");
        List<ActivityProofExportDTO> mine = allExportRecords().stream()
                .filter(ActivityProofExportRecord::hasStampedPdf)
                .filter(record -> record.containsParticipant(safeUserId, studentNo))
                .map(record -> toDTO(record, true))
                .toList();
        int safePage = safePage(page);
        int safeSize = safeSize(size);
        List<ActivityProofExportDTO> records = mine.stream()
                .skip((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .toList();
        return new ActivityProofPageDTO<>(records, mine.size());
    }

    public ActivityProofExportDTO uploadStampedPdf(ActivityProofStampedPdfUploadCmd cmd) {
        ActivityProofExportRecord record = exportRecord(cmd.id());
        byte[] content = decodeBase64(cmd.base64());
        if (content.length == 0) {
            throw new IllegalArgumentException("PDF 文件不能为空");
        }
        ensurePdfContent(content);
        String filename = pdfFilename(cmd.filename(), record);
        String contentType = pdfContentType(cmd.contentType(), filename);
        String objectKey = "stamped-pdf/" + record.id() + ".pdf";
        files.put(objectKey, new ByteArrayInputStream(content), content.length, contentType);
        ActivityProofExportRecord saved = repository.saveExportRecord(record.withStampedPdf(
                objectKey,
                filename,
                contentType,
                content.length,
                System.currentTimeMillis()
        ));
        return toDTO(saved);
    }

    public void deleteExportRecord(String id) {
        ActivityProofExportRecord record = exportRecord(id);
        deleteFileQuietly(record.outputObjectKey());
        deleteFileQuietly(record.stampedPdfObjectKey());
        repository.deleteExportRecord(record.id());
    }

    public ActivityProofDownloadDTO downloadWord(String id) {
        ActivityProofExportRecord record = exportRecord(id);
        return new ActivityProofDownloadDTO(record.outputFilename(), DOCX_CONTENT_TYPE, files.get(record.outputObjectKey()));
    }

    public ActivityProofDownloadDTO downloadStampedPdf(String id) {
        ActivityProofExportRecord record = stampedExportRecord(id);
        return new ActivityProofDownloadDTO(record.stampedPdfFilename(), PDF_CONTENT_TYPE, files.get(record.stampedPdfObjectKey()));
    }

    public ActivityProofDownloadDTO downloadMyStampedPdf(String id, String userId) {
        String safeUserId = requireText(userId, "请先登录");
        ActivityProofExportRecord record = stampedExportRecord(id);
        String studentNo = studentInfoService()
                .flatMap(service -> service.findStudentInfoByUserId(safeUserId))
                .map(PluginStudentInfoProfile::studentNo)
                .orElse("");
        if (!record.containsParticipant(safeUserId, studentNo)) {
            throw new IllegalArgumentException("该盖章证明不属于当前用户");
        }
        return new ActivityProofDownloadDTO(record.stampedPdfFilename(), PDF_CONTENT_TYPE, files.get(record.stampedPdfObjectKey()));
    }

    // ---------------------------------------------------------------- internals: lookups

    private Activity requireActivity(String id) {
        return repository.activity(requireText(id, "活动不能为空"))
                .orElseThrow(() -> new IllegalArgumentException("活动不存在"));
    }

    private ActivityParticipation requireParticipation(String activityId, String userId) {
        return repository.participation(activityId, userId)
                .orElseThrow(() -> new IllegalArgumentException("参与记录不存在"));
    }

    private List<Activity> allActivities() {
        List<Activity> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Activity> batch = repository.activities(null, null, page, SCAN_PAGE_SIZE);
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    private List<ActivityParticipation> allParticipations(String activityId) {
        List<ActivityParticipation> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<ActivityParticipation> batch = repository.participationsByActivity(activityId, page, SCAN_PAGE_SIZE);
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    private List<PlayerStudentMapping> allMappings(String serverId) {
        List<PlayerStudentMapping> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<PlayerStudentMapping> batch = repository.mappings(serverId, page, SCAN_PAGE_SIZE);
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    private List<ActivityProofExportRecord> allExportRecords() {
        List<ActivityProofExportRecord> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<ActivityProofExportRecord> batch = repository.exportRecords(page, SCAN_PAGE_SIZE);
            result.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    private ActivityProofExportRecord exportRecord(String id) {
        return repository.exportRecord(requireText(id, "导出记录不能为空"))
                .orElseThrow(() -> new IllegalArgumentException("导出记录不存在"));
    }

    private ActivityProofExportRecord stampedExportRecord(String id) {
        ActivityProofExportRecord record = exportRecord(id);
        if (!record.hasStampedPdf()) {
            throw new IllegalArgumentException("该导出记录还没有上传盖章 PDF");
        }
        return record;
    }

    private Set<String> myDeptIds(String userId) {
        if (framework == null || framework.users() == null) {
            return Set.of();
        }
        Long id = parseUserId(userId);
        if (id == null) {
            return Set.of();
        }
        try {
            return framework.users().listDepartments(id).stream()
                    .map(PluginUserDept::id)
                    .filter(value -> value != null)
                    .map(String::valueOf)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private Map<String, String> deptNameMap() {
        return departmentOptions(null).stream()
                .collect(Collectors.toMap(ActivityDeptOptionDTO::id, ActivityDeptOptionDTO::label, (first, second) -> first, LinkedHashMap::new));
    }

    private String serverName(String serverId) {
        if (!hasText(serverId)) {
            return "";
        }
        return minecraftService()
                .flatMap(service -> service.minecraftServer(serverId))
                .map(PluginMinecraftServer::name)
                .orElse(serverId);
    }

    private Long parseUserId(String userId) {
        if (!hasText(userId)) {
            return null;
        }
        try {
            return Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- internals: DTO mapping

    private ActivityDTO toDTO(Activity activity) {
        Map<String, String> deptNames = deptNameMap();
        List<String> allowedDeptNames = activity.allowedDeptIds().stream()
                .map(id -> deptNames.getOrDefault(id, id))
                .toList();
        List<ActivityParticipation> participations = allParticipations(activity.id());
        long joinedCount = participations.stream().filter(ActivityParticipation::isJoined).count();
        long verifiedCount = participations.stream()
                .filter(ActivityParticipation::isJoined)
                .filter(item -> item.verifyStatus() == VerifyStatus.PASSED)
                .count();
        return new ActivityDTO(
                activity.id(),
                activity.title(),
                activity.summary(),
                activity.description(),
                activity.coverUrl(),
                activity.signupStart(),
                activity.signupEnd(),
                activity.activityStart(),
                activity.activityEnd(),
                activity.status().name(),
                activity.deptMode().name(),
                activity.allowedDeptIds(),
                allowedDeptNames,
                activity.bindings().stream().map(this::toDTO).toList(),
                joinedCount,
                verifiedCount,
                activity.createdBy(),
                activity.createdAt(),
                activity.updatedAt(),
                activity.publishedAt()
        );
    }

    private ActivityBindingDTO toDTO(ActivityBinding binding) {
        String serverName = binding.isPlaytime() ? serverName(binding.serverId()) : "";
        String formName = binding.isForm()
                ? firstText(binding.formName(), formName(binding.formCode()), binding.formCode())
                : "";
        return new ActivityBindingDTO(
                binding.type().name(),
                binding.serverId(),
                serverName,
                binding.minOnlineMinutes(),
                binding.includeAfk(),
                binding.autoJoin(),
                binding.formCode(),
                formName,
                requirementText(binding, serverName, formName)
        );
    }

    private String requirementText(ActivityBinding binding, String serverName, String formName) {
        if (binding.isQuiz()) {
            return "完成活动答题并达标";
        }
        if (binding.isPlaytime()) {
            String metric = binding.includeAfk() ? "在线" : "有效在线";
            String server = hasText(serverName) ? "「" + serverName + "」" : "";
            return binding.minOnlineMinutes() <= 0
                    ? "在服务器" + server + "活动时段内有" + metric + "记录"
                    : "服务器" + server + "活动时段" + metric + " ≥ " + binding.minOnlineMinutes() + " 分钟";
        }
        return "提交表单「" + (hasText(formName) ? formName : binding.formCode()) + "」";
    }

    private UserActivityDTO toUserDTO(Activity activity, String userId, Set<String> myDeptIds,
                                      Map<String, String> deptNames, boolean withDescription) {
        ActivityParticipation participation = repository.participation(activity.id(), userId).orElse(null);
        boolean joined = participation != null && participation.isJoined();
        boolean eligible = activity.allowsDepartments(myDeptIds);
        long joinedCount = allParticipations(activity.id()).stream().filter(ActivityParticipation::isJoined).count();
        List<String> allowedDeptNames = activity.allowedDeptIds().stream()
                .map(id -> deptNames.getOrDefault(id, id))
                .toList();
        List<UserRequirementDTO> requirementDetails = activity.bindings().stream()
                .map(binding -> {
                    String formName = binding.isForm() ? firstText(binding.formName(), binding.formCode()) : "";
                    String text = requirementText(binding,
                            binding.isPlaytime() ? serverName(binding.serverId()) : "",
                            formName);
                    return new UserRequirementDTO(binding.type().name(), text,
                            binding.isForm() ? binding.formCode() : "", formName);
                })
                .toList();
        List<String> requirements = requirementDetails.stream().map(UserRequirementDTO::text).toList();
        return new UserActivityDTO(
                activity.id(),
                activity.title(),
                activity.summary(),
                withDescription ? activity.description() : "",
                activity.coverUrl(),
                activity.signupStart(),
                activity.signupEnd(),
                activity.activityStart(),
                activity.activityEnd(),
                activity.status().name(),
                activity.deptMode() == ActivityDeptMode.DEPTS,
                allowedDeptNames,
                requirements,
                requirementDetails,
                joinedCount,
                eligible,
                joinDisabledReason(activity, eligible, joined),
                participation == null ? "" : participation.status().name(),
                participation == null ? 0 : participation.joinedAt(),
                participation == null ? "" : participation.verifyStatus().name(),
                participation == null ? "" : participation.verifyNote()
        );
    }

    private String joinDisabledReason(Activity activity, boolean eligible, boolean joined) {
        if (joined) {
            return "";
        }
        if (activity.status() == ActivityStatus.CLOSED) {
            return "活动已结束";
        }
        if (activity.status() != ActivityStatus.PUBLISHED) {
            return "活动未开放报名";
        }
        if (activity.activityEnded(System.currentTimeMillis())) {
            return "活动已结束";
        }
        if (!activity.signupOpen(System.currentTimeMillis())) {
            return signupClosedReason(activity, System.currentTimeMillis());
        }
        if (!eligible) {
            return "仅限指定部门成员参与";
        }
        return "";
    }

    private String signupClosedReason(Activity activity, long now) {
        return activity.signupStart() > 0 && now < activity.signupStart() ? "报名尚未开始" : "报名已截止";
    }

    private ActivityParticipantAdminDTO toAdminParticipant(Activity activity, ActivityParticipation participation) {
        String userId = participation.userId();
        String username = Optional.ofNullable(parseUserId(userId))
                .flatMap(id -> framework.users().findById(id))
                .map(profile -> firstText(profile.nickname(), profile.username()))
                .orElse("");
        PluginStudentInfoProfile profile = studentInfoService()
                .flatMap(service -> service.findStudentInfoByUserId(userId))
                .orElse(null);
        ResolvedPlayer player = resolvePlayer(activity.firstPlaytimeServerId(), userId);
        return new ActivityParticipantAdminDTO(
                participation.activityId(),
                userId,
                username,
                profile == null ? "" : profile.studentName(),
                profile == null ? "" : profile.studentNo(),
                profile == null ? "" : profile.className(),
                profile == null ? "" : profile.college(),
                player == null ? "" : player.playerId(),
                player == null ? "" : player.playerName(),
                participation.status().name(),
                participation.joinedAt(),
                participation.cancelledAt(),
                participation.verifyStatus().name(),
                participation.verifiedAt(),
                participation.verifyNote(),
                participation.source().name()
        );
    }

    private MyParticipationDTO toMyDTO(ActivityParticipation participation) {
        Activity activity = repository.activity(participation.activityId()).orElse(null);
        return new MyParticipationDTO(
                participation.activityId(),
                activity == null ? "（活动已删除）" : activity.title(),
                activity == null ? "" : activity.coverUrl(),
                activity == null ? 0 : activity.activityStart(),
                activity == null ? 0 : activity.activityEnd(),
                activity == null ? "" : activity.status().name(),
                participation.status().name(),
                participation.joinedAt(),
                participation.cancelledAt(),
                participation.verifyStatus().name(),
                participation.verifiedAt(),
                participation.verifyNote()
        );
    }

    private ActivityProofSettingsDTO toDTO(ActivityProofSettings settings) {
        return new ActivityProofSettingsDTO(settings.hasTemplate(), stringId(settings.templateId()), settings.templateCode(), settings.templateName(),
                settings.templateFilename(), settings.templateUpdatedAt(),
                settings.defaultActivityName(), settings.defaultCollege(), settings.defaultIssuer(),
                settings.qqNotifyEnabled(), settings.qqConnectionId(), settings.qqGroupIds(), settings.qqMessageTemplate(),
                settings.qqSignupButtonEnabled(), settings.effectiveSignupButtonLabel(),
                settings.updatedAt());
    }

    private ActivityProofTemplateDTO toDTO(PluginWordTemplateSummary template) {
        return new ActivityProofTemplateDTO(stringId(template.id()), template.code(), template.name(),
                template.originalFilename(), template.updatedAt());
    }

    private ActivityProofMappingDTO toDTO(PlayerStudentMapping mapping) {
        return new ActivityProofMappingDTO(mapping.id(), mapping.serverId(), mapping.playerId(), mapping.playerName(),
                mapping.studentNo(), mapping.createdAt(), mapping.updatedAt());
    }

    private ActivityProofExportDTO toDTO(ActivityProofExportRecord record) {
        return toDTO(record, false);
    }

    private ActivityProofExportDTO toDTO(ActivityProofExportRecord record, boolean mine) {
        return new ActivityProofExportDTO(record.id(), record.activityId(), record.serverId(), record.serverName(), record.activityName(),
                record.outputFilename(), mine ? "" : "/admin/exports/" + encode(record.id()) + "/download",
                record.participantCount(), record.unmatchedCount(), record.operatorUserId(), record.generatedAt(),
                record.hasStampedPdf(),
                record.stampedPdfFilename(),
                stampedPdfDownloadPath(record, mine),
                record.stampedPdfSize(),
                record.stampedPdfUploadedAt());
    }

    private String stampedPdfDownloadPath(ActivityProofExportRecord record, boolean mine) {
        if (!record.hasStampedPdf()) {
            return "";
        }
        String prefix = mine ? "/me/exports/" : "/admin/exports/";
        return prefix + encode(record.id()) + "/stamped-pdf/download";
    }

    // ---------------------------------------------------------------- internals: providers

    private Optional<PluginMinecraftService> minecraftService() {
        return SoftDependencyServices.minecraft(pluginContext);
    }

    private Optional<PluginStudentInfoService> studentInfoService() {
        return pluginContext == null ? Optional.empty() : pluginContext.service(STUDENT_INFO_PLUGIN, PluginStudentInfoService.class);
    }

    private Optional<PluginSkinService> skinService() {
        return SoftDependencyServices.skin(pluginContext);
    }

    private PluginFormService formService() {
        if (framework == null) {
            return null;
        }
        try {
            return framework.forms();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean formEnabled() {
        PluginFormService forms = formService();
        return forms != null && forms.enabled();
    }

    private boolean wordTemplateEnabled() {
        return framework != null && framework.wordTemplates() != null && framework.wordTemplates().enabled();
    }

    // ---------------------------------------------------------------- internals: settings/template chain

    private ActivityProofSettings currentSettings() {
        ActivityProofSettings settings = repository.settings();
        ActivityProofSettings refreshed = refreshTemplate(settings);
        return refreshed == settings ? settings : repository.saveSettings(refreshed);
    }

    private ActivityProofSettings refreshTemplate(ActivityProofSettings settings) {
        if (settings == null || !settings.hasTemplate() || !wordTemplateEnabled()) {
            return settings;
        }
        Optional<PluginWordTemplateSummary> byId = framework.wordTemplates().template(settings.templateId());
        if (byId.isPresent()) {
            return sameTemplate(settings, byId.get()) ? settings : withTemplateSummary(settings, byId.get());
        }
        return findTemplateByCode(firstText(settings.templateCode(), DEFAULT_TEMPLATE_CODE))
                .map(template -> sameTemplate(settings, template) ? settings : withTemplateSummary(settings, template))
                .orElse(settings);
    }

    private ActivityProofSettings withTemplate(ActivityProofSettings settings, String templateId) {
        Long id = requireTemplateId(templateId);
        PluginWordTemplateSummary template = framework.wordTemplates().template(id)
                .or(() -> findTemplateByCode(firstText(settings.templateCode(), DEFAULT_TEMPLATE_CODE)))
                .orElseThrow(() -> new IllegalArgumentException("Word 模板不存在或已停用"));
        return withTemplateSummary(settings, template);
    }

    private ActivityProofSettings withTemplateSummary(ActivityProofSettings settings, PluginWordTemplateSummary template) {
        return settings.withTemplate(template.id(), template.code(), template.name(), template.originalFilename(),
                template.updatedAt(), System.currentTimeMillis());
    }

    private Optional<PluginWordTemplateSummary> findTemplateByCode(String code) {
        if (!hasText(code) || !wordTemplateEnabled()) {
            return Optional.empty();
        }
        String normalized = normalizeKey(code);
        int page = 1;
        while (true) {
            List<PluginWordTemplateSummary> batch = framework.wordTemplates().templates(code, page, SCAN_PAGE_SIZE);
            Optional<PluginWordTemplateSummary> matched = batch.stream()
                    .filter(template -> normalized.equals(normalizeKey(template.code())))
                    .findFirst();
            if (matched.isPresent() || batch.size() < SCAN_PAGE_SIZE) {
                return matched;
            }
            page++;
        }
    }

    private boolean sameTemplate(ActivityProofSettings settings, PluginWordTemplateSummary template) {
        return settings != null
                && template != null
                && settings.templateId() != null
                && settings.templateId().equals(template.id())
                && text(settings.templateCode()).equals(text(template.code()))
                && text(settings.templateName()).equals(text(template.name()))
                && text(settings.templateFilename()).equals(text(template.originalFilename()))
                && settings.templateUpdatedAt() == template.updatedAt();
    }

    // ---------------------------------------------------------------- internals: pdf/upload helpers

    private byte[] decodeBase64(String value) {
        String safeValue = requireText(value, "PDF 内容不能为空");
        int commaIndex = safeValue.indexOf(',');
        if (commaIndex >= 0) {
            safeValue = safeValue.substring(commaIndex + 1);
        }
        try {
            return Base64.getDecoder().decode(safeValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PDF 内容不是有效的 Base64", e);
        }
    }

    private String pdfFilename(String filename, ActivityProofExportRecord record) {
        String value = hasText(filename) ? filename.trim() : record.outputFilename().replaceAll("(?i)\\.docx$", ".pdf");
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!value.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            value = value + ".pdf";
        }
        return value;
    }

    private String pdfContentType(String contentType, String filename) {
        String value = text(contentType);
        if (value.isBlank()) {
            return PDF_CONTENT_TYPE;
        }
        if (!PDF_CONTENT_TYPE.equalsIgnoreCase(value) && !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("仅支持上传 PDF 文件");
        }
        return PDF_CONTENT_TYPE;
    }

    private void ensurePdfContent(byte[] content) {
        if (content.length < 5
                || content[0] != '%'
                || content[1] != 'P'
                || content[2] != 'D'
                || content[3] != 'F'
                || content[4] != '-') {
            throw new IllegalArgumentException("仅支持上传 PDF 文件");
        }
    }

    private void deleteFileQuietly(String objectKey) {
        if (!hasText(objectKey)) {
            return;
        }
        try {
            files.delete(objectKey);
        } catch (RuntimeException ignored) {
        }
    }

    // ---------------------------------------------------------------- internals: misc helpers

    private int safePage(int page) {
        return Math.max(page, 1);
    }

    private int safeSize(int size) {
        return Math.max(Math.min(size <= 0 ? 20 : size, MAX_SCAN_SIZE), 1);
    }

    private long epoch(Long value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    private String defaultProofNo() {
        return "NO." + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
    }

    private String todayText() {
        return DateTimeFormatter.ofPattern("yyyy年M月d日").format(LocalDate.now());
    }

    private String outputFilename(String activityName, String recordId) {
        String baseName = text(activityName).isBlank() ? "minecraft-activity-proof" : text(activityName);
        return baseName.replaceAll("[\\\\/:*?\"<>|]", "_") + "-" + recordId.substring(0, 8) + ".docx";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeKey(String value) {
        return text(value).toLowerCase(Locale.ROOT);
    }

    private String stringId(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long requireTemplateId(String value) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("请选择 Word 模板");
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Word template ID: " + value);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
