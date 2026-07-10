package online.yudream.base.plugin.studentinfo.application.dto;

import java.util.List;

public record StudentInfoPageDTO(List<StudentInfoDTO> records, long total) {
}
