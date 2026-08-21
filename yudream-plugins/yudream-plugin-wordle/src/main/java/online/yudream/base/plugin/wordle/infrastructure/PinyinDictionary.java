package online.yudream.base.plugin.wordle.infrastructure;

import online.yudream.base.plugin.wordle.domain.Pinyin;
import online.yudream.base.plugin.wordle.domain.PinyinLookup;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置拼音字典：classpath 单字表（覆盖 CJK 基本区与扩展 A 区，多音字取最常用读音）
 * + 内置成语整词覆盖表（修正成语语境下的多音字与变调）。
 * 整词覆盖仅在该词长度与拼音组数一致时生效，否则回退逐字查询。
 */
public final class PinyinDictionary implements PinyinLookup {

    private static final String CHAR_RESOURCE = "words/pinyin.tsv";
    private static final String WORD_RESOURCE = "words/idiom-pinyin.tsv";

    private final Map<Integer, Pinyin> byChar;
    private final Map<String, List<Pinyin>> byWord;

    public PinyinDictionary() {
        this.byChar = loadChars(CHAR_RESOURCE);
        this.byWord = loadWords(WORD_RESOURCE);
    }

    @Override
    public Pinyin of(int codePoint) {
        return byChar.get(codePoint);
    }

    @Override
    public List<Pinyin> ofWord(String word) {
        List<Pinyin> override = byWord.get(word);
        if (override != null && override.size() == word.codePointCount(0, word.length())) {
            return override;
        }
        return PinyinLookup.super.ofWord(word);
    }

    private Map<Integer, Pinyin> loadChars(String resource) {
        Map<Integer, Pinyin> table = new HashMap<>();
        for (String line : readLines(resource)) {
            String[] fields = line.split("\\s+");
            if (fields.length != 4) {
                continue;
            }
            try {
                int codePoint = Integer.parseInt(fields[0], 16);
                table.put(codePoint, new Pinyin(parseInitial(fields[1]), fields[2], Integer.parseInt(fields[3])));
            } catch (NumberFormatException ignored) {
                // 跳过无法解析的行
            }
        }
        return Map.copyOf(table);
    }

    private Map<String, List<Pinyin>> loadWords(String resource) {
        Map<String, List<Pinyin>> table = new HashMap<>();
        for (String line : readLines(resource)) {
            String[] fields = line.split("\\s+");
            if (fields.length < 2) {
                continue;
            }
            List<Pinyin> syllables = new ArrayList<>(fields.length - 1);
            for (int i = 1; i < fields.length; i++) {
                String[] parts = fields[i].split(",");
                if (parts.length != 3) {
                    syllables.clear();
                    break;
                }
                try {
                    syllables.add(new Pinyin(parseInitial(parts[0]), parts[1], Integer.parseInt(parts[2])));
                } catch (NumberFormatException e) {
                    syllables.clear();
                    break;
                }
            }
            if (!syllables.isEmpty()) {
                table.put(fields[0], List.copyOf(syllables));
            }
        }
        return Map.copyOf(table);
    }

    private String parseInitial(String field) {
        return "-".equals(field) ? null : field;
    }

    private List<String> readLines(String resource) {
        ClassLoader classLoader = PinyinDictionary.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                return List.of();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    String value = line.trim();
                    if (!value.isEmpty() && !value.startsWith("#")) {
                        lines.add(value);
                    }
                }
                return lines;
            }
        } catch (Exception e) {
            return List.of();
        }
    }
}
