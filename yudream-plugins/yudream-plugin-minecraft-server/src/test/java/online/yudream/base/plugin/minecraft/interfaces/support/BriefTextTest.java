package online.yudream.base.plugin.minecraft.interfaces.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BriefTextTest {

    @Test
    void blankInputProducesEmptyBrief() {
        assertEquals("", BriefText.ofMarkdown(null));
        assertEquals("", BriefText.ofMarkdown("  \n "));
        assertEquals("", BriefText.ofMarkdown("内容", 0));
    }

    @Test
    void stripsMarkdownAndCollapsesWhitespace() {
        String markdown = """
                # 服务器介绍

                这里是 **生存** 服务器，支持 [官网](https://example.com) 与 ![图标](https://example.com/i.png)。

                - 规则一
                - [ ] 待办

                > 引用一句话
                ```mcfunction
                say hello
                ```
                """;

        String brief = BriefText.ofMarkdown(markdown);

        assertEquals("服务器介绍 这里是 生存 服务器，支持 官网 与 图标。 规则一 待办 引用一句话 say hello", brief);
    }

    @Test
    void keepsWordInnerUnderscores() {
        assertEquals("玩家 mc_steve 的家", BriefText.ofMarkdown("玩家 mc_steve 的家"));
    }

    @Test
    void shortTextIsReturnedAsIs() {
        assertEquals("纯净生存，欢迎加入", BriefText.ofMarkdown("纯净生存，欢迎加入", 120));
    }

    @Test
    void longTextIsTruncatedAtPunctuationWithEllipsis() {
        String text = "第一段介绍，第二段介绍，第三段介绍，第四段介绍，第五段介绍，第六段介绍，第七段介绍";

        String brief = BriefText.ofMarkdown(text, 20);

        assertTrue(brief.endsWith("…"));
        assertTrue(brief.length() <= 21);
        assertEquals("第一段介绍，第二段介绍，第三段介绍", brief.substring(0, brief.length() - 1));
    }

    @Test
    void longTextWithoutPunctuationIsHardTruncated() {
        String text = "一".repeat(200);

        String brief = BriefText.ofMarkdown(text, 120);

        assertEquals(121, brief.length());
        assertTrue(brief.endsWith("…"));
    }
}
