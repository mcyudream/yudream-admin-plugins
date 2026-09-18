package online.yudream.base.plugin.material.application.command;

/**
 * 在父物料下新增一个子物料（首个版本）。
 *
 * <p>name 留空时取文件名去扩展名。note 是首个版本的备注，仅文件夹批量导入（组合物料形态）用来记录
 * 文件所在子目录，普通新增子物料不产生备注。
 */
public record CreateMaterialItemCommand(String fileId, String filename, String name, String note) {
    /** 旧签名：无备注（普通新增子物料）。 */
    public CreateMaterialItemCommand(String fileId, String filename, String name) {
        this(fileId, filename, name, null);
    }
}
