export interface PagedResult<T> {
  records: T[]
  total: number
}

export interface WordleOverview {
  gamesTotal: number
  gamesPlaying: number
  gamesWon: number
  customWords: number
  playersTotal: number
}

export interface WordEntryView {
  id: string
  mode: string
  modeLabel: string
  word: string
  length: number
  hint: string
  enabled: boolean
  createdAt: number
  createdBy: string
}

export interface WordleGameView {
  id: string
  channelId: string
  mode: string
  modeLabel: string
  length: number
  hardMode: boolean
  status: string
  maxGuesses: number
  guessCount: number
  answer: string
  startedByQq: string
  winnerQq: string
  startedAt: number
  endedAt?: number | null
}

export interface WordlePlayerView {
  userId: string
  qq: string
  nickname: string
  englishPlayed: number
  englishWins: number
  idiomPlayed: number
  idiomWins: number
  currentStreak: number
  bestStreak: number
  winDistribution: Record<string, number>
  updatedAt: number
}

export interface WordleMyStatsEmpty {
  empty: true
}

export type WordleMyStats = WordlePlayerView | WordleMyStatsEmpty

export interface WordEntryForm {
  id: string
  mode: string
  word: string
  hint: string
  enabled: boolean
}

export interface WordFilters {
  mode: string
  keyword: string
}
