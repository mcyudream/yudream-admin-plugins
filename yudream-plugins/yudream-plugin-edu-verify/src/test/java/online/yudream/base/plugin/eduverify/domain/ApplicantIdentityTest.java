package online.yudream.base.plugin.eduverify.domain;

import online.yudream.base.plugin.eduverify.bootstrap.EduVerifyPlugin;
import online.yudream.base.plugin.spi.system.user.PluginUserTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicantIdentityTest {

    @Test
    void requiresNameAndSchool() {
        ApplicantIdentity identity = ApplicantIdentity.require(" 张三 ", " 西南科技大学 ");
        assertEquals("张三", identity.realName());
        assertEquals("西南科技大学", identity.schoolName());
    }

    @Test
    void prefersOverrideThenExisting() {
        ApplicantIdentity identity = ApplicantIdentity.require("李四", "某某大学", "旧名", "旧校");
        assertEquals("李四", identity.realName());
        assertEquals("某某大学", identity.schoolName());
    }

    @Test
    void fallsBackToExistingWhenOverrideBlank() {
        ApplicantIdentity identity = ApplicantIdentity.require(" ", null, "旧名", "旧校");
        assertEquals("旧名", identity.realName());
        assertEquals("旧校", identity.schoolName());
    }

    @Test
    void blankNameFails() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ApplicantIdentity.require(" ", "某某大学"));
        assertEquals("请填写真实姓名", error.getMessage());
    }

    @Test
    void blankSchoolFails() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ApplicantIdentity.require("张三", "\n"));
        assertEquals("请填写学校名称", error.getMessage());
    }

    @Test
    void tooLongNameFails() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ApplicantIdentity.require("一".repeat(41), "某某大学"));
        assertEquals("真实姓名过长", error.getMessage());
    }

    @Test
    void personnelTagsIncludeNameSchoolAndStatus() {
        ApplicantIdentity identity = ApplicantIdentity.require("李四", "西南科技大学");
        List<PluginUserTag> tags = new ArrayList<>(identity.personnelTags("已认证"));
        assertEquals(3, tags.size());
        assertEquals(EduVerifyPlugin.CODE, tags.get(0).namespace());
        assertEquals("name", tags.get(0).code());
        assertEquals("李四", tags.get(0).label());
        assertEquals("school", tags.get(1).code());
        assertEquals("西南科技大学", tags.get(1).label());
        assertEquals("status", tags.get(2).code());
        assertEquals("已认证", tags.get(2).label());
    }
}
