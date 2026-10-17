/**
 * 反馈受理状态：pending / processing 为未完结，其余为已完结（对齐 GitHub Issue 的 open / closed）。
 *
 * <p>resolved / rejected 是「管理员已给出结论」的两个状态，提交人可在此时归档；
 * archived 是终态，归档后不再回到处理流程。
 */
export type FeedbackStatus = 'pending' | 'processing' | 'resolved' | 'rejected' | 'archived'

/** 反馈类型：用户提交时选择。 */
export type FeedbackType = 'bug' | 'suggestion' | 'question' | 'other'

/** 严重程度：用户自评、管理员可修正，并作为列表默认排序依据。 */
export type FeedbackSeverity = 'low' | 'normal' | 'high' | 'critical'

export type FeedbackCommentRole = 'user' | 'admin'

/** 处理人：一条反馈可以有多个（对应 GitHub Issue 的多个 assignee）。 */
export interface FeedbackAssignee {
  id: string
  name: string
}

export interface FeedbackComment {
  id: string
  authorId: string
  authorName: string
  authorRole: FeedbackCommentRole
  /** 是否为官方（管理员）回复，用于区分展示样式。 */
  adminReply: boolean
  content: string
  /** 仅管理员可见的内部备注，不会出现在用户端响应中。 */
  internal: boolean
  createdAt: number
  createdAtLabel: string
}

export interface FeedbackActivity {
  actorId: string
  actorName: string
  action: string
  actionLabel: string
  detail: string
  createdAt: number
  createdAtLabel: string
}

/** 气泡内的一条系统留痕。 */
export interface FeedbackTimelineAction {
  key: string
  /** 动作编码，用于挑选图标。 */
  action: string
  actionLabel: string
  detail: string
}

/**
 * 处理记录气泡。
 *
 * <p>同一来源（同一人、同一次操作）产生的系统留痕与评论合并进同一个气泡：
 * {@code actions} 是这次操作触发的系统信息（状态变更、自动加入处理人、指派等），
 * {@code content} 是这次操作附带的评论正文；两者都为空的情况不会出现。
 */
export interface FeedbackTimelineEntry {
  key: string
  actorId: string
  actorName: string
  createdAt: number
  createdAtLabel: string
  actions: FeedbackTimelineAction[]
  content: string
  adminReply: boolean
  internal: boolean
}

export interface Feedback {
  id: string
  title: string
  description: string
  type: FeedbackType
  typeLabel: string
  severity: FeedbackSeverity
  severityLabel: string
  status: FeedbackStatus
  statusLabel: string
  open: boolean
  authorId: string
  authorName: string
  /** 处理人 ID 列表，用于筛选与草稿回显。 */
  assigneeIds: string[]
  /** 处理人展示信息；后端已解析昵称，缺失时前端按 ID 兜底。 */
  assignees: FeedbackAssignee[]
  attachments: string[]
  comments: FeedbackComment[]
  activities: FeedbackActivity[]
  commentCount: number
  createdAt: number
  createdAtLabel: string
  updatedAt: number
  updatedAtLabel: string
  closedAt: number | null
  closedAtLabel: string
  /** 当前请求者是否为提交人。 */
  owned: boolean
  /** 是否允许修改内容：仅提交人且反馈未完结。 */
  canEdit: boolean
  /** 是否允许归档：仅提交人、且管理员已给出结论但尚未归档。 */
  canArchive: boolean
  /** 自动归档时间（结论时间 + 7 天）；未给出结论时为 null。 */
  autoArchiveAt: number | null
  autoArchiveAtLabel: string
}

export interface FeedbackPage<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export interface FeedbackStats {
  total: number
  pending: number
  processing: number
  open: number
  resolved: number
  rejected: number
  archived: number
}

export interface FeedbackUserOption {
  id: string
  username: string
  nickname: string
  email: string
  avatar: string
  status: string
}

/** 成员候选范围：all=全量账号目录；manage=处理人场景（附带可用标记与权限提示）。 */
export type FeedbackUserScope = 'all' | 'manage'

/**
 * 成员候选页。
 *
 * <p>manage 范围下后端返回账号目录候选与 notice。宿主 SPI 不提供按权限反查用户，候选未按
 * 管理权限预筛，管理员需确认所选成员可访问反馈管理页；账号目录不可用时 available 为 false，
 * 此时 records 恒为空、notice 说明原因——调用方必须拒绝选择，而不是放开全员。
 */
export interface FeedbackUserPage {
  records: FeedbackUserOption[]
  available?: boolean
  notice?: string
}

/** 提交 / 修改反馈的内容载荷。 */
export interface FeedbackSubmitPayload {
  title: string
  description: string
  type: FeedbackType
  severity: FeedbackSeverity
  /** 图片地址（归一化后的站内相对路径）。 */
  attachments: string[]
}

export interface FeedbackListFilters {
  keyword: string
  status: string
  type: string
  severity: string
  /** 按处理人筛选：命中「处理人名单里包含该成员」，不是单一处理人。 */
  assigneeId: string
  unassigned: boolean
  /** 管理端专用：按提交人筛选。 */
  authorId: string
  /** 提交时间区间（YdRangePicker 的 yyyy-MM-dd，查询前转毫秒区间）。 */
  submittedRange: string[] | undefined
  page: number
  size: number
}
