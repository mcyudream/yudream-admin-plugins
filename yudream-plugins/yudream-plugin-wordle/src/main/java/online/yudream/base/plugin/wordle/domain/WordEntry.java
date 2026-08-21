package online.yudream.base.plugin.wordle.domain;

/**
 * 管理端维护的自定义词条。id 为 mode:word，天然去重。
 */
public class WordEntry {

    private final String id;
    private final WordleMode mode;
    private final String word;
    private String hint;
    private boolean enabled;
    private final long createdAt;
    private final String createdBy;

    public WordEntry(WordleMode mode, String word, String hint, boolean enabled, long createdAt, String createdBy) {
        this.mode = mode;
        this.word = word;
        this.hint = hint;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.id = buildId(mode, word);
    }

    public static String buildId(WordleMode mode, String word) {
        return mode.name() + ":" + word;
    }

    public int length() {
        return word.codePointCount(0, word.length());
    }

    public String getId() {
        return id;
    }

    public WordleMode getMode() {
        return mode;
    }

    public String getWord() {
        return word;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
