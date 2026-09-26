package online.yudream.base.plugin.mcpanel.infrastructure.node;

import java.util.Optional;

/**
 * 协议帧装配器（纯函数式，可单测）：
 * - append 按 UTF-8 字节跨 fragment 累计（协议 §4 上限口径）；
 * - overLimit 判定与 complete 出帧互斥使用，reset 由调用方在关闭/出帧后调用。
 */
final class FrameAssembler {

    private final StringBuilder buffer = new StringBuilder();
    private long bytes;

    void append(CharSequence data) {
        bytes += utf8Length(data);
        buffer.append(data);
    }

    boolean overLimit(long maxBytes) {
        return bytes > maxBytes;
    }

    /** 仅 last=true 时产出完整帧；last=false 返回 empty。 */
    Optional<String> complete(boolean last) {
        if (!last) {
            return Optional.empty();
        }
        String frame = buffer.toString();
        reset();
        return Optional.of(frame);
    }

    void reset() {
        buffer.setLength(0);
        bytes = 0L;
    }

    long bytes() {
        return bytes;
    }

    /** UTF-8 编码字节数（正确处理代理对）。 */
    static long utf8Length(CharSequence sequence) {
        long bytes = 0L;
        for (int i = 0; i < sequence.length(); i++) {
            char c = sequence.charAt(i);
            if (c >= 0xD800 && c <= 0xDBFF && i + 1 < sequence.length()
                    && sequence.charAt(i + 1) >= 0xDC00 && sequence.charAt(i + 1) <= 0xDFFF) {
                bytes += 4L;
                i++;
            } else if (c <= 0x7F) {
                bytes += 1L;
            } else if (c <= 0x7FF) {
                bytes += 2L;
            } else {
                bytes += 3L;
            }
        }
        return bytes;
    }
}
