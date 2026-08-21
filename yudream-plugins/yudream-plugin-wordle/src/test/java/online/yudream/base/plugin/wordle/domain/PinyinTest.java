package online.yudream.base.plugin.wordle.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PinyinTest {

    @Test
    void toneMarkPrefersAoE() {
        assertEquals(1, new Pinyin("q", "iao", 3).toneMarkIndex());
        assertEquals(0, new Pinyin("n", "ong", 4).toneMarkIndex());
        assertEquals(0, new Pinyin("b", "an", 1).toneMarkIndex());
    }

    @Test
    void toneMarkFallsBackToSecondLetterForIuAndUi() {
        assertEquals(1, new Pinyin("l", "iu", 2).toneMarkIndex());
        assertEquals(1, new Pinyin("sh", "ui", 3).toneMarkIndex());
    }

    @Test
    void toneMarkFallsBackToFirstVowelOtherwise() {
        assertEquals(0, new Pinyin("y", "i", 1).toneMarkIndex());
        assertEquals(0, new Pinyin("n", "ü", 3).toneMarkIndex());
    }

    @Test
    void neutralToneHasNoMark() {
        assertEquals(-1, new Pinyin("m", "a", 5).toneMarkIndex());
    }

    @Test
    void markedSyllableRendersStandardPinyin() {
        assertEquals("qiǎo", new Pinyin("q", "iao", 3).markedSyllable());
        assertEquals("shuǐ", new Pinyin("sh", "ui", 3).markedSyllable());
        assertEquals("liú", new Pinyin("l", "iu", 2).markedSyllable());
        assertEquals("nòng", new Pinyin("n", "ong", 4).markedSyllable());
        assertEquals("bān", new Pinyin("b", "an", 1).markedSyllable());
        assertEquals("nǚ", new Pinyin("n", "ü", 3).markedSyllable());
        assertEquals("èr", new Pinyin(null, "er", 4).markedSyllable());
        assertEquals("yī", new Pinyin("y", "i", 1).markedSyllable());
    }

    @Test
    void markedSyllableLeavesNeutralToneUnmarked() {
        assertEquals("ma", new Pinyin("m", "a", 5).markedSyllable());
    }
}
