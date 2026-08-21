package online.yudream.base.plugin.wordle.infrastructure;

import online.yudream.base.plugin.wordle.domain.Pinyin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinyinDictionaryTest {

    private final PinyinDictionary dictionary = new PinyinDictionary();

    @Test
    void charTableCoversCommonHanzi() {
        Pinyin zhong = dictionary.of('中');
        assertNotNull(zhong);
        assertEquals("zh", zhong.initial());
        assertEquals("ong", zhong.finalPart());
        assertEquals(1, zhong.tone());

        Pinyin ai = dictionary.of('爱');
        assertNotNull(ai);
        assertNull(ai.initial());
        assertEquals("ai", ai.finalPart());
    }

    @Test
    void wordOverrideFixesPolyphoneInIdiom() {
        // 单字表取最常用读音：长 zhǎng；成语「天长地久」中应读 cháng
        Pinyin single = dictionary.of('长');
        assertNotNull(single);
        assertEquals("zh", single.initial());
        assertEquals(3, single.tone());

        List<Pinyin> word = dictionary.ofWord("天长地久");
        assertEquals(4, word.size());
        assertEquals("ch", word.get(1).initial());
        assertEquals("ang", word.get(1).finalPart());
        assertEquals(2, word.get(1).tone());
    }

    @Test
    void citationToneRestoredForYi() {
        // 整词数据中「一心一意」的一按实际变调为 yí/yì，覆盖表统一回本调 1 声
        List<Pinyin> word = dictionary.ofWord("一心一意");
        assertEquals(4, word.size());
        assertEquals(1, word.get(0).tone());
        assertEquals(1, word.get(2).tone());
    }

    @Test
    void unknownWordFallsBackToPerChar() {
        // 「春暖花开」不在内置成语词库，整词覆盖表不命中，逐字回退
        List<Pinyin> word = dictionary.ofWord("春暖花开");
        assertEquals(4, word.size());
        for (Pinyin pinyin : word) {
            assertNotNull(pinyin);
            assertTrue(pinyin.tone() >= 1 && pinyin.tone() <= 5);
        }
        assertEquals("ch", word.get(0).initial());
        assertEquals("un", word.get(0).finalPart());
    }
}
