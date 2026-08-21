package online.yudream.base.plugin.wordle.domain;

import java.util.List;

/**
 * 汉字拼音查询端口。实现方可提供整词级覆盖表修正多音字读音，默认逐字查询。
 */
public interface PinyinLookup {

    /**
     * 单字拼音；无数据时返回 null。
     */
    Pinyin of(int codePoint);

    /**
     * 整词拼音，返回长度与词的 code point 数一致，无数据的字位置为 null。
     */
    default List<Pinyin> ofWord(String word) {
        return word.codePoints().mapToObj(this::of).toList();
    }
}
