package online.yudream.base.plugin.wordle.infrastructure.repository;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.wordle.domain.Guess;
import online.yudream.base.plugin.wordle.domain.LetterState;
import online.yudream.base.plugin.wordle.domain.WordEntry;
import online.yudream.base.plugin.wordle.domain.WordleGame;
import online.yudream.base.plugin.wordle.domain.WordleMode;
import online.yudream.base.plugin.wordle.domain.WordlePlayer;
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
class WordleDocumentRepositoryTest {

    @Test
    void freshGameWithNullFieldsSurvivesSandboxStore() {
        SandboxStore store = new SandboxStore();
        WordleDocumentGameRepository repo = new WordleDocumentGameRepository(store);
        WordleGame game = new WordleGame("g1", "conn-1", null, "20001", WordleMode.ENGLISH,
                "APPLE", false, null, null, 1000L);

        repo.save(game);

        Optional<WordleGame> reloaded = repo.findActive("conn-1", "20001");
        assertTrue(reloaded.isPresent());
        assertEquals("g1", reloaded.get().getId());
        assertNull(reloaded.get().getWinnerQq());
        assertEquals(WordleGame.STATUS_PLAYING, reloaded.get().getStatus());
        assertTrue(reloaded.get().getGuesses().isEmpty());
    }

    @Test
    void gameWithGuessSurvivesSandboxStore() {
        SandboxStore store = new SandboxStore();
        WordleDocumentGameRepository repo = new WordleDocumentGameRepository(store);
        WordleGame game = new WordleGame("g2", "conn-1", "qq", "20001", WordleMode.ENGLISH,
                "APPLE", false, "10001", null, 1000L);
        game.addGuess(new Guess("apply", List.of(
                LetterState.CORRECT, LetterState.CORRECT, LetterState.CORRECT,
                LetterState.CORRECT, LetterState.CORRECT), null, "10001", 1100L));

        repo.save(game);

        WordleGame reloaded = repo.findActive("conn-1", "20001").orElseThrow();
        assertEquals(1, reloaded.getGuesses().size());
        assertEquals("apply", reloaded.getGuesses().get(0).word());
        assertNull(reloaded.getGuesses().get(0).userId());
    }

    @Test
    void playerWithNullQqSurvivesSandboxStore() {
        SandboxStore store = new SandboxStore();
        WordleDocumentPlayerRepository repo = new WordleDocumentPlayerRepository(store);
        WordlePlayer player = new WordlePlayer("u1");
        player.recordPlayed(WordleMode.ENGLISH, 1000L);
        player.recordWin(WordleMode.ENGLISH, 3, 2000L);

        repo.save(player);

        WordlePlayer reloaded = repo.findByUserId("u1").orElseThrow();
        assertNull(reloaded.getQq());
        assertEquals(1, reloaded.getEnglishPlayed());
        assertEquals(1, reloaded.getEnglishWins());
    }

    @Test
    void wordEntryWithNullHintSurvivesSandboxStore() {
        SandboxStore store = new SandboxStore();
        WordleDocumentWordRepository repo = new WordleDocumentWordRepository(store);
        WordEntry entry = new WordEntry(WordleMode.IDIOM, "画蛇添足", null, true, 1000L, null);

        repo.save(entry);

        WordEntry reloaded = repo.findById(entry.getId()).orElseThrow();
        assertEquals("画蛇添足", reloaded.getWord());
        assertNull(reloaded.getHint());
        assertTrue(reloaded.isEnabled());
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
