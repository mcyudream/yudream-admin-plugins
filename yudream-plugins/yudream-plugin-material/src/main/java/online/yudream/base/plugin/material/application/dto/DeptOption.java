package online.yudream.base.plugin.material.application.dto;

/** 部门选择器选项。label 为带父级路径的展示名（如「技术部 / 平台组」），无父级时与 name 相同。 */
public record DeptOption(String id, String name, String label) {
}
