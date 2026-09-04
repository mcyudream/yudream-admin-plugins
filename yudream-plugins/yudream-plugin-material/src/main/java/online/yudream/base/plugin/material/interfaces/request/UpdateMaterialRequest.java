package online.yudream.base.plugin.material.interfaces.request;

import java.util.List;

public record UpdateMaterialRequest(String name, String categoryId, List<String> tags, String visibility,
                                    List<String> deptIds) {
}
