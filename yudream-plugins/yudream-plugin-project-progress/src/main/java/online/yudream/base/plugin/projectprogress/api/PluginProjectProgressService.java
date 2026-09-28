package online.yudream.base.plugin.projectprogress.api;

import java.util.List;

/**
 * project-progress 对外暴露的只读服务契约（稳定 API）。
 *
 * <p>由 project-progress 在启用时经 {@code PluginContext.exposeService} 注册；消费方以 provided 依赖编译，
 * 运行时通过 {@code context.service("project-progress", PluginProjectProgressService.class)} 获取，
 * 插件缺失时按 {@code Optional.empty()} 或 {@code softdepend} 降级，不得缓存 provider 的 API 对象跨
 * disable/reload 使用。</p>
 *
 * <h2>积分联动的两条口径（完全一致：每条打卡各一次）</h2>
 * <ul>
 *   <li><b>拉取式</b>：{@link #acceptedCheckIns(long, int, int)} 按验收通过时间增量分页拉取，
 *       适合补偿/对账；</li>
 *   <li><b>推送式</b>：实现 {@link PluginProjectCheckInAcceptedListener} 并
 *       {@code context.registerExtension(PluginProjectCheckInAcceptedListener.class, listener)}，
 *       在验收通过的那一刻逐条回调，适合实时发放。</li>
 * </ul>
 *
 * <p>两条口径给的是同一批记录、同一种粒度（该细节下的每条打卡记录各一次），且都以
 * {@link PluginProjectCheckInReward#checkInId()} 为幂等键：消费方必须自己按该 id 去重，
 * 因为同一条打卡可能既被回调一次、又在增量拉取里出现一次（拉取的范围重叠也会重复出现）。</p>
 *
 * <p>两个方法都给出了 default 实现（返回空集合），这样旧版本 provider 与新版消费方混跑时，
 * 消费方拿到的是空结果而不是 {@link AbstractMethodError}，可以按「该能力不可用」降级。</p>
 */
public interface PluginProjectProgressService {

    /**
     * 「所属工作细节已验收通过」的打卡记录，按验收通过时间增量分页拉取。
     *
     * <p><b>筛选</b>：只返回所属工作细节存在 {@code ACCEPTED} 验收记录的打卡记录。工作细节上用
     * {@code ProjectWorkDetail.pendingAcceptance} 与项目 {@code doneStatusCode} 表达的「已完成/验收通过」
     * 判定不参与本查询，本查询只认验收记录（{@code ProjectAcceptanceResult.ACCEPTED}）。
     * 挂在项目上而不是任何细节上的打卡（{@code detailId} 为空）永远不会返回；
     * **被驳回的打卡（{@code reviewStatus = REJECTED}）也不会返回**——驳回表示这次证据不成立、
     * 不算这次打卡，与实时回调口径一致。</p>
     *
     * <p><b>下界</b>：{@code sinceAcceptedAt} 是**含**下界，即只返回
     * {@code acceptedAt >= sinceAcceptedAt} 的记录；传 0 表示全量。</p>
     *
     * <p><b>去重</b>：同一打卡记录在结果中最多出现一次。一条打卡经历了多轮验收（退回返工后重新提交并
     * 再次通过）时，取该细节**最后一次**验收通过的时间作为 {@code acceptedAt}，不会重复返回多条。</p>
     *
     * <p><b>排序</b>：按 {@code acceptedAt} 升序；{@code acceptedAt} 相同时按 {@code checkInId} 升序。
     * 排序在分页之前完成，因此跨页拼接不会重复也不会遗漏。</p>
     *
     * <p><b>分页</b>：{@code page} 从 1 起（小于 1 按 1 处理），{@code size} 为每页条数
     * （小于 1 按 20 处理，上限 200）。取空页返回空列表，不报错。</p>
     *
     * <p><b>增量游标用法</b>：把本批的最大 {@code acceptedAt} 作为下一轮的 {@code sinceAcceptedAt}
     * 继续拉取；由于下界是含的，边界那一秒会重复返回，消费方必须按 {@code checkInId} 幂等去重。</p>
     *
     * @param sinceAcceptedAt 验收通过时间的含下界（毫秒时间戳），传 0 表示不限
     * @param page            页码，从 1 起
     * @param size            每页条数，上限 200
     * @return 按上述排序分页的奖励项；没有匹配记录时返回空列表，不返回 {@code null}
     */
    default List<PluginProjectCheckInReward> acceptedCheckIns(long sinceAcceptedAt, int page, int size) {
        return List.of();
    }

    /**
     * 全部项目的最小视图（含已停用的项目），供消费方做按项目配置与展示。
     *
     * <p>不分页：项目数量是管理级规模，消费方一次取全量后自行缓存。未创建的插件数据返回空列表。</p>
     */
    default List<PluginProjectSummary> projects() {
        return List.of();
    }
}
