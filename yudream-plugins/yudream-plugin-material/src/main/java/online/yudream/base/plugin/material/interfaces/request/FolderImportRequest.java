package online.yudream.base.plugin.material.interfaces.request;

import java.util.List;

public record FolderImportRequest(String categoryId, String categoryName, String visibility,
                                  List<String> tags, List<Item> items) {
    public record Item(String fileId, String filename, String name) {}
}
