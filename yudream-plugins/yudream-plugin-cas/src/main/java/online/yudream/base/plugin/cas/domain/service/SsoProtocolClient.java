package online.yudream.base.plugin.cas.domain.service;

import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;

import java.util.Map;

public interface SsoProtocolClient {

    SsoProtocol protocol();

    String authorizationUrl(SsoSettings settings, String state);

    /**
     * 认证服务自身的授权地址（不带本地包装）。预热页据此重建真正要跳转的目标。
     * 默认与 {@link #authorizationUrl} 相同，只有做了包装的协议才需要覆写。
     */
    default String rawAuthorizationUrl(SsoSettings settings, String state) {
        return authorizationUrl(settings, state);
    }

    ExternalIdentity exchange(SsoSettings settings, String ticket, String state, String clientSecret);

    ConnectivityResult probe(SsoSettings settings, String clientSecret);

    record ExternalIdentity(
            String socialUid,
            String nickname,
            String avatarUrl,
            String gender,
            String location,
            Map<String, String> attributes
    ) {
        public ExternalIdentity {
            if (socialUid == null || socialUid.isBlank()) {
                throw new IllegalArgumentException("第三方登录未返回有效账号标识");
            }
            if (attributes == null) {
                attributes = Map.of();
            }
        }
    }

    record ConnectivityResult(boolean ok, String message) {
        public static ConnectivityResult ok(String message) {
            return new ConnectivityResult(true, message);
        }

        public static ConnectivityResult fail(String message) {
            return new ConnectivityResult(false, message);
        }
    }
}
