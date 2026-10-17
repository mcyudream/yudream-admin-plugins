package online.yudream.plugin.voxelithmap.domain.value;

/**
 * 地图的默认初始视角（管理员在查看页「设为默认视角」保存）。
 *
 * <p>用「相机位置 + 注视点」而不是欧拉角表达朝向：查看器重建相机时直接
 * {@code position(pos); lookAt(target)} 即可，与相机模式（飞行/第一人称/俯视倾斜）无关，
 * 也不用约定 yaw/pitch 的正负与旋转序。</p>
 *
 * @param x  相机位置 X（世界方块坐标）
 * @param y  相机位置 Y
 * @param z  相机位置 Z
 * @param tx 注视点 X
 * @param ty 注视点 Y
 * @param tz 注视点 Z
 */
public record DefaultView(double x, double y, double z, double tx, double ty, double tz) {

    public DefaultView {
        if (!finite(x) || !finite(y) || !finite(z) || !finite(tx) || !finite(ty) || !finite(tz)) {
            throw new IllegalArgumentException("默认视角坐标非法：必须为有限数值");
        }
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        if (dx * dx + dy * dy + dz * dz < 1e-6) {
            throw new IllegalArgumentException("默认视角非法：注视点不能与相机位置重合");
        }
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }
}
