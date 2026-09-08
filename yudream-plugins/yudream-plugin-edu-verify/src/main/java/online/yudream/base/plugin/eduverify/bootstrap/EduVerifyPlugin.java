package online.yudream.base.plugin.eduverify.bootstrap;

import online.yudream.base.plugin.eduverify.application.chsi.ChsiVerifier;
import online.yudream.base.plugin.eduverify.application.service.EduVerifyAppService;
import online.yudream.base.plugin.eduverify.domain.repo.AuditLogRepository;
import online.yudream.base.plugin.eduverify.domain.repo.ChsiSessionRepository;
import online.yudream.base.plugin.eduverify.domain.repo.EduDomainRepository;
import online.yudream.base.plugin.eduverify.domain.repo.EduVerificationRepository;
import online.yudream.base.plugin.eduverify.domain.repo.EmailCodeRepository;
import online.yudream.base.plugin.eduverify.domain.repo.VerifySettingsRepository;
import online.yudream.base.plugin.eduverify.infrastructure.MaterialFileStorage;
import online.yudream.base.plugin.eduverify.infrastructure.UploadRateLimiter;
import online.yudream.base.plugin.eduverify.infrastructure.chsi.OfficialPageChsiVerifier;
import online.yudream.base.plugin.eduverify.infrastructure.repository.AuditLogDocumentRepository;
import online.yudream.base.plugin.eduverify.infrastructure.repository.ChsiSessionDocumentRepository;
import online.yudream.base.plugin.eduverify.infrastructure.repository.EduDomainDocumentRepository;
import online.yudream.base.plugin.eduverify.infrastructure.repository.EduVerificationDocumentRepository;
import online.yudream.base.plugin.eduverify.infrastructure.repository.EmailCodeDocumentRepository;
import online.yudream.base.plugin.eduverify.infrastructure.repository.StagingMaterialDocumentRepository;
import online.yudream.base.plugin.eduverify.infrastructure.repository.VerifySettingsDocumentRepository;
import online.yudream.base.plugin.eduverify.interfaces.controller.EduVerifyAdminController;
import online.yudream.base.plugin.eduverify.interfaces.controller.EduVerifyPublicController;
import online.yudream.base.plugin.eduverify.interfaces.http.EduVerifyHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.system.auth.AuthEventListener;
import online.yudream.base.plugin.spi.system.auth.IdentityVerificationMethod;
import online.yudream.base.plugin.spi.system.auth.IdentityVerificationProvider;
import online.yudream.base.plugin.spi.system.auth.IdentityVerificationResult;
import online.yudream.base.plugin.spi.system.auth.UserRegisteredEvent;
import online.yudream.base.plugin.spi.system.auth.VerificationSubject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@PluginSpec(
        code = EduVerifyPlugin.CODE,
        name = "学历认证",
        version = EduVerifyPlugin.VERSION,
        description = "注册前高校学历认证：教育邮箱域名核验、学信网在线验证码与人工审核三个渠道"
)
@PluginPermissions({
        @PluginPermission(code = EduVerifyPlugin.MANAGE_PERMISSION, name = "管理学历认证", module = "学历认证",
                description = "审核认证申请、维护邮箱域名白名单与认证策略")
})
@PluginFrontend(moduleName = "edu-verify", menuTitle = "学历认证", menuIcon = "i-ri:school-line", menuSort = 36, styles = {"style.css"}, routes = {
        @PluginRoute(path = "/edu-verify", name = "edu-verify-public", title = "高校学历认证",
                icon = "i-ri:school-line", component = "edu-verify/Public", hideInMenu = true, publicAccess = true),
        @PluginRoute(path = "/platform/plugins/edu-verify/admin", name = "platform-plugin-edu-verify-admin", title = "认证审核",
                icon = "i-ri:shield-check-line", component = "edu-verify/Admin", permission = EduVerifyPlugin.MANAGE_PERMISSION, sort = 90),
        @PluginRoute(path = "/platform/plugins/edu-verify/admin/domains", name = "platform-plugin-edu-verify-admin-domains", title = "邮箱域名",
                icon = "i-ri:mail-line", component = "edu-verify/Domains", permission = EduVerifyPlugin.MANAGE_PERMISSION, sort = 91),
        @PluginRoute(path = "/platform/plugins/edu-verify/admin/settings", name = "platform-plugin-edu-verify-admin-settings", title = "认证设置",
                icon = "i-ri:settings-3-line", component = "edu-verify/Settings", permission = EduVerifyPlugin.MANAGE_PERMISSION, sort = 92)
})
public final class EduVerifyPlugin implements YuDreamPlugin {

