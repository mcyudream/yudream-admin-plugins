package online.yudream.plugin.worldmap.infrastructure.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模型烘焙器：把立方体元素的面烘焙成世界空间四边形（局部 0..16 坐标系）。
 *
 * <p>顶点顺序固定为绕外法线逆时针：A(u1,v1) → B(u1,v2) → C(u2,v2) → D(u2,v1)。
 * 依次应用：面内 uv rotation → 元素级旋转（origin/axis/angle/rescale）→
 * blockstate x/y 旋转（90 倍数，先 x 后 y）→ uvlock 修正。</p>
 *
 * <p>原版 blockstate 的旋转角约定等价于"绕轴右手系 -angle"，此处按此实现
 * （已用 oak_log axis、stairs facing 等约定验证）。</p>
 */
final class ModelBaker {

    private final ModelResolver resolver;
    private final TextureAtlas atlas;

    ModelBaker(ModelResolver resolver, TextureAtlas atlas) {
        this.resolver = resolver;
        this.atlas = atlas;
    }

    /** 烘焙一个已解析模型，应用 blockstate 旋转（x/y 为 0..270 的 90 倍数）。 */
    List<BakedQuad> bake(ResolvedModel model, int xRot, int yRot, boolean uvlock) {
        List<BakedQuad> quads = new ArrayList<>();
        for (ModelElement element : model.elements()) {
            bakeElement(model, element, xRot, yRot, uvlock, quads);
        }
        return quads;
    }

    /** 缺失模型回退：品红/黑棋盘全立方体（不做 cullface，保证可见便于排查）。 */
    BakedQuad[] missingCube() {
        List<BakedQuad> quads = new ArrayList<>(6);
        float[] from = {0, 0, 0};
        float[] to = {16, 16, 16};
        for (String dirName : Direction.NAMES) {
            Direction dir = Direction.byName(dirName);
            float[][] corners = cornersFor(dir, from, to);
            float[] uv = {0, 0, 16, 16};
            float[][] uvPairs = uvPairs(uv);
            quads.add(buildQuad(corners, uvPairs, TextureAtlas.MISSING_TEXTURE,
                    TintType.NONE, true, null));
        }
        return quads.toArray(new BakedQuad[0]);
    }

    /**
     * 内置液体几何：原版水/熔岩没有 JSON 模型（客户端 FluidRenderer 硬编码），
     * 此处合成一个顶面按液位降低的全立方体，纹理用 still 贴图（流动贴图一期不做）。
     * 不做 cullface（相邻液体面的剔除由渲染层处理）；shade=false 避免无 AO 数据时发暗。
     */
    List<BakedQuad> bakeLiquid(String texturePath, float height, TintType tint) {
        float[] from = {0, 0, 0};
        float[] to = {16, height, 16};
        List<BakedQuad> quads = new ArrayList<>(6);
        for (String dirName : Direction.NAMES) {
            Direction dir = Direction.byName(dirName);
            float[][] corners = cornersFor(dir, from, to);
            float[][] uvPairs = uvPairs(defaultUv(dir, from, to));
            quads.add(buildQuad(corners, uvPairs, texturePath, tint, false, null));
        }
        return quads;
    }

