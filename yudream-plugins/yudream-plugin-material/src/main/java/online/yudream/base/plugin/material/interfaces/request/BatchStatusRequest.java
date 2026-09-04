package online.yudream.base.plugin.material.interfaces.request;

import java.util.List;

public record BatchStatusRequest(List<String> ids, String status) {
}
