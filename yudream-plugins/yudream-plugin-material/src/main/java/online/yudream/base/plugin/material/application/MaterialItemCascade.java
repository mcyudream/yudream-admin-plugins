package online.yudream.base.plugin.material.application;

/**
 * 父物料删除时的子物料级联清理。
 *
 * <p>由 {@link MaterialItemService} 实现并在装配阶段注入 {@link MaterialService}：
 * 级联方向是「父 → 子」，而子物料用例又需要父物料的归属/可见性判定，直接互相构造会形成循环依赖，
 * 因此这里用接口把「父删除」对「子清理」的依赖倒置出来。
 */
@FunctionalInterface
public interface MaterialItemCascade {
    /** 删除某父物料下全部子物料、其全部版本记录与文件对象。 */
    void deleteByMaterial(String materialId);
}
