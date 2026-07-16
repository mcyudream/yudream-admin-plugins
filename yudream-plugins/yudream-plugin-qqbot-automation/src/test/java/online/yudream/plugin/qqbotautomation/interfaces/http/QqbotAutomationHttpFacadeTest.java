package online.yudream.plugin.qqbotautomation.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiModelOption;
import online.yudream.base.plugin.spi.system.ai.PluginAiProviderOption;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QqbotAutomationHttpFacadeTest {

    @Test
    void exposesSystemProvidersWithTheirModelsForPolicySelection() {
        PluginAiProviderOption provider = new PluginAiProviderOption("openai", "OpenAI", List.of(
                new PluginAiModelOption("gpt-5", "GPT-5")
        ));
        PluginAiService ai = (PluginAiService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginAiService.class}, (proxy, method, args) -> "providers".equals(method.getName()) ? List.of(provider) : null);
        FrameworkServices framework = (FrameworkServices) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FrameworkServices.class}, (proxy, method, args) -> "ai".equals(method.getName()) ? ai : null);
        QqbotAutomationHttpFacade facade = new QqbotAutomationHttpFacade(null, null, framework);

        PluginHttpResponse response = facade.aiOptions();

        assertEquals(200, response.status());
        assertEquals(List.of(provider), response.body());
    }
}
