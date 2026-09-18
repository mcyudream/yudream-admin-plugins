package online.yudream.base.plugin.material.application.command;

import java.util.List;

/**
 * 文件夹导入：分类统一用所选根文件夹名（categoryId 优先，缺省按 categoryName 查找或自动创建）。
 *
 * <p>导入形态由 mode 决定：
 * <ul>
 *   <li>{@link #MODE_FILES}（缺省）：每个文件创建一个单文件物料；item.tags 携带所在子文件夹名（由外到内），
 *       与批次 tags 合并后统一归一化（去重、上限 8 个、单签 20 字）。</li>
 *   <li>{@link #MODE_BUNDLE}：整批合并为一个组合物料（name 为其名称，缺省取分类名），每个文件成为一个子物料；
 *       item.tags 中的子目录名写进该子物料首个版本的备注，使目录来源不在合并过程中静默丢失。</li>
 * </ul>
 *
 * <p>两种形态的名称规则一致：item.name 显式给出时原样使用；否则由 item.tags 的中间子目录与文件名用 '-' 拼接
 * （如 海报/横版/封面.png → 「海报-横版-封面」），根文件夹名不进名称。
 *
 * 两种形态都保持「单个文件失败不中断整批」。可见性缺省 PRIVATE；DEPT 时 deptIds 为显式选择的可见部门。
 */
public record FolderImportCommand(String mode, String name, String categoryId, String categoryName,
                                  String visibility, List<String> deptIds, List<String> tags, List<Item> items) {
    public static final String MODE_FILES = "FILES";
    public static final String MODE_BUNDLE = "BUNDLE";

    /** 旧签名：逐个文件导入（mode = FILES），兼容既有调用与测试。 */
    public FolderImportCommand(String categoryId, String categoryName, String visibility,
                               List<String> deptIds, List<String> tags, List<Item> items) {
        this(MODE_FILES, null, categoryId, categoryName, visibility, deptIds, tags, items);
    }

    /** 是否为「整批合并为一个组合物料」形态；mode 缺省或无法识别时按逐个文件导入处理。 */
    public boolean bundle() {
        return MODE_BUNDLE.equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public record Item(String fileId, String filename, String name, List<String> tags) {}
}
