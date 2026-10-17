export type TodoStatus = 'pending' | 'done'

export type TodoPriority = 'low' | 'normal' | 'high' | 'urgent'

export type TodoSource = 'self' | 'admin'

export interface TodoSubTask {
  id: string
  title: string
  done: boolean
  /** 被指派负责该小任务的成员 ID 列表。 */
  assigneeIds: string[]
  /** 单独设置的优先级；空表示跟随任务整体设置。 */
  priority?: TodoPriority | null
  /** 单独设置的截止时间（epoch 毫秒）；空表示跟随任务整体设置。 */
  dueAt?: number | null
  /** 小任务最近一次内容变更时间（epoch 毫秒）。 */
  updatedAt: number
  /** 格式化后的更新时间展示文案。 */
  updatedAtLabel: string
}

/** Java Long / Snowflake ID 一律以字符串承载。 */
export interface TodoTask {
  id: string
  ownerId: string
  ownerName: string
  title: string
  remark: string
  priority: TodoPriority
  status: TodoStatus
  dueAt: number | null
  dueAtLabel: string
  completedAt: number | null
  completedAtLabel: string
  source: TodoSource
  assignedBy: string | null
  assignedByName: string
  overdue: boolean
  createdAt: number
  createdAtLabel: string
  updatedAt: number
  updatedAtLabel: string
  subTasks: TodoSubTask[]
  subTaskTotal: number
  subTaskDone: number
  /** 参与人用户 ID（不含归属人）；参与人可见并可推进任务，但不能编辑删除。 */
  participantIds: string[]
  /** 任务库可见范围：PRIVATE / DEPT / PUBLIC。 */
  visibility: string
  /** DEPT 可见时的部门 ID 列表。 */
  deptIds: string[]
  /** DEPT 可见时的部门名称列表（仅展示用）。 */
  deptNames: string[]
  /** 归档标记：归档任务从默认清单、统计与日程中收起，仅在「已归档」筛选下可见。 */
  archived: boolean
  /** 当前请求者是否为归属人：归属人可编辑删除，参与人只能推进。 */
  owned: boolean
  /** 当前请求者是否为创建者（或管理员）：只有他们能修改参与人。 */
  canManageParticipants: boolean
  /** 当前请求者负责的小任务 ID 列表：驱动小任务行的加入/退出按钮。 */
  mySubTaskIds: string[]
}

/**
 * 表格行：在待办之上附加两个**仅前端使用**的字段，用于把二级小任务渲染成树型表格里的独立行。
 *
 * <p>小任务子行由父任务展开而来，因此复用 {@link TodoTask} 的形状，让同一套列定义与插槽
 * 无需区分联合类型即可渲染；后端不会返回这两个字段。
 */
export interface TodoTableRow extends TodoTask {
  /** 子行标记：非空表示本行是 `subTaskParentId` 这条任务下的小任务。 */
  subTaskParentId?: string
  /** 子行标记：优先级未单独设置，列内展示「跟随任务」。 */
  subFollowPriority?: boolean
  /** 子行标记：截止时间未单独设置，列内展示「跟随任务」。 */
  subFollowDue?: boolean
  /** 树型表格默认读取的子行集合（仅父任务行有，由前端按 subTasks 派生）。 */
  children?: TodoTableRow[]
}

/** 任务库条目：可复用的已有任务。 */
export interface TodoTemplate {
  id: string
  title: string
  remark: string
  priority: TodoPriority
  subTaskTotal: number
  ownerName: string
  /** 清单中已有同名未完成任务的条目不可再添加。 */
  inMyList: boolean
}

export interface TodoPage<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export interface TodoOverview {
  total: number
  pending: number
  done: number
  overdue: number
  /** 当前用户可见的已归档任务数。 */
  archived: number
  dueToday: number
  completionRate: number
  upcoming: TodoTask[]
  /** 未完成任务按优先级统计，键为优先级 code。 */
  byPriority: Record<TodoPriority, number>
}

/** 日程表中单日的待办聚合。 */
export interface TodoScheduleDay {
  /** 日期键 yyyy-MM-dd。 */
  date: string
  total: number
  done: number
  overdue: number
  /** 当日截止的小任务数。 */
  subTasks: number
  tasks: TodoTask[]
}

export interface TodoSchedule {
  from: string
  to: string
  /** 覆盖查询区间的每一天，含无待办的日子。 */
  days: TodoScheduleDay[]
}

export interface TodoAdminStats {
  total: number
  pending: number
  done: number
  overdue: number
  /** 全平台已归档任务数。 */
  archived: number
}

export interface TodoUserOption {
  id: string
  username: string
  nickname: string
  email: string
  avatar: string
  status: string
  displayName: string
}

/** 创建/编辑时提交的小任务输入项：标题 + 负责人（精确到小任务粒度）。 */
export interface TodoSubTaskInput {
  title: string
  assigneeIds: string[]
  /** 单独设置的优先级；空表示跟随任务整体设置。 */
  priority?: string
  /** 单独设置的截止时间（epoch 毫秒）；空表示跟随任务整体设置。 */
  dueAt?: number | null
}

export interface TodoTaskPayload {
  title: string
  remark: string
  priority: TodoPriority
  dueAt: number | null
  /** 二级小任务（含负责人）；后端按标题复用已有小任务以保留勾选状态。 */
  subTasks: TodoSubTaskInput[]
  /** 参与人用户 ID 列表；只有创建者或管理员提交的会被采纳。 */
  participantIds: string[]
  /** 任务库可见范围：PRIVATE / DEPT / PUBLIC，空按 PRIVATE 处理。 */
  visibility: string
  /** DEPT 可见时的部门 ID 列表。 */
  deptIds: string[]
}

/** 部门选项：id 供提交，label 带父级路径供展示。 */
export interface TodoDeptOption {
  id: string
  name: string
  label: string
}

export const VISIBILITY_OPTIONS = [
  { label: '仅参与人', value: 'PRIVATE' },
  { label: '指定部门', value: 'DEPT' },
  { label: '全站公开', value: 'PUBLIC' },
]

export interface TodoFromTemplatePayload {
  templateId: string
  dueAt: number | null
}

export interface TodoListFilters {
  keyword: string
  status: string
  priority: string
}
