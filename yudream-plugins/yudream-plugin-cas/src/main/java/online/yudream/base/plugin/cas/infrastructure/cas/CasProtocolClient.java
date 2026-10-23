package online.yudream.base.plugin.cas.infrastructure.cas;

import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;
import online.yudream.base.plugin.cas.domain.service.SsoProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.http.HttpClientSupport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class CasProtocolClient implements SsoProtocolClient {

    @Override
    public SsoProtocol protocol() {
        return SsoProtocol.CAS;
    }

    @Override
    public String authorizationUrl(SsoSettings settings, String state) {
        // 开启预热时先跳到本站自己的预热页：它会先发一次跨站请求拿到前置网关的会话 cookie
        // （部分网关对首次不带该 cookie 的请求直接回 404），再跳下面的真实地址。
        if (settings.loginWarmup()) {
            return settings.warmupUrl(state);
        }
        return rawAuthorizationUrl(settings, state);
    }

    @Override
    public String rawAuthorizationUrl(SsoSettings settings, String state) {
        String service = settings.casServiceUrl(state);
        return settings.loginUrl() + "?service=" + encode(service);
    }

    @Override
    public ExternalIdentity exchange(SsoSettings settings, String ticket, String state, String clientSecret) {
        if (ticket == null || ticket.isBlank()) {
            throw new IllegalArgumentException("CAS 回调缺少 ticket");
        }
        String service = settings.casServiceUrl(state);
        String url = settings.validateUrl() + "?service=" + encode(service) + "&ticket=" + encode(ticket);
        HttpClientSupport.HttpResponse response = HttpClientSupport.get(url, null);
        if (!response.ok()) {
            throw new IllegalStateException("CAS 票据校验失败（HTTP " + response.status() + "）");
        }
        CasServiceResponseParser.CasIdentity identity = CasServiceResponseParser.parse(response.body());
        return new ExternalIdentity(identity.user(), identity.displayName(), identity.avatarUrl(), "", "", identity.attributes());
    }

    @Override
    public ConnectivityResult probe(SsoSettings settings, String clientSecret) {
        if (settings.callbackUrl().isBlank()) {
            return ConnectivityResult.fail("请先填写本站回调地址");
        }
        try {
            HttpClientSupport.HttpResponse login = HttpClientSupport.get(settings.loginUrl(), null);
            if (login.status() >= 500) {
                return ConnectivityResult.fail("登录页不可达（HTTP " + login.status() + "）");
            }
            HttpClientSupport.HttpResponse validate = HttpClientSupport.get(
                    settings.validateUrl() + "?service=" + encode(settings.callbackUrl()) + "&ticket=ST-probe",
                    null
            );
            if (validate.status() >= 500) {
                return ConnectivityResult.fail("票据校验端点不可达（HTTP " + validate.status() + "）");
            }
            if (validate.body() != null && validate.body().contains("authenticationFailure")) {
                return ConnectivityResult.ok("CAS 登录页与 /cas/p3/serviceValidate 可达；探测票据按预期被拒绝");
            }
            return ConnectivityResult.ok("CAS 登录页可达（HTTP " + login.status() + "），校验端点 HTTP " + validate.status());
        } catch (RuntimeException e) {
            return ConnectivityResult.fail(e.getMessage());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
