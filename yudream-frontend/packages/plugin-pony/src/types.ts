export interface PagedResult<T> {
  records: T[]
  total: number
}

export interface PonyOverview {
  gamesTotal: number
  gamesPlaying: number
  gamesWon: number
  playersTotal: number
}

export interface PonyGameView {
  id: string
  channelId: string
  size: number
  status: string
  horsesPlaced: number
  lives: number
  mistakes: number
  startedByQq: string
  winnerQq: string
  startedAt: number
  endedAt?: number | null
}

export interface PonyPlayerView {
  userId: string
  qq: string
  nickname: string
  played: number
  wins: number
  horsesPlaced: number
  currentStreak: number
  bestStreak: number
  updatedAt: number
}

export interface PonyMyStatsEmpty {
  empty: true
}

export type PonyMyStats = PonyPlayerView | PonyMyStatsEmpty
