package online.yudream.base.plugin.wordle.domain;

import java.util.Locale;

public enum WordleMode {
    ENGLISH("英文单词"),
    IDIOM("四字成语");

    private final String label;

    WordleMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static WordleMode from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return WordleMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
