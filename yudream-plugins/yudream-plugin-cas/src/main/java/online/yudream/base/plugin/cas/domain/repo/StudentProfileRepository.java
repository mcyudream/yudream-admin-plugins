package online.yudream.base.plugin.cas.domain.repo;

import online.yudream.base.plugin.cas.domain.aggregate.StudentProfile;

import java.util.List;
import java.util.Optional;

public interface StudentProfileRepository {

    Optional<StudentProfile> find(String socialUid);

    StudentProfile save(StudentProfile profile);

    List<StudentProfile> page(int page, int size);

    List<StudentProfile> findBySocialUid(String socialUid);

    /** 关键词搜索（学号/姓名/学院/专业包含匹配），带扫描上限保护。 */
    List<StudentProfile> search(String keyword);

    long count();
}
