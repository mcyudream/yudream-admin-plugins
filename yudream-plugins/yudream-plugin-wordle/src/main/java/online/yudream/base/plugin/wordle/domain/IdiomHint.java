package online.yudream.base.plugin.wordle.domain;

/**
 * 成语模式下某个猜测字的完整提示：汉字本身 + 声母/韵母/声调各自的命中状态。
 * pinyin 为 null 表示该字无拼音数据，无法给出音节提示。
 */
public record IdiomHint(String ch, LetterState charState, Pinyin pinyin,
                        LetterState initialState, LetterState finalState, LetterState toneState) {
}
