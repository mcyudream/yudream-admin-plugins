package online.yudream.base.plugin.pony.infrastructure.repository;

import online.yudream.base.plugin.pony.domain.PonyGame;
import online.yudream.base.plugin.pony.domain.PonyPlayer;
import online.yudream.base.plugin.pony.domain.PonyPuzzle;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宿主沙盒 overlay 存储会用 Map.copyOf 复制文档，null 值直接抛 NPE；
 * 这里用同样严格的存储回归验证仓储写入前已移除 null 值，且读回不受影响。
 */
class PonyDocumentRepositoryTest {

    private static PonyPuzzle puzzle() {
        int size = 6;
        int[] regions = new int[size * size];
        int[] solution = {0, 2, 4, 1, 3, 5};
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                regions[row * size + col] = row;
            }
        }
        return new PonyPuzzle(size, regions, solution);
    }

    @Test
    void freshGameWithNullFieldsSurvivesSandboxStore() {
        SandboxStore store = new SandboxStore();
        PonyDocumentGameRepository repo = new PonyDocumentGameRepository(store);
        PonyGame game = new PonyGame("g1", "conn-1", null, "20001", puzzle(), null, null, 1000L);

        repo.save(game);

        Optional<PonyGame> reloaded = repo.findActive("conn-1", "20001");
        assertTrue(reloaded.isPresent());
        assertEquals("g1", reloaded.get().getId());
        assertNull(reloaded.get().getWinnerQq());
        assertEquals(PonyGame.STATUS_PLAYING, reloaded.get().getStatus());
        assertTrue(reloaded.get().getHorses().isEmpty());
    }

    @Test
    void gameWithPlacedHorseSurvivesSandboxStore() {
        SandboxStore store = new SandboxStore();
        PonyDocumentGameRepository repo = new PonyDocumentGameRepository(store);
        PonyGame game = new PonyGame("g2", "conn-1", "qq", "20001", puzzle(), "10001", null, 1000L);
        game.placeHorse(0, 0, null, null, 1100L);

        repo.save(game);

        PonyGame reloaded = repo.findActive("conn-1", "20001").orElseThrow();
        assertEquals(1, reloaded.getHorses().size());
        assertEquals(0, reloaded.getHorses().get(0).cell());
        assertNull(reloaded.getHorses().get(0).qq());
        assertTrue(reloaded.getMarks().contains(6));
    }

    @Test
    void playerWithNullQqSurvivesSandboxStore() {
        SandboxStore store = new SandboxStore();
        PonyDocumentPlayerRepository repo = new PonyDocumentPlayerRepository(store);
        PonyPlayer player = new PonyPlayer("u1");
        player.recordPlayed(1000L);
        player.recordWin(3, 2000L);

        repo.save(player);

        PonyPlayer reloaded = repo.findByUserId("u1").orElseThrow();
        assertNull(reloaded.getQq());
        assertEquals(1, reloaded.getPlayed());
        assertEquals(1, reloaded.getWins());
        assertEquals(3, reloaded.getHorsesPlaced());
    }

    /** 与宿主 SandboxAwarePluginDocumentStore 行为一致：save 时 Map.copyOf，遇 null 抛 NPE。 */
    private static final class SandboxStore implements PluginDocumentStore {

        private final Map<String, Map<String, Map<String, Object>>> collections = new HashMap<>();

        @Override
        public Map<String, Object> save(String collection, String id, Map<String, Object> document) {
            collections.computeIfAbsent(collection, ignored -> new HashMap<>()).put(id, Map.copyOf(document));
            return document;
        }

        @Override
        public Optional<Map<String, Object>> findById(String collection, String id) {
            return Optional.ofNullable(collections.getOrDefault(collection, Map.of()).get(id));
        }

        @Override
        public List<Map<String, Object>> findAll(String collection, int page, int size) {
            List<Map<String, Object>> all = new ArrayList<>(collections.getOrDefault(collection, Map.of()).values());
            int from = Math.min(Math.max(0, page - 1) * size, all.size());
            return all.subList(from, Math.min(from + size, all.size()));
        }

        @Override
        public List<Map<String, Object>> findByField(String collection, String field, Object value, int page, int size) {
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> doc : collections.getOrDefault(collection, Map.of()).values()) {
                if (Objects.equals(doc.get(field), value)) {
                    matched.add(doc);
                }
            }
            int from = Math.min(Math.max(0, page - 1) * size, matched.size());
            return matched.subList(from, Math.min(from + size, matched.size()));
        }

        @Override
        public long count(String collection) {
            return collections.getOrDefault(collection, Map.of()).size();
        }

        @Override
        public void delete(String collection, String id) {
            Map<String, Map<String, Object>> docs = collections.get(collection);
            if (docs != null) {
                docs.remove(id);
            }
        }
    }
}
