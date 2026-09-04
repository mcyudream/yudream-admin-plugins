package online.yudream.base.plugin.questionbank.application;

import online.yudream.base.plugin.questionbank.domain.QuestionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OptionStripperTest {

    @Test
    void stripsTrailingOptionLines() {
        String content = "附魔到多少会出现“过于昂贵”？\nA. 40\nB. 39\nC. 63\nD. 31";
        assertEquals("附魔到多少会出现“过于昂贵”？",
                OptionStripper.strip(content, QuestionType.SINGLE, 4));
    }

    @Test
    void stripsFullWidthAndParenOptionLines() {
        String content = "题干内容\nＡ、苹果\nＢ．香蕉\nC) 橘子\nD: 西瓜\n";
        assertEquals("题干内容", OptionStripper.strip(content, QuestionType.MULTIPLE, 4));
    }

    @Test
    void stripsInlineOptionTail() {
        String content = "附魔到多少会出现“过于昂贵”？ A. 40 B. 39 C. 63 D. 31";
        assertEquals("附魔到多少会出现“过于昂贵”？",
                OptionStripper.strip(content, QuestionType.SINGLE, 4));
    }

    @Test
    void keepsSingleTrailingOptionLine() {
        String content = "下面哪项正确？\nA. 以上都对";
        assertEquals(content, OptionStripper.strip(content, QuestionType.SINGLE, 4));
    }

    @Test
    void keepsOriginalWhenStripWouldEmpty() {
        String content = "A. 40 B. 39";
        assertEquals(content, OptionStripper.strip(content, QuestionType.SINGLE, 2));
    }

    @Test
    void ignoresNonChoiceTypes() {
        String content = "请简述 A. 与 B. 的区别 A. 甲 B. 乙";
        assertEquals(content, OptionStripper.strip(content, QuestionType.SHORT, 0));
        assertEquals(content, OptionStripper.strip(content, QuestionType.FILL, 4));
    }

    @Test
    void ignoresWhenTooFewOptions() {
        String content = "题干\nA. 40\nB. 39";
        assertEquals(content, OptionStripper.strip(content, QuestionType.SINGLE, 1));
    }

    @Test
    void keepsMiddleOptionLikeLines() {
        String content = "A. 甲 B. 乙 哪个对？\n选项如上";
        assertEquals(content, OptionStripper.strip(content, QuestionType.SINGLE, 2));
    }
}
