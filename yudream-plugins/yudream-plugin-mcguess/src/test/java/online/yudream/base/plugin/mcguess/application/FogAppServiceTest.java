package online.yudream.base.plugin.mcguess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.mcguess.domain.FogGame;
import online.yudream.base.plugin.mcguess.domain.FogGameRepository;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McguessPlayer;
import online.yudream.base.plugin.mcguess.domain.McguessPlayerRepository;
import online.yudream.base.plugin.mcguess.infrastructure.IconSupport;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import org.junit.jupiter.api.Test;

/**
 * 迷雾开局：云端未一键更新渲染资产时 iconItems 为空，必须回退全量物品，
 * 而不是 Random.nextInt(0) 把 bound must be positive 漏到 QQ。
 */
class FogAppServiceTest {

    @Test
    void startsWhenWikiPublishedButNoRenderAssets() {
        McCatalog catalog = new McCatalog(List.of(
                new McItem("minecraft:diamond", "diamond", "钻石", false, false),
                new McItem("minecraft:stick", "stick", "木棍", false, false)), Map.of(), "1.21");
        InMemoryFogGames games = new InMemoryFogGames();
        FogAppService service = service(games, catalog);

        String reply = service.status(event("10001"));
        assertTrue(reply.contains("迷雾"));
        FogGame game = games.findActive("conn-1", "8888").orElseThrow();
        assertTrue(game.isPlaying());
        assertTrue(List.of("minecraft:diamond", "minecraft:stick").contains(game.getTargetId()));
    }

    @Test
    void emptyCatalogThrowsChineseInsteadOfBoundMustBePositive() {
        FogAppService service = service(new InMemoryFogGames(), new McCatalog(List.of(), Map.of(), "1.21"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.status(event("10001")));
        assertTrue(error.getMessage().contains("可用物品不足"));
        assertTrue(!error.getMessage().contains("bound must be positive"));
    }

    @Test
    void prefersIconItemsWhenPresent() {
        McCatalog catalog = new McCatalog(List.of(
                new McItem("minecraft:dirt", "dirt", "泥土", false, false),
                new McItem("minecraft:diamond_sword", "diamond_sword", "钻石剑", false, true)), Map.of(), "1.21");
        InMemoryFogGames games = new InMemoryFogGames();
        FogAppService service = service(games, catalog);
        service.status(event("10001"));
        assertEquals("minecraft:diamond_sword", games.findActive("conn-1", "8888").orElseThrow().getTargetId());
    }

    private static FogAppService service(InMemoryFogGames games, McCatalog catalog) {
        FrameworkServices framework = (FrameworkServices) Proxy.newProxyInstance(
                FogAppServiceTest.class.getClassLoader(), new Class<?>[]{FrameworkServices.class},
                (proxy, method, args) -> null);
        McguessSupport support = new McguessSupport(new InMemoryPlayers(), framework);
        IconSupport icons = new IconSupport(Optional::empty, Optional::empty);
        return new FogAppService(games, catalog, icons, support);
    }

    private static PluginEvent event(String qq) {
        return new PluginEvent("seq", "message_receive", "milky", qq, "8888", "", null, null,
                Map.of(), null, null, "conn-1", "self-1", "msg-1");
    }

    static final class InMemoryFogGames implements FogGameRepository {
        final Map<String, FogGame> data = new LinkedHashMap<>();

        @Override
        public Optional<FogGame> findById(String id) {
            return Optional.ofNullable(data.get(id));
        }

        @Override
        public Optional<FogGame> findActive(String connectionId, String channelId) {
            return data.values().stream()
                    .filter(game -> game.isPlaying()
                            && game.getConnectionId().equals(connectionId)
                            && game.getChannelId().equals(channelId))
                    .findFirst();
        }

        @Override
        public Optional<FogGame> findLatest(String connectionId, String channelId) {
            return data.values().stream()
                    .filter(game -> game.getConnectionId().equals(connectionId) && game.getChannelId().equals(channelId))
                    .max(Comparator.comparingLong(FogGame::getStartedAt));
        }

        @Override
        public List<FogGame> search(String status, int page, int size) {
            List<FogGame> all = data.values().stream()
                    .filter(game -> status == null || game.getStatus().equals(status))
                    .toList();
            int from = Math.min((page - 1) * size, all.size());
            return all.subList(from, Math.min(from + size, all.size()));
        }

        @Override
        public long count(String status) {
            return data.values().stream().filter(game -> game.getStatus().equals(status)).count();
        }

        @Override
        public long countAll() {
            return data.size();
        }

        @Override
        public void save(FogGame game) {
            data.put(game.getId(), game);
        }

        @Override
        public void delete(String id) {
            data.remove(id);
        }
    }

    static final class InMemoryPlayers implements McguessPlayerRepository {
        final Map<String, McguessPlayer> data = new LinkedHashMap<>();

        @Override
        public Optional<McguessPlayer> findByUserId(String userId) {
            return Optional.ofNullable(data.get(userId));
        }

        @Override
        public List<McguessPlayer> search(int page, int size) {
            List<McguessPlayer> all = new ArrayList<>(data.values());
            int from = Math.min((page - 1) * size, all.size());
            return all.subList(from, Math.min(from + size, all.size()));
        }

        @Override
        public long count() {
            return data.size();
        }

        @Override
        public void save(McguessPlayer player) {
            data.put(player.getUserId(), player);
        }
    }
}
