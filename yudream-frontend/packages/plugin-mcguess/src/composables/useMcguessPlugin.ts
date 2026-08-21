import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { GameFilters, McguessGameView, McguessMyStats, McguessOverview, McguessPlayerView } from '../types'
import { useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createMcguessApi } from '../api/mcguess-api'

export function useMcguessPlugin(sdk: YuDreamPluginSdk) {
  const api = createMcguessApi(sdk)
  const toast = useFaToast()
  const loading = ref(false)
  const overview = ref<McguessOverview | null>(null)
  const games = ref<McguessGameView[]>([])
  const players = ref<McguessPlayerView[]>([])
  const myStats = ref<McguessMyStats | null>(null)
  const gamePager = reactive({ page: 1, size: 10, total: 0 })
  const playerPager = reactive({ page: 1, size: 10, total: 0 })
  const gameFilters = reactive<GameFilters>({ mode: '', status: '' })

  const myStatsView = computed(() => (myStats.value && !('empty' in myStats.value) ? myStats.value : null))
  const myStatsEmpty = computed(() => !!myStats.value && 'empty' in myStats.value)
  const winRate = computed(() => (myStatsView.value ? winRateOf(myStatsView.value) : '-'))

  function totalPlayed(player: McguessPlayerView) {
    return player.itemPlayed + player.mobPlayed + player.recipePlayed
      + player.fogPlayed + player.quizPlayed + player.bingoPlayed + player.spotPlayed
  }

  function totalWins(player: McguessPlayerView) {
    return player.itemWins + player.mobWins + player.recipeWins
      + player.fogWins + player.quizWins + player.bingoWins + player.spotWins
  }

  function winRateOf(player: McguessPlayerView) {
    const played = totalPlayed(player)
    if (!played) {
      return '-'
    }
    return `${Math.round((totalWins(player) / played) * 100)}%`
  }

  function errorMessage(error: unknown) {
    if (error && typeof error === 'object') {
      const data = (error as { response?: { data?: { message?: string } }, message?: string })
      return data.response?.data?.message || data.message || '请求失败'
    }
    return '请求失败'
  }

  function formatTime(value?: number | null) {
    if (!value) {
      return '-'
    }
    return new Date(value).toLocaleString('zh-CN', { hour12: false })
  }

  function playerLabel(player: McguessPlayerView) {
    return player.nickname || (player.qq ? `QQ ${player.qq}` : `用户 ${player.userId}`)
  }

  function gameStatusLabel(status: string) {
    if (status === 'PLAYING') {
      return '进行中'
    }
    if (status === 'WON') {
      return '已获胜'
    }
    if (status === 'LOST') {
      return '已揭晓'
    }
    return status || '-'
  }

  async function run(task: () => Promise<void>, silent = false) {
    loading.value = true
    try {
      await task()
    }
    catch (error) {
      if (!silent) {
        toast.error(errorMessage(error))
      }
    }
    finally {
      loading.value = false
    }
  }

  async function loadOverview() {
    await run(async () => { overview.value = await api.overview() })
  }

  async function loadGames() {
    await run(async () => {
      const result = await api.games(gameFilters.mode, gameFilters.status, gamePager.page, gamePager.size)
      games.value = result.records
      gamePager.total = result.total
      const maxPage = Math.max(1, Math.ceil(result.total / gamePager.size))
      if (gamePager.page > maxPage) {
        gamePager.page = maxPage
        const next = await api.games(gameFilters.mode, gameFilters.status, gamePager.page, gamePager.size)
        games.value = next.records
        gamePager.total = next.total
      }
    })
  }

  function applyGameFilters() {
    gamePager.page = 1
    return loadGames()
  }

  async function loadPlayers() {
    await run(async () => {
      const result = await api.players(playerPager.page, playerPager.size)
      players.value = result.records
      playerPager.total = result.total
    })
  }

  async function loadMyStats() {
    await run(async () => { myStats.value = await api.myStats() })
  }

  return reactive({
    loading,
    overview,
    games,
    players,
    myStats,
    myStatsView,
    myStatsEmpty,
    winRate,
    gamePager,
    playerPager,
    gameFilters,
    formatTime,
    playerLabel,
    totalPlayed,
    totalWins,
    winRateOf,
    gameStatusLabel,
    loadOverview,
    loadGames,
    applyGameFilters,
    loadPlayers,
    loadMyStats,
  })
}

export type McguessPluginModel = ReturnType<typeof useMcguessPlugin>
