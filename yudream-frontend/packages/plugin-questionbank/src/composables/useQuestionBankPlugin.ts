import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AiProviderOption, AnswerPayload, CategoryView, ComposeOptions, ComposeRecordView, PaperPayload, PaperPrintView, PaperView, PluginSettings, PracticeFilter, PracticeMeta, QuestionPayload, QuestionView, QuizRankEntry, SessionSummary, SessionView, TagView } from '../types'
import { useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createQuestionBankApi, saveBlob } from '../api/questionbank-api'
import { errorMessage } from './utils'

export function useQuestionBankPlugin(sdk: YuDreamPluginSdk) {
  const api = createQuestionBankApi(sdk)
  const toast = useFaToast()

  const loading = ref(false)

  // ---------- 用户端：抽题 ----------
  const meta = ref<PracticeMeta | null>(null)
  const practiceFilter = reactive<{ categoryId: string, tags: string[], types: string[], difficulties: number[], count: number }>({
    categoryId: '',
    tags: [],
    types: [],
    difficulties: [],
    count: 10,
  })
  const poolCount = ref<number | null>(null)
  const counting = ref(false)

  // ---------- 用户端：作答/成绩 ----------
  const session = ref<SessionView | null>(null)
  const sessionLoading = ref(false)
  const submitting = ref(false)

  const myRecords = ref<SessionSummary[]>([])
  const myRecordsPager = reactive({ page: 1, size: 10, total: 0 })

  // ---------- 管理端：题目 ----------
  const adminQuestions = ref<QuestionView[]>([])
  const adminPager = reactive({ page: 1, size: 20, total: 0 })
  const adminFilters = reactive({ keyword: '', categoryId: '', tag: '', type: '', difficulty: undefined as number | undefined, status: '' })
  const adminTags = ref<TagView[]>([])

  // ---------- 管理端：分类/记录 ----------
  const adminCategories = ref<CategoryView[]>([])
  const adminRecords = ref<SessionSummary[]>([])
  const adminRecordsPager = reactive({ page: 1, size: 20, total: 0 })
  const adminRecordsFilters = reactive({ keyword: '', status: '' })

  // ---------- 用户端：题单 ----------
  const papers = ref<PaperView[]>([])
  const papersLoading = ref(false)

  // ---------- 管理端：题单/打印 ----------
  const adminPapers = ref<PaperView[]>([])
  const adminPapersPager = reactive({ page: 1, size: 20, total: 0 })
  const adminPapersFilters = reactive({ keyword: '', status: '' })
  const printData = ref<PaperPrintView | null>(null)
  const printLoading = ref(false)

  // ---------- 管理端：简答审核/设置 ----------
  const reviewList = ref<SessionSummary[]>([])
  const reviewPager = reactive({ page: 1, size: 20, total: 0 })
  const reviewKeyword = ref('')
  const reviewSession = ref<SessionView | null>(null)
  const reviewSessionLoading = ref(false)
  const settings = ref<PluginSettings | null>(null)
  const settingsSaving = ref(false)

  // ---------- 组卷中心（COMPOSE 权限） ----------
  const composeQuestions = ref<QuestionView[]>([])
  const composePager = reactive({ page: 1, size: 20, total: 0 })
  const composeFilters = reactive({ keyword: '', categoryId: '', tag: '', type: '' })
  const composeOptions = ref<ComposeOptions>({ categories: [], tags: [] })
  const composeSelected = ref<QuestionView[]>([])
  const composeDrawing = ref(false)
  const composeExporting = ref(false)
  const composeRecords = ref<ComposeRecordView[]>([])
  const composeRecordsPager = reactive({ page: 1, size: 10, total: 0 })
  const composeRecordsFilters = reactive({ keyword: '', all: false, ownerId: '' })
  const composeRecordsLoading = ref(false)
  const composeRecordSaving = ref(false)
  const composeRecordDetail = ref<ComposeRecordView | null>(null)
  const composeRecordDetailLoading = ref(false)
  /** 是否具备题库管理权限：管理端组卷记录（/admin/**）据此开放「我的/全部」切换与创建者列；个人练习数据不经此开关。 */
  const questionbankManager = computed(() => (sdk.account?.permissions ?? []).includes('plugin:questionbank:manage'))

  function clampPage(pager: { page: number, size: number, total: number }, count: number) {
    if (count <= 0 && pager.page > 1) {
      pager.page -= 1
    }
  }

  // ---------- 用户端：抽题 ----------

  async function loadMeta() {
    try {
      meta.value = await api.meta()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  function buildFilter(): PracticeFilter {
    return {
      categoryId: practiceFilter.categoryId || undefined,
      tags: practiceFilter.tags.length ? [...practiceFilter.tags] : undefined,
      types: practiceFilter.types.length ? [...practiceFilter.types] : undefined,
      difficulties: practiceFilter.difficulties.length ? [...practiceFilter.difficulties] : undefined,
    }
  }

  async function refreshPoolCount() {
    // 自由刷题已关闭时后端会拒绝统计请求，直接保持关闭态而不报错
    if (meta.value?.practiceEnabled === false) {
      poolCount.value = null
      return
    }
    counting.value = true
    try {
      const result = await api.practiceCount(buildFilter())
      poolCount.value = Number(result.count)
    }
    catch (error) {
      poolCount.value = null
      toast.error(errorMessage(error))
    }
    finally {
      counting.value = false
    }
  }

  /** 抽题成功返回新练习；失败返回 null。 */
  async function startPractice(): Promise<SessionView | null> {
    loading.value = true
    try {
      const created = await api.createSession({ ...buildFilter(), count: practiceFilter.count })
      session.value = created
      return created
    }
    catch (error) {
      toast.error(errorMessage(error))
      return null
    }
    finally {
      loading.value = false
    }
  }

  // ---------- 用户端：作答/成绩 ----------

  async function loadSession(sessionId: string) {
    sessionLoading.value = true
    try {
      session.value = await api.mySession(sessionId)
    }
    catch (error) {
      session.value = null
      toast.error(errorMessage(error))
    }
    finally {
      sessionLoading.value = false
    }
  }

  async function submitSession(sessionId: string, answers: AnswerPayload[]) {
    submitting.value = true
    try {
      session.value = await api.submitSession(sessionId, answers)
      toast.success('已提交，快来看看成绩吧')
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
    finally {
      submitting.value = false
    }
  }

  async function selfMark(sessionId: string, questionId: string, correct: boolean) {
    try {
      session.value = await api.selfMark(sessionId, questionId, correct)
      toast.success(correct ? '已标记为答对' : '已标记为答错')
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function loadMyRecords() {
    loading.value = true
    try {
      const page = await api.mySessions(myRecordsPager.page, myRecordsPager.size)
      myRecords.value = page.records
      myRecordsPager.total = Number(page.total)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  async function removeMyRecord(row: SessionSummary) {
    try {
      await api.deleteSession(row.id)
      toast.success('练习记录已删除')
      clampPage(myRecordsPager, myRecords.value.length - 1)
      await loadMyRecords()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  // ---------- 管理端：题目 ----------

  async function loadAdminQuestions() {
    loading.value = true
    try {
      const page = await api.adminQuestions(adminFilters.keyword, adminFilters.categoryId, adminFilters.tag,
        adminFilters.type, adminFilters.difficulty, adminFilters.status, adminPager.page, adminPager.size)
      adminQuestions.value = page.records
      adminPager.total = Number(page.total)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  function applyAdminFilters() {
    adminPager.page = 1
    void loadAdminQuestions()
  }

  async function loadAdminTags() {
    try {
      adminTags.value = await api.adminTags()
    }
    catch {
      adminTags.value = []
    }
  }

  async function removeQuestion(row: QuestionView) {
    try {
      await api.deleteQuestion(row.id)
      toast.success('题目已删除')
      clampPage(adminPager, adminQuestions.value.length - 1)
      await loadAdminQuestions()
      void loadAdminTags()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function batchRemoveQuestions(ids: string[]) {
    try {
      const result = await api.batchDeleteQuestions(ids)
      toast.success(`已删除 ${result.deleted} 道题`)
      clampPage(adminPager, adminQuestions.value.length - ids.length)
      await loadAdminQuestions()
      void loadAdminTags()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  // ---------- 管理端：分类 ----------

  async function loadAdminCategories() {
    try {
      adminCategories.value = await api.adminCategories()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function saveCategory(categoryId: string | null, name: string, sort: number) {
    try {
      if (categoryId) {
        await api.updateCategory(categoryId, { name, sort })
      }
      else {
        await api.createCategory({ name, sort })
      }
      toast.success('分类已保存')
      await loadAdminCategories()
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  async function removeCategory(row: CategoryView) {
    try {
      await api.deleteCategory(row.id)
      toast.success(`已删除分类「${row.name}」`)
      await loadAdminCategories()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function mergeCategories(targetId: string, sourceIds: string[]) {
    try {
      const result = await api.mergeCategories({ targetId, sourceIds })
      toast.success(`已合并分类，迁移 ${result.moved} 道题`)
      await loadAdminCategories()
    }
    catch (error) {
      toast.error(errorMessage(error))
      throw error
    }
  }

  // ---------- 用户端：QQ 抢答排行榜 ----------

  const quizLeaderboard = ref<QuizRankEntry[]>([])
  const quizLeaderboardLoading = ref(false)

  async function loadQuizLeaderboard() {
    quizLeaderboardLoading.value = true
    try {
      quizLeaderboard.value = await api.quizLeaderboard()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      quizLeaderboardLoading.value = false
    }
  }

  // ---------- 管理端：练习记录 ----------

  async function loadAdminRecords() {
    loading.value = true
    try {
      const page = await api.adminRecords(adminRecordsFilters.keyword, adminRecordsFilters.status,
        adminRecordsPager.page, adminRecordsPager.size)
      adminRecords.value = page.records
      adminRecordsPager.total = Number(page.total)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  function applyAdminRecordsFilters() {
    adminRecordsPager.page = 1
    void loadAdminRecords()
  }

  async function removeAdminRecord(row: SessionSummary) {
    try {
      await api.deleteRecord(row.id)
      toast.success('记录已删除')
      clampPage(adminRecordsPager, adminRecords.value.length - 1)
      await loadAdminRecords()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  /** Markdown 编辑器图片上传：落库为 /api/files/... 相对路径。 */
  async function uploadMarkdownImage(file: File) {
    if (!file.type.startsWith('image/')) {
      throw new Error('请选择图片文件')
    }
    const uploaded = await sdk.files.uploadImage(file, { module: 'questionbank', publicAccess: true })
    return normalizeUploadUrl(uploaded)
  }

  // ---------- 用户端：题单 ----------

  async function loadMyPapers() {
    papersLoading.value = true
    try {
      papers.value = await api.myPapers()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      papersLoading.value = false
    }
  }

  /** 题单作答：现场抽题创建会话（RULE 模式每次随机）；失败返回 null。 */
  async function attemptPaper(paperId: string): Promise<SessionView | null> {
    loading.value = true
    try {
      const created = await api.attemptPaper(paperId)
      session.value = created
      return created
    }
    catch (error) {
      toast.error(errorMessage(error))
      return null
    }
    finally {
      loading.value = false
    }
  }

  // ---------- 管理端：题单/打印 ----------

  async function loadAdminPapers() {
    loading.value = true
    try {
      const page = await api.adminPapers(adminPapersFilters.keyword, adminPapersFilters.status,
        adminPapersPager.page, adminPapersPager.size)
      adminPapers.value = page.records
      adminPapersPager.total = Number(page.total)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  function applyAdminPapersFilters() {
    adminPapersPager.page = 1
    void loadAdminPapers()
  }

  async function savePaper(paperId: string | null, payload: PaperPayload): Promise<PaperView | null> {
    loading.value = true
    try {
      const saved = paperId
        ? await api.updatePaper(paperId, payload)
        : await api.createPaper(payload)
      toast.success('题单已保存')
      return saved
    }
    catch (error) {
      toast.error(errorMessage(error))
      return null
    }
    finally {
      loading.value = false
    }
  }

  async function removePaper(row: PaperView) {
    try {
      await api.deletePaper(row.id)
      toast.success(`题单「${row.name}」已删除`)
      clampPage(adminPapersPager, adminPapers.value.length - 1)
      await loadAdminPapers()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  /** 打印数据：每次调用后端现场重新抽题（RULE），刷新即换题。 */
  async function loadPrint(paperId: string) {
    printLoading.value = true
    try {
      printData.value = await api.printPaper(paperId)
    }
    catch (error) {
      printData.value = null
      toast.error(errorMessage(error))
    }
    finally {
      printLoading.value = false
    }
  }

  // ---------- 管理端：简答审核/设置 ----------

  async function loadReviewQueue() {
    loading.value = true
    try {
      const page = await api.reviewQueue(reviewKeyword.value, reviewPager.page, reviewPager.size)
      reviewList.value = page.records
      reviewPager.total = Number(page.total)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  function applyReviewFilters() {
    reviewPager.page = 1
    void loadReviewQueue()
  }

  async function loadReviewSession(sessionId: string) {
    reviewSessionLoading.value = true
    try {
      reviewSession.value = await api.reviewSession(sessionId)
    }
    catch (error) {
      reviewSession.value = null
      toast.error(errorMessage(error))
    }
    finally {
      reviewSessionLoading.value = false
    }
  }

  /** 审核判分成功返回最新会话；失败返回 null。 */
  async function reviewMark(sessionId: string, questionId: string, correct: boolean): Promise<SessionView | null> {
    try {
      const updated = await api.reviewMark(sessionId, questionId, correct)
      reviewSession.value = updated
      toast.success(correct ? '已判定为答对' : '已判定为答错')
      return updated
    }
    catch (error) {
      toast.error(errorMessage(error))
      return null
    }
  }

  const aiProviders = ref<AiProviderOption[]>([])

  /** 平台 AI 供应商/模型选项；平台未配置 AI 时为空，设置页据此降级提示。 */
  async function loadAiProviders() {
    try {
      aiProviders.value = await api.aiOptions()
    }
    catch {
      aiProviders.value = []
    }
  }

  async function loadSettings() {
    try {
      settings.value = await api.adminSettings()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function saveSettings(payload: Partial<PluginSettings>) {
    settingsSaving.value = true
    try {
      settings.value = await api.updateSettings(payload)
      toast.success('设置已保存')
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      settingsSaving.value = false
    }
  }

  // ---------- 组卷中心（COMPOSE 权限） ----------

  async function loadComposeQuestions() {
    loading.value = true
    try {
      const page = await api.composeQuestions(composeFilters.keyword, composeFilters.categoryId,
        composeFilters.tag, composeFilters.type, composePager.page, composePager.size)
      composeQuestions.value = page.records
      composePager.total = Number(page.total)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      loading.value = false
    }
  }

  function applyComposeFilters() {
    composePager.page = 1
    void loadComposeQuestions()
  }

  async function loadComposeOptions() {
    try {
      composeOptions.value = await api.composeOptions()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  function addComposeQuestion(row: QuestionView) {
    if (composeSelected.value.some(item => item.id === row.id)) {
      toast.warning('这道题已在组卷列表中')
      return
    }
    if (composeSelected.value.length >= 200) {
      toast.warning('单次组卷最多 200 道题')
      return
    }
    composeSelected.value = [...composeSelected.value, row]
  }

  function removeComposeQuestion(id: string) {
    composeSelected.value = composeSelected.value.filter(item => item.id !== id)
  }

  /** 随机抽题：抽到的题按序追加到组卷列表（自动去重），返回新增数量。 */
  async function drawCompose(rule: { categoryId?: string, tags?: string[], types?: string[], difficulties?: number[], count: number }) {
    composeDrawing.value = true
    try {
      const result = await api.composeDraw(rule)
      const existing = new Set(composeSelected.value.map(item => item.id))
      const fresh = result.questions.filter(item => !existing.has(item.id))
      if (!result.questions.length) {
        toast.warning('符合条件的题目不足，一道都没有抽到')
        return
      }
      composeSelected.value = [...composeSelected.value, ...fresh].slice(0, 200)
      toast.success(`抽到 ${result.questions.length} 道题，新增 ${fresh.length} 道（自动去重）`)
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      composeDrawing.value = false
    }
  }

  /** 导出 Word：按组卷列表顺序出卷；平台未开启 Word 模板能力时后端会返回明确错误。 */
  async function exportComposeWord(title: string, description: string, withAnswers: boolean) {
    if (!composeSelected.value.length) {
      toast.warning('请先手动选题或随机抽题')
      return
    }
    composeExporting.value = true
    try {
      const blob = await api.composeExportWord({
        title: title.trim() || '试题卷',
        description: description.trim() || undefined,
        questionIds: composeSelected.value.map(item => item.id),
        withAnswers,
      })
      saveBlob(blob.data, blob.headers, `${title.trim() || '试题卷'}.docx`)
      toast.success('Word 试卷已开始下载')
    }
    catch (error) {
      toast.error(errorMessage(error, '导出失败'))
    }
    finally {
      composeExporting.value = false
    }
  }

  /** 保存当前组卷列表为记录（只存题目引用，实时解析）。 */
  async function saveComposeRecord(title: string, description: string, withAnswers: boolean) {
    if (!composeSelected.value.length) {
      toast.warning('请先手动选题或随机抽题')
      return false
    }
    composeRecordSaving.value = true
    try {
      await api.saveComposeRecord({
        title: title.trim() || '未命名组卷',
        description: description.trim() || undefined,
        questionIds: composeSelected.value.map(item => item.id),
        withAnswers,
      })
      toast.success('组卷记录已保存')
      return true
    }
    catch (error) {
      toast.error(errorMessage(error, '保存失败'))
      return false
    }
    finally {
      composeRecordSaving.value = false
    }
  }

  async function loadComposeRecords() {
    composeRecordsLoading.value = true
    try {
      const page = await api.composeRecords(composeRecordsFilters.keyword, composeRecordsFilters.all,
        composeRecordsFilters.ownerId, composeRecordsPager.page, composeRecordsPager.size)
      composeRecords.value = page.records
      composeRecordsPager.total = Number(page.total)
      if (composeRecordsPager.page > 1 && !page.records.length) {
        composeRecordsPager.page = 1
        await loadComposeRecords()
      }
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
    finally {
      composeRecordsLoading.value = false
    }
  }

  function applyComposeRecordsFilters() {
    composeRecordsPager.page = 1
    return loadComposeRecords()
  }

  /** 记录详情（含实时解析的题目与解析），用于在线查看抽屉。 */
  async function loadComposeRecordDetail(recordId: string) {
    composeRecordDetailLoading.value = true
    try {
      composeRecordDetail.value = await api.composeRecord(recordId)
    }
    catch (error) {
      toast.error(errorMessage(error))
      composeRecordDetail.value = null
    }
    finally {
      composeRecordDetailLoading.value = false
    }
  }

  async function removeComposeRecord(recordId: string) {
    try {
      await api.deleteComposeRecord(recordId)
      toast.success('记录已删除')
      await loadComposeRecords()
    }
    catch (error) {
      toast.error(errorMessage(error))
    }
  }

  async function exportComposeRecordWord(record: ComposeRecordView) {
    try {
      const blob = await api.exportComposeRecordWord(record.id)
      saveBlob(blob.data, blob.headers, `${record.title}.docx`)
      toast.success('Word 试卷已开始下载')
    }
    catch (error) {
      toast.error(errorMessage(error, '导出失败'))
    }
  }

  /** 分享/取消分享；分享成功返回完整公开链接（已尝试写入剪贴板）。 */
  async function shareComposeRecord(record: ComposeRecordView) {
    try {
      const result = record.shared
        ? (await api.unshareComposeRecord(record.id), null)
        : await api.shareComposeRecord(record.id)
      if (result?.token) {
        const link = `${window.location.origin}/platform/plugins/questionbank/shared?token=${result.token}`
        await copyText(link)
        toast.success('分享链接已生成并复制到剪贴板')
        return link
      }
      toast.success('已取消分享')
      return null
    }
    catch (error) {
      toast.error(errorMessage(error))
      return null
    }
    finally {
      await loadComposeRecords()
    }
  }

  /** 复制文本：优先 Clipboard API，非安全上下文回退 execCommand。 */
  async function copyText(text: string) {
    try {
      await navigator.clipboard.writeText(text)
    }
    catch {
      const area = document.createElement('textarea')
      area.value = text
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      document.execCommand('copy')
      area.remove()
    }
  }

  const model = reactive({
    api,
    loading,
    meta,
    practiceFilter,
    poolCount,
    counting,
    session,
    sessionLoading,
    submitting,
    myRecords,
    myRecordsPager,
    adminQuestions,
    adminPager,
    adminFilters,
    adminTags,
    adminCategories,
    adminRecords,
    adminRecordsPager,
    adminRecordsFilters,
    papers,
    papersLoading,
    adminPapers,
    adminPapersPager,
    adminPapersFilters,
    printData,
    printLoading,
    reviewList,
    reviewPager,
    reviewKeyword,
    reviewSession,
    reviewSessionLoading,
    settings,
    settingsSaving,
    aiProviders,
    composeQuestions,
    composePager,
    composeFilters,
    composeOptions,
    composeSelected,
    composeDrawing,
    composeExporting,
    composeRecords,
    composeRecordsPager,
    composeRecordsFilters,
    composeRecordsLoading,
    composeRecordSaving,
    composeRecordDetail,
    composeRecordDetailLoading,
    questionbankManager,
    loadMeta,
    refreshPoolCount,
    startPractice,
    loadSession,
    submitSession,
    selfMark,
    loadMyRecords,
    removeMyRecord,
    loadAdminQuestions,
    applyAdminFilters,
    loadAdminTags,
    removeQuestion,
    batchRemoveQuestions,
    loadAdminCategories,
    saveCategory,
    removeCategory,
    mergeCategories,
    quizLeaderboard,
    quizLeaderboardLoading,
    loadQuizLeaderboard,
    loadAdminRecords,
    applyAdminRecordsFilters,
    removeAdminRecord,
    uploadMarkdownImage,
    loadMyPapers,
    attemptPaper,
    loadAdminPapers,
    applyAdminPapersFilters,
    savePaper,
    removePaper,
    loadPrint,
    loadReviewQueue,
    applyReviewFilters,
    loadReviewSession,
    reviewMark,
    loadSettings,
    saveSettings,
    loadAiProviders,
    loadComposeQuestions,
    applyComposeFilters,
    loadComposeOptions,
    addComposeQuestion,
    removeComposeQuestion,
    drawCompose,
    exportComposeWord,
    saveComposeRecord,
    loadComposeRecords,
    applyComposeRecordsFilters,
    loadComposeRecordDetail,
    removeComposeRecord,
    exportComposeRecordWord,
    shareComposeRecord,
  })
  // sdk 保持原引用不进 reactive 代理，避免宿主注入对象被包装
  return Object.assign(model, { sdk })
}

function normalizeUploadUrl(uploaded: { assetUrl?: string, url?: string }) {
  const raw = (uploaded.assetUrl || uploaded.url || '').trim()
  return raw
    .replace(/^https?:\/\/[^/]+(?=\/api\/files\/)/i, '')
    .replace(/^\/proxy(?=\/api\/files\/)/i, '')
}

export type QuestionBankPluginModel = ReturnType<typeof useQuestionBankPlugin>