    private void bakeElement(ResolvedModel model, ModelElement element,
                             int xRot, int yRot, boolean uvlock, List<BakedQuad> out) {
        for (Map.Entry<String, ModelFace> entry : element.faces().entrySet()) {
            Direction dir = Direction.byName(entry.getKey());
            if (dir == null) {
                continue;
            }
            ModelFace face = entry.getValue();
            float[][] corners = cornersFor(dir, element.from(), element.to());

            // uv：显式或按方向默认推导
            float[] uv = face.uv() != null ? face.uv() : defaultUv(dir, element.from(), element.to());
            float[][] uvPairs = uvPairs(uv);
            // 面内 uv 旋转（顺时针，0/90/180/270）
            uvPairs = rotateAssignment(uvPairs, ((face.rotation() % 360) + 360) % 360 / 90);

            // cullface 方向向量（随模型一起旋转）
            float[] cullVec = null;
            if (face.cullface() != null) {
                Direction cull = Direction.byName(face.cullface());
                if (cull != null) {
                    cullVec = cull.vec();
                }
            }

            // 元素级旋转：p' = o + R · S · (p - o)
            ModelElement.ElementRotation rot = element.rotation();
            if (rot != null && rot.angleDegrees() != 0) {
                double theta = Math.toRadians(-rot.angleDegrees()); // 原版约定：右手系 -angle
                float scale = rot.rescale() ? (float) (1.0 / Math.cos(theta)) : 1f;
                for (float[] corner : corners) {
                    rotateInPlace(corner, rot.axis(), theta, rot.origin(), scale);
                }
                if (cullVec != null) {
                    rotateVectorInPlace(cullVec, rot.axis(), theta);
                }
            }

            // uvlock 需在 blockstate 旋转前、基于原方向计算补偿步数
            int uvlockSteps = 0;
            if (uvlock && (xRot != 0 || yRot != 0)) {
                uvlockSteps = uvlockSteps(dir, xRot, yRot);
            }

            // blockstate 旋转：先 x 后 y，绕 (8,8,8)，右手系 -angle
            if (xRot != 0 || yRot != 0) {
                double tx = Math.toRadians(-xRot);
                double ty = Math.toRadians(-yRot);
                float[] center = {8, 8, 8};
                for (float[] corner : corners) {
                    if (xRot != 0) {
                        rotateInPlace(corner, 'x', tx, center, 1f);
                    }
                    if (yRot != 0) {
                        rotateInPlace(corner, 'y', ty, center, 1f);
                    }
                }
                if (cullVec != null) {
                    if (xRot != 0) {
                        rotateVectorInPlace(cullVec, 'x', tx);
                    }
                    if (yRot != 0) {
                        rotateVectorInPlace(cullVec, 'y', ty);
                    }
                }
            }
            if (uvlockSteps != 0) {
                uvPairs = rotateAssignment(uvPairs, uvlockSteps);
            }

            String texturePath = resolver.resolveFaceTexture(model, face.texture());
            TintType tint = face.tintIndex() != null ? tintOf(texturePath) : TintType.NONE;
            String cullface = cullVec == null ? null : Direction.nameOf(snapped(cullVec));
            boolean shade = model.ambientOcclusion() && element.shade();
            out.add(buildQuad(corners, uvPairs, texturePath, tint, shade, cullface));
        }
    }

    /** 组装 BakedQuad：uv 映射到贴图集空间，未知纹理回退缺失纹理。 */
    private BakedQuad buildQuad(float[][] corners, float[][] uvPairs, String texturePath,
                                TintType tint, boolean shade, String cullface) {
        TextureAtlas.UVRect rect = texturePath == null ? null : atlas.uv(texturePath);
        boolean translucent = texturePath != null && atlas.isTranslucent(texturePath);
        if (rect == null) {
            rect = atlas.uv(TextureAtlas.MISSING_TEXTURE);
        }
        float[] positions = new float[12];
        float[] uvs = new float[8];
        for (int i = 0; i < 4; i++) {
            positions[i * 3] = corners[i][0];
            positions[i * 3 + 1] = corners[i][1];
            positions[i * 3 + 2] = corners[i][2];
            uvs[i * 2] = rect.mapU(uvPairs[i][0]);
            uvs[i * 2 + 1] = rect.mapV(uvPairs[i][1]);
        }
        return new BakedQuad(positions, uvs, tint, shade, cullface, translucent);
    }

    // ---------- 方向表 ----------

    /** 六方向（向量 + 名称）。 */
    private enum Direction {
        DOWN("down", 0, -1, 0), UP("up", 0, 1, 0),
        NORTH("north", 0, 0, -1), SOUTH("south", 0, 0, 1),
        WEST("west", -1, 0, 0), EAST("east", 1, 0, 0);

        static final String[] NAMES = {"down", "up", "north", "south", "east", "west"};

        final String label;
        final float[] vec;

        Direction(String label, float x, float y, float z) {
            this.label = label;
            this.vec = new float[]{x, y, z};
        }

        float[] vec() {
            return vec.clone();
        }

