package online.yudream.base.plugin.studentinfo.api;

import java.util.List;
import java.util.Optional;

/** Stable service contract exposed by the yudream-student-info plugin. */
public interface PluginStudentInfoService {
    Optional<PluginStudentInfoProfile> findStudentInfoByUserId(String userId);
    Optional<PluginStudentInfoProfile> findStudentInfoByStudentNo(String studentNo);
    List<PluginStudentInfoProfile> studentInfos(String keyword, int page, int size);
}
