package online.yudream.base.plugin.cas.application.service;

import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.spi.system.user.PluginUserService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingQueryServiceTest {

    @Test
    void reportsBoundLocalAccount() {
        BindingQueryService service = new BindingQueryService(users(() -> Optional.of(
                new PluginUserProfile(357806992028471296L, "godot", "Godot", "g@example.com", "13900000000",
                        "10001", "avatar.png", "NORMAL"))), "cas");

        Map<String, Object> binding = service.lookup("cas", "5061220122");

        assertEquals(true, binding.get("available"));
        assertEquals(true, binding.get("bound"));
        // 长 ID 必须是字符串，避免前端精度丢失
        assertEquals("357806992028471296", binding.get("userId"));
        assertEquals("godot", binding.get("username"));
        assertEquals("Godot", binding.get("nickname"));
        assertEquals("g@example.com", binding.get("email"));
        assertEquals("13900000000", binding.get("phone"));
        assertEquals("NORMAL", binding.get("status"));
    }

    @Test
    void reportsUnboundAccount() {
        BindingQueryService service = new BindingQueryService(users(() -> Optional.empty()), "cas");

        Map<String, Object> binding = service.lookup("cas", "5061220122");

        assertEquals(true, binding.get("available"));
        assertEquals(false, binding.get("bound"));
        assertEquals("该账号尚未绑定本站用户", binding.get("message"));
        assertNull(binding.get("username"));
    }

    @Test
    void queriesWithTrimmedProviderPlatformAndSocialUid() {
        AtomicReference<String> seen = new AtomicReference<>();
        BindingQueryService service = new BindingQueryService(
                users(() -> Optional.empty(), seen), " cas ");

        service.lookup(" CAS ", " 5061220122 ");

        assertEquals("cas|CAS|5061220122", seen.get());
    }

    @Test
    void degradesWhenHostDoesNotSupportLookup() {
        PluginUserService unsupported = (PluginUserService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PluginUserService.class},
                (proxy, method, args) -> {
                    if ("findByExternalIdentity".equals(method.getName())) {
                        throw new UnsupportedOperationException("宿主不支持查询外部账号绑定");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        BindingQueryService service = new BindingQueryService(unsupported, "cas");

        Map<String, Object> binding = service.lookup("cas", "5061220122");

        assertEquals(false, binding.get("available"));
        assertEquals(false, binding.get("bound"));
        assertTrue(String.valueOf(binding.get("message")).contains("宿主版本不支持"), String.valueOf(binding.get("message")));
    }

    @Test
    void degradesWhenHostFailureIsThrown() {
        BindingQueryService service = new BindingQueryService(
                users(() -> {
                    throw new IllegalStateException("外部账号绑定的用户不存在");
                }), "cas");

        Map<String, Object> binding = service.lookup("cas", "5061220122");

        assertEquals(false, binding.get("available"));
        assertEquals(false, binding.get("bound"));
        assertTrue(String.valueOf(binding.get("message")).contains("外部账号绑定的用户不存在"),
                String.valueOf(binding.get("message")));
    }

    @Test
    void degradesWithoutHostUserService() {
        Map<String, Object> binding = new BindingQueryService(null, "cas").lookup("cas", "5061220122");

        assertEquals(false, binding.get("available"));
        assertEquals(false, binding.get("bound"));
        assertTrue(String.valueOf(binding.get("message")).contains("未提供用户服务"), String.valueOf(binding.get("message")));
    }

    @Test
    void rejectsMissingIdentifier() {
        BindingQueryService service = new BindingQueryService(users(() -> Optional.empty()), "cas");

        assertFalse((Boolean) service.lookup("cas", " ").get("available"));
        assertFalse((Boolean) service.lookup("", "5061220122").get("available"));
        assertEquals("缺少账号标识，无法查询绑定", service.lookup("cas", "").get("message"));
    }

    private interface Lookup {
        Optional<PluginUserProfile> find();
    }

    private static PluginUserService users(Lookup lookup) {
        return users(lookup, new AtomicReference<>());
    }

    /** 记录最后一次查询的 (providerCode, platformType, socialUid)，便于断言参数拼装。 */
    private static PluginUserService users(Lookup lookup, AtomicReference<String> seen) {
        return (PluginUserService) Proxy.newProxyInstance(
                BindingQueryServiceTest.class.getClassLoader(),
                new Class<?>[]{PluginUserService.class},
                (proxy, method, args) -> {
                    if ("findByExternalIdentity".equals(method.getName())) {
                        seen.set(args[0] + "|" + args[1] + "|" + args[2]);
                        return lookup.find();
                    }
                    if ("toString".equals(method.getName())) {
                        return "FakePluginUserService";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
