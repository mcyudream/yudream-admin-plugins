package online.yudream.base.plugin.eduverify.bootstrap;

import online.yudream.base.plugin.eduverify.application.service.EduVerifyAppService;
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
import online.yudream.base.plugin.spi.system.auth.IdentityVerificationResult;
import online.yudream.base.plugin.spi.system.command.PluginCommandService;
import online.yudream.base.plugin.spi.system.document.PluginWordTemplateService;
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

class EduVerifyRegisterGateTest {

    private static final String EMAIL = "student@example.com";

    private MemoryVerificationRepository verifications;
    private MemorySettingsRepository settingsRepo;
    private EduVerifyAppService app;

    @BeforeEach
    void setUp() {
        verifications = new MemoryVerificationRepository();
        settingsRepo = new MemorySettingsRepository(VerifySettings.defaults());
        app = new EduVerifyAppService(
                verifications,
                new MemoryEmailCodes(),
                new MemoryDomains(),
                new MemoryAudits(),
                settingsRepo,
                new MemoryChsiSessions(),
                (code, config) -> {
                    throw new UnsupportedOperationException();
                },
                new EmptyFramework(),
                new MaterialFileStorage(new NoopFiles()),
                new MemoryStaging(),
                new UploadRateLimiter(),
                new PluginFilePreviewService() {
                }
        );
    }

    @Test
    void unverifiedEmailIsDenied() {
        IdentityVerificationResult result = EduVerifyPlugin.gate(app, EMAIL);
        assertFalse(result.verified());
        assertEquals("请先完成高校学历认证（教育邮箱、学信网在线验证码或人工审核）", result.message());
    }

    @Test
    void blankEmailIsDenied() {
        IdentityVerificationResult result = EduVerifyPlugin.gate(app, " ");
        assertFalse(result.verified());
        assertEquals("请先填写注册邮箱", result.message());
    }

    @Test
    void pendingManualReviewIsDenied() {
        long now = System.currentTimeMillis();
        verifications.save(new EduVerification(
                "MANUAL:" + EMAIL, EMAIL, null, "MANUAL", "PENDING",
                "张三", "某某大学", "", "", List.of(), null, now, 0, null, 0, now, now
        ));
        IdentityVerificationResult result = EduVerifyPlugin.gate(app, EMAIL);
        assertFalse(result.verified());
        assertEquals("人工审核中，审核通过后即可注册", result.message());
    }

    @Test
    void enablingNotifyWithoutGroupsFails() {
        VerifySettings defaults = VerifySettings.defaults();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> app.saveSettings(new VerifySettings(
                defaults.emailEnabled(), defaults.chsiEnabled(), defaults.manualEnabled(),
                defaults.codeTtlMinutes(), defaults.codeResendSeconds(), defaults.codeDailyLimit(),
                defaults.validityDays(), defaults.retentionDays(), defaults.chsiDailyLimit(),
                defaults.chsiReportUrlTemplate(), defaults.chsiSelectors(),
                defaults.emailTutorialMarkdown(), defaults.chsiTutorialMarkdown(), defaults.manualTutorialMarkdown(),
                true, List.of(), defaults.manualNotifyTemplate(),
                defaults.chsiMailConfirmationEnabled(), defaults.chsiMailboxId(), defaults.chsiAllowedFromDomains(),
                defaults.chsiMailKeywords(), defaults.chsiMailWaitMinutes()
        )));
        assertEquals("启用群通知需要选择消息连接与至少一个群", error.getMessage());
    }

    @Test
    void enablingNotifyWithGroupSucceeds() {
        VerifySettings defaults = VerifySettings.defaults();
        VerifySettings saved = app.saveSettings(new VerifySettings(
                defaults.emailEnabled(), defaults.chsiEnabled(), defaults.manualEnabled(),
                defaults.codeTtlMinutes(), defaults.codeResendSeconds(), defaults.codeDailyLimit(),
                defaults.validityDays(), defaults.retentionDays(), defaults.chsiDailyLimit(),
                defaults.chsiReportUrlTemplate(), defaults.chsiSelectors(),
                defaults.emailTutorialMarkdown(), defaults.chsiTutorialMarkdown(), defaults.manualTutorialMarkdown(),
                true, List.of(new VerifySettings.NotifyGroupTarget("conn-1", "group-1")), defaults.manualNotifyTemplate(),
                defaults.chsiMailConfirmationEnabled(), defaults.chsiMailboxId(), defaults.chsiAllowedFromDomains(),
                defaults.chsiMailKeywords(), defaults.chsiMailWaitMinutes()
        ));
        assertTrue(saved.manualNotifyEnabled());
        assertEquals(1, saved.manualNotifyGroups().size());
        assertEquals("conn-1", saved.manualNotifyGroups().getFirst().connectionId());
        assertEquals("group-1", saved.manualNotifyGroups().getFirst().groupId());
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

    private static final class EmptyFramework implements FrameworkServices {
        @Override
        public PluginUserService users() {
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException();
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
}
