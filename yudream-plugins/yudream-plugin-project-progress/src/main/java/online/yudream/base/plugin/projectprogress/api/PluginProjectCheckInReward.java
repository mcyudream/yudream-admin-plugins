package online.yudream.base.plugin.projectprogress.api;

/**
 * 一条「所属工作细节已验收通过」的打卡记录。
 *
 * <p>这是 project-progress 面向其他插件（例如积分发放）暴露的稳定传输对象：只含原始值类型与字符串，
 * 不引用任何 provider 内部类型，消费方以 provided 依赖编译即可。字段全部不可变。</p>
 *
 * <p>口径：工作细节验收通过后，该细节下的**每条**打卡记录各产生一条本对象（一条打卡一次）。
 * 同一条打卡若经历了多轮验收（退回返工后重新提交并再次通过），只产生一条，
 * {@link #acceptedAt()} 取该细节**最后一次**验收通过的时间。</p>
 *
 * <p>增量拉取（{@link PluginProjectProgressService#acceptedCheckIns(long, int, int)}）与验收通过实时回调
 * （{@link PluginProjectCheckInAcceptedListener}）两条路径共用同一套映射，本对象的每个字段（含
 * {@link #effectiveMillis()}）在两条路径上口径完全一致。</p>
 *
 * @param checkInId   打卡记录 id。**幂等键**：消费方按它去重，同一 id 不会对应两条不同的打卡
 * @param detailId    工作细节（任务）id
 * @param detailTitle 工作细节标题
 * @param projectId   项目 id
 * @param projectName 项目名称
 * @param userId      打卡人，平台用户 ID 的字符串形式
 * @param checkInType 打卡类型，provider 侧 {@code ProjectCheckInType} 的枚举名
 *                    （{@code IMAGE} / {@code FILE} / {@code LOCATION} / {@code MINECRAFT_ONLINE}）。
 *                    这里刻意用字符串而不是枚举，消费方无需依赖 provider 的领域类型
 * @param checkInAt   打卡时间（毫秒时间戳）
 * @param acceptedAt  该工作细节最后一次验收通过的时间（毫秒时间戳）。增量拉取游标用它；
 *                    它与 {@link PluginProjectProgressService#acceptedCheckIns(long, int, int)}
 *                    的 {@code sinceAcceptedAt} 同一口径
 * @param acceptedBy  验收人用户 ID；历史数据缺失时为空字符串，不会是 {@code null}
 * @param effectiveMillis 本条打卡证据中**打卡周期窗口内**的有效在线毫秒数，与打卡记录页/导出显示的
 *                   「有效在线 XX 分钟」是同一个值（{@code ProjectMinecraftEvidence.effectiveOnlineMillis}，
 *                   项目未开启「计入挂机」时即「在线 − 挂机」）。只有 Minecraft 在线时长打卡
 *                   （{@code checkInType = MINECRAFT_ONLINE}）且证据存在时才有值；
 *                   非 Minecraft 打卡、证据缺失或按本字段之前的口径落库的历史记录一律为 {@code 0}。
 *                   按时薪折算奖励的消费方据此计算；为 {@code 0} 表示本条没有可折算的时长
 */
public record PluginProjectCheckInReward(
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
        long effectiveMillis
) {

    public PluginProjectCheckInReward {
        acceptedBy = acceptedBy == null ? "" : acceptedBy;
        effectiveMillis = Math.max(effectiveMillis, 0L);
    }
}
