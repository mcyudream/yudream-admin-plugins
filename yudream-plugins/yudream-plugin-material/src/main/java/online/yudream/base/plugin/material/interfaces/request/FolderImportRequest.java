package online.yudream.base.plugin.material.interfaces.request;

import java.util.List;

/**
 * 文件夹导入请求。mode = FILES（缺省）时每个文件一个单文件物料；
 * mode = BUNDLE 时整批合并为一个组合物料，name 为该组合物料名称（缺省取 categoryName）。
 *
 * <p>item.tags 是文件所在的中间子文件夹名（由外到内，根文件夹不计）：FILES 下并入物料标签，
 * BUNDLE 下写进子物料首个版本备注；两种形态都用它把目录名拼进物料名/子物料名（用 '-' 连接）。
 */
public record FolderImportRequest(String mode, String name, String categoryId, String categoryName,
                                  String visibility, List<String> deptIds, List<String> tags, List<Item> items) {
    /** name 留空时按「中间子目录-文件名」自动拼接。 */
    public record Item(String fileId, String filename, String name, List<String> tags) {}
}
