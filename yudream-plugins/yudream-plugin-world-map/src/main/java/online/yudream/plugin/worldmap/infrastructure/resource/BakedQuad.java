package online.yudream.plugin.worldmap.infrastructure.resource;

/**
 * 烘焙后的四边形面片（局部 0..16 方块坐标系，渲染层负责平移到世界坐标）。
 *
 * @param positions   4 个顶点的 xyz，共 12 个 float，顶点顺序为绕外法线逆时针（CCW），
 *                    可按 (0,1,2),(0,2,3) 三角化
 * @param uvs         4 个顶点的 uv，共 8 个 float，与 positions 顶点一一对应，
 *                    已映射到贴图集 atlas 空间 [0,1]
 * @param tint        染色类型（草/树叶/水等）
 * @param shade       是否受环境光遮蔽/方向明暗影响（对应模型 ambientocclusion && shade）
 * @param cullface    遮挡剔除面：down/up/north/south/east/west 或 null（不剔除）。
 *                    渲染层在相邻方块该侧面完整不透明时可跳过此面
 * @param translucent 是否为半透明材质（水/玻璃/冰等；一期按不透明渲染，仅作标记）
 */
public record BakedQuad(float[] positions, float[] uvs, TintType tint,
                        boolean shade, String cullface, boolean translucent) {

    public BakedQuad {
        if (positions == null || positions.length != 12) {
            throw new IllegalArgumentException("positions 必须为 12 个 float（4 顶点 xyz）");
        }
        if (uvs == null || uvs.length != 8) {
            throw new IllegalArgumentException("uvs 必须为 8 个 float（4 顶点 uv）");
        }
        if (tint == null) {
            tint = TintType.NONE;
        }
    }
}
