import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PonyGameView, PonyMyStats, PonyOverview, PonyPlayerView } from '../types'
import { useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createPonyApi } from '../api/pony-api'

export function usePonyPlugin(sdk: YuDreamPluginSdk) {
  const api = createPonyApi(sdk)
  const toast = useFaToast()
  const loading = ref(false)
  const overview = ref<PonyOverview | null>(null)
  const games = ref<PonyGameView[]>([])
  const players = ref<PonyPlayerView[]>([])
  const myStats = ref<PonyMyStats | null>(null)
  const gamePager = reactive({ page: 1, size: 10, total: 0 })
  const playerPager = reactive({ page: 1, size: 10, total: 0 })
  const gameStatusFilter = ref('')

  const myStatsView = computed(() => (myStats.value && !('empty' in myStats.value) ? myStats.value : null))
  const myStatsEmpty = computed(() => !!myStats.value && 'empty' in myStats.value)
  const winRate = computed(() => (myStatsView.value && myStatsView.value.played > 0 ? `${Math.round((myStatsView.value.wins / myStatsView.value.played) * 100)}%` : '-'))

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

  function playerLabel(player: PonyPlayerView) {
    return player.nickname || `QQ ${player.qq}` || `用户 ${player.userId}`
  }

  function playerWinRate(player: PonyPlayerView) {
    if (!player.played) {
      return '-'
    }
    return `${Math.round((player.wins / player.played) * 100)}%`
  }

  function gameStatusLabel(status: string) {
    if (status === 'PLAYING') {
      return '进行中'
    }
    if (status === 'WON') {
      return '已归位'
    }
    if (status === 'LOST') {
      return '已结束'
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
      const result = await api.games(gameStatusFilter.value, gamePager.page, gamePager.size)
      games.value = result.records
      gamePager.total = result.total
    })
  }

  function applyGameFilter() {
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
    gameStatusFilter,
    formatTime,
    playerLabel,
    playerWinRate,
    gameStatusLabel,
    loadOverview,
    loadGames,
    applyGameFilter,
    loadPlayers,
    loadMyStats,
  })
}

export type PonyPluginModel = ReturnType<typeof usePonyPlugin>
