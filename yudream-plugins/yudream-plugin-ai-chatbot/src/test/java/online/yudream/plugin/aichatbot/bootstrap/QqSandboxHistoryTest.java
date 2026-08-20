package online.yudream.plugin.aichatbot.bootstrap;

import online.yudream.base.plugin.spi.system.ai.PluginAiChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QqSandboxHistoryTest {

    @Test
    void keepsSandboxTurnsSessionLocalAndUsesProductionHistoryAsReadOnlySeed() {
        QqSandboxHistory history = new QqSandboxHistory();
        AtomicInteger productionReads = new AtomicInteger();

        history.append("session-1", "group", "user", "sandbox question");
        List<PluginAiChatMessage> first = history.read("session-1", "group", 10, () -> {
            productionReads.incrementAndGet();
            return List.of(new PluginAiChatMessage("assistant", "production context"));
        });
        history.append("session-1", "group", "assistant", "sandbox answer");
        List<PluginAiChatMessage> second = history.read("session-1", "group", 10, () -> {
            productionReads.incrementAndGet();
            return List.of();
        });

        assertEquals(List.of(
                new PluginAiChatMessage("assistant", "production context"),
                new PluginAiChatMessage("user", "sandbox question")
        ), first);
        assertEquals(List.of(
                new PluginAiChatMessage("assistant", "production context"),
                new PluginAiChatMessage("user", "sandbox question"),
                new PluginAiChatMessage("assistant", "sandbox answer")
        ), second);
        assertEquals(1, productionReads.get());
        assertEquals(List.of(), history.read("session-2", "group", 10, List::of));
    }

    @Test
    void capsEachSessionHistoryWithoutPersistentWrites() {
        QqSandboxHistory history = new QqSandboxHistory();
        for (int index = 0; index < 40; index++) {
            history.append("session-1", "group", "user", "message-" + index);
        }

        List<PluginAiChatMessage> messages = history.read("session-1", "group", 50, List::of);

        assertEquals(32, messages.size());
        assertEquals("message-8", messages.getFirst().content());
        assertEquals("message-39", messages.getLast().content());
    }
}
