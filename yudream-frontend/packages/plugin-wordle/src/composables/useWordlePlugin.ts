import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { WordEntryForm, WordEntryView, WordFilters, WordleGameView, WordleMyStats, WordleOverview, WordlePlayerView } from '../types'
import { useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createWordleApi } from '../api/wordle-api'

export function useWordlePlugin(sdk: YuDreamPluginSdk) {
  const api = createWordleApi(sdk)
  const toast = useFaToast()
  const loading = ref(false)
  const saving = ref(false)
  const overview = ref<WordleOverview | null>(null)
  const words = ref<WordEntryView[]>([])
  const games = ref<WordleGameView[]>([])
  const players = ref<WordlePlayerView[]>([])
  const myStats = ref<WordleMyStats | null>(null)
  const wordPager = reactive({ page: 1, size: 10, total: 0 })
  const gamePager = reactive({ page: 1, size: 10, total: 0 })
  const playerPager = reactive({ page: 1, size: 10, total: 0 })
  const wordFilters = reactive<WordFilters>({ mode: '', keyword: '' })
  const gameStatusFilter = ref('')
  const wordForm = reactive<WordEntryForm>({ id: '', mode: 'ENGLISH', word: '', hint: '', enabled: true })
  const editingWord = ref(false)

  const myStatsView = computed(() => (myStats.value && !('empty' in myStats.value) ? myStats.value : null))
  const myStatsEmpty = computed(() => !!myStats.value && 'empty' in myStats.value)
  const totalPlayed = computed(() => (myStatsView.value ? myStatsView.value.englishPlayed + myStatsView.value.idiomPlayed : 0))
  const totalWins = computed(() => (myStatsView.value ? myStatsView.value.englishWins + myStatsView.value.idiomWins : 0))
  const winRate = computed(() => (totalPlayed.value > 0 ? `${Math.round((totalWins.value / totalPlayed.value) * 100)}%` : '-'))

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

  function playerLabel(player: WordlePlayerView) {
    return player.nickname || `QQ ${player.qq}` || `用户 ${player.userId}`
  }

  function playerWinRate(player: WordlePlayerView) {
    const played = player.englishPlayed + player.idiomPlayed
    if (!played) {
      return '-'
    }
    return `${Math.round(((player.englishWins + player.idiomWins) / played) * 100)}%`
  }

  function gameStatusLabel(status: string) {
    if (status === 'PLAYING') {
      return '进行中'
    }
    if (status === 'WON') {
      return '已猜中'
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

  async function loadWords() {
    await run(async () => {
      const result = await api.words(wordFilters.mode, wordFilters.keyword.trim(), wordPager.page, wordPager.size)
      words.value = result.records
      wordPager.total = result.total
      const maxPage = Math.max(1, Math.ceil(result.total / wordPager.size))
      if (wordPager.page > maxPage) {
        wordPager.page = maxPage
        const next = await api.words(wordFilters.mode, wordFilters.keyword.trim(), wordPager.page, wordPager.size)
        words.value = next.records
        wordPager.total = next.total
      }
    })
  }

  function applyWordFilters() {
    wordPager.page = 1
    return loadWords()
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

  function newWord() {
    editingWord.value = false
    wordForm.id = ''
    wordForm.mode = 'ENGLISH'
    wordForm.word = ''
    wordForm.hint = ''
    wordForm.enabled = true
  }

  function editWord(entry: WordEntryView) {
    editingWord.value = true
    wordForm.id = entry.id
    wordForm.mode = entry.mode
    wordForm.word = entry.word
    wordForm.hint = entry.hint || ''
    wordForm.enabled = entry.enabled
  }

  async function saveWord() {
    if (!editingWord.value && !wordForm.word.trim()) {
      toast.warning('请填写词语')
      return false
    }
    saving.value = true
    try {
      if (editingWord.value) {
        await api.updateWord(wordForm)
        toast.success('词条已更新')
      }
      else {
        await api.createWord({ mode: wordForm.mode, word: wordForm.word.trim(), hint: wordForm.hint.trim() || undefined })
        toast.success('词条已创建')
      }
      await loadWords()
      return true
    }
    catch (error) {
      toast.error(errorMessage(error))
      return false
    }
    finally {
      saving.value = false
    }
  }

  async function toggleWord(entry: WordEntryView) {
    try {
      await api.updateWord({ id: entry.id, mode: entry.mode, word: entry.word, hint: entry.hint || '', enabled: !entry.enabled })
      toast.success(entry.enabled ? '词条已停用' : '词条已启用')
      await loadWords()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function removeWord(entry: WordEntryView) {
    try {
      await api.deleteWord(entry.id)
      toast.success('词条已删除')
      if (words.value.length === 1 && wordPager.page > 1) {
        wordPager.page -= 1
      }
      await loadWords()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  return reactive({
    loading,
    saving,
    overview,
    words,
    games,
    players,
    myStats,
    myStatsView,
    myStatsEmpty,
    totalPlayed,
    totalWins,
    winRate,
    wordPager,
    gamePager,
    playerPager,
    wordFilters,
    gameStatusFilter,
    wordForm,
    editingWord,
    formatTime,
    playerLabel,
    playerWinRate,
    gameStatusLabel,
    loadOverview,
    loadWords,
    applyWordFilters,
    loadGames,
    applyGameFilter,
    loadPlayers,
    loadMyStats,
    newWord,
    editWord,
    saveWord,
    toggleWord,
    removeWord,
  })
}

export type WordlePluginModel = ReturnType<typeof useWordlePlugin>
