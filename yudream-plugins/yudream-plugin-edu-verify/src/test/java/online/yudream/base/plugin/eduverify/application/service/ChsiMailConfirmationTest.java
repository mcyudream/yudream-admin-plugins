package online.yudream.base.plugin.eduverify.application.service;

import online.yudream.base.plugin.eduverify.application.chsi.ChsiVerifier;
import online.yudream.base.plugin.eduverify.domain.aggregate.AuditLog;
import online.yudream.base.plugin.eduverify.domain.aggregate.ChsiSession;
import online.yudream.base.plugin.eduverify.domain.aggregate.EduDomain;
import online.yudream.base.plugin.eduverify.domain.aggregate.EduVerification;
import online.yudream.base.plugin.eduverify.domain.aggregate.EmailCode;
import online.yudream.base.plugin.eduverify.domain.aggregate.StagingMaterial;
import online.yudream.base.plugin.eduverify.domain.aggregate.VerifySettings;
import online.yudream.base.plugin.eduverify.domain.repo.AuditLogRepository;
import online.yudream.base.plugin.eduverify.domain.repo.ChsiSessionRepository;
import online.yudream.base.plugin.eduverify.domain.repo.EduDomainRepository;
import online.yudream.base.plugin.eduverify.domain.repo.EduVerificationRepository;
import online.yudream.base.plugin.eduverify.domain.repo.EmailCodeRepository;
import online.yudream.base.plugin.eduverify.domain.repo.StagingMaterialRepository;
import online.yudream.base.plugin.eduverify.domain.repo.VerifySettingsRepository;
import online.yudream.base.plugin.eduverify.infrastructure.MaterialFileStorage;
import online.yudream.base.plugin.eduverify.infrastructure.UploadRateLimiter;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import online.yudream.base.plugin.spi.system.command.PluginCommandService;
import online.yudream.base.plugin.spi.system.document.PluginWordTemplateService;
import online.yudream.base.plugin.spi.system.mail.PluginInboundMailMatch;
import online.yudream.base.plugin.spi.system.mail.PluginInboundMailQuery;
import online.yudream.base.plugin.spi.system.mail.PluginInboundMailService;
import online.yudream.base.plugin.spi.system.mail.PluginMailService;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageResult;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingConnection;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingGroup;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingRawService;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingService;
import online.yudream.base.plugin.spi.system.preview.PluginFilePreviewService;
import online.yudream.base.plugin.spi.system.render.PluginRenderService;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.base.plugin.spi.system.user.PluginQqBindingService;
import online.yudream.base.plugin.spi.system.user.PluginUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChsiMailConfirmationTest {

    private static final String EMAIL = "student@example.com";
    private static final String VCODE = "APEVUKH9C8SSGS5D";

    private MemoryVerificationRepository verifications;
    private MemorySettingsRepository settingsRepo;
    private StubInboundMail inboundMail;
    private EduVerifyAppService app;

    @BeforeEach
    void setUp() {
        verifications = new MemoryVerificationRepository();
        settingsRepo = new MemorySettingsRepository(withMailConfirmation(true));
        inboundMail = new StubInboundMail();
        app = new EduVerifyAppService(
                verifications,
                new MemoryEmailCodes(),
                new MemoryDomains(),
                new MemoryAudits(),
                settingsRepo,
                new MemoryChsiSessions(),
                (code, config) -> ChsiVerifier.ChsiVerifyResult.passed("张三", "某某大学", "在籍"),
                new StubFramework(inboundMail),
                new MaterialFileStorage(new NoopFiles()),
                new MemoryStaging(),
                new UploadRateLimiter(),
                new PluginFilePreviewService() {
                }
        );
    }

    @Test
    void htmlSuccessWaitsForOfficialMailWhenConfirmationEnabled() {
        Map<String, Object> result = app.chsiVerify(EMAIL, VCODE, "", "");
        assertEquals("PENDING_MAIL", result.get("status"));
        assertTrue(app.isPendingMail(EMAIL));
        assertFalse(app.isPassed(EMAIL));
        EduVerification waiting = verifications.findById("CHSI:" + EMAIL).orElseThrow();
        assertEquals("PENDING_MAIL", waiting.status());
        assertEquals("张三", waiting.realName());
        assertEquals("某某大学", waiting.schoolName());
    }

    @Test
    void htmlSuccessStillPassesWhenConfirmationDisabled() {
        settingsRepo.save(withMailConfirmation(false));
        Map<String, Object> result = app.chsiVerify(EMAIL, VCODE, "张三", "某某大学");
        assertEquals("PASSED", result.get("status"));
        assertTrue(app.isPassed(EMAIL));
        assertFalse(app.isPendingMail(EMAIL));
    }

    @Test
    void matchedOfficialMailPromotesPendingRecord() {
        app.chsiVerify(EMAIL, VCODE, "张三", "某某大学");
        inboundMail.next = PluginInboundMailMatch.matched(System.currentTimeMillis());
        Map<String, Object> result = app.confirmChsiMail(EMAIL);
        assertEquals("PASSED", result.get("status"));
        assertTrue(app.isPassed(EMAIL));
        assertFalse(app.isPendingMail(EMAIL));
        assertEquals(VCODE, inboundMail.lastQuery.verificationCode());
        assertTrue(inboundMail.lastQuery.allowedFromDomains().contains("chsi.com.cn"));
        assertTrue(inboundMail.lastQuery.requiredKeywords().contains("在线验证报告"));
        assertEquals("default", inboundMail.lastQuery.mailboxId());
    }

    @Test
    void unavailableInboundMailDoesNotPass() {
        app.chsiVerify(EMAIL, VCODE, "张三", "某某大学");
        inboundMail.next = PluginInboundMailMatch.unavailable("宿主暂时无法读取入站邮箱");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> app.confirmChsiMail(EMAIL));
        assertTrue(error.getMessage().contains("无法读取"));
        assertFalse(app.isPassed(EMAIL));
        assertEquals("PENDING_MAIL", verifications.findById("CHSI:" + EMAIL).orElseThrow().status());
    }

    @Test
    void pendingMailWithoutMatchDoesNotPass() {
        app.chsiVerify(EMAIL, VCODE, "张三", "某某大学");
        inboundMail.next = PluginInboundMailMatch.pending("尚未收到匹配的学信网报告邮件");
        Map<String, Object> result = app.confirmChsiMail(EMAIL);
        assertEquals("PENDING_MAIL", result.get("status"));
        assertFalse(app.isPassed(EMAIL));
        assertTrue(app.isPendingMail(EMAIL));
    }

    @Test
    void expiredMailWaitFailsClosed() {
        app.chsiVerify(EMAIL, VCODE, "张三", "某某大学");
        EduVerification waiting = verifications.findById("CHSI:" + EMAIL).orElseThrow();
        verifications.save(new EduVerification(
                waiting.id(), waiting.emailLower(), waiting.userId(), waiting.channel(), waiting.status(),
                waiting.realName(), waiting.schoolName(), waiting.note(), waiting.vcode(), waiting.materials(),
                waiting.reason(), waiting.submittedAt(), waiting.decidedAt(), waiting.decidedBy(),
                System.currentTimeMillis() - 1_000L, waiting.createdAt(), waiting.updatedAt()
        ));
        inboundMail.next = PluginInboundMailMatch.matched(System.currentTimeMillis());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> app.confirmChsiMail(EMAIL));
        assertTrue(error.getMessage().contains("超时"));
        assertFalse(app.isPassed(EMAIL));
        assertEquals("EXPIRED", verifications.findById("CHSI:" + EMAIL).orElseThrow().status());
    }

    @Test
    void pollLeavesPendingWhenImapUnavailable() {
        app.chsiVerify(EMAIL, VCODE, "张三", "某某大学");
        inboundMail.next = PluginInboundMailMatch.unavailable("宿主暂时无法读取入站邮箱");
        assertEquals(0, app.pollPendingChsiMails());
        assertFalse(app.isPassed(EMAIL));
        assertEquals("PENDING_MAIL", verifications.findById("CHSI:" + EMAIL).orElseThrow().status());
    }

    private static VerifySettings withMailConfirmation(boolean enabled) {
        VerifySettings defaults = VerifySettings.defaults();
        return new VerifySettings(
                defaults.emailEnabled(), defaults.chsiEnabled(), defaults.manualEnabled(),
                defaults.codeTtlMinutes(), defaults.codeResendSeconds(), defaults.codeDailyLimit(),
                defaults.validityDays(), defaults.retentionDays(), defaults.chsiDailyLimit(),
                defaults.chsiReportUrlTemplate(), defaults.chsiSelectors(),
                defaults.emailTutorialMarkdown(), defaults.chsiTutorialMarkdown(), defaults.manualTutorialMarkdown(),
                defaults.manualNotifyEnabled(), defaults.manualNotifyGroups(), defaults.manualNotifyTemplate(),
                enabled, defaults.chsiMailboxId(), defaults.chsiAllowedFromDomains(),
                defaults.chsiMailKeywords(), defaults.chsiMailWaitMinutes()
        );
    }

    private static final class StubInboundMail implements PluginInboundMailService {
        PluginInboundMailMatch next = PluginInboundMailMatch.pending("尚未收到匹配的学信网报告邮件");
        PluginInboundMailQuery lastQuery;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String mailboxId() {
            return "default";
        }

        @Override
        public PluginInboundMailMatch match(PluginInboundMailQuery query) {
            lastQuery = query;
            return next;
        }
    }

    private static final class StubFramework implements FrameworkServices {
        private final PluginInboundMailService inboundMail;

        private StubFramework(PluginInboundMailService inboundMail) {
            this.inboundMail = inboundMail;
        }

        @Override
        public PluginUserService users() {
            return new PluginUserService() {
                @Override
                public Optional<online.yudream.base.plugin.spi.system.user.PluginUserProfile> authenticate(String usernameOrEmail, String password) {
                    return Optional.empty();
                }

                @Override
                public online.yudream.base.plugin.spi.system.user.PluginUserProfile create(online.yudream.base.plugin.spi.system.user.PluginUserCreate create) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<online.yudream.base.plugin.spi.system.user.PluginUserProfile> findById(Long userId) {
                    return Optional.empty();
                }

                @Override
                public Optional<online.yudream.base.plugin.spi.system.user.PluginUserProfile> findByUsername(String username) {
                    return Optional.empty();
                }

                @Override
                public Optional<online.yudream.base.plugin.spi.system.user.PluginUserProfile> findByEmail(String email) {
                    return Optional.empty();
                }

                @Override
                public Optional<online.yudream.base.plugin.spi.system.user.PluginUserProfile> findByQq(String qq) {
                    return Optional.empty();
                }

                @Override
                public void bindQqOnce(Long userId, String qq) {
                }

                @Override
                public List<online.yudream.base.plugin.spi.system.user.PluginUserOption> searchUsers(String keyword, Long deptId, int page, int size) {
                    return List.of();
                }

                @Override
                public List<online.yudream.base.plugin.spi.system.user.PluginDeptOption> listDepartments(String keyword) {
                    return List.of();
                }

                @Override
                public List<online.yudream.base.plugin.spi.system.user.PluginUserRole> listRoles(Long userId) {
                    return List.of();
                }

                @Override
                public List<online.yudream.base.plugin.spi.system.user.PluginUserDept> listDepartments(Long userId) {
                    return List.of();
                }

                @Override
                public void updateProfile(Long userId, online.yudream.base.plugin.spi.system.user.PluginUserProfileUpdate update) {
                }

                @Override
                public List<online.yudream.base.plugin.spi.system.user.PluginUserTag> listTags(Long userId) {
                    return List.of();
                }

                @Override
                public void replaceTags(Long userId, String namespace, List<online.yudream.base.plugin.spi.system.user.PluginUserTag> tags) {
                }

                @Override
                public void replaceFields(Long userId, String namespace, List<online.yudream.base.plugin.spi.system.user.PluginUserField> fields) {
                }
            };
        }

        @Override
        public PluginQqBindingService qqBindings() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginCommandService commands() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginAiService ai() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginSecurityService security() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginMailService mail() {
            return message -> {
            };
        }

        @Override
        public PluginInboundMailService inboundMail() {
            return inboundMail;
        }

        @Override
        public PluginWordTemplateService wordTemplates() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginDocumentStore documents(String pluginCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginFileStore files(String pluginCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginMessagingService messaging() {
            return new PluginMessagingService() {
                @Override
                public List<PluginMessagingConnection> connections() {
                    return List.of();
                }

                @Override
                public List<PluginMessagingGroup> groups(String connectionId) {
                    return List.of();
                }

                @Override
                public CompletionStage<PluginMessageResult> send(PluginMessageRequest request) {
                    return CompletableFuture.completedFuture(new PluginMessageResult(List.of(), false, true));
                }

                @Override
                public CompletionStage<PluginMessageResult> sendDirectToBoundUser(String userId, PluginMessageContent content) {
                    return CompletableFuture.completedFuture(new PluginMessageResult(List.of(), false, true));
                }

                @Override
                public CompletionStage<PluginMessageResult> sendToChannel(String connectionId, String channelId, PluginMessageContent content) {
                    return CompletableFuture.completedFuture(new PluginMessageResult(List.of(), false, true));
                }
            };
        }

        @Override
        public PluginMessagingRawService messagingRaw() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginRenderService render() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PluginStoredFile> platformFile(String fileId) {
            return Optional.empty();
        }

        @Override
        public Optional<String> setting(String key) {
            return Optional.empty();
        }
    }

    private static final class MemoryVerificationRepository implements EduVerificationRepository {
        private final Map<String, EduVerification> store = new LinkedHashMap<>();

        @Override
        public EduVerification save(EduVerification verification) {
            store.put(verification.id(), verification);
            return verification;
        }

        @Override
        public Optional<EduVerification> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<EduVerification> findByEmail(String emailLower) {
            return store.values().stream().filter(item -> emailLower.equals(item.emailLower())).toList();
        }

        @Override
        public List<EduVerification> findByUserId(String userId) {
            return store.values().stream().filter(item -> userId.equals(item.userId())).toList();
        }

        @Override
        public List<EduVerification> findByStatus(String status, int page, int size) {
            return store.values().stream().filter(item -> status.equals(item.status())).toList();
        }

        @Override
        public List<EduVerification> findByChannel(String channel, int page, int size) {
            return store.values().stream().filter(item -> channel.equals(item.channel())).toList();
        }

        @Override
        public List<EduVerification> listAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public long count() {
            return store.size();
        }

        @Override
        public void delete(String id) {
            store.remove(id);
        }
    }

    private static final class MemorySettingsRepository implements VerifySettingsRepository {
        private VerifySettings current;

        private MemorySettingsRepository(VerifySettings current) {
            this.current = current;
        }

        @Override
        public VerifySettings get() {
            return current;
        }

        @Override
        public VerifySettings save(VerifySettings settings) {
            current = settings;
            return current;
        }
    }

    private static final class MemoryEmailCodes implements EmailCodeRepository {
        @Override
        public EmailCode save(EmailCode code) {
            return code;
        }

        @Override
        public Optional<EmailCode> findByEmail(String emailLower) {
            return Optional.empty();
        }

        @Override
        public void delete(String emailLower) {
        }
    }

    private static final class MemoryDomains implements EduDomainRepository {
        @Override
        public EduDomain save(EduDomain domain) {
            return domain;
        }

        @Override
        public Optional<EduDomain> findByDomain(String domain) {
            return Optional.empty();
        }

        @Override
        public List<EduDomain> listAll() {
            return List.of();
        }

        @Override
        public void delete(String domain) {
        }
    }

    private static final class MemoryAudits implements AuditLogRepository {
        @Override
        public AuditLog save(AuditLog log) {
            return log;
        }

        @Override
        public List<AuditLog> listRecent(int page, int size) {
            return List.of();
        }

        @Override
        public List<AuditLog> findByEmail(String emailLower, int page, int size) {
            return List.of();
        }
    }

    private static final class MemoryChsiSessions implements ChsiSessionRepository {
        @Override
        public ChsiSession save(ChsiSession session) {
            return session;
        }

        @Override
        public Optional<ChsiSession> findByEmail(String emailLower) {
            return Optional.empty();
        }

        @Override
        public void delete(String emailLower) {
        }
    }

    private static final class MemoryStaging implements StagingMaterialRepository {
        @Override
        public StagingMaterial save(StagingMaterial material) {
            return material;
        }

        @Override
        public Optional<StagingMaterial> findById(String id) {
            return Optional.empty();
        }

        @Override
        public List<StagingMaterial> findByEmail(String emailLower) {
            return List.of();
        }

        @Override
        public List<StagingMaterial> listAll() {
            return List.of();
        }

        @Override
        public void delete(String id) {
        }
    }

    private static final class NoopFiles implements PluginFileStore {
        @Override
        public String put(String objectKey, InputStream inputStream, long contentLength, String contentType) {
            return objectKey;
        }

        @Override
        public PluginStoredFile get(String objectKey) {
            throw new IllegalStateException("unused");
        }

        @Override
        public void delete(String objectKey) {
        }
    }
}
