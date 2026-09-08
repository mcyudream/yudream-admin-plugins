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
import online.yudream.base.plugin.mcguess.domain.BingoGame;
import online.yudream.base.plugin.mcguess.domain.BingoGameRepository;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McguessPlayer;
import online.yudream.base.plugin.mcguess.domain.McguessPlayerRepository;
import online.yudream.base.plugin.mcguess.infrastructure.IconSupport;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import org.junit.jupiter.api.Test;

/**
 * 宾果开局：云端未一键更新渲染资产时 iconItems 不足 25，必须回退全量物品，
 * 而不是 subList(0, 25) 把 toIndex = 25 漏到 QQ。
 */
class BingoAppServiceTest {

    @Test
    void startsWhenWikiPublishedButNoRenderAssets() {
        McCatalog catalog = catalog(30, 0);
        InMemoryBingoGames games = new InMemoryBingoGames();
        BingoAppService service = service(games, catalog);

        String reply = service.status(event("10001"));
        assertTrue(reply.contains("宾果"));
        BingoGame game = games.findActive("conn-1", "8888").orElseThrow();
        assertEquals(BingoGame.CELL_COUNT, game.getCells().size());
        assertEquals(BingoGame.CELL_COUNT, game.getCells().stream().distinct().count());
    }

    @Test
    void undersizedCatalogThrowsChineseInsteadOfToIndex() {
        BingoAppService service = service(new InMemoryBingoGames(), catalog(24, 0));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.status(event("10001")));
        assertTrue(error.getMessage().contains("可用物品不足 25"));
        assertTrue(!error.getMessage().contains("toIndex"));
    }

    @Test
    void prefersIconItemsWhenEnough() {
        McCatalog catalog = catalog(40, 25);
        InMemoryBingoGames games = new InMemoryBingoGames();
        BingoAppService service = service(games, catalog);
        service.status(event("10001"));
        BingoGame game = games.findActive("conn-1", "8888").orElseThrow();
        assertTrue(game.getCells().stream().allMatch(id -> {
            int index = Integer.parseInt(id.substring("minecraft:item_".length()));
            return index < 25;
        }));
    }

    private static McCatalog catalog(int total, int icons) {
        List<McItem> items = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            items.add(new McItem("minecraft:item_" + i, "item_" + i, "物品" + i, false, i < icons));
        }
        return new McCatalog(items, Map.of(), "1.21");
    }

    private static BingoAppService service(InMemoryBingoGames games, McCatalog catalog) {
        FrameworkServices framework = (FrameworkServices) Proxy.newProxyInstance(
                BingoAppServiceTest.class.getClassLoader(), new Class<?>[]{FrameworkServices.class},
                (proxy, method, args) -> null);
        McguessSupport support = new McguessSupport(new InMemoryPlayers(), framework);
        IconSupport icons = new IconSupport(Optional::empty, Optional::empty);
        return new BingoAppService(games, catalog, icons, support);
    }

    private static PluginEvent event(String qq) {
        return new PluginEvent("seq", "message_receive", "milky", qq, "8888", "", null, null,
                Map.of(), null, null, "conn-1", "self-1", "msg-1");
    }

    static final class InMemoryBingoGames implements BingoGameRepository {
        final Map<String, BingoGame> data = new LinkedHashMap<>();

        @Override
        public Optional<BingoGame> findById(String id) {
            return Optional.ofNullable(data.get(id));
        }

        @Override
        public Optional<BingoGame> findActive(String connectionId, String channelId) {
            return data.values().stream()
                    .filter(game -> game.isPlaying()
                            && game.getConnectionId().equals(connectionId)
                            && game.getChannelId().equals(channelId))
                    .findFirst();
        }

        @Override
        public Optional<BingoGame> findLatest(String connectionId, String channelId) {
            return data.values().stream()
                    .filter(game -> game.getConnectionId().equals(connectionId) && game.getChannelId().equals(channelId))
                    .max(Comparator.comparingLong(BingoGame::getStartedAt));
        }

        @Override
        public List<BingoGame> search(String status, int page, int size) {
            List<BingoGame> all = data.values().stream()
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
        public void save(BingoGame game) {
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
