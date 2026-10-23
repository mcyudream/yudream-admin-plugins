package online.yudream.base.plugin.activityproof.application.service;

import online.yudream.base.plugin.activityproof.domain.aggregate.Activity;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityEffect;
import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityStatus;
import online.yudream.base.plugin.activityproof.domain.repo.ActivityEffectRepository;
import online.yudream.base.plugin.activityproof.domain.repo.ActivityProofRepository;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 活动效果提交：上传附件、提交、查询、审核与删除。附件先落插件文件存储，提交记录只引用 objectKey。 */
public class ActivityEffectService {

    /** 单附件大小上限；宿主网关本身允许更大，这里按业务收紧（默认 200MB，视频场景够用）。 */
    public static final long MAX_ATTACHMENT_BYTES = 200L * 1024 * 1024;
    /** 单次提交附件数量上限。 */
    public static final int MAX_ATTACHMENTS_PER_SUBMIT = 20;

    private final ActivityProofRepository activities;
    private final ActivityEffectRepository effects;
    private final PluginFileStore files;

    public ActivityEffectService(ActivityProofRepository activities, ActivityEffectRepository effects,
                                 PluginFileStore files) {
        this.activities = activities;
        this.effects = effects;
        this.files = files;
    }

    /** 上传单个附件（multipart 直传）：活动已结束后开放，返回已落库的附件描述。 */
    public ActivityEffect.Attachment uploadAttachment(String activityId, String filename,
                                                      String contentType, byte[] data) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("上传文件内容为空");
        }
        return storeAttachment(activityId, filename, contentType, data);
    }

    /** 平台文件桥：前端经 sdk.files.uploadImage 先传宿主 /api/files，这里按 fileId 拷入插件存储。 */
    public ActivityEffect.Attachment uploadPlatformFile(String activityId, String fileId, String filenameHint,
                                                        online.yudream.base.plugin.spi.system.FrameworkServices framework) {
        requireSubmissionOpen(activityId);
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("缺少平台文件 fileId");
        }
        online.yudream.base.plugin.spi.system.storage.PluginStoredFile platform;
        try {
            platform = framework.platformFile(fileId)
                    .orElseThrow(() -> new IllegalArgumentException("平台文件不存在或已被清理，请重新上传"));
        } catch (LinkageError e) {
            throw new IllegalStateException("宿主文件服务不可用，请升级宿主");
        }
        String filename = filenameHint != null && !filenameHint.isBlank()
                ? filenameHint : String.valueOf(platform.objectKey());
        byte[] data;
        long length;
        try (var input = platform.inputStream()) {
            if (platform.contentLength() != null && platform.contentLength() >= 0) {
                length = platform.contentLength();
                data = input.readAllBytes();
            } else {
                data = input.readAllBytes();
                length = data.length;
            }
        } catch (Exception e) {
            throw new IllegalStateException("平台文件读取失败：" + e.getMessage(), e);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("上传文件内容为空");
        }
        if (length > MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("单个文件不能超过 200MB");
        }
        return storeAttachment(activityId, filename, platform.contentType(), data);
    }

    private ActivityEffect.Attachment storeAttachment(String activityId, String filename,
                                                      String contentType, byte[] data) {
        if (data.length > MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("单个文件不能超过 200MB");
        }
        String safeName = sanitize(filename);
        String objectKey = "effects/" + activityId + "/" + UUID.randomUUID() + "/" + safeName;
        String resolvedType = contentType == null || contentType.isBlank() ? kindOf(safeName) : contentType;
        try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
            files.put(objectKey, input, data.length, resolvedType);
        } catch (Exception e) {
            throw new IllegalStateException("附件保存失败：" + e.getMessage(), e);
        }
        return new ActivityEffect.Attachment(objectKey, safeName, resolvedType, data.length,
                kindOf(safeName, resolvedType), "");
    }

    /** 提交效果：文字 + 已上传附件描述。 */
    public ActivityEffect submit(String activityId, String userId, String text,
                                 List<ActivityEffect.Attachment> attachments) {
        requireSubmissionOpen(activityId);
        List<ActivityEffect.Attachment> safe = attachments == null ? List.of() : attachments;
        if (safe.size() > MAX_ATTACHMENTS_PER_SUBMIT) {
            throw new IllegalArgumentException("一次最多提交 " + MAX_ATTACHMENTS_PER_SUBMIT + " 个附件");
        }
        if ((text == null || text.isBlank()) && safe.isEmpty()) {
            throw new IllegalArgumentException("请填写效果说明或至少上传一个附件");
        }
        return effects.save(ActivityEffect.create(activityId, userId, text, safe));
    }

    public ActivityEffect requireOwned(String effectId, String userId) {
        ActivityEffect effect = effect(effectId)
                .orElseThrow(() -> new IllegalArgumentException("效果提交不存在或已被删除"));
        if (!effect.submittedBy().equals(userId)) {
            throw new IllegalArgumentException("只能操作本人的效果提交");
        }
        return effect;
    }

    public java.util.Optional<ActivityEffect> effect(String effectId) {
        return effects.effect(effectId);
    }

    /** 撤回本人待审提交（连同附件文件一并清理）。 */
    public void withdraw(String effectId, String userId) {
        ActivityEffect effect = requireOwned(effectId, userId);
        if (!effect.pending()) {
            throw new IllegalArgumentException("已审核的提交不能撤回");
        }
        deleteWithFiles(effect);
    }

    public List<ActivityEffect> byActivity(String activityId, int page, int size) {
        return effects.effectsByActivity(activityId, page, size);
    }

    public long countByActivity(String activityId) {
        return effects.countEffectsByActivity(activityId);
    }

    public List<ActivityEffect> bySubmitter(String userId, int page, int size) {
        return effects.effectsBySubmitter(userId, page, size);
    }

    public long countBySubmitter(String userId) {
        return effects.countEffectsBySubmitter(userId);
    }

    public List<ActivityEffect> adminList(String activityId, String status, int page, int size) {
        return effects.effects(activityId, status, page, size);
    }

    public long adminCount(String activityId, String status) {
        return effects.countEffects(activityId, status);
    }

    public ActivityEffect review(String effectId, String reviewer, boolean accept, String note) {
        ActivityEffect effect = effects.effect(effectId)
                .orElseThrow(() -> new IllegalArgumentException("效果提交不存在或已被删除"));
        ActivityEffect reviewed = effect.review(reviewer, accept, note);
        if (!accept) {
            // 驳回即清理附件文件，避免存储悬挂
            deleteAttachmentFiles(reviewed.attachments());
        }
        return effects.save(reviewed);
    }

    public void adminDelete(String effectId) {
        effects.effect(effectId).ifPresent(this::deleteWithFiles);
    }

    /** 活动是否已开放效果提交：已结束，或已发布且活动结束时间已过。 */
    public boolean submissionOpen(String activityId) {
        return activityOf(activityId).map(this::ended).orElse(false);
    }

    private void requireSubmissionOpen(String activityId) {
        Activity activity = activityOf(activityId)
                .orElseThrow(() -> new IllegalArgumentException("活动不存在或已被删除"));
        if (!ended(activity)) {
            throw new IllegalArgumentException("活动结束后才能上传效果材料");
        }
    }

    private boolean ended(Activity activity) {
        if (activity.status() == ActivityStatus.CLOSED) {
            return true;
        }
        return activity.status() == ActivityStatus.PUBLISHED
                && activity.activityEnd() > 0
                && activity.activityEnd() <= System.currentTimeMillis();
    }

    private Optional<Activity> activityOf(String activityId) {
        return activities.activity(activityId);
    }

    private void deleteWithFiles(ActivityEffect effect) {
        deleteAttachmentFiles(effect.attachments());
        effects.delete(effect.id());
    }

    private void deleteAttachmentFiles(List<ActivityEffect.Attachment> attachments) {
        for (ActivityEffect.Attachment attachment : attachments) {
            try {
                files.delete(attachment.fileKey());
            } catch (RuntimeException ignored) {
                // 单个附件清理失败不阻塞主流程
            }
        }
    }

    private static String sanitize(String filename) {
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty()) {
            name = "file";
        }
        return name;
    }

    private static String kindOf(String filename) {
        return kindOf(filename, "");
    }

    private static String kindOf(String filename, String contentType) {
        String lower = (filename + " " + (contentType == null ? "" : contentType)).toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("image/") || endsWithAny(lower, ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic")) {
            return "image";
        }
        if (lower.contains("video/") || endsWithAny(lower, ".mp4", ".mov", ".webm", ".avi", ".mkv")) {
            return "video";
        }
        return "doc";
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 供控制器将附件上传结果直接返回给前端。 */
    public static List<ActivityEffect.Attachment> copyAttachments(List<ActivityEffect.Attachment> source) {
        return source == null ? List.of() : new ArrayList<>(source);
    }

    /** 读附件文件流（下载/预览用）。 */
    public PluginStoredFile openFile(String objectKey) {
        return files.get(objectKey);
    }
}
