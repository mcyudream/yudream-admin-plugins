package online.yudream.base.plugin.feedback.interfaces.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.feedback.application.service.FeedbackAppService;
import online.yudream.base.plugin.feedback.infrastructure.JsonSupport;
import online.yudream.base.plugin.feedback.infrastructure.repository.FeedbackDocumentRepository;
import online.yudream.base.plugin.feedback.support.FakeUserService;
import online.yudream.base.plugin.feedback.support.InMemoryDocumentStore;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import org.junit.jupiter.api.Test;

/**
 * 成员选择器接口的范围语义。
 *
 * <p>关键契约是：{@code scope=manage} 必须带 {@code available} 与 {@code notice}。账号目录不可用时
 * 前端必须拒绝指派；可用时提示候选未按反馈管理权限预筛，由管理员确认所选成员可访问管理页。
 */
class FeedbackHttpFacadeTest {

    private FeedbackHttpFacade facade() {
        FeedbackAppService service = new FeedbackAppService(
                new FeedbackDocumentRepository(new InMemoryDocumentStore()),
                new FakeUserService().add(7, "admin7", "陈七"));
        return new FeedbackHttpFacade(service, new JsonSupport(new ObjectMapper()));
    }

    private static PluginHttpRequest request(Map<String, List<String>> query) {
        return new PluginHttpRequest("GET", "/admin/users", Map.of(), query, "",
                new PluginPrincipal(1L, List.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 默认范围列全量成员且不引入可用标记() {
        PluginHttpResponse response = facade().adminUsers(request(Map.of("keyword", List.of("陈"))));

        Map<String, Object> body = (Map<String, Object>) response.body();
        assertEquals(1, ((List<?>) body.get("records")).size(), "全量范围应能搜到成员");
        assertFalse(body.containsKey("available"), "全量范围不涉及权限确认，不应引入 available");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 管理范围返回账号目录候选与权限提示() {
        PluginHttpResponse response = facade().adminUsers(request(Map.of("scope", List.of("manage"))));

        Map<String, Object> body = (Map<String, Object>) response.body();
        assertEquals(Boolean.TRUE, body.get("available"));
        assertEquals(1, ((List<?>) body.get("records")).size());
        assertNotNull(body.get("notice"), "必须提示候选没有按管理权限预筛");
    }
}
