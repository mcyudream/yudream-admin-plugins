package online.yudream.base.plugin.cas.application.service;

import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.spi.system.user.PluginUserService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 本站账号绑定查询：把「外部账号（学工号）」对应到本站用户，供管理端学生信息页展示绑定情况。
 *
 * <p>数据源是宿主 SPI 2.29.0 的 {@link PluginUserService#findByExternalIdentity}，
 * 即宿主 external account 表里 (providerCode, platformType, socialUid) 唯一命中的那条绑定。
 * 旧宿主没有该能力（默认实现直接抛 {@link UnsupportedOperationException}）、宿主未注入用户服务、
 * 或绑定记录指向的用户已不存在时，统一降级为 {@code available=false}，
 * 只影响这一列/这一块的信息展示，绝不让管理页整体报错。</p>
 */
public final class BindingQueryService {

    /** 宿主查询返回的异常文案最长保留长度，避免把宿主内部堆栈/细节透到管理端。 */
    private static final int MESSAGE_MAX_LENGTH = 120;

    private final PluginUserService users;
    private final String providerCode;

    /**
     * @param users        宿主用户服务，可为 null（测试或宿主未注册时）
     * @param providerCode 本插件在宿主注册的第三方登录通道标识，即插件 code
     */
    public BindingQueryService(PluginUserService users, String providerCode) {
        this.users = users;
        this.providerCode = providerCode == null ? "" : providerCode.trim();
    }

    /** 查询单个外部账号的绑定结果；返回结构直接进管理端 JSON（长 ID 一律字符串）。 */
    public Map<String, Object> lookup(String platformType, String socialUid) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("available", false);
        view.put("bound", false);
        if (users == null) {
            view.put("message", "宿主未提供用户服务，无法查询绑定");
            return view;
        }
        if (providerCode.isBlank() || platformType == null || platformType.isBlank()
                || socialUid == null || socialUid.isBlank()) {
            view.put("message", "缺少账号标识，无法查询绑定");
            return view;
        }
        try {
            Optional<PluginUserProfile> profile = users.findByExternalIdentity(
                    providerCode, platformType.trim(), socialUid.trim());
            view.put("available", true);
            view.put("bound", profile.isPresent());
            if (profile.isPresent()) {
                fill(view, profile.get());
            } else {
                view.put("message", "该账号尚未绑定本站用户");
            }
            return view;
        } catch (UnsupportedOperationException e) {
            view.put("message", "当前宿主版本不支持查询第三方账号绑定");
            return view;
        } catch (RuntimeException e) {
            view.put("message", "绑定查询失败：" + describe(e));
            return view;
        }
    }

    private static void fill(Map<String, Object> view, PluginUserProfile user) {
        view.put("userId", user.id() == null ? "" : String.valueOf(user.id()));
        view.put("username", text(user.username()));
        view.put("nickname", text(user.nickname()));
        view.put("email", text(user.email()));
        view.put("phone", text(user.phone()));
        view.put("avatar", text(user.avatar()));
        view.put("status", text(user.status()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String trimmed = message.trim().replace('\n', ' ').replace('\r', ' ');
        return trimmed.length() <= MESSAGE_MAX_LENGTH ? trimmed : trimmed.substring(0, MESSAGE_MAX_LENGTH) + "…";
    }
}
