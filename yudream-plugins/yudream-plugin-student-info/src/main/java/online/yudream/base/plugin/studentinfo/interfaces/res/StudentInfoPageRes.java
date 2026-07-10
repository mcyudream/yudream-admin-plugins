package online.yudream.base.plugin.studentinfo.interfaces.res;

import java.util.List;

public record StudentInfoPageRes(List<StudentInfoRes> records, long total) {
}
