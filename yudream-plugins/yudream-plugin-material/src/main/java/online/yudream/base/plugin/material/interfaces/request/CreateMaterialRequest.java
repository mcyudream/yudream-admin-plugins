package online.yudream.base.plugin.material.interfaces.request;

import java.util.List;

public record CreateMaterialRequest(String fileId, String filename, String name, String categoryId, List<String> tags,
                                    String visibility) {
}
