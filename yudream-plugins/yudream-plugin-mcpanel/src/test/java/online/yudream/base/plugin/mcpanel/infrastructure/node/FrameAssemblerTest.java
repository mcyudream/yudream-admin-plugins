package online.yudream.base.plugin.mcpanel.infrastructure.node;


import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帧装配器：跨 fragment 字节累计、UTF-8 多字节切分、上限与复位。
 */
class FrameAssemblerTest {

    @Test
    void fragmentsJoinAcrossMultiByteCharBoundaries() {
        FrameAssembler assembler = new FrameAssembler();
        String json = "{\"p\":\"节点\"}";
        // "节" = E8 8A 82 三字节，UTF-8 charSequence 层为 1 个 char（BMP 内）。
        // 以 char 边界切分：任何切法都应拼回同一帧。
        for (int split = 1; split < json.length(); split++) {
            FrameAssembler per = new FrameAssembler();
            per.append(json.substring(0, split));
            Optional<String> notLast = per.complete(false);
            assertTrue(notLast.isEmpty(), "last=false 不得出帧 split=" + split);
            per.append(json.substring(split));
            Optional<String> frame = per.complete(true);
            assertTrue(frame.isPresent());
            assertEquals(json, frame.get());
        }
    }

    @Test
    void byteCountingUsesUtf8NotChars() {
        FrameAssembler assembler = new FrameAssembler();
        String cjk = "节".repeat(100); // 100 chars × 3 bytes = 300 bytes
        assembler.append(cjk);
        assertEquals(300L, assembler.bytes());
        assertFalse(assembler.overLimit(300), "300 字节对 300 上限不算超限");
        assertTrue(assembler.overLimit(299), "300 字节对 299 上限应超限");
    }

    @Test
    void overLimitAndReset() {
        FrameAssembler assembler = new FrameAssembler();
        assembler.append("x".repeat(300));
        assertTrue(assembler.overLimit(256));
        assembler.reset();
        assertEquals(0L, assembler.bytes());
        assertFalse(assembler.overLimit(256));
        assembler.append("hello");
        assertEquals(Optional.of("hello"), assembler.complete(true));
        assertEquals(0L, assembler.bytes());
    }

    @Test
    void surrogatePairCountsFourBytes() {
        assertEquals(4L, FrameAssembler.utf8Length("😀"));
        assertEquals(5L, FrameAssembler.utf8Length("a😀"));
    }
}
