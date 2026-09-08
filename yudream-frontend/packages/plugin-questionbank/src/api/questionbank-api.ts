import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AiProviderOption, AnswerPayload, CategoryView, ComposeOptions, ComposeRecordView, ComposeRule, ImportResult, Page, PaperPayload, PaperPrintView, PaperView, PluginSettings, PracticeFilter, PracticeMeta, QuestionPayload, QuestionView, QuizRankEntry, ScreenDrawResult, SessionSummary, SessionView, SharedComposeView, TagView } from '../types'

type AiCatalog = {
  providers: () => Promise<AiProviderOption[]>
}

function aiCatalog(sdk: YuDreamPluginSdk): AiCatalog {
  const client = (sdk as YuDreamPluginSdk & { ai?: AiCatalog }).ai
  if (!client) {
    return { providers: async () => [] }
  }
  return client
}

function query(params: Record<string, string | number | undefined>) {
  const value = new URLSearchParams()
  for (const [key, item] of Object.entries(params)) {
    if (item !== undefined && item !== '') {
      value.set(key, String(item))
    }
  }
  const text = value.toString()
  return text ? `?${text}` : ''
}

const id = (value: string) => encodeURIComponent(value)

/** 简单列表端点后端统一返回 {records:[...]}，在此拆包；裸数组响应也兼容。 */
function records<T>(promise: Promise<unknown>): Promise<T[]> {
  return promise.then((body) => {
    if (Array.isArray(body)) {
      return body as T[]
    }
    const wrapped = body as { records?: T[] } | null | undefined
    return Array.isArray(wrapped?.records) ? wrapped.records : []
  })
}

