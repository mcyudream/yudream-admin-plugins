package online.yudream.base.plugin.eduverify.application.service;

import online.yudream.base.plugin.eduverify.application.chsi.ChsiVerifier;
import online.yudream.base.plugin.eduverify.bootstrap.EduVerifyPlugin;
import online.yudream.base.plugin.eduverify.domain.ApplicantIdentity;
import online.yudream.base.plugin.eduverify.domain.aggregate.AuditLog;
import online.yudream.base.plugin.eduverify.domain.aggregate.ChsiSession;
import online.yudream.base.plugin.eduverify.domain.aggregate.EduDomain;
import online.yudream.base.plugin.eduverify.domain.aggregate.EduVerification;
import online.yudream.base.plugin.eduverify.domain.aggregate.EmailCode;
import online.yudream.base.plugin.eduverify.domain.aggregate.VerifySettings;
import online.yudream.base.plugin.eduverify.domain.enumerate.VerificationChannel;
import online.yudream.base.plugin.eduverify.domain.enumerate.VerificationStatus;
import online.yudream.base.plugin.eduverify.domain.repo.AuditLogRepository;
import online.yudream.base.plugin.eduverify.domain.repo.ChsiSessionRepository;
import online.yudream.base.plugin.eduverify.domain.repo.EduDomainRepository;
import online.yudream.base.plugin.eduverify.domain.repo.EduVerificationRepository;
import online.yudream.base.plugin.eduverify.domain.repo.EmailCodeRepository;
import online.yudream.base.plugin.eduverify.domain.repo.VerifySettingsRepository;
import online.yudream.base.plugin.eduverify.domain.aggregate.StagingMaterial;
import online.yudream.base.plugin.eduverify.domain.repo.StagingMaterialRepository;
import online.yudream.base.plugin.eduverify.infrastructure.MaterialContentGuard;
import online.yudream.base.plugin.eduverify.infrastructure.MaterialFileStorage;
import online.yudream.base.plugin.eduverify.infrastructure.UploadRateLimiter;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.mail.PluginInboundMailMatch;
import online.yudream.base.plugin.spi.system.mail.PluginInboundMailQuery;
import online.yudream.base.plugin.spi.system.mail.PluginMailMessage;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.preview.PluginFilePreviewService;
import online.yudream.base.plugin.spi.system.preview.PluginPreviewFile;
import online.yudream.base.plugin.spi.system.preview.PluginPreviewInfo;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.base.plugin.spi.system.user.PluginUserTag;
import online.yudream.base.plugin.spi.system.user.PluginUserField;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class EduVerifyAppService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.ofHours(8));
    private static final long CHSI_SESSION_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_MATERIALS = 6;
    private static final long MAX_MATERIAL_BYTES = MaterialContentGuard.MAX_BYTES;
    private static final long STAGING_TTL_MS = TimeUnit.HOURS.toMillis(2);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EduVerificationRepository verifications;
    private final EmailCodeRepository emailCodes;
    private final EduDomainRepository domains;
    private final AuditLogRepository audits;
    private final VerifySettingsRepository settingsRepo;
    private final ChsiSessionRepository chsiSessions;
    private final ChsiVerifier chsiVerifier;
    private final FrameworkServices framework;
    private final MaterialFileStorage files;
    private final StagingMaterialRepository staging;
    private final UploadRateLimiter uploadLimiter;
    private final PluginFilePreviewService preview;

    public EduVerifyAppService(
            EduVerificationRepository verifications,
            EmailCodeRepository emailCodes,
            EduDomainRepository domains,
            AuditLogRepository audits,
            VerifySettingsRepository settingsRepo,
            ChsiSessionRepository chsiSessions,
            ChsiVerifier chsiVerifier,
            FrameworkServices framework,
            MaterialFileStorage files,
            StagingMaterialRepository staging,
            UploadRateLimiter uploadLimiter,
            PluginFilePreviewService preview
    ) {
        this.verifications = verifications;
        this.emailCodes = emailCodes;
        this.domains = domains;
        this.audits = audits;
        this.settingsRepo = settingsRepo;
        this.chsiSessions = chsiSessions;
        this.chsiVerifier = chsiVerifier;
        this.framework = framework;
        this.files = files;
        this.staging = staging;
        this.uploadLimiter = uploadLimiter;
        this.preview = preview;
    }

    public void seedDefaults() {
        if (domains.listAll().isEmpty()) {
            long now = now();
            domains.save(new EduDomain("edu.cn", null, null, true, "SYSTEM", now, now));
        }
        settingsRepo.get();
    }

    public Map<String, Object> publicMethods() {
        VerifySettings settings = settingsRepo.get();
        List<Map<String, Object>> channels = new ArrayList<>();
        channels.add(channel("EMAIL", "教育邮箱", "填写白名单内的高校教育邮箱即视为已核验，无需验证码", settings.emailEnabled(), settings.emailTutorialMarkdown()));
        channels.add(channel("CHSI", "学信网在线验证码", settings.chsiMailConfirmationEnabled()
                ? "填写真实姓名、学校与 16 位在线验证码；官方报告页核验通过后，还须用学信网页面将报告发送到指定邮箱，两步都通过后才可注册"
                : "填写真实姓名、学校与 16 位在线验证码；读取官方报告页核验，失败则转入人工审核", settings.chsiEnabled(), settings.chsiTutorialMarkdown()));
        channels.add(channel("MANUAL", "人工审核", "填写真实姓名与学校并上传证明材料，由管理员核对后留档", settings.manualEnabled(), settings.manualTutorialMarkdown()));
        channels.add(channel("CARSI", "CARSI（预留）", "高校身份联邦尚未开通，当前不可用", false, null));
        return Map.of(
                "pluginCode", EduVerifyPlugin.CODE,
                "methodCode", EduVerifyPlugin.METHOD_CODE,
                "channels", channels,
                "settings", publicSettings(settings)
        );
    }

    public Map<String, Object> sendEmailCode(String rawEmail) {
        VerifySettings settings = settingsRepo.get();
        require(settings.emailEnabled(), "教育邮箱认证未开启");
        String email = normalizeEmail(rawEmail);
        requireEduDomain(email);
        long now = now();
        EmailCode existing = emailCodes.findByEmail(email).orElse(null);
        String today = DAY.format(Instant.ofEpochMilli(now));
        int sendCount = 1;
        if (existing != null) {
            if (now - existing.lastSendAt() < TimeUnit.SECONDS.toMillis(settings.codeResendSeconds())) {
                throw new IllegalArgumentException("发送过于频繁，请稍后再试");
            }
            sendCount = today.equals(existing.dayBucket()) ? existing.sendCount() + 1 : 1;
            if (sendCount > settings.codeDailyLimit()) {
                throw new IllegalArgumentException("今日验证码发送次数已达上限");
            }
        }
        String code = sixDigits();
        String hash = sha256(code + ":" + email);
        long expiresAt = now + TimeUnit.MINUTES.toMillis(settings.codeTtlMinutes());
        EmailCode saved = existing == null
                ? new EmailCode(email, hash, expiresAt, 0, sendCount, today, now, now)
                : existing.nextSend(hash, expiresAt, sendCount, today, now);
        emailCodes.save(saved);
        framework.mail().send(PluginMailMessage.text(
                List.of(email),
                "高校学历认证验证码",
                "您的教育邮箱认证验证码为 " + code + "，" + settings.codeTtlMinutes() + " 分钟内有效。如非本人操作请忽略本邮件。"
        ));
        audit("USER", email, "EMAIL_SEND", "EMAIL", email, null, "发送验证码");
        return Map.of(
                "sent", true,
                "email", maskEmail(email),
                "ttlMinutes", settings.codeTtlMinutes(),
                "resendSeconds", settings.codeResendSeconds()
        );
    }

    public Map<String, Object> verifyEmailCode(String rawEmail, String code) {
        VerifySettings settings = settingsRepo.get();
        require(settings.emailEnabled(), "教育邮箱认证未开启");
        String email = normalizeEmail(rawEmail);
        requireText(code, "验证码不能为空");
        EmailCode stored = emailCodes.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("请先获取验证码"));
        long now = now();
        if (stored.expiresAt() < now) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        if (stored.attempts() >= 5) {
            throw new IllegalArgumentException("验证码错误次数过多，请重新获取");
        }
        if (!sha256(code.trim() + ":" + email).equals(stored.codeHash())) {
            emailCodes.save(stored.withAttempt(stored.attempts() + 1));
            throw new IllegalArgumentException("验证码不正确");
        }
        emailCodes.delete(email);
        EduVerification passed = pass(email, VerificationChannel.EMAIL, null, extractSchool(email), "教育邮箱验证通过", null, List.of(), now, settings);
        audit("USER", email, "EMAIL_PASS", "EMAIL", email, passed.userId(), "教育邮箱认证通过");
        return toPublicVerification(passed, false);
    }

    public Map<String, Object> chsiVerify(String rawEmail, String vcode, String realName, String schoolName) {
        VerifySettings settings = settingsRepo.get();
        require(settings.chsiEnabled(), "学信网认证未开启");
        String email = normalizeEmail(rawEmail);
        String code = requireText(vcode, "请填写 16 位学信网在线验证码").trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9]{16}")) {
            throw new IllegalArgumentException("在线验证码必须是 16 位字母或数字");
        }
        long now = now();
        ensureChsiQuota(email, settings, now);
        ChsiVerifier.ChsiVerifyResult result = chsiVerifier.verify(code, new ChsiVerifier.ChsiParseConfig(
                settings.chsiReportUrlTemplate(), settings.chsiSelectors()
        ));
        return switch (result.kind()) {
            case "PASSED" -> {
                chsiSessions.delete(email);
                ApplicantIdentity identity = ApplicantIdentity.require(result.realName(), result.schoolName());
                if (!settings.chsiMailConfirmationEnabled()) {
                    EduVerification passed = pass(email, VerificationChannel.CHSI, identity.realName(), identity.schoolName(),
                            "学信网在线验证码核验通过", code, List.of(), now, settings);
                    audit("USER", email, "CHSI_PASS", "CHSI", email, passed.userId(),
                            identity.audit("学信网核验通过", null));
                    yield Map.of("status", "PASSED", "verification", toPublicVerification(passed, false));
                }
                yield waitForChsiMail(email, code, identity, now, settings);
            }
            case "UNAVAILABLE" -> degradeToManual(email, code, result.message(), realName, schoolName, now, settings);
            default -> degradeToManual(email, code, result.message(), realName, schoolName, now, settings);
        };
    }

    public Map<String, Object> submitManual(String rawEmail, String realName, String schoolName, String note,
                                            String vcode, List<Map<String, Object>> materialItems) {
        VerifySettings settings = settingsRepo.get();
        require(settings.manualEnabled(), "人工审核未开启");
        String email = normalizeEmail(rawEmail);
        ApplicantIdentity identity = ApplicantIdentity.require(realName, schoolName);
        String name = identity.realName();
        String school = identity.schoolName();
        List<Map<String, Object>> materials = storeMaterials(email, materialItems);
        long now = now();
        String id = EduVerification.idOf("MANUAL", email);
        EduVerification existing = verifications.findById(id).orElse(null);
        if (existing != null && "PASSED".equals(existing.status()) && existing.passed(now)) {
            throw new IllegalArgumentException("该邮箱已通过认证，无需重复提交");
        }
        long createdAt = existing == null ? now : existing.createdAt();
        List<Map<String, Object>> nextMaterials = materials.isEmpty() && existing != null ? existing.materials() : materials;
        if (nextMaterials.isEmpty()) {
            throw new IllegalArgumentException("请至少上传一份证明材料");
        }
        EduVerification pending = new EduVerification(id, email, existing == null ? null : existing.userId(),
                "MANUAL", "PENDING", name, school, blankToNull(note), blankToNull(vcode),
                nextMaterials, null, now, 0L, null, 0L, createdAt, now);
        verifications.save(pending);
        audit("USER", email, "MANUAL_SUBMIT", "MANUAL", email, pending.userId(),
                identity.audit("提交人工审核", blankToNull(note)));
        notifyManualReview(pending, settings);
        syncUserTags(pending.userId());
        return toPublicVerification(pending, false);
    }

    public Map<String, Object> uploadManualMaterial(String rawEmail, String filename, String contentType, String content, String clientKey) {
        VerifySettings settings = settingsRepo.get();
        require(settings.manualEnabled(), "人工审核未开启");
        String email = normalizeEmail(rawEmail);
        String ipKey = hashClientKey(clientKey);
        String today = DAY.format(Instant.ofEpochMilli(now()));
        uploadLimiter.acquire("ip:" + ipKey, 8, TimeUnit.MINUTES.toMillis(1), 2000, 40, today);
        uploadLimiter.acquire("email:" + email, 6, TimeUnit.MINUTES.toMillis(10), 3000, 18, today);
        byte[] bytes = MaterialContentGuard.decodeBase64(content);
        MaterialContentGuard.Detected detected = MaterialContentGuard.detect(bytes, contentType, filename);
        String safeName = MaterialContentGuard.sanitizeFilename(filename, detected.extension());
        String digest = sha256Bytes(bytes);
        long now = now();
        List<StagingMaterial> existing = liveStaging(email, now);
        for (StagingMaterial item : existing) {
            if (digest.equals(item.sha256())) {
                return toUploadResult(item);
            }
        }
        if (existing.size() >= MAX_MATERIALS) {
            throw new IllegalArgumentException("该邮箱待提交材料已达 " + MAX_MATERIALS + " 份上限");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String objectKey = "staging/" + email.replace('@', '_') + "/" + id + "-" + safeName;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            files.put(objectKey, in, bytes.length, detected.contentType());
        } catch (Exception e) {
            throw new IllegalArgumentException("材料保存失败：" + e.getMessage(), e);
        }
        StagingMaterial saved = staging.save(new StagingMaterial(
                id, email, ipKey, objectKey, safeName, detected.contentType(), bytes.length, digest, now, now + STAGING_TTL_MS
        ));
        return toUploadResult(saved);
    }

    public Map<String, Object> confirmChsiMail(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        EduVerification item = verifications.findById(EduVerification.idOf(VerificationChannel.CHSI.name(), email))
                .orElseThrow(() -> new IllegalArgumentException("未找到该邮箱的学信网认证记录"));
        return applyChsiMailMatch(item, settingsRepo.get(), now(), true);
    }

    public int pollPendingChsiMails() {
        VerifySettings settings = settingsRepo.get();
        if (!settings.chsiMailConfirmationEnabled()) {
            return 0;
        }
        long now = now();
        int changed = 0;
        for (EduVerification item : verifications.listAll()) {
            if (!"PENDING_MAIL".equals(item.status())) {
                continue;
            }
            try {
                Map<String, Object> result = applyChsiMailMatch(item, settings, now, false);
                if (!"PENDING_MAIL".equals(String.valueOf(result.get("status")))) {
                    changed++;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return changed;
    }

    public Map<String, Object> statusByEmail(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        verifications.findById(EduVerification.idOf(VerificationChannel.CHSI.name(), email))
                .filter(item -> "PENDING_MAIL".equals(item.status()))
                .ifPresent(item -> {
                    try {
                        applyChsiMailMatch(item, settingsRepo.get(), now(), false);
                    } catch (RuntimeException ignored) {
                    }
                });
        List<EduVerification> records = verifications.findByEmail(email);
        Map<String, Object> payload = statusPayload(records, false);
        boolean eduDomain = matchesNormalizedEduDomain(email);
        boolean passed = Boolean.TRUE.equals(payload.get("passed")) || eduDomain;
        boolean pendingMail = !passed && records.stream().anyMatch(item -> "PENDING_MAIL".equals(item.status()));
        boolean pending = !passed && (pendingMail || records.stream().anyMatch(item -> "PENDING".equals(item.status())));
        payload.put("passed", passed);
        payload.put("eduDomain", eduDomain);
        payload.put("pending", pending);
        payload.put("pendingMail", pendingMail);
        return payload;
    }

    public Map<String, Object> adminPage(String status, String channel, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        String statusFilter = blankToNull(status);
        String channelFilter = blankToNull(channel);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<EduVerification> all = verifications.listAll().stream()
                .filter(item -> item.userId() == null || item.userId().isBlank())
                .filter(item -> statusFilter == null || statusFilter.equals(item.status()))
                .filter(item -> channelFilter == null || channelFilter.equals(item.channel()))
                .filter(item -> kw.isBlank()
                        || contains(item.emailLower(), kw)
                        || contains(item.realName(), kw)
                        || contains(item.schoolName(), kw)
                        || contains(item.userId(), kw)
                        || contains(item.vcode(), kw))
                .sorted((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()))
                .toList();
        int from = Math.min((safePage - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<Map<String, Object>> records = all.subList(from, to).stream()
                .map(item -> toAdminVerification(item, false))
                .toList();
        return Map.of("records", records, "total", all.size(), "page", safePage, "size", safeSize);
    }

    public Map<String, Object> adminDetail(String id) {
        EduVerification item = verifications.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("认证记录不存在"));
        return toAdminVerification(item, true);
    }

    public Map<String, Object> approve(String id, String reviewerId, String reason, String realName, String schoolName) {
        EduVerification item = requirePendingOrRejected(id);
        ApplicantIdentity identity = ApplicantIdentity.require(realName, schoolName, item.realName(), item.schoolName());
        VerifySettings settings = settingsRepo.get();
        long now = now();
        long expiresAt = settings.validityDays() <= 0 ? 0L : now + TimeUnit.DAYS.toMillis(settings.validityDays());
        EduVerification saved = verifications.save(item.decided(
                "PASSED", reviewerId, blankToNull(reason), identity.realName(), identity.schoolName(), expiresAt, now));
        notifyResult(saved, true, reason);
        audit("ADMIN", reviewerId, "APPROVE", saved.channel(), saved.emailLower(), saved.userId(),
                identity.audit("通过认证", reason));
        syncUserTags(saved.userId());
        return toAdminVerification(saved, true);
    }

    public Map<String, Object> reject(String id, String reviewerId, String reason) {
        String note = requireText(reason, "驳回必须填写原因");
        EduVerification item = requirePendingOrRejected(id);
        long now = now();
        EduVerification saved = verifications.save(item.decided("REJECTED", reviewerId, note, 0L, now));
        notifyResult(saved, false, note);
        audit("ADMIN", reviewerId, "REJECT", saved.channel(), saved.emailLower(), saved.userId(), note);
        syncUserTags(saved.userId());
        return toAdminVerification(saved, true);
    }

    public Map<String, Object> revoke(String id, String reviewerId, String reason) {
        String note = requireText(reason, "撤销必须填写原因");
        EduVerification item = verifications.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("认证记录不存在"));
        if (!"PASSED".equals(item.status())) {
            throw new IllegalArgumentException("仅已通过的认证可以撤销");
        }
        if (item.userId() != null && !item.userId().isBlank()) {
            throw new IllegalArgumentException("已注册用户的认证记录不可撤销");
        }
        long now = now();
        EduVerification saved = verifications.save(item.decided("REVOKED", reviewerId, note, 0L, now));
        notifyResult(saved, false, "认证已撤销：" + note);
        audit("ADMIN", reviewerId, "REVOKE", saved.channel(), saved.emailLower(), saved.userId(), note);
        syncUserTags(saved.userId());
        return toAdminVerification(saved, true);
    }

    public List<Map<String, Object>> listDomains() {
        return domains.listAll().stream()
                .sorted((a, b) -> a.domain().compareTo(b.domain()))
                .map(this::toDomain)
                .toList();
    }

    public Map<String, Object> saveDomain(String rawDomain, String chineseName, String englishName, boolean enabled, String source) {
        String domain = normalizeDomain(rawDomain);
        long now = now();
        EduDomain existing = domains.findByDomain(domain).orElse(null);
        String resolvedSource = source == null || source.isBlank() ? "MANUAL" : source.trim().toUpperCase(Locale.ROOT);
        if (existing != null && existing.manuallyMaintained() && !"MANUAL".equals(resolvedSource)) {
            throw new IllegalArgumentException("管理员维护的域名不能被导入任务覆盖");
        }
        EduDomain saved = domains.save(new EduDomain(
                domain,
                blankToNull(chineseName),
                blankToNull(englishName),
                enabled,
                resolvedSource,
                existing == null ? now : existing.createdAt(),
                now
        ));
        audit("ADMIN", null, "DOMAIN_SAVE", null, null, null, domain);
        return toDomain(saved);
    }

    public void deleteDomain(String rawDomain) {
        String domain = normalizeDomain(rawDomain);
        if ("edu.cn".equals(domain)) {
            throw new IllegalArgumentException("默认教育邮箱后缀不能删除");
        }
        domains.delete(domain);
        audit("ADMIN", null, "DOMAIN_DELETE", null, null, null, domain);
    }

    public VerifySettings settings() {
        return settingsRepo.get();
    }

    public VerifySettings saveSettings(VerifySettings next) {
        VerifySettings sanitized = new VerifySettings(
                next.emailEnabled(),
                next.chsiEnabled(),
                next.manualEnabled(),
                clamp(next.codeTtlMinutes(), 5, 60),
                clamp(next.codeResendSeconds(), 30, 600),
                clamp(next.codeDailyLimit(), 1, 20),
                clamp(next.validityDays(), 0, 3650),
                clamp(next.retentionDays(), 1, 365),
                clamp(next.chsiDailyLimit(), 1, 20),
                sanitizeUrlTemplate(next.chsiReportUrlTemplate()),
                sanitizeSelectors(next.chsiSelectors()),
                sanitizeTutorial(next.emailTutorialMarkdown(), VerifySettings.defaults().emailTutorialMarkdown()),
                sanitizeTutorial(next.chsiTutorialMarkdown(), VerifySettings.defaults().chsiTutorialMarkdown()),
                sanitizeTutorial(next.manualTutorialMarkdown(), VerifySettings.defaults().manualTutorialMarkdown()),
                next.manualNotifyEnabled(),
                sanitizeNotifyGroups(next.manualNotifyEnabled(), next.manualNotifyGroups()),
                sanitizeNotifyTemplate(next.manualNotifyTemplate()),
                next.chsiMailConfirmationEnabled(),
                sanitizeMailboxId(next.chsiMailboxId()),
                normalizeTextList(next.chsiAllowedFromDomains(), VerifySettings.defaults().chsiAllowedFromDomains()),
                normalizeTextList(next.chsiMailKeywords(), VerifySettings.defaults().chsiMailKeywords()),
                clamp(next.chsiMailWaitMinutes(), 1, 60)
        );
        VerifySettings saved = settingsRepo.save(sanitized);
        audit("ADMIN", null, "SETTINGS_SAVE", null, null, null, null);
        return saved;
    }

    public Map<String, Object> auditPage(String email, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 50);
        List<AuditLog> records = email == null || email.isBlank()
                ? audits.listRecent(safePage, safeSize)
                : audits.findByEmail(normalizeEmail(email), safePage, safeSize);
        return Map.of(
                "records", records.stream().map(this::toAudit).toList(),
                "page", safePage,
                "size", safeSize
        );
    }

    public boolean isPassed(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String normalized;
        try {
            normalized = normalizeEmail(email);
        } catch (IllegalArgumentException e) {
            return false;
        }
        long now = now();
        if (verifications.findByEmail(normalized).stream().anyMatch(item -> item.passed(now))) {
            return true;
        }
        return matchesNormalizedEduDomain(normalized);
    }

    public boolean isPending(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        try {
            return verifications.findByEmail(normalizeEmail(email)).stream()
                    .anyMatch(item -> "PENDING".equals(item.status()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isPendingMail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        try {
            return verifications.findByEmail(normalizeEmail(email)).stream()
                    .anyMatch(item -> "PENDING_MAIL".equals(item.status()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public void ensureEmailDomainPass(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return;
        }
        String email;
        try {
            email = normalizeEmail(rawEmail);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!matchesNormalizedEduDomain(email)) {
            return;
        }
        long now = now();
        if (verifications.findByEmail(email).stream().anyMatch(item -> item.passed(now))) {
            return;
        }
        EduVerification passed = pass(
                email,
                VerificationChannel.EMAIL,
                null,
                extractSchool(email),
                "教育邮箱域名白名单自动核验",
                null,
                List.of(),
                now,
                settingsRepo.get()
        );
        audit("SYSTEM", email, "EMAIL_DOMAIN_PASS", "EMAIL", email, passed.userId(), "教育邮箱域名自动核验通过");
    }

    public void bindUser(String email, String userId) {
        if (email == null || email.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        ensureEmailDomainPass(email);
        long now = now();
        long userKey;
        try {
            userKey = Long.parseLong(userId);
        } catch (NumberFormatException e) {
            return;
        }
        String emailLower = email.trim().toLowerCase(Locale.ROOT);
        for (EduVerification item : verifications.findByEmail(emailLower)) {
            if (item.userId() != null && !item.userId().isBlank()) {
                continue;
            }
            if (!item.passed(now)) {
                continue;
            }
            syncConsumedUserTags(userKey, item);
            verifications.delete(item.id());
            audit("SYSTEM", userId, "REGISTER_CONSUME", item.channel(), item.emailLower(), userId,
                    "注册成功后消费认证记录");
        }
    }

    private void syncConsumedUserTags(long userId, EduVerification item) {
        List<PluginUserField> fields = new ArrayList<>();
        if (item.realName() != null && !item.realName().isBlank()) {
            fields.add(new PluginUserField(EduVerifyPlugin.CODE, "realName", "姓名", item.realName()));
        }
        if (item.schoolName() != null && !item.schoolName().isBlank()) {
            fields.add(new PluginUserField(EduVerifyPlugin.CODE, "schoolName", "学校", item.schoolName()));
        }
        try {
            framework.users().replaceFields(userId, EduVerifyPlugin.CODE, fields);
        } catch (RuntimeException ignored) {
        }
        List<PluginUserTag> tags = new ArrayList<>();
        if (item.realName() != null && !item.realName().isBlank()
                && item.schoolName() != null && !item.schoolName().isBlank()) {
            tags.addAll(ApplicantIdentity.require(item.realName(), item.schoolName()).personnelTags(verificationTag(item)));
        } else {
            tags.add(new PluginUserTag(EduVerifyPlugin.CODE, "verified", verificationTag(item)));
            if (item.schoolName() != null && !item.schoolName().isBlank()) {
                tags.add(new PluginUserTag(EduVerifyPlugin.CODE, "school", item.schoolName()));
            }
        }
        replaceUserTags(userId, tags);
    }

    private String verificationTag(EduVerification item) {
        if ("CHSI".equals(item.channel())) {
            return "学信网核验";
        }
        if ("EMAIL".equals(item.channel())) {
            return "edu邮箱核验";
        }
        if ("MANUAL".equals(item.channel())) {
            return "人工核验:" + (item.decidedBy() == null || item.decidedBy().isBlank() ? "SYSTEM" : item.decidedBy());
        }
        return "学历认证";
    }

    public void syncUserTags(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        long userKey;
        try {
            userKey = Long.parseLong(userId);
        } catch (NumberFormatException e) {
            return;
        }
        List<EduVerification> mine = verifications.findByUserId(userId);
        EduVerification best = pickBest(mine);
        if (best == null) {
            replaceUserTags(userKey, List.of());
            return;
        }
        List<PluginUserTag> tags = new ArrayList<>();
        String statusLabel = tagStatusName(best.status());
        if (best.realName() != null && !best.realName().isBlank()
                && best.schoolName() != null && !best.schoolName().isBlank()) {
            tags.addAll(ApplicantIdentity.require(best.realName(), best.schoolName()).personnelTags(statusLabel));
        } else {
            if (best.schoolName() != null && !best.schoolName().isBlank()) {
                tags.add(new PluginUserTag(EduVerifyPlugin.CODE, "school", best.schoolName()));
            }
            if (statusLabel != null) {
                tags.add(new PluginUserTag(EduVerifyPlugin.CODE, "status", statusLabel));
            }
        }
        replaceUserTags(userKey, tags);
    }

    public int purgeExpiredMaterials() {
        VerifySettings settings = settingsRepo.get();
        long cutoff = now() - TimeUnit.DAYS.toMillis(settings.retentionDays());
        int purged = 0;
        for (EduVerification item : verifications.listAll()) {
            if (!"PASSED".equals(item.status()) && !"REJECTED".equals(item.status()) && !"REVOKED".equals(item.status())) {
                continue;
            }
            if (item.decidedAt() <= 0 || item.decidedAt() > cutoff || item.materials().isEmpty()) {
                continue;
            }
            for (Map<String, Object> material : item.materials()) {
                Object key = material.get("objectKey");
                if (key != null) {
                    files.deleteQuietly(String.valueOf(key));
                    purged++;
                }
            }
            verifications.save(item.withMaterials(List.of(), item.vcode(), now()));
        }
        purged += purgeExpiredStaging();
        return purged;
    }

    public int purgeExpiredStaging() {
        long now = now();
        int purged = 0;
        for (StagingMaterial item : staging.listAll()) {
            if (item.expiresAt() > now) {
                continue;
            }
            files.deleteQuietly(item.objectKey());
            staging.delete(item.id());
            purged++;
        }
        return purged;
    }

    private Map<String, Object> waitForChsiMail(
            String email,
            String code,
            ApplicantIdentity identity,
            long now,
            VerifySettings settings
    ) {
        String id = EduVerification.idOf(VerificationChannel.CHSI.name(), email);
        EduVerification existing = verifications.findById(id).orElse(null);
        if (existing != null && existing.passed(now)) {
            throw new IllegalArgumentException("该邮箱已通过认证，无需重复提交");
        }
        long waitUntil = now + TimeUnit.MINUTES.toMillis(Math.max(1, settings.chsiMailWaitMinutes()));
        EduVerification pending = new EduVerification(
                id,
                email,
                existing == null ? null : existing.userId(),
                VerificationChannel.CHSI.name(),
                "PENDING_MAIL",
                identity.realName(),
                identity.schoolName(),
                "学信网报告页已核验，等待官方报告邮件确认",
                code,
                List.of(),
                "请在学信网报告页使用官方发送按钮，把报告发到站点指定收件邮箱",
                now,
                0L,
                null,
                waitUntil,
                existing == null ? now : existing.createdAt(),
                now
        );
        verifications.save(pending);
        audit("USER", email, "CHSI_WAIT_MAIL", "CHSI", email, pending.userId(),
                identity.audit("等待学信网报告邮件", null));
        return applyChsiMailMatch(pending, settings, now, false);
    }

    private Map<String, Object> applyChsiMailMatch(EduVerification item, VerifySettings settings, long now, boolean userTriggered) {
        if (item == null || !"CHSI".equals(item.channel())) {
            if (userTriggered) {
                throw new IllegalArgumentException("当前记录不是学信网邮件确认状态");
            }
            return Map.of("status", item == null ? "NOT_FOUND" : item.status());
        }
        if (item.passed(now)) {
            return Map.of("status", "PASSED", "verification", toPublicVerification(item, false));
        }
        if (!"PENDING_MAIL".equals(item.status())) {
            if (userTriggered) {
                throw new IllegalArgumentException("当前记录不是学信网邮件确认状态");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", item.status());
            payload.put("verification", toPublicVerification(item, false));
            return payload;
        }
        if (item.mailWaitExpired(now)) {
            EduVerification expired = verifications.save(item.decided(
                    "EXPIRED", "SYSTEM", "等待学信网报告邮件已超时", 0L, now));
            audit("SYSTEM", "inbound-mail", "CHSI_MAIL_EXPIRED", "CHSI", expired.emailLower(), expired.userId(),
                    "等待学信网报告邮件已超时");
            if (userTriggered) {
                throw new IllegalArgumentException("等待学信网报告邮件已超时，请重新提交在线验证码");
            }
            return Map.of(
                    "status", "EXPIRED",
                    "message", "等待学信网报告邮件已超时，请重新提交在线验证码",
                    "verification", toPublicVerification(expired, false)
            );
        }
        PluginInboundMailMatch match;
        try {
            match = framework.inboundMail().match(new PluginInboundMailQuery(
                    resolveMailboxId(settings),
                    item.vcode(),
                    settings.chsiAllowedFromDomains(),
                    settings.chsiMailKeywords(),
                    item.submittedAt(),
                    item.expiresAt()
            ));
        } catch (RuntimeException error) {
            match = PluginInboundMailMatch.unavailable("宿主暂时无法读取入站邮箱");
        }
        if (match != null && PluginInboundMailMatch.MATCHED.equals(match.status())) {
            EduVerification passed = pass(
                    item.emailLower(),
                    VerificationChannel.CHSI,
                    item.realName(),
                    item.schoolName(),
                    "学信网在线验证码与官方报告邮件均已核验",
                    item.vcode(),
                    List.of(),
                    now,
                    settings
            );
            audit("SYSTEM", "inbound-mail", "CHSI_MAIL_PASS", "CHSI", passed.emailLower(), passed.userId(),
                    "学信网报告邮件匹配通过");
            return Map.of("status", "PASSED", "verification", toPublicVerification(passed, false));
        }
        String message = match == null || match.message() == null || match.message().isBlank()
                ? "尚未收到匹配的学信网报告邮件"
                : match.message();
        boolean unavailable = match != null && PluginInboundMailMatch.UNAVAILABLE.equals(match.status());
        if (unavailable && (match.message() == null || match.message().isBlank())) {
            message = "宿主暂时无法读取入站邮箱，认证不会自动通过";
        }
        if (unavailable && userTriggered) {
            throw new IllegalArgumentException(message);
        }
        EduVerification waiting = verifications.save(item.withReason(message, now));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "PENDING_MAIL");
        payload.put("message", message);
        payload.put("waitMinutes", settings.chsiMailWaitMinutes());
        payload.put("verification", toPublicVerification(waiting, false));
        return payload;
    }

    private String resolveMailboxId(VerifySettings settings) {
        String hostId = framework.inboundMail().mailboxId();
        String configured = settings.chsiMailboxId() == null ? "" : settings.chsiMailboxId().trim();
        if (configured.isBlank()) {
            return hostId == null ? "" : hostId.trim();
        }
        if (hostId != null && !hostId.isBlank() && !configured.equals(hostId.trim())) {
            return hostId.trim();
        }
        return configured;
    }

    private Map<String, Object> degradeToManual(
            String email,
            String vcode,
            String message,
            String realName,
            String schoolName,
            long now,
            VerifySettings settings
    ) {
        if (!settings.manualEnabled()) {
            throw new IllegalArgumentException(message == null ? "学信网核验失败，且人工审核未开启" : message);
        }
        String id = EduVerification.idOf("MANUAL", email);
        EduVerification existing = verifications.findById(id).orElse(null);
        if (existing != null && "PASSED".equals(existing.status()) && existing.passed(now)) {
            throw new IllegalArgumentException("该邮箱已通过认证，无需重复提交");
        }
        ApplicantIdentity identity = ApplicantIdentity.optional(
                realName, schoolName,
                existing == null ? null : existing.realName(),
                existing == null ? null : existing.schoolName()
        );
        String note = "学信网自动核验未完成，已转入人工审核。请补充证明材料，管理员将核对姓名与学校。";
        EduVerification pending = new EduVerification(
                id, email, existing == null ? null : existing.userId(), "MANUAL", "PENDING",
                identity == null ? null : identity.realName(),
                identity == null ? null : identity.schoolName(),
                note,
                vcode,
                existing == null ? List.of() : existing.materials(),
                message, now, 0L, null, 0L,
                existing == null ? now : existing.createdAt(), now
        );
        verifications.save(pending);
        audit("SYSTEM", "chsi", "CHSI_DEGRADE", "CHSI", email, pending.userId(),
                identity == null ? (message == null ? "学信网核验失败" : message) : identity.audit(message, null));
        syncUserTags(pending.userId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "DEGRADED");
        payload.put("message", message == null
                ? "学信网核验暂不可用，已转入人工审核。请补充证明材料，管理员将核对姓名与学校"
                : message + "。已转入人工审核，请补充证明材料");
        payload.put("verification", toPublicVerification(pending, false));
        return payload;
    }

    private EduVerification pass(String email, VerificationChannel channel, String realName, String schoolName,
                                 String note, String vcode, List<Map<String, Object>> materials, long now,
                                 VerifySettings settings) {
        long expiresAt = settings.validityDays() <= 0 ? 0L : now + TimeUnit.DAYS.toMillis(settings.validityDays());
        String id = EduVerification.idOf(channel.name(), email);
        EduVerification existing = verifications.findById(id).orElse(null);
        EduVerification next = new EduVerification(
                id, email, existing == null ? null : existing.userId(), channel.name(), "PASSED",
                realName, schoolName, note, vcode, materials, null, now, now, "SYSTEM", expiresAt,
                existing == null ? now : existing.createdAt(), now
        );
        EduVerification saved = verifications.save(next);
        syncUserTags(saved.userId());
        return saved;
    }

    private ChsiSession ensureChsiQuota(String email, VerifySettings settings, long now) {
        String today = DAY.format(Instant.ofEpochMilli(now));
        ChsiSession session = chsiSessions.findByEmail(email).orElse(ChsiSession.fresh(email, "", now, CHSI_SESSION_TTL_MS));
        int attempts = today.equals(session.dayBucket()) ? session.attempts() + 1 : 1;
        if (attempts > settings.chsiDailyLimit()) {
            throw new IllegalArgumentException("今日学信网核验次数已达上限，请改走人工审核");
        }
        ChsiSession counted = session.counted(attempts, today, now);
        return chsiSessions.save(counted);
    }

    private static String sanitizeMailboxId(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return "";
        }
        if (!value.matches("[a-zA-Z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("收件箱标识只能包含字母、数字、点、下划线和短横线");
        }
        return value;
    }

    private static List<String> normalizeTextList(List<String> raw, List<String> defaults) {
        if (raw == null || raw.isEmpty()) {
            return defaults;
        }
        List<String> result = raw.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return result.isEmpty() ? defaults : result;
    }

    private static long number(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String sanitizeTutorial(String raw, String defaultValue) {
        String value = raw == null || raw.isBlank() ? defaultValue : raw.trim();
        if (value.length() > 16_000) {
            throw new IllegalArgumentException("渠道教程不能超过 16000 个字符");
        }
        return value;
    }

    private static String sanitizeNotifyTemplate(String raw) {
        String value = raw == null || raw.isBlank() ? VerifySettings.defaults().manualNotifyTemplate() : raw.trim();
        if (value.length() > 2_000) {
            throw new IllegalArgumentException("审核通知模板不能超过 2000 个字符");
        }
        return value;
    }

    private static List<VerifySettings.NotifyGroupTarget> sanitizeNotifyGroups(
            boolean enabled,
            List<VerifySettings.NotifyGroupTarget> targets
    ) {
        if (targets == null || targets.isEmpty()) {
            if (enabled) {
                throw new IllegalArgumentException("启用群通知需要选择消息连接与至少一个群");
            }
            return List.of();
        }
        Map<String, VerifySettings.NotifyGroupTarget> unique = new LinkedHashMap<>();
        for (VerifySettings.NotifyGroupTarget target : targets) {
            if (target == null || !target.complete()) {
                continue;
            }
            if (target.connectionId().length() > 80 || target.groupId().length() > 80) {
                throw new IllegalArgumentException("审核通知目标格式不正确");
            }
            unique.putIfAbsent(target.connectionId() + ":" + target.groupId(), target);
        }
        if (unique.isEmpty()) {
            if (enabled) {
                throw new IllegalArgumentException("启用群通知需要选择消息连接与至少一个群");
            }
            return List.of();
        }
        if (unique.size() > 20) {
            throw new IllegalArgumentException("最多配置 20 个审核通知群");
        }
        return List.copyOf(unique.values());
    }

    private static String sanitizeUrlTemplate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > 240) {
            throw new IllegalArgumentException("报告页地址模板过长");
        }
        if (!(value.startsWith("https://www.chsi.com.cn/") || value.startsWith("http://www.chsi.com.cn/"))) {
            throw new IllegalArgumentException("报告页地址模板仅允许学信网官方域名");
        }
        if (!value.contains("{code}")) {
            throw new IllegalArgumentException("报告页地址模板必须包含 {code}");
        }
        return value;
    }

    private static Map<String, String> sanitizeSelectors(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> allowed = Map.of(
                "realName", "realName",
                "schoolName", "schoolName",
                "studentStatus", "studentStatus",
                "vcode", "vcode"
        );
        Map<String, String> next = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String field = allowed.get(key.trim());
            if (field == null) {
                return;
            }
            String selector = value.trim();
            if (selector.isBlank()) {
                return;
            }
            if (selector.length() > 200) {
                throw new IllegalArgumentException("CSS 选择器过长");
            }
            next.put(field, selector);
        });
        return next.isEmpty() ? Map.of() : Map.copyOf(next);
    }

    private List<Map<String, Object>> storeMaterials(String email, List<Map<String, Object>> materialItems) {
        if (materialItems == null || materialItems.isEmpty()) {
            return List.of();
        }
        if (materialItems.size() > MAX_MATERIALS) {
            throw new IllegalArgumentException("最多上传 " + MAX_MATERIALS + " 份材料");
        }
        List<Map<String, Object>> stored = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 1;
        long now = now();
        String verificationKey = "MANUAL-" + email.replace('@', '_');
        for (Map<String, Object> item : materialItems) {
            String fileId = string(item.get("fileId"));
            String kind = firstNonBlank(string(item.get("kind")), "OTHER");
            requireText(fileId, "材料文件不能为空");
            if (!seen.add(fileId)) {
                throw new IllegalArgumentException("材料文件重复，请勿重复提交同一份文件");
            }
            StagingMaterial staged = staging.findById(fileId)
                    .orElseThrow(() -> new IllegalArgumentException("材料已失效，请重新上传"));
            if (!email.equals(staged.emailLower())) {
                throw new IllegalArgumentException("材料与联系邮箱不匹配，请用同一邮箱重新上传");
            }
            if (staged.expiresAt() < now) {
                files.deleteQuietly(staged.objectKey());
                staging.delete(staged.id());
                throw new IllegalArgumentException("材料已过期，请重新上传");
            }
            if (staged.size() > MAX_MATERIAL_BYTES) {
                throw new IllegalArgumentException("单份材料不能超过 8MB");
            }
            PluginStoredFile source = files.getOrNull(staged.objectKey());
            if (source == null) {
                staging.delete(staged.id());
                throw new IllegalArgumentException("材料已失效，请重新上传");
            }
            String filename = firstNonBlank(string(item.get("filename")), staged.filename(), "material-" + index);
            String contentType = firstNonBlank(staged.contentType(), string(item.get("contentType")), "application/octet-stream");
            String objectKey = files.objectKey(verificationKey, index, filename);
            try (InputStream in = source.inputStream()) {
                files.put(objectKey, in, staged.size(), contentType);
            } catch (Exception e) {
                throw new IllegalArgumentException("材料保存失败：" + e.getMessage(), e);
            }
            files.deleteQuietly(staged.objectKey());
            staging.delete(staged.id());
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("objectKey", objectKey);
            saved.put("filename", filename);
            saved.put("contentType", contentType);
            saved.put("kind", kind);
            saved.put("size", staged.size());
            stored.add(saved);
            index++;
        }
        return stored;
    }

    private List<StagingMaterial> liveStaging(String email, long now) {
        List<StagingMaterial> live = new ArrayList<>();
        for (StagingMaterial item : staging.findByEmail(email)) {
            if (item.expiresAt() < now) {
                files.deleteQuietly(item.objectKey());
                staging.delete(item.id());
                continue;
            }
            live.add(item);
        }
        return live;
    }

    private Map<String, Object> toUploadResult(StagingMaterial item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.id());
        map.put("originalName", item.filename());
        map.put("contentType", item.contentType());
        map.put("size", item.size());
        map.put("module", EduVerifyPlugin.CODE);
        return map;
    }

    private static String hashClientKey(String clientKey) {
        String value = clientKey == null || clientKey.isBlank() ? "unknown" : clientKey.trim().toLowerCase(Locale.ROOT);
        return sha256(value);
    }

    private static String sha256Bytes(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Map<String, Object> statusPayload(List<EduVerification> records, boolean signed) {
        long now = now();
        boolean passed = records.stream().anyMatch(item -> item.passed(now));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("passed", passed);
        payload.put("records", records.stream()
                .sorted((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()))
                .map(item -> signed ? toAdminVerification(item, true) : toPublicVerification(item, false))
                .toList());
        return payload;
    }

    private EduVerification requirePendingOrRejected(String id) {
        EduVerification item = verifications.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("认证记录不存在"));
        if (!"PENDING".equals(item.status()) && !"REJECTED".equals(item.status())) {
            throw new IllegalArgumentException("当前状态不可审核");
        }
        return item;
    }

    private void notifyManualReview(EduVerification item, VerifySettings settings) {
        if (!settings.manualNotifyEnabled() || settings.manualNotifyGroups() == null || settings.manualNotifyGroups().isEmpty()) {
            return;
        }
        String message = settings.manualNotifyTemplate()
                .replace("{email}", maskEmail(item.emailLower()))
                .replace("{realName}", item.realName() == null ? "-" : item.realName())
                .replace("{schoolName}", item.schoolName() == null ? "-" : item.schoolName())
                .replace("{materialCount}", String.valueOf(item.materials().size()));
        PluginMessageContent content = new PluginMessageContent(PluginMessageContent.Type.TEXT, message, null, Map.of());
        for (VerifySettings.NotifyGroupTarget target : settings.manualNotifyGroups()) {
            try {
                framework.messaging().sendToChannel(target.connectionId(), target.groupId(), content)
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                audit("SYSTEM", null, "MANUAL_NOTIFY_FAILED", "MANUAL", item.emailLower(), item.userId(),
                                        target.connectionId() + ":" + target.groupId() + " 发送失败");
                            }
                        });
            } catch (RuntimeException error) {
                audit("SYSTEM", null, "MANUAL_NOTIFY_FAILED", "MANUAL", item.emailLower(), item.userId(),
                        target.connectionId() + ":" + target.groupId() + " 发送失败");
            }
        }
    }

    private void notifyResult(EduVerification item, boolean passed, String reason) {
        String subject = passed ? "高校学历认证已通过" : "高校学历认证未通过";
        String body = passed
                ? "您的高校学历认证已通过，可以使用该邮箱完成账号注册。"
                : "您的高校学历认证未通过。" + (reason == null ? "" : "原因：" + reason);
        try {
            framework.mail().send(PluginMailMessage.text(List.of(item.emailLower()), subject, body));
        } catch (RuntimeException ignored) {
        }
        if (item.userId() != null && !item.userId().isBlank()) {
            try {
                framework.messaging().sendDirectToBoundUser(item.userId(),
                        new PluginMessageContent(PluginMessageContent.Type.TEXT, body, List.of(), Map.of()));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void requireEduDomain(String email) {
        if (!matchesNormalizedEduDomain(email)) {
            throw new IllegalArgumentException("该邮箱域名不在教育邮箱白名单中");
        }
    }

    private boolean matchesNormalizedEduDomain(String email) {
        return bestDomain(email) != null;
    }

    private EduDomain bestDomain(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            return null;
        }
        String domain = email.substring(email.indexOf('@') + 1);
        return domains.listAll().stream()
                .filter(item -> item != null && item.matches(domain))
                .sorted((left, right) -> Integer.compare(right.domain().length(), left.domain().length()))
                .findFirst()
                .orElse(null);
    }

    private void replaceUserTags(long userId, List<PluginUserTag> tags) {
        try {
            framework.users().replaceTags(userId, EduVerifyPlugin.CODE, tags);
        } catch (RuntimeException ignored) {
        }
    }

    private static EduVerification pickBest(List<EduVerification> records) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        long now = now();
        return records.stream()
                .filter(item -> item != null)
                .min((a, b) -> {
                    int rank = Integer.compare(tagRank(a, now), tagRank(b, now));
                    if (rank != 0) {
                        return rank;
                    }
                    return Long.compare(b.updatedAt(), a.updatedAt());
                })
                .orElse(null);
    }

    private static int tagRank(EduVerification item, long now) {
        if (item.passed(now)) {
            return 0;
        }
        if ("PENDING".equals(item.status()) || "PENDING_MAIL".equals(item.status())) {
            return 1;
        }
        if ("REJECTED".equals(item.status())) {
            return 2;
        }
        if ("REVOKED".equals(item.status())) {
            return 3;
        }
        return 4;
    }

    private static String tagStatusName(String status) {
        if ("PASSED".equals(status)) {
            return "已认证";
        }
        if ("PENDING".equals(status)) {
            return "审核中";
        }
        if ("PENDING_MAIL".equals(status)) {
            return "等待邮件";
        }
        if ("REJECTED".equals(status)) {
            return "已驳回";
        }
        if ("REVOKED".equals(status)) {
            return "已撤销";
        }
        return null;
    }

    private Map<String, Object> toPublicVerification(EduVerification item, boolean includeMaterials) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.id());
        map.put("email", maskEmail(item.emailLower()));
        map.put("channel", item.channel());
        map.put("channelName", channelName(item.channel()));
        map.put("status", item.status());
        map.put("statusName", statusName(item.status()));
        map.put("realName", item.realName());
        map.put("schoolName", item.schoolName());
        map.put("reason", item.reason());
        map.put("submittedAt", String.valueOf(item.submittedAt()));
        map.put("decidedAt", String.valueOf(item.decidedAt()));
        map.put("expiresAt", String.valueOf(item.expiresAt()));
        map.put("updatedAt", String.valueOf(item.updatedAt()));
        if (includeMaterials) {
            map.put("materials", signedMaterials(item));
        }
        return map;
    }

    private Map<String, Object> toAdminVerification(EduVerification item, boolean includeMaterials) {
        Map<String, Object> map = toPublicVerification(item, false);
        map.put("email", item.emailLower());
        map.put("userId", item.userId());
        map.put("note", item.note());
        map.put("vcode", item.vcode());
        map.put("decidedBy", item.decidedBy());
        map.put("createdAt", String.valueOf(item.createdAt()));
        if (includeMaterials) {
            map.put("materials", signedMaterials(item));
        } else {
            map.put("materialCount", item.materials().size());
        }
        return map;
    }

    private List<Map<String, Object>> signedMaterials(EduVerification item) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> material : item.materials()) {
            Map<String, Object> copy = new LinkedHashMap<>(material);
            String objectKey = string(material.get("objectKey"));
            String filename = string(material.get("filename"));
            String contentType = string(material.get("contentType"));
            long size = number(material.get("size"), 0L);
            if (objectKey == null) {
                copy.put("previewMode", PluginPreviewInfo.MODE_NONE);
                copy.put("previewMessage", "材料文件不存在");
            } else {
                PluginPreviewInfo decision = preview.preview(EduVerifyPlugin.CODE,
                        new PluginPreviewFile(objectKey, filename, contentType, size));
                copy.put("previewMode", decision.mode());
                copy.put("previewUrl", decision.url());
                copy.put("previewMessage", decision.message());
                if (PluginPreviewInfo.MODE_DIRECT.equals(decision.mode()) && contentType != null && contentType.startsWith("image/")) {
                    copy.put("thumbnailUrl", decision.url());
                }
            }
            result.add(copy);
        }
        return result;
    }

    private Map<String, Object> toDomain(EduDomain domain) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("domain", domain.domain());
        map.put("chineseName", domain.chineseName());
        map.put("englishName", domain.englishName());
        map.put("enabled", domain.enabled());
        map.put("source", domain.source());
        map.put("createdAt", String.valueOf(domain.createdAt()));
        map.put("updatedAt", String.valueOf(domain.updatedAt()));
        return map;
    }

    private Map<String, Object> toAudit(AuditLog log) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", log.id());
        map.put("at", String.valueOf(log.at()));
        map.put("actorType", log.actorType());
        map.put("actorId", log.actorId());
        map.put("action", log.action());
        map.put("channel", log.channel());
        map.put("emailLower", log.emailLower());
        map.put("userId", log.userId());
        map.put("detail", log.detail());
        return map;
    }

    private Map<String, Object> publicSettings(VerifySettings settings) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("emailEnabled", settings.emailEnabled());
        map.put("chsiEnabled", settings.chsiEnabled());
        map.put("manualEnabled", settings.manualEnabled());
        map.put("codeTtlMinutes", settings.codeTtlMinutes());
        map.put("codeResendSeconds", settings.codeResendSeconds());
        map.put("validityDays", settings.validityDays());
        map.put("chsiMailConfirmationEnabled", settings.chsiMailConfirmationEnabled());
        map.put("chsiMailWaitMinutes", settings.chsiMailWaitMinutes());
        return map;
    }

    private Map<String, Object> channel(String code, String name, String description, boolean enabled, String tutorialMarkdown) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("name", name);
        map.put("description", description);
        map.put("enabled", enabled);
        map.put("tutorialMarkdown", tutorialMarkdown);
        return map;
    }

    private void audit(String actorType, String actorId, String action, String channel, String email, String userId, String detail) {
        long now = now();
        String id = inverted(now) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        audits.save(new AuditLog(id, now, actorType, actorId, action, channel, email, userId, detail));
    }

    private static String inverted(long now) {
        return String.format("%013d", Long.MAX_VALUE - now);
    }

    private static String sixDigits() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String normalizeEmail(String raw) {
        String email = requireText(raw, "邮箱不能为空").toLowerCase(Locale.ROOT);
        if (!email.contains("@") || email.startsWith("@") || email.endsWith("@")) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        return email;
    }

    private static String normalizeDomain(String raw) {
        String domain = requireText(raw, "域名不能为空").toLowerCase(Locale.ROOT).replaceFirst("^@+", "").replaceFirst("\\.$", "");
        if (domain.length() > 253 || domain.contains("@") || domain.contains("/") || domain.contains("*")
                || domain.equals("localhost") || !domain.matches("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")) {
            throw new IllegalArgumentException("域名格式不正确");
        }
        return domain;
    }

    private String extractSchool(String email) {
        EduDomain matched = bestDomain(email);
        if (matched != null && matched.chineseName() != null && !matched.chineseName().isBlank()) {
            return matched.chineseName();
        }
        String domain = email.substring(email.indexOf('@') + 1);
        if (domain.endsWith(".edu.cn")) {
            String body = domain.substring(0, domain.length() - ".edu.cn".length());
            int last = body.lastIndexOf('.');
            return last >= 0 ? body.substring(last + 1) : body;
        }
        return domain;
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "*" + email.substring(at);
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String channelName(String channel) {
        VerificationChannel value = VerificationChannel.of(channel);
        return value == null ? channel : value.displayName();
    }

    private static String statusName(String status) {
        VerificationStatus value = VerificationStatus.of(status);
        return value == null ? status : value.displayName();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean contains(String value, String kw) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(kw);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
