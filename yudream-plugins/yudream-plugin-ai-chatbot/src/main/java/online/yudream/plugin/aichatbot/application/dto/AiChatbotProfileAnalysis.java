package online.yudream.plugin.aichatbot.application.dto;

import java.util.List;

/** Parsed AI profile analysis result before it is merged into a stored profile. */
public record AiChatbotProfileAnalysis(String summary, String personality, String interactionStyle,
                                       List<String> tags, List<AiChatbotMemoryFact> facts) {
    public AiChatbotProfileAnalysis {
        summary = summary == null ? "" : summary;
        personality = personality == null ? "" : personality;
        interactionStyle = interactionStyle == null ? "" : interactionStyle;
        tags = tags == null ? List.of() : List.copyOf(tags);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
