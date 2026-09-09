package online.yudream.base.plugin.eduverify.domain;

import online.yudream.base.plugin.eduverify.bootstrap.EduVerifyPlugin;
import online.yudream.base.plugin.spi.system.user.PluginUserTag;

import java.util.ArrayList;
import java.util.List;

/** 学历认证留档身份：真实姓名与学校必填，写入人员管理标签。 */
public record ApplicantIdentity(String realName, String schoolName) {

    public ApplicantIdentity {
        realName = requireName(realName);
        schoolName = requireSchool(schoolName);
    }

    public static ApplicantIdentity require(String realName, String schoolName) {
        return new ApplicantIdentity(realName, schoolName);
    }

    public static ApplicantIdentity require(String realName, String schoolName, String fallbackName, String fallbackSchool) {
        return require(firstNonBlank(realName, fallbackName), firstNonBlank(schoolName, fallbackSchool));
    }

    public static ApplicantIdentity optional(String realName, String schoolName, String fallbackName, String fallbackSchool) {
        String name = firstNonBlank(realName, fallbackName);
        String school = firstNonBlank(schoolName, fallbackSchool);
        if (name == null || school == null) {
            return null;
        }
        return require(name, school);
    }

    public List<PluginUserTag> personnelTags(String statusLabel) {
        List<PluginUserTag> tags = new ArrayList<>();
        tags.add(new PluginUserTag(EduVerifyPlugin.CODE, "name", realName));
        tags.add(new PluginUserTag(EduVerifyPlugin.CODE, "school", schoolName));
        if (statusLabel != null && !statusLabel.isBlank()) {
            tags.add(new PluginUserTag(EduVerifyPlugin.CODE, "status", statusLabel.trim()));
        }
        return List.copyOf(tags);
    }

    public String audit(String action, String reason) {
        StringBuilder text = new StringBuilder();
        if (action != null && !action.isBlank()) {
            text.append(action.trim());
        }
        text.append("；姓名=").append(realName).append("；学校=").append(schoolName);
        if (reason != null && !reason.isBlank()) {
            text.append("；").append(reason.trim());
        }
        return text.toString();
    }

    private static String requireName(String value) {
        String name = requireText(value, "请填写真实姓名");
        if (name.length() > 40) {
            throw new IllegalArgumentException("真实姓名过长");
        }
        return name;
    }

    private static String requireSchool(String value) {
        String school = requireText(value, "请填写学校名称");
        if (school.length() > 80) {
            throw new IllegalArgumentException("学校名称过长");
        }
        return school;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
