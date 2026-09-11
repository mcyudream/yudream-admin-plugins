/** 宿主把 long 序列化为 JSON 字符串，时间戳与计数都要兼容 number|string。 */
export type TimeValue = number | string | number[] | null | undefined

export interface Page<T> {
  records: T[]
  total: number | string
}

export type QuestionType = 'SINGLE' | 'MULTIPLE' | 'TRUE_FALSE' | 'FILL' | 'SHORT'

export interface QuestionView {
  id: string
  type: QuestionType
  typeLabel: string
  categoryId?: string
  categoryName?: string
  tags: string[]
  content: string
  options: string[]
  answer?: string
  answers?: string[]
  blanks?: string[][]
  referenceAnswer?: string
  analysis?: string
  difficulty: number
  status: string
  createdBy?: string
  createdByName?: string
  createdAt: TimeValue
  updatedAt: TimeValue
}

/** 创建/编辑/导入共用的题目载荷；键字母、空选项等由后端归一化。 */
export interface QuestionPayload {
  type: string
  categoryId?: string | null
  categoryName?: string
  tags?: string[]
  content: string
  options?: string[]
  answer?: string
  answers?: string[]
  blanks?: string[][]
  referenceAnswer?: string
  analysis?: string
  difficulty?: number
  status?: string
}

export interface CategoryView {
  id: string
  name: string
  sort: number
  questionCount: number
  createdAt: TimeValue
}

/** QQ 抢答排行榜条目：展示名由服务端反解绑定账号，未绑定时为遮蔽后的 QQ 号。 */
export interface QuizRankEntry {
  rank: number
  name: string
  bound: boolean
  /** 宿主把 long 序列化为字符串，累计题数读取时须 Number() */
  score: number | string
  lastAt: TimeValue
}

export interface TagView {
  name: string
  count: number
}

export interface DrawApiQuery {
  categoryId?: string
  tags?: string
  type?: string
  difficulty?: number
  seed?: string
  count?: number
}

export interface DrawApiResult {
  questions: QuestionView[]
  seed: string
  total: number | string
}

export interface DrawApiOptions {
  categories: { id: string, name: string, questionCount?: number | string }[]
  tags: TagView[]
}

export interface PracticeMeta {
  total: number
  categories: { id: string, name: string, count: number }[]
  tags: Record<string, number>
  types: { type: QuestionType, label: string, count: number }[]
  /** 自由刷题开关；关闭后抽题入口不可用，题单作答不受影响。 */
  practiceEnabled: boolean
}

export type PaperMode = 'RULE' | 'MANUAL'
export type SubjectiveMode = 'SELF' | 'REVIEW' | 'AI'
export type PaperStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export interface PaperView {
  id: string
  name: string
  description?: string
  mode: PaperMode
  categoryId?: string
  tags: string[]
  types: string[]
  difficulties: number[]
  count: number
  questionIds: string[]
  /** 展示用题量：RULE 为抽题数，MANUAL 为所选题目数。 */
  questionCount: number
  subjectiveMode: SubjectiveMode
  status: PaperStatus
  createdBy?: string
  createdByName?: string
  createdAt: TimeValue
  updatedAt: TimeValue
}

export interface PaperPayload {
  name: string
  description?: string
  mode: PaperMode
  categoryId?: string | null
  tags?: string[]
  types?: string[]
  difficulties?: number[]
  count?: number
  questionIds?: string[]
  subjectiveMode?: SubjectiveMode
  status?: PaperStatus
}

/** 打印数据：题单 + 现场抽取的题目（RULE 每次重新随机）。 */
export interface PaperPrintView {
  paper: PaperView
  categoryName?: string
  questions: QuestionView[]
  drawnAt: TimeValue
}

/** QQ 抽题分组：名称 → 分类/标签规则（名称唯一，空白项后端忽略）。 */
export interface QqQuizGroup {
  name: string
  categoryId?: string
  tags?: string[]
}

export interface PluginSettings {
  practiceEnabled: boolean
  aiProviderCode?: string | null
  aiModelCode?: string | null
  qqGroups: QqQuizGroup[]
  qqDefaultGroup?: string | null
  qqAnswerSeconds: number
  qqAiGrading: boolean
}

/** 组卷/大屏共用的随机抽题规则。 */
export interface ComposeRule {
  categoryId?: string
  tags?: string[]
  types?: string[]
  difficulties?: number[]
  count?: number
}

/** 组卷拾取器筛选项：分类（带题数）+ 全库标签。 */
export interface ComposeOptions {
  categories: CategoryView[]
  tags: TagView[]
}

/** 大屏抽题结果：题单名（随机抽题时为空）+ 带答案的题目。 */
export interface ScreenDrawResult {
  paperName?: string
  questions: QuestionView[]
}

/** 组卷记录列表/详情视图。 */
export interface ComposeRecordView {
  id: string
  title: string
  description?: string
  withAnswers: boolean
  questionCount: number
  ownerId: string
  ownerName: string
  shared: boolean
  shareToken?: string
  shareAt?: TimeValue
  createdAt?: TimeValue
  updatedAt?: TimeValue
  questions?: QuestionView[]
}

/** 分享页公开视图（免登录）。 */
export interface SharedComposeView {
  title: string
  description?: string
  ownerName: string
  createdAt?: TimeValue
  questions: QuestionView[]
}

/** 平台 AI 供应商/模型选项（设置页级联选择器）。 */
export interface AiModelOption {
  code: string
  name: string
}

export interface AiProviderOption {
  code: string
  name: string
  models: AiModelOption[]
}

export interface PracticeFilter {
  categoryId?: string
  tags?: string[]
  types?: string[]
  difficulties?: number[]
  count?: number
}

export interface SessionSummary {
  id: string
  userId?: string
  userName?: string
  categoryId?: string
  tags: string[]
  types: string[]
  difficulties: number[]
  requestedCount: number
  totalCount: number
  correctCount: number
  status: 'ONGOING' | 'FINISHED' | string
  createdAt: TimeValue
  submittedAt: TimeValue
  paperId?: string
  paperName?: string
  subjectiveMode?: SubjectiveMode
  /** 管理端视图：REVIEW 模式且仍有简答题待审核。 */
  pendingReview?: boolean
}

export interface SessionAnswerView {
  choice?: string
  choices?: string[]
  blanks?: string[]
  text?: string
  correct?: boolean
}

export interface SessionQuestionView {
  questionId: string
  type: QuestionType
  typeLabel: string
  content: string
  options: string[]
  difficulty: number
  /** 仅填空题：作答输入框个数（未提交时不泄露 blanks 答案本体）。 */
  blankCount?: number
  /** 以下字段仅提交后（reveal）返回。 */
  answer?: string
  answers?: string[]
  blanks?: string[][]
  referenceAnswer?: string
  analysis?: string
  userAnswer?: SessionAnswerView
}

export interface SessionView extends SessionSummary {
  questions: SessionQuestionView[]
}

export interface AnswerPayload {
  questionId: string
  choice?: string
  choices?: string[]
  blanks?: string[]
  text?: string
}

export interface ImportFailure {
  index: number
  reason: string
}

export interface ImportResult {
  imported: number
  failures: ImportFailure[]
}
