package online.yudream.base.plugin.projectprogress.api;

/**
 * 验收通过实时回调扩展点。
 *
 * <p>其他插件实现本接口并通过
 * {@code PluginContext.registerExtension(PluginProjectCheckInAcceptedListener.class, listener)} 注册；
 * project-progress 在工作细节验收通过**成功落库之后**回调。</p>
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>对该工作细节下的**每条**打卡记录各回调一次（一条打卡一次，被驳回的打卡不算、不会回调），
 *       与 {@link PluginProjectProgressService#acceptedCheckIns(long, int, int)} 的口径完全一致；</li>
 *   <li>回调发生在验收用例内、验收结果已落库之后；回调抛出的异常（含 {@link RuntimeException} 与
 *       {@link LinkageError}）会被逐个监听者隔离并只记日志，<b>不会</b>影响验收结果、事务与其它监听者；</li>
 *   <li>细节下没有任何打卡记录时不会回调；没有任何监听者时 project-progress 不做额外读取；</li>
 *   <li>实现方应把回调当作「通知」而不是「长任务」：这里做重活会拖慢验收请求，耗时发放请自行
 *       转异步/队列，并以 {@link PluginProjectCheckInReward#checkInId()} 做幂等；</li>
 *   <li>验收用例不会因为回调而改成异步长任务；回调里如需读数据请一次性批量读取。</li>
 * </ul>
 */
public interface PluginProjectCheckInAcceptedListener {

    /**
     * 一条打卡记录因所属工作细节验收通过而应获得奖励时回调一次。
     *
     * @param reward 该打卡记录的奖励项；{@code acceptedAt} 是本次验收通过的时间
     */
    void onCheckInAccepted(PluginProjectCheckInReward reward);
}