        static Direction byName(String name) {
            for (Direction d : values()) {
                if (d.label.equals(name)) {
                    return d;
                }
            }
            return null;
        }

        static String nameOf(float[] vec) {
            Direction best = null;
            float bestDot = -Float.MAX_VALUE;
            for (Direction d : values()) {
                float dot = d.vec[0] * vec[0] + d.vec[1] * vec[1] + d.vec[2] * vec[2];
                if (dot > bestDot) {
                    bestDot = dot;
                    best = d;
                }
            }
            return best == null ? null : best.label;
        }
    }

    /** 每个方向的面四角（A=u1v1, B=u1v2, C=u2v2, D=u2v1，绕外法线逆时针）。 */
    private static float[][] cornersFor(Direction dir, float[] from, float[] to) {
        float x1 = from[0], y1 = from[1], z1 = from[2];
        float x2 = to[0], y2 = to[1], z2 = to[2];
        return switch (dir) {
            case DOWN -> new float[][]{{x1, y1, z2}, {x1, y1, z1}, {x2, y1, z1}, {x2, y1, z2}};
            case UP -> new float[][]{{x1, y2, z1}, {x1, y2, z2}, {x2, y2, z2}, {x2, y2, z1}};
            case NORTH -> new float[][]{{x2, y2, z1}, {x2, y1, z1}, {x1, y1, z1}, {x1, y2, z1}};
            case SOUTH -> new float[][]{{x1, y2, z2}, {x1, y1, z2}, {x2, y1, z2}, {x2, y2, z2}};
            case WEST -> new float[][]{{x1, y2, z1}, {x1, y1, z1}, {x1, y1, z2}, {x1, y2, z2}};
            case EAST -> new float[][]{{x2, y2, z2}, {x2, y1, z2}, {x2, y1, z1}, {x2, y2, z1}};
        };
    }

    /** 每个方向未显式指定 uv 时的默认值（原版 BlockElement 推导规则）。 */
    private static float[] defaultUv(Direction dir, float[] from, float[] to) {
        float x1 = from[0], y1 = from[1], z1 = from[2];
        float x2 = to[0], y2 = to[1], z2 = to[2];
        return switch (dir) {
            case DOWN -> new float[]{x1, 16 - z2, x2, 16 - z1};
            case UP -> new float[]{x1, z1, x2, z2};
            case NORTH -> new float[]{16 - x2, 16 - y2, 16 - x1, 16 - y1};
            case SOUTH -> new float[]{x1, 16 - y2, x2, 16 - y1};
            case WEST -> new float[]{z1, 16 - y2, z2, 16 - y1};
            case EAST -> new float[]{16 - z2, 16 - y2, 16 - z1, 16 - y1};
        };
    }

    /** uv 矩形 → 4 顶点 uv 对（A=u1v1, B=u1v2, C=u2v2, D=u2v1）。 */
    private static float[][] uvPairs(float[] uv) {
        return new float[][]{
                {uv[0], uv[1]}, {uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}};
    }

    /** uv 角点指派整体顺时针旋转 steps 步（等价于纹理顺时针旋转 steps×90°）。 */
    private static float[][] rotateAssignment(float[][] pairs, int steps) {
        int n = ((steps % 4) + 4) % 4;
        if (n == 0) {
            return pairs;
        }
        float[][] rotated = new float[4][2];
        for (int i = 0; i < 4; i++) {
            rotated[i] = pairs[(i + n) % 4];
        }
        return rotated;
    }

    // ---------- 旋转与 uvlock ----------

