package online.yudream.base.plugin.playtimepoints.application.port;

import java.util.List;
import java.util.function.Consumer;

/**
 * project-progress 插件端口：只暴露「工作细节验收通过后的打卡奖励项」与项目最小视图，
 * 隔离 provider API 类型（实现集中在 infrastructure）。端口用插件自己的 {@code RewardRef}/{@code ProjectRef}
 * 承载数据，消费方（application/domain）完全不引用 provider 类，provider 缺失时不会触发类解析失败。
 *
 * <p>语义与 project-progress 的对外契约一致：</p>
 * <ul>
 *   <li>{@link #acceptedCheckIns(long, int, int)} 按验收通过时间**含下界**升序增量分页，被驳回的打卡不返回，
 *       项目级（detailId 为空）的打卡不返回；分页 page 从 1 起、size 上限 200，越界返回空列表；</li>
 *   <li>同一条打卡可能既出现在拉取结果里、又被 {@link #registerAcceptedListener} 回调一次，
 *       消费方必须按 {@code checkInId} 幂等；</li>
 *   <li>provider 未安装 / 未启用 / 旧版没有该能力时，全部方法降级为空结果或静默跳过，绝不抛错。</li>
 * </ul>
 */
public interface ProjectProgressPort {

    /** provider 是否可用；未安装、未启用或 API 类无法解析时为 false。 */
    boolean available();

    /** 所属工作细节已验收通过的打卡记录（按 acceptedAt 含下界升序分页）；不可用时返回空列表。 */
    List<RewardRef> acceptedCheckIns(long sinceAcceptedAt, int page, int size);

    /** 全部项目的最小视图（含已停用项目）；不可用或没有项目时返回空列表。 */
    List<ProjectRef> projects();

    /**
     * 注册「验收通过」实时回调；provider 未安装/未启用/旧版没有该扩展点时静默跳过（不抛错）。
     *
     * <p>注册成功后，工作细节验收通过落库的那一刻会为每条打卡各回调一次。回调发生在 provider 的验收用例内，
     * 实现方必须把它当作「通知」而不是长任务：这里的实现只做一次幂等入账，耗时发放要自行异步化。</p>
     */
    void registerAcceptedListener(Consumer<RewardRef> listener);

    /**
     * 一条「所属工作细节已验收通过」的打卡记录。字段与 provider 的
     * {@code PluginProjectCheckInReward} 一一对应，但不引用其类型。
     *
     * @param checkInId 打卡记录 id，**幂等键**
     * @param userId    打卡人的平台用户 ID（字符串），可直接作为钱包账户
     * @param acceptedAt 该细节最后一次验收通过的时间（毫秒）
     * @param effectiveMillis 该打卡证据里**窗口内**的有效在线毫秒数（provider 1.6.0 起提供；
     *                   按时薪折算奖励用它）。非 Minecraft 打卡、证据缺失或旧版 provider 为 {@code 0}，
     *                   消费方按「本条没有可折算时长」处理
     */
    record RewardRef(
            String checkInId,
            String detailId,
            String detailTitle,
            String projectId,
            String projectName,
            String userId,
            String checkInType,
            long checkInAt,
            long acceptedAt,
            String acceptedBy,
            long effectiveMillis) {

        public RewardRef {
            effectiveMillis = Math.max(effectiveMillis, 0L);
        }
    }

    /** 项目最小视图：id、名称、是否启用。 */
    record ProjectRef(String id, String name, boolean enabled) {
    }
}
