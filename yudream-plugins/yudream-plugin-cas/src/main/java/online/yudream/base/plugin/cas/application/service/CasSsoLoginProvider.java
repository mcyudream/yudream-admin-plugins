package online.yudream.base.plugin.cas.application.service;

import online.yudream.base.plugin.spi.system.auth.PluginExternalLoginAuthorizeRequest;
import online.yudream.base.plugin.spi.system.auth.PluginExternalLoginDescriptor;
import online.yudream.base.plugin.spi.system.auth.PluginExternalLoginExchangeRequest;
import online.yudream.base.plugin.spi.system.auth.PluginExternalLoginIdentity;
import online.yudream.base.plugin.spi.system.auth.PluginExternalLoginPresentation;
import online.yudream.base.plugin.spi.system.auth.PluginExternalLoginProvider;
import online.yudream.base.plugin.cas.bootstrap.CasSsoPlugin;
import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.domain.service.SsoProtocolClient;

import java.util.List;

public final class CasSsoLoginProvider implements PluginExternalLoginProvider {

    /**
     * 第三方登录通道标识 = 插件管理标识 {@link CasSsoPlugin#CODE}。
     * 宿主 SPI 2.27.0 后 login.vue / profile.vue 不再硬编码 "wwoyun"，
     * 登录入口按 {@code /api/external-login/providers} 返回的 enabled 提供方渲染并直接传入 {@code providerCode}，
     * 宿主后端按 {@code descriptor.providerCode()} 精确匹配插件扩展点。
     */
    public static final String PROVIDER_CODE = CasSsoPlugin.CODE;

    /**
     * 登录入口排序位。宿主内置 Tab 的基线是「账号密码登录」100、「Passkey 登录」200，
     * 插件入口按同一 sort 升序排列，因此 0 让本插件的统一身份认证 Tab 排在首位。
     */
    private static final int LOGIN_ENTRY_SORT = 0;

    private final SettingsService settings;
    private final StudentInfoService studentInfo;

    public CasSsoLoginProvider(SettingsService settings, StudentInfoService studentInfo) {
        this.settings = settings;
        this.studentInfo = studentInfo;
    }

    @Override
    public PluginExternalLoginDescriptor descriptor() {
        SsoSettings current = settings.current();
        return new PluginExternalLoginDescriptor(
                PROVIDER_CODE,
                current.displayName(),
                current.icon(),
                List.of(current.protocol().typeCode()),
                LOGIN_ENTRY_SORT
        );
    }

    /**
     * 登录页呈现方式：与「账号密码登录 / Passkey 登录」并列的登录方式 Tab。
     * 宿主 SPI 2.32.0 起支持；旧宿主未实现该方法时为缺省 ICON（图标按钮），不影响登录流程。
     */
    @Override
    public PluginExternalLoginPresentation presentation() {
        return PluginExternalLoginPresentation.TAB;
    }

    @Override
    public boolean enabled() {
        return settings.current().loginEnabled();
    }

    @Override
    public String authorizationUrl(PluginExternalLoginAuthorizeRequest request) {
        if (request == null || request.state() == null || request.state().isBlank()) {
            throw new IllegalArgumentException("授权请求缺少 state");
        }
        ensureType(request.platformType());
        return settings.authorizationUrl(request.state());
    }

    @Override
    public PluginExternalLoginIdentity exchange(PluginExternalLoginExchangeRequest request) {
        if (request == null || request.ticket() == null || request.ticket().isBlank()) {
            throw new IllegalArgumentException("回调缺少票据");
        }
        ensureType(request.platformType());
        SsoSettings current = settings.current();
        SsoProtocolClient.ExternalIdentity identity = settings.exchange(request.ticket(), request.state());
        // 学生档案 upsert 是 best-effort：记录失败不影响登录主流程
        studentInfo.record(identity, current.protocol());
        return new PluginExternalLoginIdentity(
                identity.socialUid(),
                identity.nickname(),
                identity.avatarUrl(),
                identity.gender(),
                identity.location()
        );
    }

    private void ensureType(String platformType) {
        String expected = settings.current().protocol().typeCode();
        if (platformType != null && !platformType.isBlank() && !expected.equalsIgnoreCase(platformType)) {
            throw new IllegalArgumentException("当前协议为 " + expected + "，不支持 " + platformType);
        }
    }
}