    /** 绕轴旋转点（可选 rescale：先在旋转平面两轴缩放再旋转），绕 origin。 */
    private static void rotateInPlace(float[] p, char axis, double theta, float[] origin, float scale) {
        float x = p[0] - origin[0];
        float y = p[1] - origin[1];
        float z = p[2] - origin[2];
        if (scale != 1f) {
            switch (axis) {
                case 'x' -> { x *= 1; y *= scale; z *= scale; }
                case 'y' -> { x *= scale; z *= scale; }
                case 'z' -> { x *= scale; y *= scale; }
                default -> { }
            }
        }
        float cos = (float) Math.cos(theta);
        float sin = (float) Math.sin(theta);
        switch (axis) {
            case 'x' -> {
                p[0] = origin[0] + x;
                p[1] = origin[1] + y * cos - z * sin;
                p[2] = origin[2] + y * sin + z * cos;
            }
            case 'y' -> {
                p[0] = origin[0] + x * cos + z * sin;
                p[1] = origin[1] + y;
                p[2] = origin[2] + -x * sin + z * cos;
            }
            case 'z' -> {
                p[0] = origin[0] + x * cos - y * sin;
                p[1] = origin[1] + x * sin + y * cos;
                p[2] = origin[2] + z;
            }
            default -> { }
        }
    }

    private static void rotateVectorInPlace(float[] v, char axis, double theta) {
        float[] origin = {0, 0, 0};
        rotateInPlace(v, axis, theta, origin, 1f);
    }

    /** 方向向量吸附到最近的主轴方向。 */
    private static float[] snapped(float[] vec) {
        float ax = Math.abs(vec[0]), ay = Math.abs(vec[1]), az = Math.abs(vec[2]);
        if (ax >= ay && ax >= az) {
            return new float[]{Math.signum(vec[0]), 0, 0};
        }
        if (ay >= ax && ay >= az) {
            return new float[]{0, Math.signum(vec[1]), 0};
        }
        return new float[]{0, 0, Math.signum(vec[2])};
    }

    /** 面在模型空间中 u/v 增长方向的基向量（与 cornersFor/defaultUv 推导一致）。 */
    private static float[] uAxis(Direction dir) {
        return switch (dir) {
            case DOWN, UP, SOUTH -> new float[]{1, 0, 0};
            case NORTH, EAST -> new float[]{-1, 0, 0};
            case WEST -> new float[]{0, 0, 1};
        };
    }

    private static float[] vAxis(Direction dir) {
        return switch (dir) {
            case DOWN -> new float[]{0, 0, -1};
            case UP -> new float[]{0, 0, 1};
            case NORTH, SOUTH, WEST, EAST -> new float[]{0, -1, 0};
        };
    }

    /**
     * uvlock 补偿：blockstate 旋转后，纹理应相对于世界保持不转。
     * 计算把面 uv 指派顺时针旋转多少步可让 u 轴回到新方向的规范方向。
     */
    private static int uvlockSteps(Direction dir, int xRot, int yRot) {
        double tx = Math.toRadians(-xRot);
        double ty = Math.toRadians(-yRot);
        float[] u = uAxis(dir);
        float[] normal = dir.vec();
        if (xRot != 0) {
            rotateVectorInPlace(u, 'x', tx);
            rotateVectorInPlace(normal, 'x', tx);
        }
        if (yRot != 0) {
            rotateVectorInPlace(u, 'y', ty);
            rotateVectorInPlace(normal, 'y', ty);
        }
        Direction newDir = Direction.byName(Direction.nameOf(snapped(normal)));
        if (newDir == null) {
            return 0;
        }
        float[] uc = uAxis(newDir);
        float[] vc = vAxis(newDir);
        int a = Math.round(u[0] * uc[0] + u[1] * uc[1] + u[2] * uc[2]);
        int b = Math.round(u[0] * vc[0] + u[1] * vc[1] + u[2] * vc[2]);
        // 找 k 使顺时针旋转 k 步后 (a,b) → (1,0)；顺时针一步：(a,b) → (-b, a)
        for (int k = 0; k < 4; k++) {
            if (a == 1 && b == 0) {
                return k;
            }
            int na = -b;
            b = a;
            a = na;
        }
        return 0;
    }

    /** 染色启发：按纹理路径粗分类（渲染层再按 TintType 查群系色表）。 */
    private static TintType tintOf(String texturePath) {
        if (texturePath == null) {
            return TintType.GRASS;
        }
        if (texturePath.contains("water")) {
            return TintType.WATER;
        }
        if (texturePath.contains("leaves")) {
            return TintType.FOLIAGE;
        }
        if (texturePath.contains("redstone")) {
            return TintType.REDSTONE;
        }
        return TintType.GRASS;
    }
}
