/**
 * 管理端列表的「按存档收纳」。
 *
 * <p>同一次导入或同一次上传建出的各维度地图本质上是**一个存档**，列表里摊成三行只会让人分不清
 * 哪几行是同一份存档。这里把它们收敛成一组：一行一个存档，维度收在行内切换。</p>
 *
 * <p>分组键与后端 {@code MapProject.worldGroup()} 同源；老项目没有这个字段时按存档文件 id、
 * 来源实例、自身 id 逐级退回——同一次上传的各维度共享同一个存档文件 id，同样能被收敛。</p>
 */

/** 分组只依赖这几个字段，便于直接吃管理端 DTO，也便于测试。 */
export interface MapArchiveRef {
  id: string
  name: string
  worldName?: string
  dimension: string
  worldGroup?: string | null
  worldFileId?: string
  sourceInstanceId?: string | null
}

export interface MapArchiveGroup<T extends MapArchiveRef> {
  /** 分组键：同组各维度一致，可作为行内选择的稳定标识 */
  key: string
  /** 存档名：取主世界那张的存档名（列表里显示的就是它） */
  archiveName: string
  /** 组内维度，按主世界 → 下界 → 末地排序 */
  dimensions: T[]
}

/** 维度固定顺序：主世界永远第一，认不出的维度排最后（保持稳定，不随接口顺序跳动）。 */
export function dimensionRank(dimension: string): number {
  switch (dimension) {
    case 'minecraft:overworld':
      return 0
    case 'minecraft:the_nether':
      return 1
    case 'minecraft:the_end':
      return 2
    default:
      return 3
  }
}

/** 分组键：世界分组 → 存档文件 → 来源实例 → 自身（逐级退回，老数据也能收敛）。 */
export function archiveKeyOf(map: MapArchiveRef): string {
  const group = map.worldGroup?.trim()
  if (group) return group
  if (map.worldFileId) return `archive:${map.worldFileId}`
  const instance = map.sourceInstanceId?.trim()
  if (instance) return `instance:${instance}`
  return `map:${map.id}`
}

/** 存档名：主世界的存档名最准；没有主世界就退回组内第一张的存档名/展示名。 */
function pickArchiveName<T extends MapArchiveRef>(dimensions: readonly T[]): string {
  const overworld = dimensions.find(item => item.dimension === 'minecraft:overworld')
  const preferred = overworld ?? dimensions[0]
  return (preferred?.worldName || preferred?.name || '').trim()
}

/** 把扁平的地图列表收敛成「一个存档一组」，组内按维度固定顺序排列。 */
export function groupMapsByArchive<T extends MapArchiveRef>(maps: readonly T[]): MapArchiveGroup<T>[] {
  const buckets = new Map<string, T[]>()
  for (const map of maps) {
    const key = archiveKeyOf(map)
    const bucket = buckets.get(key)
    if (bucket) {
      bucket.push(map)
    }
    else {
      buckets.set(key, [map])
    }
  }
  return [...buckets.entries()].map(([key, dimensions]) => ({
    key,
    archiveName: pickArchiveName(dimensions),
    dimensions: [...dimensions].sort((a, b) =>
      dimensionRank(a.dimension) - dimensionRank(b.dimension) || a.name.localeCompare(b.name)),
  }))
}
