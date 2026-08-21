package online.yudream.base.plugin.wordle.application.dto;

public record WordEntryView(String id, String mode, String modeLabel, String word, int length,
                            String hint, boolean enabled, long createdAt, String createdBy) {
}
