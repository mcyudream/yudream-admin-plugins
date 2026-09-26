package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * 文件写入的编码转换（面板侧）。
 * 节点协议只接受 base64 原始字节或 utf8 文本（不落转码责任到节点）；
 * 前端编辑器固定提交 base64(UTF-8 文本) + charset 目标编码，这里完成
 * UTF-8 → 目标编码的字节转码后再以 encoding=base64 下发。
 * 编码白名单与前端 EDITOR_ENCODINGS 保持一致。
 */
public final class FileCharsetCodec {

    private static final Set<String> ALLOWED = Set.of(
            "utf-8", "gbk", "gb18030", "big5", "shift_jis", "euc-jp",
            "windows-1252", "iso-8859-1", "utf-16le", "utf-16be");

    private FileCharsetCodec() {
    }

    /**
     * 若 payload 携带 charset 且非 utf-8：把 content（base64 UTF-8 文本）转码为
     * base64（目标编码字节），并移除 charset、固定 encoding=base64。
     * 非法 base64 / 非白名单编码 / 超限一律拒绝，不降级为原样透传。
     */
    public static void applyCharset(Map<String, Object> payload) {
        Object raw = payload.remove("charset");
        if (raw == null) {
            return;
        }
        String charset = raw.toString().trim().toLowerCase();
        if (charset.isEmpty() || "utf-8".equals(charset) || "utf8".equals(charset)) {
            return;
        }
        if (!ALLOWED.contains(charset)) {
            throw McpanelBusinessException.invalid("不支持的写入编码：" + charset);
        }
        Object content = payload.get("content");
        if (!(content instanceof String base64)) {
            throw McpanelBusinessException.invalid("转码写入缺少文件内容");
        }
        byte[] utf8Bytes;
        try {
            utf8Bytes = Base64.getDecoder().decode(base64);
        }
        catch (IllegalArgumentException e) {
            throw McpanelBusinessException.invalid("文件内容不是有效 base64，无法转码");
        }
        String text = new String(utf8Bytes, StandardCharsets.UTF_8);
        byte[] encoded = text.getBytes(Charset.forName(charset));
        payload.put("content", Base64.getEncoder().encodeToString(encoded));
        payload.put("encoding", "base64");
    }
}
