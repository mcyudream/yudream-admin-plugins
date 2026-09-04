package online.yudream.base.plugin.material.interfaces.request;

import java.util.List;

public record BatchTagsRequest(List<String> ids, List<String> tags, String mode) {
}
