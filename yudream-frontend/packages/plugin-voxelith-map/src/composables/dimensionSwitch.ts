/**
 * 维度切换项：渲染页与展示页共用的「同一世界的其它维度」列表。
 *
 * <p>同一世界的各维度是**各自独立的地图**（各自渲染、各自瓦片、各自封面），所以「切换维度」本质是
 * 切到兄弟地图——服务端按世界分组返回这组地图，这里把它整理成选择器要的形态。</p>
 *
 * <p>为什么做成纯函数：列表顺序与去重规则是这段逻辑里唯一会出错的地方（顺序跳动＝按钮位置乱跳，
 * 去重写错＝同一维度出现两个入口），单独可测才能钉住。</p>
 */
import { dimensionLabel } from './dimensionLabels'

/** 服务端返回的维度地图条目（公开端是 MapSummary，管理端是 MapAdmin，取共同字段即可）。 */
export interface DimensionEntry {
  id: string
  name: string
  dimension: string
  /**
   * 是否有可浏览的产物。
   *
   * <p>公开端（MapSummary）直接给这个字段；管理端（MapAdmin）没有它，只有状态，
   * 因此 {@link buildDimensionChoices} 会按状态的 READY 自行判定。</p>
   */
  ready?: boolean
  /** 管理端的地图状态名（EMPTY / RENDERING / READY …）；公开端没有这个字段 */
  state?: string
}

export interface DimensionChoice {
  id: string
  name: string
  dimension: string
  ready: boolean
  state: string
  /** 当前正在看/在渲染的那张图 */
  current: boolean
  /** 选择器按钮文案（维度中文名） */
  label: string
}

/**
 * 整理维度切换项。
 *
 * <p>保持服务端给的顺序（主世界 → 下界 → 末地），不在这里重排：服务端已经按固定权重排过，
 * 前端再排一次只会多一处可能不一致的地方。</p>
 *
 * <p>按维度去重，且**优先保留当前这张图**：老数据按存档分组时，同一次导入的地图共享存档 id，
 * 理论上不会撞维度；真撞上了也必须让「当前维度」留在选择器里，否则会出现「按钮里没有你正在看的图」。</p>
 *
 * @param entries      服务端返回的维度地图（含当前地图）
 * @param currentMapId 当前地图 id
 */
export function buildDimensionChoices(
  entries: readonly DimensionEntry[],
  currentMapId: string,
): DimensionChoice[] {
  const byDimension = new Map<string, DimensionEntry>()
  for (const entry of entries) {
    if (!entry || !entry.id) continue
    const dimension = entry.dimension ?? ''
    const existing = byDimension.get(dimension)
    if (!existing) {
      byDimension.set(dimension, entry)
      continue
    }
    // 同一维度出现多张：当前这张优先，其余按服务端顺序保留先到的
    if (entry.id === currentMapId) {
      byDimension.set(dimension, entry)
    }
  }
  return [...byDimension.values()].map(entry => ({
    id: entry.id,
    name: entry.name,
    dimension: entry.dimension,
    ready: isReady(entry),
    state: entry.state ?? '',
    current: entry.id === currentMapId,
    label: dimensionLabel(entry.dimension),
  }))
}

/**
 * 该维度是否已有可浏览的产物。
 *
 * <p>公开端给了 {@code ready}；管理端的 DTO 没有这个字段（只有状态），按 READY 判定——
 * 少了这一步，渲染页的「未渲染」标记永远不会出现（{@code undefined !== false} 恒为真）。</p>
 *
 * <p>两边都没给（不该发生）时按**已就绪**处理：宁可少标一个「未渲染」，也不要凭空声称某个维度
 * 没有产物——后者会让人去做一次多余的渲染。</p>
 */
function isReady(entry: DimensionEntry): boolean {
  if (typeof entry.ready === 'boolean') {
    return entry.ready
  }
  if (entry.state) {
    return entry.state === 'READY'
  }
  return true
}

/** 是否需要显示维度切换器：只有一个维度时没什么可切的（渲染页会改用静态标签展示当前维度）。 */
export function needsDimensionSwitch(choices: readonly DimensionChoice[]): boolean {
  return choices.length > 1
}
