package online.yudream.base.plugin.wordle.infrastructure.repository;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.wordle.domain.WordEntry;
import online.yudream.base.plugin.wordle.domain.WordEntryRepository;
import online.yudream.base.plugin.wordle.domain.WordleMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class WordleDocumentWordRepository implements WordEntryRepository {

    private static final String COLLECTION = "wordle_words";
    private static final int SCAN_PAGE_SIZE = 200;

    private final PluginDocumentStore store;

    public WordleDocumentWordRepository(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public Optional<WordEntry> findById(String id) {
        return store.findById(COLLECTION, id).map(this::toEntry);
    }

    @Override
    public List<WordEntry> search(String mode, String keyword, int page, int size) {
        List<WordEntry> filtered = filter(mode, keyword);
        int from = Math.min(Math.max(0, (page - 1) * size), filtered.size());
        int to = Math.min(from + size, filtered.size());
        return filtered.subList(from, to);
    }

    @Override
    public long count(String mode, String keyword) {
        return filter(mode, keyword).size();
    }

    @Override
    public List<WordEntry> findEnabled(WordleMode mode) {
        List<WordEntry> result = new ArrayList<>();
        for (WordEntry entry : filter(mode.name(), null)) {
            if (entry.isEnabled()) {
                result.add(entry);
            }
        }
        return result;
    }

    @Override
    public long countAll() {
        return store.count(COLLECTION);
    }

    private List<WordEntry> filter(String mode, String keyword) {
        List<WordEntry> all = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> docs = store.findAll(COLLECTION, page, SCAN_PAGE_SIZE);
            docs.forEach(doc -> all.add(toEntry(doc)));
            if (docs.size() < SCAN_PAGE_SIZE) {
                break;
            }
            page++;
        }
        String keywordLower = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);
        List<WordEntry> filtered = all.stream()
                .filter(entry -> mode == null || mode.isBlank() || entry.getMode().name().equalsIgnoreCase(mode))
                .filter(entry -> keywordLower == null || entry.getWord().toLowerCase(Locale.ROOT).contains(keywordLower))
                .sorted(Comparator.comparingLong(WordEntry::getCreatedAt).reversed())
                .toList();
        return new ArrayList<>(filtered);
    }

    @Override
    public void save(WordEntry entry) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", entry.getId());
        doc.put("mode", entry.getMode().name());
        doc.put("word", entry.getWord());
        doc.put("length", entry.length());
        doc.put("hint", entry.getHint());
        doc.put("enabled", entry.isEnabled());
        doc.put("createdAt", entry.getCreatedAt());
        doc.put("createdBy", entry.getCreatedBy());
        DocumentSupport.stripNulls(doc);
        store.save(COLLECTION, entry.getId(), doc);
    }

    @Override
    public void delete(String id) {
        store.delete(COLLECTION, id);
    }

    private WordEntry toEntry(Map<String, Object> doc) {
        return new WordEntry(
                WordleMode.from(DocumentSupport.stringOrDefault(doc, "mode", "ENGLISH")),
                DocumentSupport.string(doc, "word"),
                DocumentSupport.string(doc, "hint"),
                DocumentSupport.bool(doc, "enabled", true),
                DocumentSupport.longValue(doc, "createdAt", 0),
                DocumentSupport.string(doc, "createdBy"));
    }
}
