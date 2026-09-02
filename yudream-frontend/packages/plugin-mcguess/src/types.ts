export interface PagedResult<T> {
  records: T[]
  total: number
}

export interface McguessModeStats {
  total: number
  playing: number
  won: number
}

export interface McguessOverview {
  item: McguessModeStats
  mob: McguessModeStats
  recipe: McguessModeStats
  fog: McguessModeStats
  quiz: McguessModeStats
  bingo: McguessModeStats
  spot: McguessModeStats
  playerCount: number
  itemCount: number
  craftableCount: number
  guessTargetCount: number
  mobCount: number
  conditionCount: number
}

export interface McguessGameView {
  id: string
  mode: string
  modeZh: string
  connectionId: string
  platform: string
  channelId: string
  /** 猜物与迷雾/找茬为目标中文名；猜生物为填格进度（如 5/9）；快答为答题进度（如 3/5）；宾果为认领进度（如 12/25） */
  target: string
  status: string
  guessCount: number
  winnerQq: string
  startedAt: number
  endedAt?: number | null
}

export interface McguessPlayerView {
  userId: string
  qq: string
  nickname: string
  itemPlayed: number
  itemWins: number
  mobPlayed: number
  mobWins: number
  recipePlayed: number
  recipeWins: number
  fogPlayed: number
  fogWins: number
  quizPlayed: number
  quizWins: number
  bingoPlayed: number
  bingoWins: number
  spotPlayed: number
  spotWins: number
  totalGuesses: number
  /** 比大小历史最佳连胜 */
  holBest: number
  /** 图鉴已收集物品数 */
  collectionCount: number
  updatedAt: number
}

export interface McguessMyStatsEmpty {
  empty: true
}

export type McguessMyStats = McguessPlayerView | McguessMyStatsEmpty

export interface GameFilters {
  mode: string
  status: string
}

export interface McguessSettings {
  /** 钉住的 MC 数据版本（空串 = 跟随 mc-wiki 默认发布版本） */
  gameVersion: string
  /** 实际生效的数据版本；provider 不可用或从未发布时为 null */
  effectiveVersion: string | null
  /** mc-wiki 默认发布版本（最新发布） */
  defaultVersion: string | null
  /** mc-wiki 全部已发布版本（版本选择器 options） */
  publishedVersions: string[]
  /** 钉住的版本已被取消发布、当前降级回默认 */
  pinnedMissing: boolean
  /** mc-wiki provider 当前是否可用 */
  providerAvailable: boolean
}
