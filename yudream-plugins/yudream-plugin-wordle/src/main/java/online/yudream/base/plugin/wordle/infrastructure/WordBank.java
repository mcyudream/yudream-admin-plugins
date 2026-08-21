package online.yudream.base.plugin.wordle.infrastructure;

import online.yudream.base.plugin.wordle.domain.WordEntry;
import online.yudream.base.plugin.wordle.domain.WordEntryRepository;
import online.yudream.base.plugin.wordle.domain.WordleMode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * 词库：classpath 内置词表 + 管理端维护的自定义词条（启用状态）的并集。
 * 内置词表按 模式 + 词长 索引；自定义词条随时从文档库读取，保证管理端改动立即生效。
 */
public class WordBank {

    private static final Map<WordleMode, Map<Integer, String>> BUILTIN_RESOURCES = new EnumMap<>(WordleMode.class);

    static {
        Map<Integer, String> english = new HashMap<>();
        english.put(4, "words/english-4.txt");
        english.put(5, "words/english-5.txt");
        english.put(6, "words/english-6.txt");
        BUILTIN_RESOURCES.put(WordleMode.ENGLISH, english);
        BUILTIN_RESOURCES.put(WordleMode.IDIOM, Map.of(4, "words/idiom-4.txt"));
    }

    private final WordEntryRepository entries;
    private final Map<WordleMode, Map<Integer, List<String>>> builtin = new EnumMap<>(WordleMode.class);
    private final Random random = new Random();

    public WordBank(WordEntryRepository entries) {
        this.entries = entries;
        for (WordleMode mode : WordleMode.values()) {
            Map<Integer, List<String>> byLength = new HashMap<>();
            BUILTIN_RESOURCES.getOrDefault(mode, Map.of())
                    .forEach((length, resource) -> byLength.put(length, load(resource)));
            builtin.put(mode, byLength);
        }
    }

    public List<Integer> supportedLengths(WordleMode mode) {
        return builtin.getOrDefault(mode, Map.of()).keySet().stream().sorted().toList();
    }

    public boolean supports(WordleMode mode, int length) {
        return builtin.getOrDefault(mode, Map.of()).containsKey(length);
    }

    /**
     * 答案池：内置词 + 启用的自定义词，按词长过滤，去重。
     */
    public List<String> answerPool(WordleMode mode, int length) {
        List<String> pool = new ArrayList<>(builtin.getOrDefault(mode, Map.of()).getOrDefault(length, List.of()));
        for (WordEntry entry : entries.findEnabled(mode)) {
            if (entry.length() == length && !pool.contains(entry.getWord())) {
                pool.add(entry.getWord());
            }
        }
        return pool;
    }

    public Optional<String> randomAnswer(WordleMode mode, int length) {
        List<String> pool = answerPool(mode, length);
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(pool.get(random.nextInt(pool.size())));
    }

    /**
     * 随机猜：从答案池中挑一个尚未猜过的词。
     */
    public Optional<String> randomGuess(WordleMode mode, int length, List<String> excluded) {
        List<String> candidates = answerPool(mode, length).stream()
                .filter(word -> !excluded.contains(word))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(random.nextInt(candidates.size())));
    }

    public Optional<String> hintOf(WordleMode mode, String word) {
        return entries.findById(WordEntry.buildId(mode, word))
                .filter(WordEntry::isEnabled)
                .map(WordEntry::getHint)
                .filter(hint -> hint != null && !hint.isBlank());
    }

    private List<String> load(String resource) {
        ClassLoader classLoader = WordBank.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                return List.of();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                List<String> words = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    String word = line.trim();
                    if (!word.isEmpty() && !word.startsWith("#")) {
                        words.add(word);
                    }
                }
                return List.copyOf(words);
            }
        } catch (Exception e) {
            return List.of();
        }
    }
}
