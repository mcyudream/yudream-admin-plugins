package online.yudream.base.plugin.eduverify.interfaces.request;

import java.util.List;
import java.util.Map;

public record ManualSubmitRequest(
        String email,
        String realName,
        String schoolName,
        String note,
        String vcode,
        List<Map<String, Object>> materials
) {
}
