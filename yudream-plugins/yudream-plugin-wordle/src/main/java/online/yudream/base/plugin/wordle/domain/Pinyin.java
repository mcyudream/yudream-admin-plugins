package online.yudream.base.plugin.wordle.domain;

/**
 * 一个汉字的拼音拆解：声母（null 表示零声母）、韵母（不含声调）、声调（1-4，5 表示轻声）。
 */
public record Pinyin(String initial, String finalPart, int tone) {

    public boolean hasInitial() {
        return initial != null && !initial.isEmpty();
    }

    /**
     * 声调符号应标注的韵母字母下标；轻声或无可标元音时为 -1。
     * 规则与汉语拼音方案一致：a/o/e 优先；iu、ui 标在后一个字母；其余标在第一个 i/u/ü。
     */
    public int toneMarkIndex() {
        if (tone < 1 || tone > 4) {
            return -1;
        }
        for (char vowel : new char[]{'a', 'o', 'e'}) {
            int idx = finalPart.indexOf(vowel);
            if (idx >= 0) {
                return idx;
            }
        }
        int iu = finalPart.indexOf("iu");
        if (iu >= 0) {
            return iu + 1;
        }
        int ui = finalPart.indexOf("ui");
        if (ui >= 0) {
            return ui + 1;
        }
        for (int i = 0; i < finalPart.length(); i++) {
            char c = finalPart.charAt(i);
            if (c == 'i' || c == 'u' || c == 'ü') {
                return i;
            }
        }
        return -1;
    }

    /** 带声调符号的完整音节（如 qiǎo、shuǐ、nǚ、èr）；轻声或无法标调时返回无调音节。 */
    public String markedSyllable() {
        String head = hasInitial() ? initial : "";
        int idx = toneMarkIndex();
        if (idx < 0) {
            return head + finalPart;
        }
        String marks = switch (finalPart.charAt(idx)) {
            case 'a' -> "āáǎà";
            case 'o' -> "ōóǒò";
            case 'e' -> "ēéěè";
            case 'i' -> "īíǐì";
            case 'u' -> "ūúǔù";
            case 'ü' -> "ǖǘǚǜ";
            default -> null;
        };
        if (marks == null) {
            return head + finalPart;
        }
        return head + finalPart.substring(0, idx) + marks.charAt(tone - 1) + finalPart.substring(idx + 1);
    }
}