export function createQuestionBankApi(sdk: YuDreamPluginSdk) {
  const ai = aiCatalog(sdk)
  return {
    // ---------- 用户端（/me/**，仅本人练习数据） ----------
    meta: () => sdk.http.get<PracticeMeta>('/me/meta'),
    practiceCount: (filter: PracticeFilter) =>
      sdk.http.post<{ count: number }>('/me/practice/count', filter),
    createSession: (filter: PracticeFilter) =>
      sdk.http.post<SessionView>('/me/practice/sessions', filter),
    mySessions: (page = 1, size = 10) =>
      sdk.http.get<Page<SessionSummary>>(`/me/practice/sessions${query({ page, size })}`),
    mySession: (sessionId: string) =>
      sdk.http.get<SessionView>(`/me/practice/sessions/${id(sessionId)}`),
    submitSession: (sessionId: string, answers: AnswerPayload[]) =>
      sdk.http.post<SessionView>(`/me/practice/sessions/${id(sessionId)}/submit`, { answers }),
    selfMark: (sessionId: string, questionId: string, correct: boolean) =>
      sdk.http.post<SessionView>(`/me/practice/sessions/${id(sessionId)}/self-mark`, { questionId, correct }),
    deleteSession: (sessionId: string) =>
      sdk.http.request<{ deleted: boolean }>(`/me/practice/sessions/${id(sessionId)}`, { method: 'DELETE' }),
    myPapers: () => records<PaperView>(sdk.http.get('/me/papers')),
    attemptPaper: (paperId: string) =>
      sdk.http.post<SessionView>(`/me/papers/${id(paperId)}/attempt`),
    quizLeaderboard: () => records<QuizRankEntry>(sdk.http.get('/me/quiz/leaderboard')),
    // ---------- 管理端（/admin/**，跨用户） ----------
    adminQuestions: (keyword = '', categoryId = '', tag = '', type = '', difficulty?: number, status = '', page = 1, size = 20) =>
      sdk.http.get<Page<QuestionView>>(`/admin/questions${query({ keyword, categoryId, tag, type, difficulty, status, page, size })}`),
    adminQuestion: (questionId: string) =>
      sdk.http.get<QuestionView>(`/admin/questions/${id(questionId)}`),
    createQuestion: (data: QuestionPayload) =>
      sdk.http.post<QuestionView>('/admin/questions', data),
    updateQuestion: (questionId: string, data: QuestionPayload) =>
      sdk.http.request<QuestionView>(`/admin/questions/${id(questionId)}`, { method: 'PUT', data }),
    deleteQuestion: (questionId: string) =>
      sdk.http.request<{ deleted: boolean }>(`/admin/questions/${id(questionId)}`, { method: 'DELETE' }),
    batchDeleteQuestions: (ids: string[]) =>
      sdk.http.post<{ deleted: number }>('/admin/questions/batch-delete', { ids }),
    importQuestions: (questions: QuestionPayload[]) =>
      sdk.http.post<ImportResult>('/admin/questions/import', { questions }),
    aiImportStart: (text: string) =>
      sdk.http.post<{ jobId: string }>('/admin/questions/ai-import/start', { text }),
    aiImportEventsUrl: (jobId: string) => sdk.http.url(`/admin/questions/ai-import/${id(jobId)}/events`),
    aiOptions: () => ai.providers(),
    exportQuestions: (format: 'json' | 'markdown', categoryId = '', tag = '', type = '', ids: string[] = []) =>
      sdk.http.blob(`/admin/questions-export${query({ format, categoryId, tag, type, ids: ids.join(',') })}`),
    adminCategories: () => records<CategoryView>(sdk.http.get('/admin/categories')),
    createCategory: (data: { name: string, sort?: number }) =>
      sdk.http.post<CategoryView>('/admin/categories', data),
    updateCategory: (categoryId: string, data: { name?: string, sort?: number }) =>
      sdk.http.request<CategoryView>(`/admin/categories/${id(categoryId)}`, { method: 'PUT', data }),
    deleteCategory: (categoryId: string) =>
      sdk.http.request<{ deleted: boolean }>(`/admin/categories/${id(categoryId)}`, { method: 'DELETE' }),
    mergeCategories: (data: { targetId: string, sourceIds: string[] }) =>
      sdk.http.post<{ moved: number }>('/admin/categories/merge', data),
    adminTags: () => records<TagView>(sdk.http.get('/admin/tags')),
    adminRecords: (keyword = '', status = '', page = 1, size = 20) =>
      sdk.http.get<Page<SessionSummary>>(`/admin/records${query({ keyword, status, page, size })}`),
    adminRecord: (sessionId: string) =>
      sdk.http.get<SessionView>(`/admin/records/${id(sessionId)}`),
    deleteRecord: (sessionId: string) =>
      sdk.http.request<{ deleted: boolean }>(`/admin/records/${id(sessionId)}`, { method: 'DELETE' }),
    adminPapers: (keyword = '', status = '', page = 1, size = 20) =>
      sdk.http.get<Page<PaperView>>(`/admin/papers${query({ keyword, status, page, size })}`),
    adminPaper: (paperId: string) =>
      sdk.http.get<PaperView>(`/admin/papers/${id(paperId)}`),
    createPaper: (data: PaperPayload) =>
      sdk.http.post<PaperView>('/admin/papers', data),
    updatePaper: (paperId: string, data: PaperPayload) =>
      sdk.http.request<PaperView>(`/admin/papers/${id(paperId)}`, { method: 'PUT', data }),
    deletePaper: (paperId: string) =>
      sdk.http.request<{ deleted: boolean }>(`/admin/papers/${id(paperId)}`, { method: 'DELETE' }),
    printPaper: (paperId: string) =>
      sdk.http.get<PaperPrintView>(`/admin/papers/${id(paperId)}/print`),
    reviewQueue: (keyword = '', page = 1, size = 20) =>
      sdk.http.get<Page<SessionSummary>>(`/admin/review${query({ keyword, page, size })}`),
    reviewSession: (sessionId: string) =>
      sdk.http.get<SessionView>(`/admin/records/${id(sessionId)}`),
    reviewMark: (sessionId: string, questionId: string, correct: boolean) =>
      sdk.http.post<SessionView>(`/admin/review/${id(sessionId)}`, { questionId, correct }),
    adminSettings: () => sdk.http.get<PluginSettings>('/admin/settings'),
    updateSettings: (data: Partial<PluginSettings>) =>
      sdk.http.request<PluginSettings>('/admin/settings', { method: 'PUT', data }),
    // ---------- 组卷中心（/admin/compose/**，COMPOSE 权限） ----------
    composeQuestions: (keyword = '', categoryId = '', tag = '', type = '', page = 1, size = 20) =>
      sdk.http.get<Page<QuestionView>>(`/admin/compose/questions${query({ keyword, categoryId, tag, type, page, size })}`),
    composeOptions: () => sdk.http.get<ComposeOptions>('/admin/compose/options'),
    composeDraw: (rule: ComposeRule) =>
      sdk.http.post<{ questions: QuestionView[] }>('/admin/compose/draw', rule),
    composeExportWord: (payload: { title: string, description?: string, questionIds: string[], withAnswers: boolean }) =>
      sdk.http.blob('/admin/compose/export-word', { method: 'POST', data: payload }),
    // ---------- 组卷记录（/admin/compose/records/**，COMPOSE 权限，归属隔离） ----------
    saveComposeRecord: (payload: { title: string, description?: string, questionIds: string[], withAnswers: boolean }) =>
      sdk.http.post<ComposeRecordView>('/admin/compose/records', payload),
    composeRecords: (keyword = '', all = false, ownerId = '', page = 1, size = 10) =>
      sdk.http.get<Page<ComposeRecordView>>(`/admin/compose/records${query({ keyword, all: all ? 'true' : '', ownerId, page, size })}`),
    composeRecord: (recordId: string) =>
      sdk.http.get<ComposeRecordView>(`/admin/compose/records/${id(recordId)}`),
    deleteComposeRecord: (recordId: string) =>
      sdk.http.request(`/admin/compose/records/${id(recordId)}`, { method: 'DELETE' }),
    exportComposeRecordWord: (recordId: string) =>
      sdk.http.blob(`/admin/compose/records/${id(recordId)}/export-word`, { method: 'POST' }),
    shareComposeRecord: (recordId: string) =>
      sdk.http.post<{ token: string }>(`/admin/compose/records/${id(recordId)}/share`),
    unshareComposeRecord: (recordId: string) =>
      sdk.http.post(`/admin/compose/records/${id(recordId)}/unshare`),
    // ---------- 分享公开页（/compose/shared/**，免登录） ----------
    fetchSharedCompose: (token: string) =>
      sdk.http.get<SharedComposeView>(`/compose/shared/${id(token)}`),
    // ---------- 抢答大屏（/admin/screen/**，处理体内双权限校验） ----------
    screenPapers: () => records<PaperView>(sdk.http.get('/admin/screen/papers')),
    screenDraw: (payload: { recordId?: string, questionIds?: string[], paperId?: string, rule?: ComposeRule }) =>
      sdk.http.post<ScreenDrawResult>('/admin/screen/draw', payload),
  }
}

export type QuestionBankApi = ReturnType<typeof createQuestionBankApi>

/** 用浏览器下载 blob 响应（下载端点带权限，不能裸 <a href>）。 */
export function saveBlob(data: Blob, headers: Record<string, string>, fallbackName: string) {
  const disposition = headers['content-disposition'] || ''
  const match = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  const filename = match ? decodeURIComponent(match[1]) : fallbackName
  const url = URL.createObjectURL(data)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
