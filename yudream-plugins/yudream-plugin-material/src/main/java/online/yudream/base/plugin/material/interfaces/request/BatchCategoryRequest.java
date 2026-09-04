package online.yudream.base.plugin.material.interfaces.request;

import java.util.List;

public record BatchCategoryRequest(List<String> ids, String categoryId) {
}
