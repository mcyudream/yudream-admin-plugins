/**
 * 渲染设置表单的「改了什么」摘要。
 *
 * <p>自动保存此前只显示一句「已自动保存」，用户既不知道保存了哪一项、也不知道是什么时候保存的
 * （改了好几个字段、或隔了很久才看到提示时尤其困惑）。这里把「表单快照」拍平成一个 路径→值 的映射，
 * 再对比出变化的字段名，交给界面显示成「已自动保存：名称、minY · 14:32:05」。</p>
 *
 * <p>刻意做成纯函数：对比逻辑一旦写错，提示就会撒谎（说保存了没改的东西，或漏掉真改了的），
 * 而这正是「提示要给出具体操作」的意义所在。</p>
 */

/** 字段 → 展示名（顺序即展示顺序）。未列出的字段按路径原名兜底。 */
const FIELD_LABELS: Record<string, string> = {
  'name': '名称',
  'dimension': '维度',
  'settings.minY': '最低渲染高度',
  'settings.mcVersion': '游戏版本',
  'settings.loaderVersion': '加载器版本',
  'settings.maxChunks': '区块上限',
  'settings.batchChunks': '批次大小',
  'settings.sampleChunks': '采样步长',
  'settings.maxLevel': 'LOD 层级',
  'settings.skipHarvest': '跳过模型采集',
  'settings.lodAtlas': 'LOD 图集',
  'settings.meshopt': '网格压缩',
  'regionMinX': '范围 X 起',
  'regionMinZ': '范围 Z 起',
  'regionMaxX': '范围 X 止',
  'regionMaxZ': '范围 Z 止',
  'clearRegion': '清除渲染范围',
  'mods': '模组',
}

/** 把嵌套表单拍平成 { 路径: 标量 }；对象/数组按 JSON 比较整体变化。 */
export function flattenForm(input: unknown, prefix = '', out: Record<string, string> = {}): Record<string, string> {
  if (input === null || input === undefined) {
    out[prefix] = ''
    return out
  }
  if (typeof input !== 'object') {
    out[prefix] = String(input)
    return out
  }
  for (const [key, value] of Object.entries(input as Record<string, unknown>)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (value !== null && typeof value === 'object') {
      flattenForm(value, path, out)
    }
    else {
      out[path] = value === null || value === undefined ? '' : String(value)
    }
  }
  return out
}

/**
 * 对比两份快照，返回变化字段的展示名（保持 {@link FIELD_LABELS} 的顺序，未识别字段按路径顺序追加）。
 *
 * @param before 上一次「已落盘」的快照
 * @param after  当前表单
 */
export function describeFormChanges(before: unknown, after: unknown): string[] {
  const from = flattenForm(before)
  const to = flattenForm(after)
  const changed = new Set<string>()
  for (const [path, value] of Object.entries(to)) {
    if (from[path] !== value) {
      changed.add(path)
    }
  }
  // 被删除的字段（例如清空渲染范围后 region 字段消失）也算变化
  for (const path of Object.keys(from)) {
    if (!(path in to)) {
      changed.add(path)
    }
  }
  const ordered: string[] = []
  for (const path of Object.keys(FIELD_LABELS)) {
    if (changed.has(path)) {
      ordered.push(FIELD_LABELS[path]!)
      changed.delete(path)
    }
  }
  for (const path of Array.from(changed).sort()) {
    ordered.push(path)
  }
  return ordered
}

/** 当前时刻的 HH:mm:ss（保存时间提示用，与后端时间无关，只表示「刚刚」）。 */
export function clockTime(date: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
