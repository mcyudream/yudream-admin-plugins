package online.yudream.base.plugin.feedback.interfaces.assembler;

import online.yudream.base.plugin.feedback.application.cmd.FeedbackSubmitCmd;
import online.yudream.base.plugin.feedback.interfaces.request.FeedbackSubmitRequest;

/** 请求体到应用层命令的转换：只做空值与枚举解析，业务校验交给领域。 */
public class FeedbackWebAssembler {

    public FeedbackSubmitCmd toSubmitCmd(FeedbackSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return FeedbackSubmitCmd.of(request.safeTitle(), request.safeDescription(),
                request.type(), request.severity(), request.safeAttachments());
    }
}
