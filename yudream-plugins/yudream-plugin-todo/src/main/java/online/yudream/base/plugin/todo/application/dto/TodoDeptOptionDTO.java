package online.yudream.base.plugin.todo.application.dto;

/** 部门选项：id 供提交，label 带父级路径供展示。 */
public record TodoDeptOptionDTO(String id, String name, String label) {
}