    public static final String CODE = "edu-verify";
    public static final String VERSION = "1.5.0";
    public static final String MANAGE_PERMISSION = "plugin:edu-verify:manage";
    public static final String METHOD_CODE = "edu-verify";

    private static final Logger LOGGER = Logger.getLogger(EduVerifyPlugin.class.getName());

    @Override
    public void onEnable(PluginContext context) {
        EduVerificationRepository verifications = new EduVerificationDocumentRepository(context.documents());
        EmailCodeRepository emailCodes = new EmailCodeDocumentRepository(context.documents());
        EduDomainRepository domains = new EduDomainDocumentRepository(context.documents());
        AuditLogRepository audits = new AuditLogDocumentRepository(context.documents());
        VerifySettingsRepository settings = new VerifySettingsDocumentRepository(context.documents());
        ChsiSessionRepository chsiSessions = new ChsiSessionDocumentRepository(context.documents());
        ChsiVerifier chsi = new OfficialPageChsiVerifier(context.framework().render());
        MaterialFileStorage files = new MaterialFileStorage(context.files());
        StagingMaterialDocumentRepository staging = new StagingMaterialDocumentRepository(context.documents());
        UploadRateLimiter limiter = new UploadRateLimiter();
        EduVerifyAppService app = new EduVerifyAppService(
                verifications, emailCodes, domains, audits, settings, chsiSessions, chsi,
                context.framework(), files, staging, limiter, context.filePreview()
        );
        app.seedDefaults();
        EduVerifyHttpFacade http = new EduVerifyHttpFacade(app);
        context.registerHttpController(new EduVerifyPublicController(http));
        context.registerHttpController(new EduVerifyAdminController(http));
        context.registerExtension(IdentityVerificationProvider.class, new EduVerifyIdentityProvider(app));
        context.registerExtension(AuthEventListener.class, new EduVerifyAuthListener(app));

        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "edu-verify-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduleWithFixedDelay(() -> {
            try {
                int confirmed = app.pollPendingChsiMails();
                if (confirmed > 0) {
                    LOGGER.info("[edu-verify] confirmed " + confirmed + " pending CHSI mail records");
                }
            } catch (RuntimeException exception) {
                LOGGER.log(Level.WARNING, "[edu-verify] CHSI mail poll failed", exception);
            }
        }, 30, 30, TimeUnit.SECONDS);
        watchdog.scheduleWithFixedDelay(() -> {
            try {
                int purged = app.purgeExpiredMaterials();
                if (purged > 0) {
                    LOGGER.info("[edu-verify] purged " + purged + " expired materials");
                }
            } catch (RuntimeException exception) {
                LOGGER.log(Level.WARNING, "[edu-verify] material purge failed", exception);
            }
        }, 1, 24, TimeUnit.HOURS);
        context.onDispose(watchdog::shutdownNow);
    }

    private static final class EduVerifyIdentityProvider implements IdentityVerificationProvider {

        private static final IdentityVerificationMethod METHOD = new IdentityVerificationMethod(
                METHOD_CODE,
                "高校学历认证",
                "填写高校教育邮箱即视为已核验；也可用学信网在线验证码（必要时还需官方报告邮件确认）或人工审核",
                "i-ri:school-line",
                10
        );

        private final EduVerifyAppService app;

        private EduVerifyIdentityProvider(EduVerifyAppService app) {
            this.app = app;
        }

        @Override
        public IdentityVerificationMethod method() {
            return METHOD;
        }

        @Override
        public IdentityVerificationResult check(VerificationSubject subject) {
            if (subject == null || subject.email() == null || subject.email().isBlank()) {
                return IdentityVerificationResult.unverified("请先填写注册邮箱");
            }
            if (app.isPassed(subject.email())) {
                app.ensureEmailDomainPass(subject.email());
                return IdentityVerificationResult.passed();
            }
            if (app.isPendingMail(subject.email())) {
                return IdentityVerificationResult.unverified("学信网报告邮件确认中，请使用学信网页面将报告发送到指定邮箱后再注册");
            }
            if (app.isPending(subject.email())) {
                return IdentityVerificationResult.unverified("人工审核中，审核通过后即可注册");
            }
            return IdentityVerificationResult.unverified("请先完成高校学历认证（教育邮箱、学信网在线验证码或人工审核）");
        }
    }

    private static final class EduVerifyAuthListener implements AuthEventListener {

        private final EduVerifyAppService app;

        private EduVerifyAuthListener(EduVerifyAppService app) {
            this.app = app;
        }

        @Override
        public void onUserRegistered(UserRegisteredEvent event) {
            if (event == null) {
                return;
            }
            app.bindUser(event.email(), event.userId());
        }
    }
}
