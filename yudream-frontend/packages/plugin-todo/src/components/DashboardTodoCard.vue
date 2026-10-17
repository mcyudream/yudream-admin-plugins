<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TodoSchedule, TodoScheduleDay, TodoTask } from '../types'
import { YdAction, YdIcon, YdTabs } from '@yudream/components'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createTodoUserApi } from '../api/todo-api'
import { useUserDirectory } from '../composables/useUserDirectory'
import { errorMessage, formatDue, priorityMeta } from '../composables/utils'

/**
 * 主页卡片「我的待办」：表单式待办清单 + 周/月日程表，双栏并排、窄卡自动堆叠。
 *
 * <p>卡片只做展示：不提供勾选等写操作，点击任意待办即跳转到「我的清单」页去处理。
 */
const props = defineProps<{
  sdk: YuDreamPluginSdk
}>()

const VIEW_TABS = [
  { label: '周', value: 'week' },
  { label: '月', value: 'month' },
]

const WEEK_LABELS = ['一', '二', '三', '四', '五', '六', '日']

/**
 * 双栏并排的宽度下限：窄于此卡片切为上下堆叠（与下方 @container 759px 断点对应）。
 * 堆叠后日程面板独占整行宽度，周数门槛因此比并排低。
 */
const SIDE_BY_SIDE_MIN_WIDTH = 760

/** 极窄档（手机竖屏全宽卡）上限：此宽度以下周视图始终单周，避免日期格被压成牙签。 */
const SINGLE_WEEK_MAX_WIDTH = 480

/** 周视图最多铺几周：高度再富余也不无限铺，免得一次请求过多日期、翻页跨度过大。 */
const MAX_WEEKS_PER_VIEW = 4

/**
 * 单周行固定高度（行头 + 日期格，不含行间距）：日程面板不再把行 1fr 拉伸，
 * 行高恒为 63px（在 84px 基础上再降 25%），面板高度富余由多铺几周消化，不足时留白。
 * 与下方 .todo-board__weeks 的 grid-auto-rows 保持一致，改动需同步。
 */
const WEEK_ROW_BASE_HEIGHT = 76

/** 日程面板里除日期区之外的开销（面板标题、行间距）估算值，折算周数时先扣掉。 */
const CALENDAR_CHROME_HEIGHT = 40

/**
 * 卡片高度三档（按内容区实测高度 boardHeight 划分）：
 * 低档（< LIST_ONLY_MAX_BOARD）只显示待办清单，不渲染日程表；
 * 中档（< CALENDAR_TABS_MIN_BOARD）清单 + 周视图日程表；
 * 高档（≥ CALENDAR_TABS_MIN_BOARD）清单 + 周视图/月视图切换（YdTabs 出现）。
 */
const LIST_ONLY_MAX_BOARD = 200
const CALENDAR_TABS_MIN_BOARD = 310

/** 堆叠（窄卡）显示日程表所需的最小内容高度：低于此值直接隐藏日程、清单独占（清单优先）。 */
const STACKED_CALENDAR_MIN_BOARD = 340

/** 月视图网格的最小可用高度：星期行 16 + 6 行日期格 ×28 + 5 个行距 ×4 = 204，不足则不提供周/月切换。 */
const MONTH_GRID_MIN_HEIGHT = 204

/** 卡片内的行间距：根节点 gap 与顶栏/双栏间 gap 都是 12px（--todo-card-gap / sp-4）。 */
const BOARD_GAP = 12

/** 一次拉取的可展示条数；超出时在表头提示，避免卡片里出现无底洞式滚动。 */
const LIST_LIMIT = 100

const api = createTodoUserApi(props.sdk)
const directory = useUserDirectory(props.sdk, 'user')
const router = useRouter()

/** 「我的清单」页路由，由 TodoPlugin 的 @PluginRoute 注册。 */
const TASKS_PATH = '/platform/plugins/todo/tasks'

const loading = ref(true)
const error = ref('')
const tasks = ref<TodoTask[]>([])
const totalCount = ref(0)
const pendingCount = ref(0)
const overdueCount = ref(0)
const dueTodayCount = ref(0)
const doneCount = ref(0)
const completionRate = ref(0)

const view = ref('week')
const filter = ref('all')
const schedule = ref<TodoSchedule | null>(null)
const loadingSchedule = ref(false)
const scheduleError = ref('')

const today = startOfDay(Date.now())
const anchor = ref(today)
const selected = ref(toKey(today))

/** 卡片根节点，用来实测尺寸（宿主栅格拖动 / 收起侧栏都不触发 Vue 的响应式更新，只能自己量）。 */
const cardRef = ref<HTMLElement | null>(null)
/** 顶栏：量出它的高度后，把卡片剩余高度整块交给双栏容器。 */
const barRef = ref<HTMLElement | null>(null)
/** 日程面板：实测它的最终高度，用来折算周视图一次铺几周。 */
const calendarRef = ref<HTMLElement | null>(null)
/** 日程面板头：实测高度，折算周数时精确扣掉（此前按估算值 40，偏差会导致末行截断或留白）。 */
const calendarHeadRef = ref<HTMLElement | null>(null)
/** 日程面板非日期区开销：面板头实测 + 面板内 gap（sp-3 = 12）；未量到按估算 40 兜底。 */
const calendarChrome = ref(CALENDAR_CHROME_HEIGHT)
/** 截断兜底：实测内容超高时往下减的周数；面板高度变化时归零重估。 */
const overflowTrim = ref(0)
/** 上一次实测的日程面板高度，用于识别「高度变了要重置 overflowTrim」。 */
let lastCalendarHeight = 0

/** 卡片实测宽度（border-box）；0 表示尚未量到，按宽卡处理，避免首帧就闪成单周。 */
const cardWidth = ref(0)
/** 卡片内容区实测高度（宿主给定的盒子高度）；0 表示尚未量到。 */
const boardHeight = ref(0)
const barHeight = ref(0)
/** 日程面板实测高度；0 表示尚未量到，周数先按宽度下限走。 */
const calendarHeight = ref(0)

let observer: ResizeObserver | null = null

/** 日程请求序号，用于丢弃过期响应（见 loadSchedule）。 */
let scheduleSeq = 0

/**
 * 量一次卡片尺寸。
 *
 * <p>宿主把卡片放在 GridStack 栅格 + flex 容器里，拖动或收起侧栏改的都是容器像素尺寸，不经过 Vue 的
 * 响应式，只能用 ResizeObserver 喂回来。这里只量「双栏容器以外」的部分（根节点高度、顶栏高度）——
 * 双栏高度由 splitHeight 推导锁定，日程面板自身高度则单独量（calendarHeight），
 * 两组度量互为输入但互不反哺，不会出现「日程变高 → 重算出更高目标 → 再变高」的自我放大。
 */
function measure() {
  const root = cardRef.value
  if (root) {
    const width = root.getBoundingClientRect().width
    if (width > 0) {
      cardWidth.value = Math.round(width)
    }
    if (root.clientHeight > 0) {
      boardHeight.value = root.clientHeight
    }
  }
  if (barRef.value && barRef.value.offsetHeight > 0) {
    barHeight.value = barRef.value.offsetHeight
  }
  measureCalendar()
}

function measureCalendar() {
  const head = calendarHeadRef.value
  if (head && head.offsetHeight > 0) {
    calendarChrome.value = head.offsetHeight + 12
  }
  const calendar = calendarRef.value
  if (calendar && calendar.clientHeight > 0) {
    if (Math.abs(calendar.clientHeight - lastCalendarHeight) > 4) {
      // 面板高度变了（拖卡片/切布局）：截断兜底归零，按新高度重新折算周数。
      lastCalendarHeight = calendar.clientHeight
      overflowTrim.value = 0
    }
    calendarHeight.value = calendar.clientHeight
    // 防截断自检：面板 overflow:hidden，行高/行距/面板头任一偏差都会切掉末行。
    // 内容实测超高就减一周重排，直到不截断（最多减 2 周，防死循环）。
    if (calendar.scrollHeight > calendar.clientHeight + 1 && overflowTrim.value < 2) {
      overflowTrim.value += 1
      void nextTick(() => measureCalendar())
    }
  }
}

/** 观察卡片、顶栏与日程面板的尺寸变化，任一变化都重新量一次。 */
function observeSize() {
  if (typeof ResizeObserver === 'undefined') {
    return
  }
  const elements = [cardRef.value, barRef.value, calendarRef.value]
    .filter((element): element is HTMLElement => element !== null)
  if (elements.length === 0) {
    return
  }
  observer = new ResizeObserver(() => measure())
  elements.forEach(element => observer?.observe(element))
}

/**
 * 双栏容器的固定高度 = 卡片高度 - 上下内边距 - 顶栏 - 行间距。
 *
 * <p>旧版只给日程面板算 minHeight，清单面板按自然高度排开——清单一长（最多 100 条）整个卡片
 * 就被顶出宿主给定的高度。现在把这整块剩余高度直接锁给双栏容器：清单在面板内自行滚动，
 * 日程面板靠 CSS flex 吃满自己的份额，任何卡片高度下内容都恰好铺满、既不溢出也不留白。
 * 未量到尺寸（0）时不设高度，按自然高度兜底。
 */
/** 下边距按高度分档：卡片越矮下边距越小，把空间让给清单/日程内容（顶边距恒为 sp-1 = 4）。 */
const FOUR_ROW_BOARD = 360
const bottomPadding = computed(() => {
  if (boardHeight.value === 0) {
    return 16
  }
  if (boardHeight.value < LIST_ONLY_MAX_BOARD) {
    return 12
  }
  // 4 档（宿主栅格 4 行，约 360px）是分界：以下边距偏小往上抬，以上偏大往下压，整体向中间收敛。
  if (boardHeight.value < FOUR_ROW_BOARD) {
    return 16
  }
  return 14
})

/** 根节点上下内边距合计 = sp-1(4) + 动态下边距，splitHeight 推导与内联样式共用。 */
const paddingY = computed(() => 4 + bottomPadding.value)

/** 内联下边距：覆盖 CSS 里的固定 21px（padding-inline 仍由 CSS 容器查询分级）。 */
const boardStyle = computed(() => ({ paddingBottom: `${bottomPadding.value}px` }))

const splitHeight = computed(() => {
  if (boardHeight.value === 0 || barHeight.value === 0) {
    return 0
  }
  return Math.max(220, Math.round(boardHeight.value - paddingY.value - barHeight.value - BOARD_GAP))
})

const splitStyle = computed(() => (
  splitHeight.value > 0 ? { height: `${splitHeight.value}px` } : undefined
))

/**
 * 日程表可见性：低档卡片只保留待办清单；堆叠（窄卡）时清单与日程上下分高度，
 * 高度不足两块都残，故门槛抬高到 STACKED_CALENDAR_MIN_BOARD——优先保证清单完整显示。
 * 未量到尺寸时按有日程兜底，避免首帧闪没。
 */
const showCalendar = computed(() => {
  if (boardHeight.value === 0) {
    return true
  }
  if (boardHeight.value < LIST_ONLY_MAX_BOARD) {
    return false
  }
  if (cardWidth.value > 0 && cardWidth.value < SIDE_BY_SIDE_MIN_WIDTH
    && boardHeight.value < STACKED_CALENDAR_MIN_BOARD) {
    return false
  }
  return true
})

/**
 * 周视图/月视图切换只在高档、且日程面板实测高度放得下完整月历（6 行×28px）时出现；
 * 放不下就固定周视图，切过去也是被压扁的残月历，不如不给切换。
 */
const showViewTabs = computed(() => {
  if (boardHeight.value < CALENDAR_TABS_MIN_BOARD) {
    return false
  }
  if (calendarHeight.value === 0) {
    // 未量到尺寸时按可切换兜底，与 showCalendar 的首帧策略一致。
    return true
  }
  return calendarHeight.value - calendarChrome.value >= MONTH_GRID_MIN_HEIGHT
})

watch(showViewTabs, (visible) => {
  if (!visible && view.value !== 'week') {
    view.value = 'week'
  }
})

/**
 * 从低档拖高到中/高档时，日程面板是刚挂载的（此前 v-if 隐藏），若低档首帧没拉过日程，
 * 这里必须补一次请求，否则日历区域一直是空的，看起来就像「高度变了但布局没更新」。
 */
watch(showCalendar, (visible) => {
  if (visible && days.value.length === 0 && !loadingSchedule.value) {
    void loadSchedule()
  }
})

/** 按日程面板实测高度折算能铺几周：扣掉实测的面板头开销后，按「行高 + 行间距」计算。 */
const weeksByHeight = computed(() => {
  const room = calendarHeight.value - calendarChrome.value
  const rowTotal = WEEK_ROW_BASE_HEIGHT + 12
  return room < WEEK_ROW_BASE_HEIGHT ? 1 : Math.max(1, Math.floor((room + 12) / rowTotal))
})

/**
 * 周视图一次铺开几周：宽度给出下限，高度富余时继续往上加，直到铺满或到达上限。
 * 下限按布局分档：并排（≥760，日程面板约 1/3 宽）保底三周；堆叠（<760，日程全宽）保底两周；
 * 极窄（<480，手机竖屏）始终单周。未量到尺寸按宽卡处理，避免首帧闪成单周。
 */
const weeksPerView = computed(() => {
  if (cardWidth.value > 0 && cardWidth.value < SINGLE_WEEK_MAX_WIDTH) {
    return 1
  }
  const floor = cardWidth.value > 0 && cardWidth.value < SIDE_BY_SIDE_MIN_WIDTH ? 2 : 3
  const fit = Math.min(MAX_WEEKS_PER_VIEW, Math.max(floor, weeksByHeight.value))
  // 截断兜底优先于保底周数：实测放不下的周数坚决减下去（overflowTrim 见 measureCalendar）。
  return Math.max(1, fit - overflowTrim.value)
})

const range = computed(() => {
  if (view.value === 'week') {
    const from = startOfWeek(anchor.value)
    return { from, to: addDays(from, weeksPerView.value * 7 - 1) }
  }
  const first = new Date(anchor.value)
  first.setDate(1)
  const from = startOfWeek(first.getTime())
  return { from, to: addDays(from, 41) }
})

const rangeLabel = computed(() => {
  const from = new Date(range.value.from)
  if (view.value === 'week') {
    const to = new Date(range.value.to)
    return `${from.getMonth() + 1}月${from.getDate()}日 - ${to.getMonth() + 1}月${to.getDate()}日`
  }
  // 标题锚定当前所在月：网格 from 是 startOfWeek(本月1号)，1 号不是周一时会回退到上个月，
  // 按它取名就会「9 月显示成 8 月」。
  const current = new Date(anchor.value)
  return `${current.getFullYear()}年${current.getMonth() + 1}月`
})

const days = computed<TodoScheduleDay[]>(() => schedule.value?.days ?? [])
/** 区间内截止的任务总数，用于日程表头提示「该范围截止 N 条」。 */
const rangeDueCount = computed(() => days.value.reduce((sum, day) => sum + day.total, 0))

/** 周视图按「一周一行」切分：每行 7 个日期格，行首标注本周/下周与这段日期；单周时只有一行。 */
const weekRows = computed(() => {
  const rows: { key: string; label: string; range: string; days: TodoScheduleDay[] }[] = []
  const first = range.value.from
  for (let index = 0; index < days.value.length; index += 7) {
    const slice = days.value.slice(index, index + 7)
    const start = addDays(first, index)
    rows.push({
      key: slice[0]?.date ?? `${index}`,
      label: weekLabel(start),
      range: `${monthDay(start)} - ${monthDay(addDays(start, 6))}`,
      days: slice,
    })
  }
  return rows
})

const visibleTasks = computed(() => [...tasks.value.filter(matchFilter)].sort(compareTask))

const listHint = computed(() => {
  if (filter.value === 'day') {
    return `${dayKeyLabel(selected.value)} 截止 ${visibleTasks.value.length} 条`
  }
  const suffix = totalCount.value > LIST_LIMIT ? `，展示前 ${LIST_LIMIT} 条` : ''
  return `共 ${totalCount.value} 条 · 完成率 ${completionRate.value}%${suffix}`
})

/** 点统计胶囊 = 切到对应筛选；再点当前激活项则回到「全部」。 */
function toggleFilter(value: 'pending' | 'today' | 'overdue' | 'done') {
  filter.value = filter.value === value ? 'all' : value
}

onMounted(() => {
  // 先同步量一次尺寸：窄卡首帧就按单周 / 少铺几周去拉日程，避免先按默认值请求一遍再重来。
  measure()
  void load()
  if (showCalendar.value) {
    void loadSchedule()
  }
  observeSize()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})

watch(range, () => {
  const keys = days.value.map(day => day.date)
  if (keys.length > 0 && !keys.includes(selected.value)) {
    selected.value = keys.includes(toKey(today)) ? toKey(today) : keys[0]
  }
  if (showCalendar.value) {
    void loadSchedule()
  }
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [overview, page] = await Promise.all([
      api.overview(),
      // 与清单页默认口径一致：已归档任务不进入主页卡片清单。
      api.tasks({ page: 1, size: LIST_LIMIT, archived: 'false' }),
    ])
    totalCount.value = overview.total
    pendingCount.value = overview.pending
    overdueCount.value = overview.overdue
    dueTodayCount.value = overview.dueToday
    doneCount.value = overview.done
    completionRate.value = overview.completionRate
    tasks.value = page.records ?? []
    void directory.ensure(tasks.value.flatMap(item => item.participantIds ?? []))
  }
  catch (cause: unknown) {
    error.value = errorMessage(cause, '待办加载失败')
  }
  finally {
    loading.value = false
  }
}

async function loadSchedule() {
  // 拖动卡片改变宽度时会连续翻转单周/双周，range 跟着连跳——只认最后一次请求的结果，
  // 否则先发的旧响应后到，会把日程覆盖成已经不该显示的区间。
  const seq = ++scheduleSeq
  loadingSchedule.value = true
  scheduleError.value = ''
  try {
    const result = await api.schedule(range.value.from, range.value.to)
    if (seq !== scheduleSeq) {
      return
    }
    schedule.value = result
    const keys = result.days.map(day => day.date)
    if (!keys.includes(selected.value)) {
      selected.value = keys.includes(toKey(today)) ? toKey(today) : (keys[0] ?? selected.value)
    }
  }
  catch (cause: unknown) {
    if (seq === scheduleSeq) {
      scheduleError.value = errorMessage(cause, '日程加载失败')
    }
  }
  finally {
    if (seq === scheduleSeq) {
      loadingSchedule.value = false
    }
  }
}

/** 卡片只做展示：点击某条待办跳转到「我的清单」，并带上关键词让落地即定位到该条。 */
function openTask(task: TodoTask) {
  void router.push({ path: TASKS_PATH, query: { keyword: task.title } })
}

/** 打开「我的清单」查看全部待办。 */
function openAll() {
  void router.push({ path: TASKS_PATH })
}

function matchFilter(task: TodoTask): boolean {
  switch (filter.value) {
    case 'pending':
      return task.status !== 'done'
    case 'overdue':
      return task.overdue
    case 'done':
      return task.status === 'done'
    case 'today': {
      const millis = dueAtMillis(task)
      return millis !== null && toKey(millis) === toKey(today)
    }
    case 'day': {
      const millis = dueAtMillis(task)
      return millis !== null && toKey(millis) === selected.value
    }
    default:
      return true
  }
}

/** 未完成优先，再按截止时间升序（无截止时间排最后），同刻按优先级。 */
function compareTask(left: TodoTask, right: TodoTask): number {
  const leftDone = left.status === 'done' ? 1 : 0
  const rightDone = right.status === 'done' ? 1 : 0
  if (leftDone !== rightDone) {
    return leftDone - rightDone
  }
  const leftDue = dueAtMillis(left)
  const rightDue = dueAtMillis(right)
  if (leftDue !== rightDue) {
    if (leftDue === null) {
      return 1
    }
    if (rightDue === null) {
      return -1
    }
    return leftDue - rightDue
  }
  return priorityMeta(right.priority).weight - priorityMeta(left.priority).weight
}

/** 截止时间主行：相对表述（今天截止 / 3 天后截止 / 逾期 2 天）。 */
function dueRelative(task: TodoTask): string {
  const millis = dueAtMillis(task)
  if (millis === null) {
    return '不限时'
  }
  const days = Math.round((startOfDay(millis) - startOfDay(Date.now())) / 86_400_000)
  if (task.status === 'done') {
    return days <= 0 ? '已截止' : `${days} 天后`
  }
  if (task.overdue) {
    return days < -1 ? `逾期 ${Math.abs(days)} 天` : '已逾期'
  }
  if (days === 0) {
    return '今天截止'
  }
  if (days === 1) {
    return '明天截止'
  }
  return `${days} 天后截止`
}

/** 截止时间副行：精确到分钟的本地日期。 */
function dueExact(task: TodoTask): string {
  const millis = dueAtMillis(task)
  return millis === null ? '未设置' : formatDue(millis)
}

function assigneeLabels(task: TodoTask): string[] {
  return directory.labels((task.participantIds ?? []).slice(0, 3))
}

function moreAssignees(task: TodoTask): number {
  return Math.max(0, (task.participantIds ?? []).length - 3)
}

function shift(direction: number) {
  if (view.value === 'week') {
    // 翻页步长跟着视图走：单周视图一次挪一周，多周视图一次挪 weeksPerView 周，翻页后不出现重复的那一周。
    anchor.value = addDays(anchor.value, direction * weeksPerView.value * 7)
    return
  }
  const next = new Date(anchor.value)
  next.setMonth(next.getMonth() + direction)
  anchor.value = next.getTime()
}

function backToToday() {
  anchor.value = today
  selected.value = toKey(today)
}

/** 点击日程格：把左侧清单切换为「当天截止」视图；月视图点到非本月日期时自动跳到对应月。 */
function selectDay(dateKey: string) {
  if (view.value === 'month' && !inCurrentMonth(dateKey)) {
    anchor.value = new Date(Number(dateKey.slice(0, 4)), Number(dateKey.slice(5, 7)) - 1, 1).getTime()
  }
  if (view.value === 'week') {
    maybeScrollSelectedWeek(dateKey)
  }
  selected.value = dateKey
  filter.value = 'day'
}

/**
 * 周视图选中最后一行（第三周）的日期时，把该周滚动为当前第一周：
 * 锚点直接跳到所选周所在周一，整体区间随之前移，所选日期落在新区间的第一行。
 */
function maybeScrollSelectedWeek(dateKey: string) {
  if (weeksPerView.value <= 1) {
    return
  }
  const millis = new Date(Number(dateKey.slice(0, 4)), Number(dateKey.slice(5, 7)) - 1, Number(dateKey.slice(8, 10))).getTime()
  const dayOffset = Math.floor((startOfDay(millis) - range.value.from) / 86_400_000)
  if (Math.floor(dayOffset / 7) >= weeksPerView.value - 1) {
    anchor.value = startOfWeek(millis)
  }
}

function isToday(dateKey: string): boolean {
  return dateKey === toKey(today)
}

function dayNumber(dateKey: string): number {
  return Number(dateKey.slice(8, 10))
}

/** 日期键（yyyy-MM-dd）转「M月d日」短文案，用于按日筛选提示。 */
function dayKeyLabel(dateKey: string): string {
  const month = Number(dateKey.slice(5, 7))
  const day = Number(dateKey.slice(8, 10))
  return `${month}月${day}日`
}

/** 行首文案：相对今天所在周给出「本周 / 下周」，更远的周直接写日期。 */
function weekLabel(startMillis: number): string {
  const weeks = Math.round((startOfDay(startMillis) - startOfWeek(today)) / (7 * 86_400_000))
  if (weeks === 0) {
    return '本周'
  }
  if (weeks === 1) {
    return '下周'
  }
  if (weeks === -1) {
    return '上周'
  }
  if (weeks === 2) {
    return '下下周'
  }
  return `${monthDay(startMillis)}当周`
}

/** 日期格表头里的「周几」，不带「周」字。 */
function weekdayLabel(dateKey: string): string {
  const index = (new Date(Number(dateKey.slice(0, 4)), Number(dateKey.slice(5, 7)) - 1, Number(dateKey.slice(8, 10))).getDay() + 6) % 7
  return WEEK_LABELS[index]
}

function monthDay(millis: number): string {
  const date = new Date(millis)
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

function dayPending(day: TodoScheduleDay): number {
  return day.total - day.done
}

function inCurrentMonth(dateKey: string): boolean {
  const target = new Date(anchor.value)
  return Number(dateKey.slice(5, 7)) === target.getMonth() + 1
}

function dueAtMillis(task: TodoTask): number | null {
  // types.ts 把 dueAt 声明为 number，但后端实际以字符串形式的 epoch 返回，这里统一归一。
  const raw = task.dueAt as number | string | null | undefined
  if (raw === null || raw === undefined || raw === '') {
    return null
  }
  const millis = Number(raw)
  return Number.isFinite(millis) && millis > 0 ? millis : null
}

function startOfDay(millis: number): number {
  const date = new Date(millis)
  date.setHours(0, 0, 0, 0)
  return date.getTime()
}

function addDays(millis: number, days: number): number {
  const date = new Date(millis)
  date.setDate(date.getDate() + days)
  return startOfDay(date.getTime())
}

/** 以周一为一周起点。 */
function startOfWeek(millis: number): number {
  const date = new Date(startOfDay(millis))
  const offset = (date.getDay() + 6) % 7
  return addDays(date.getTime(), -offset)
}

function toKey(millis: number): string {
  const date = new Date(millis)
  const pad = (value: number) => `${value}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
</script>

<template>
  <div ref="cardRef" class="dashboard-card__content todo-board" :style="boardStyle">
    <header ref="barRef" class="todo-board__bar">
      <ul class="todo-board__stats">
        <li>
          <YdAction
            type="button"
            class="todo-board__stat"
            :class="{ 'is-active': filter === 'pending' }"
            :aria-pressed="filter === 'pending'"
            title="只看未完成的待办，再点一次恢复全部"
            @click="toggleFilter('pending')"
          >
            <i class="todo-board__stat-dot is-primary" aria-hidden="true" />
            <strong>{{ pendingCount }}</strong>
            <small>未完成</small>
          </YdAction>
        </li>
        <li>
          <YdAction
            type="button"
            class="todo-board__stat"
            :class="{ 'is-active': filter === 'today' }"
            :aria-pressed="filter === 'today'"
            title="只看今天截止的待办，再点一次恢复全部"
            @click="toggleFilter('today')"
          >
            <i class="todo-board__stat-dot is-warning" aria-hidden="true" />
            <strong>{{ dueTodayCount }}</strong>
            <small>今日到期</small>
          </YdAction>
        </li>
        <li>
          <YdAction
            type="button"
            class="todo-board__stat"
            :class="{ 'is-active': filter === 'overdue' }"
            :aria-pressed="filter === 'overdue'"
            title="只看已逾期的待办，再点一次恢复全部"
            @click="toggleFilter('overdue')"
          >
            <i class="todo-board__stat-dot is-danger" aria-hidden="true" />
            <strong>{{ overdueCount }}</strong>
            <small>已逾期</small>
          </YdAction>
        </li>
        <li>
          <YdAction
            type="button"
            class="todo-board__stat"
            :class="{ 'is-active': filter === 'done' }"
            :aria-pressed="filter === 'done'"
            title="只看已完成的待办，再点一次恢复全部"
            @click="toggleFilter('done')"
          >
            <i class="todo-board__stat-dot is-success" aria-hidden="true" />
            <strong>{{ doneCount }}</strong>
            <small>已完成</small>
          </YdAction>
        </li>
      </ul>

      <div v-if="showCalendar" class="todo-board__tools">
        <YdAction type="button" class="todo-board__nav" aria-label="上一页" @click="shift(-1)">
          <YdIcon name="i-ri:arrow-left-s-line" />
        </YdAction>
        <span class="todo-board__range">{{ rangeLabel }}</span>
        <YdAction type="button" class="todo-board__nav" aria-label="下一页" @click="shift(1)">
          <YdIcon name="i-ri:arrow-right-s-line" />
        </YdAction>
        <YdAction type="button" class="todo-board__today" @click="backToToday">
          今天
        </YdAction>
        <YdTabs v-if="showViewTabs" v-model="view" :list="VIEW_TABS" class="todo-board__tabs" />
      </div>
    </header>

    <div v-if="error" class="todo-board__state">
      <YdIcon name="i-ri:error-warning-line" />
      <span>{{ error }}</span>
      <YdAction type="button" class="todo-board__retry" @click="load">
        重试
      </YdAction>
    </div>

    <div v-else class="todo-board__split" :style="splitStyle">
      <section class="todo-board__panel todo-board__panel--list">
        <header class="todo-board__panel-head">
          <h3>待办清单</h3>
          <small class="todo-board__hint">{{ listHint }}</small>
          <YdAction type="button" class="todo-board__link" @click="openAll">
            查看全部
            <YdIcon name="i-ri:arrow-right-s-line" />
          </YdAction>
        </header>

        <div class="todo-board__table">
          <div class="todo-board__thead">
            <span aria-hidden="true" />
            <span>待办</span>
            <span>优先级</span>
            <span>截止时间</span>
            <span class="todo-board__th-owner">负责人</span>
          </div>

          <div v-if="loading" class="todo-board__skeleton">
            <span v-for="index in 5" :key="index" class="todo-board__skeleton-row" />
          </div>

          <p v-else-if="visibleTasks.length === 0" class="todo-board__empty">
            <YdIcon name="i-ri:checkbox-circle-line" />
            这个范围内没有待办
          </p>

          <ul v-else class="todo-board__rows">
            <li v-for="task in visibleTasks" :key="task.id" class="todo-board__row-item">
              <YdAction
                type="button"
                class="todo-board__row"
                :class="{ 'is-done': task.status === 'done', 'is-overdue': task.overdue }"
                :title="`打开「${task.title}」查看详情`"
                @click="openTask(task)"
              >
                <span
                  class="todo-board__check"
                  :class="{ 'is-done': task.status === 'done' }"
                  aria-hidden="true"
                >
                  <YdIcon v-if="task.status === 'done'" name="i-ri:check-line" />
                </span>

                <span class="todo-board__cell-title">
                  <span class="todo-board__title" :title="task.title">{{ task.title }}</span>
                  <span v-if="task.subTaskTotal > 0" class="todo-board__sub">
                    小任务 {{ task.subTaskDone }}/{{ task.subTaskTotal }}
                  </span>
                  <span v-if="task.remark" class="todo-board__remark" :title="task.remark">{{ task.remark }}</span>
                </span>

                <span class="todo-board__priority" :style="{ color: priorityMeta(task.priority).tone }">
                  <i class="todo-board__dot" :style="{ background: priorityMeta(task.priority).tone }" />
                  {{ priorityMeta(task.priority).label }}
                </span>

                <span class="todo-board__due" :class="{ 'is-overdue': task.overdue }">
                  <span class="todo-board__due-main">{{ dueRelative(task) }}</span>
                  <span class="todo-board__due-date" :title="dueExact(task)">{{ dueExact(task) }}</span>
                </span>

                <span class="todo-board__assignees">
                  <span v-for="label in assigneeLabels(task)" :key="label" class="todo-board__assignee">{{ label }}</span>
                  <span v-if="moreAssignees(task) > 0" class="todo-board__assignee is-more">+{{ moreAssignees(task) }}</span>
                  <span v-if="assigneeLabels(task).length === 0" class="todo-board__assignee is-empty">仅自己</span>
                </span>
              </YdAction>
            </li>
          </ul>
        </div>

      </section>

      <section v-if="showCalendar" ref="calendarRef" class="todo-board__panel todo-board__panel--calendar">
        <header ref="calendarHeadRef" class="todo-board__panel-head">
          <h3>日程表</h3>
          <small class="todo-board__hint">
            {{ rangeDueCount > 0 ? `该范围截止 ${rangeDueCount} 条` : '该范围无截止' }}
          </small>
        </header>

        <p v-if="scheduleError" class="todo-board__empty">{{ scheduleError }}</p>
        <div v-else-if="loadingSchedule && days.length === 0" class="todo-board__empty">日程加载中…</div>

        <div v-else :key="`${view}-${rangeLabel}`" class="todo-board__calendar">
          <div v-if="view === 'week'" class="todo-board__weeks">
            <section v-for="row in weekRows" :key="row.key" class="todo-board__week">
              <header class="todo-board__week-head">
                <strong>{{ row.label }}</strong>
                <span>{{ row.range }}</span>
              </header>
              <div class="todo-board__week-grid">
                <YdAction
                  v-for="day in row.days"
                  :key="day.date"
                  type="button"
                  class="todo-board__day"
                  :class="{ 'is-today': isToday(day.date), 'is-selected': filter === 'day' && selected === day.date, 'is-idle': day.total === 0 && day.subTasks === 0 }"
                  @click="selectDay(day.date)"
                >
                  <header class="todo-board__day-head">
                    <span class="todo-board__day-num">
                      <em class="todo-board__day-week">{{ weekdayLabel(day.date) }}</em>
                      {{ dayNumber(day.date) }}
                    </span>
                  </header>
                  <footer v-if="day.total > 0 || day.subTasks > 0" class="todo-board__day-due">
                    <b class="todo-board__day-due-main">{{ day.total }}</b>
                    <span class="todo-board__day-due-sub">/{{ day.subTasks }}</span>
                  </footer>
                </YdAction>
              </div>
            </section>
          </div>

          <div v-if="view !== 'week'" class="todo-board__month">
            <div class="todo-board__weekdays">
              <span v-for="label in WEEK_LABELS" :key="label">{{ label }}</span>
            </div>
            <div class="todo-board__grid">
              <YdAction
                v-for="day in days"
                :key="day.date"
                type="button"
                class="todo-board__cell"
                :class="{
                  'is-today': isToday(day.date),
                  'is-selected': filter === 'day' && selected === day.date,
                  'is-outside': !inCurrentMonth(day.date),
                  'is-due': day.total > 0,
                  'is-clear': day.total > 0 && dayPending(day) === 0,
                }"
                @click="selectDay(day.date)"
              >
                <span class="todo-board__cell-num">{{ dayNumber(day.date) }}</span>
                <span v-if="day.total > 0" class="todo-board__cell-badge">{{ day.total }}</span>
              </YdAction>
            </div>
          </div>

        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ------------------------------------------------------------------ 骨架 */
/**
 * 主页卡片根节点。宿主把 .dashboard-card__content 写在 scoped 样式里（margin: 4px 16px 16px），
 * 这里用「双类名」把特异性抬到 (0,3,0) 稳过宿主那条规则，自己掌控内外边距：
 * 不用 margin 改用 padding，左右留白明显加大，也不再受宿主 16px 约束。
 * 高度交给双栏容器的 splitStyle（由卡片实测高度推导），根节点自身不再约束纵向尺寸。
 */
.dashboard-card__content.todo-board {
  display: grid;
  align-content: start;
  gap: var(--todo-card-gap);
  margin: 0;
  padding: var(--todo-sp-1) var(--todo-sp-8) 21px;
  min-width: 0;
}

/* ------------------------------------------------------------------ 顶栏 */
.todo-board__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--todo-sp-4);
  flex-wrap: wrap;
}

.todo-board__stats {
  display: flex;
  /* 筛选入口只此一处（面板头胶囊已去重），尽量一行完整展示，不再换行。 */
  flex-wrap: nowrap;
  gap: var(--todo-sp-1);
  margin: 0;
  padding: 0;
  list-style: none;
}

.todo-board__stat {
  display: inline-flex;
  align-items: center;
  gap: var(--todo-sp-2);
  padding: var(--todo-sp-1) var(--todo-sp-3);
  border: 1px solid transparent;
  border-radius: var(--todo-radius-pill);
  background: var(--color-fill-1, #f7f8fa);
  font: inherit;
  transition: background 0.18s ease, color 0.18s ease;
}

button.todo-board__stat {
  cursor: pointer;
}

button.todo-board__stat:hover {
  background: var(--color-fill-2, #f2f3f5);
  color: var(--todo-text);
}

button.todo-board__stat:focus-visible {
  outline: 2px solid rgba(22, 93, 255, 0.45);
  outline-offset: 1px;
}

/* 统计胶囊激活态：与筛选胶囊同色系，让「点统计 = 设筛选」的联动可感知。 */
.todo-board__stat.is-active {
  background: rgba(22, 93, 255, 0.1);
  color: var(--todo-primary);
}

.todo-board__stat-dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--todo-primary);
}

.todo-board__stat-dot.is-warning {
  background: var(--todo-warning);
}

.todo-board__stat-dot.is-danger {
  background: var(--todo-danger);
}

.todo-board__stat-dot.is-success {
  background: var(--todo-success);
}

.todo-board__stat strong {
  color: var(--todo-text);
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.todo-board__stat small {
  color: var(--todo-muted);
  font-size: 11px;
}

.todo-board__tools {
  display: flex;
  align-items: center;
  gap: var(--todo-sp-2);
  flex-wrap: wrap;
}

.todo-board__nav,
.todo-board__today {
  display: grid;
  place-items: center;
  min-width: 26px;
  height: 26px;
  padding: 0 var(--todo-sp-3);
  border: 1px solid var(--color-border-2, #e5e6eb);
  border-radius: var(--todo-radius-sm);
  background: var(--color-bg-2, #fff);
  color: var(--color-text-2, #4e5969);
  font-size: 12px;
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease, border-color 0.18s ease;
}

.todo-board__nav:hover,
.todo-board__today:hover {
  border-color: var(--color-border-3, #c9cdd4);
  background: var(--color-fill-1, #f7f8fa);
  color: var(--todo-text);
}

.todo-board__range {
  min-width: 116px;
  color: var(--todo-text);
  font-size: 13px;
  font-weight: 600;
  text-align: center;
}

.todo-board__tabs {
  width: auto;
}

/* ------------------------------------------------------------------ 状态 */
.todo-board__state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--todo-sp-3);
  min-height: 120px;
  color: var(--todo-danger);
  font-size: 13px;
}

.todo-board__retry {
  padding: var(--todo-sp-1) var(--todo-sp-3);
  border: 1px solid var(--color-border-2, #e5e6eb);
  border-radius: var(--todo-radius-sm);
  background: var(--color-bg-2, #fff);
  color: var(--todo-text);
  font-size: 12px;
  cursor: pointer;
}

/* ------------------------------------------------------------------ 双栏 */
/**
 * 双栏容器：高度由 splitStyle 内联锁定（卡片高度 - 上下内边距 - 顶栏 - 行间距）。
 * 并排时两个面板 stretch 到容器高度，清单超长在面板内滚动；堆叠（窄卡）时改为纵向分配，
 * 清单与日程各占一份弹性高度，同样不撑破容器 —— 布局切换全部由容器查询驱动。
 */
.todo-board__split {
  display: flex;
  flex-wrap: nowrap;
  /* 两面板顶边对齐、同高拉伸；谁的内容多谁自己内部滚。 */
  align-items: stretch;
  gap: var(--todo-sp-4);
  min-height: 0;
}

.todo-board__panel {
  display: flex;
  flex-direction: column;
  gap: var(--todo-sp-3);
  min-width: 0;
  min-height: 0;
}

.todo-board__panel--list {
  flex: 2 1 400px;
  /* 清单面板内容超出时自行滚动，整卡高度不受清单条数影响。 */
  overflow-y: auto;
  overscroll-behavior: contain;
}

.todo-board__panel--calendar {
  flex: 1 1 320px;
  overflow: hidden;
}

.todo-board__panel-head {
  display: flex;
  align-items: center;
  gap: var(--todo-sp-3);
  flex-wrap: wrap;
}

.todo-board__panel-head h3 {
  margin: 0;
  color: var(--todo-text);
  font-size: 13px;
  font-weight: 600;
}

.todo-board__hint {
  margin-left: auto;
  color: var(--todo-muted);
  font-size: 11px;
}

/* 面板头部的次级跳转入口，视觉上弱于筛选胶囊。 */
.todo-board__link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px var(--todo-sp-2);
  border: 1px solid var(--color-border-2, #e5e6eb);
  border-radius: var(--todo-radius-pill);
  background: var(--color-bg-2, #fff);
  color: var(--color-text-2, #4e5969);
  font-size: 11px;
  cursor: pointer;
  transition: border-color 0.18s ease, color 0.18s ease;
}

.todo-board__link:hover {
  border-color: var(--color-border-3, #c9cdd4);
  color: var(--todo-text);
}

/* ------------------------------------------------------------------ 表单式清单 */
/* 只保留横向滚动：表格有 min-width，介于并排门槛与面板最小宽之间时需要横向拖动。 */
.todo-board__table {
  min-width: 0;
  overflow-x: auto;
}

.todo-board__thead,
.todo-board__row {
  display: grid;
  align-items: center;
  gap: var(--todo-sp-2);
  grid-template-columns: 22px minmax(120px, 2fr) 52px 96px minmax(80px, 1.2fr);
  min-width: 380px;
}

.todo-board__thead {
  padding: var(--todo-sp-1) var(--todo-sp-2);
  border-bottom: 1px solid var(--color-border-1, #e5e6eb);
  background: var(--color-bg-2, #fff);
  color: var(--todo-muted);
  font-size: 11px;
}

.todo-board__rows {
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 整行是一个按钮：点哪都能跳转到「我的清单」，因此重置掉浏览器按钮默认样式。 */
.todo-board__row {
  width: 100%;
  padding: var(--todo-sp-3) var(--todo-sp-2);
  border: none;
  border-bottom: 1px solid var(--color-border-1, #e5e6eb);
  border-radius: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.18s ease;
}

.todo-board__rows li:last-child .todo-board__row {
  border-bottom: none;
}

.todo-board__row:hover {
  background: var(--color-fill-1, #f7f8fa);
}

.todo-board__row:hover .todo-board__title {
  color: var(--todo-primary);
}

.todo-board__row:focus-visible {
  outline: 2px solid var(--color-border-3, #c9cdd4);
  outline-offset: -2px;
}

.todo-board__row.is-done .todo-board__title {
  color: var(--todo-muted);
  text-decoration: line-through;
}

/* 完成状态只读展示，不接受点击，避免在概览卡片里误改数据。 */
.todo-board__check {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  padding: 0;
  border: 1.5px solid var(--color-border-3, #c9cdd4);
  border-radius: 50%;
  background: transparent;
  color: var(--todo-success);
  font-size: 11px;
  cursor: inherit;
}

.todo-board__check.is-done {
  border-color: var(--todo-success);
  background: rgb(var(--success-1));
}

.todo-board__cell-title {
  display: flex;
  align-items: center;
  gap: var(--todo-sp-2);
  min-width: 0;
}

.todo-board__title {
  overflow: hidden;
  color: var(--todo-text);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-board__sub,
.todo-board__remark {
  flex: none;
  overflow: hidden;
  max-width: 40%;
  color: var(--todo-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-board__remark {
  max-width: 34%;
  opacity: 0.85;
}

.todo-board__priority {
  display: inline-flex;
  align-items: center;
  gap: var(--todo-sp-1);
  font-size: 11px;
  font-weight: 600;
}

.todo-board__dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--todo-muted);
}

.todo-board__due {
  display: grid;
  gap: 1px;
  min-width: 0;
}

.todo-board__due-main {
  color: var(--color-text-2, #4e5969);
  font-size: 11px;
}

.todo-board__due.is-overdue .todo-board__due-main {
  color: var(--todo-danger);
  font-weight: 600;
}

.todo-board__due-date {
  overflow: hidden;
  color: var(--todo-muted);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-board__assignees {
  display: flex;
  flex-wrap: wrap;
  gap: var(--todo-sp-1);
  min-width: 0;
}

.todo-board__assignee {
  display: inline-flex;
  align-items: center;
  overflow: hidden;
  max-width: 72px;
  padding: 0 var(--todo-sp-2);
  height: 18px;
  border-radius: var(--todo-radius-pill);
  background: var(--color-fill-2, #f2f3f5);
  color: var(--color-text-2, #4e5969);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-board__assignee.is-more,
.todo-board__assignee.is-empty {
  background: var(--color-fill-1, #f7f8fa);
  color: var(--todo-muted);
}

.todo-board__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--todo-sp-2);
  margin: 0;
  padding: var(--todo-sp-5) 0;
  color: var(--todo-muted);
  font-size: 12px;
}

.todo-board__skeleton {
  display: grid;
  gap: var(--todo-sp-3);
  padding: var(--todo-sp-3) 0;
}

.todo-board__skeleton-row {
  height: 20px;
  border-radius: var(--todo-radius-sm);
  background: linear-gradient(90deg, var(--color-fill-2) 25%, var(--color-fill-3) 37%, var(--color-fill-2) 63%);
  background-size: 400% 100%;
  animation: todo-board-shimmer 1.4s ease infinite;
}

/* ------------------------------------------------------------------ 日程表 */
/* 面板高度由双栏容器锁定后，日期区吃掉标题与图例之外的剩余空间，
   月视图 / 周视图再各自把这份高度均分给日期行，卡片下方不会留白。 */
.todo-board__calendar {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  gap: var(--todo-sp-3);
  min-width: 0;
  min-height: 0;
  animation: todo-board-in 0.3s cubic-bezier(0.22, 1, 0.36, 1) backwards;
}

/* 周视图「一周一行」：铺几周由 weeksPerView 决定（宽度给下限、高度富余时继续加）。
   行高用 minmax(0, 1fr) 均分面板高度 —— 即使铺出的周数超出舒适高度也只会压缩格子内容
   （日期格内部裁切），整块日程区永不撑破面板。 */
/* 单周行固定高度：76px（WEEK_ROW_BASE_HEIGHT），不再随面板拉伸；
   面板高度富余由 weeksPerView 多铺几周消化，不足时在底部留白。 */
.todo-board__weeks {
  display: grid;
  flex: 1 1 auto;
  grid-auto-rows: 76px;
  gap: var(--todo-sp-3);
  min-height: 0;
  overflow: hidden;
}

.todo-board__week {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: var(--todo-sp-1);
  min-height: 0;
}

.todo-board__week-head {
  display: flex;
  align-items: baseline;
  gap: var(--todo-sp-2);
  color: var(--todo-muted);
  font-size: 11px;
}

.todo-board__week-head strong {
  color: var(--todo-text);
  font-size: 12px;
  font-weight: 600;
}

.todo-board__week-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: var(--todo-sp-2);
  min-height: 0;
}

.todo-board__day {
  display: flex;
  flex-direction: column;
  gap: var(--todo-sp-1);
  min-width: 0;
  min-height: 0;
  padding: var(--todo-sp-1);
  border: 1px solid var(--color-border-1, #e5e6eb);
  border-radius: var(--todo-radius-sm);
  background: var(--color-bg-2, #fff);
  cursor: pointer;
  overflow: hidden;
  transition: border-color 0.18s ease, background 0.18s ease, box-shadow 0.18s ease;
}

.todo-board__day:hover {
  border-color: rgba(22, 93, 255, 0.35);
  box-shadow: 0 2px 8px rgb(29 33 41 / 0.06);
}

.todo-board__day.is-selected {
  border-color: var(--color-primary-6, #165dff);
  background: rgba(22, 93, 255, 0.1);
  font-weight: 600;
}

.todo-board__day.is-idle {
  opacity: 0.72;
}

.todo-board__day-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--todo-sp-1);
}

.todo-board__day-num {
  display: inline-flex;
  align-items: baseline;
  gap: var(--todo-sp-1);
  color: var(--color-text-2, #4e5969);
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.todo-board__day-week {
  color: var(--todo-muted);
  font-size: 10px;
  font-style: normal;
  font-weight: 400;
}

/* 今日：整格淡橙填充（月视图同款）。 */
.todo-board__day.is-today {
  border-color: rgba(255, 125, 0, 0.4);
  background: rgba(255, 125, 0, 0.14);
}

.todo-board__day.is-today .todo-board__day-num {
  color: rgb(var(--orange-6, 255 125 0));
  font-weight: 700;
}

/* 底部截止统计：占满头部以下剩余空间并垂直居中；大任务大字号、小任务小字号做差分。 */
.todo-board__day-due {
  display: flex;
  flex: 1 1 auto;
  align-items: center;
  gap: 2px;
  min-height: 0;
  font-variant-numeric: tabular-nums;
}

.todo-board__day-due-main {
  color: var(--color-text-1, #1d2129);
  font-size: 14px;
  font-weight: 600;
  line-height: 1;
}

.todo-board__day-due-sub {
  color: var(--todo-muted);
  font-size: 11px;
  line-height: 1;
}

/* 月视图同样吃掉面板剩余高度：6 行日期格用 1fr 均分，整张月历刚好铺满卡片。 */
.todo-board__month {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  gap: var(--todo-sp-1);
  min-width: 0;
  min-height: 0;
}

.todo-board__weekdays,
.todo-board__grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: var(--todo-sp-1);
}

.todo-board__grid {
  flex: 1 1 auto;
  /* 28px 是日期格下限（格子里的数字与角标要放得下），超出部分由 1fr 均分给 6 行。 */
  grid-auto-rows: minmax(28px, 1fr);
  min-height: 0;
}

.todo-board__weekdays {
  color: var(--todo-muted);
  font-size: 11px;
  text-align: center;
}

.todo-board__cell {
  position: relative;
  display: grid;
  gap: var(--todo-sp-1);
  place-items: center;
  padding: var(--todo-sp-2) var(--todo-sp-1);
  border: 1px solid transparent;
  border-radius: var(--todo-radius-sm);
  background: var(--color-fill-1, #f7f8fa);
  color: var(--color-text-2, #4e5969);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  overflow: hidden;
  transition: background 0.18s ease, border-color 0.18s ease;
}

.todo-board__cell:hover {
  border-color: rgba(22, 93, 255, 0.35);
}

.todo-board__cell.is-outside {
  opacity: 0.42;
}

.todo-board__cell.is-due {
  background: var(--color-fill-2, #f2f3f5);
  color: var(--todo-text);
  font-weight: 600;
}

.todo-board__cell.is-clear {
  font-weight: 400;
  opacity: 0.72;
}

/* 今日：整格淡橙填充（周视图同款），与选中态（主题蓝底+实线边框）做差分。 */
.todo-board__cell.is-today {
  border-color: rgba(255, 125, 0, 0.4);
  background: rgba(255, 125, 0, 0.16);
  color: rgb(var(--orange-6, 255 125 0));
  font-weight: 700;
}

/* 今日同时被选中：选中态优先，底色转主题蓝（周/月视图统一，组合选择器优先级高于单类）。 */
.todo-board__day.is-today.is-selected,
.todo-board__cell.is-today.is-selected {
  border-color: var(--color-primary-6, #165dff);
  background: rgba(22, 93, 255, 0.1);
}

.todo-board__cell.is-selected {
  border-color: var(--color-primary-6, #165dff);
  background: rgba(22, 93, 255, 0.1);
  font-weight: 600;
}

.todo-board__cell-badge {
  position: absolute;
  top: 2px;
  right: 2px;
  min-width: 14px;
  padding: 0 4px;
  border-radius: var(--todo-radius-pill);
  background: var(--todo-primary);
  color: var(--color-bg-2, #fff);
  font-size: 10px;
  line-height: 14px;
  text-align: center;
}

@keyframes todo-board-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }

  to {
    opacity: 1;
    transform: none;
  }
}

@keyframes todo-board-shimmer {
  from {
    background-position: 100% 50%;
  }

  to {
    background-position: 0 50%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .todo-board__calendar {
    animation: none;
  }

  .todo-board__skeleton-row {
    animation: none;
    background: var(--color-fill-2);
  }

  .todo-board__row,
  .todo-board__day,
  .todo-board__cell,
  .todo-board__check {
    transition: none;
  }
}

/* 卡片变窄时逐级收窄左右留白，别让宽留白反过来把内容挤扁。
   注意选择器要带上根节点的双类名，否则特异性低于上面的默认值、覆盖不掉。 */
@container (max-width: 960px) {
  .dashboard-card__content.todo-board {
    padding-inline: var(--todo-sp-6);
  }

  .todo-board__stat {
    padding: 2px var(--todo-sp-2);
  }
}

/* 卡片被拖窄时（宿主卡片容器声明了 container-type: inline-size）：
   双栏切为上下堆叠，清单与日程按弹性比例瓜分容器高度，各自内部消化溢出，
   不再把卡片顶高；清单表格退化为「勾选 + 内容」两列，避免出现横向滚动条。
   断点必须与脚本端 SIDE_BY_SIDE_MIN_WIDTH（760px）一致。 */
@container (max-width: 759px) {
  .dashboard-card__content.todo-board {
    padding-inline: var(--todo-sp-5);
  }

  .todo-board__split {
    flex-direction: column;
    flex-wrap: nowrap;
  }

  .todo-board__panel--list {
    flex: 1 1 42%;
    min-height: 120px;
  }

  .todo-board__panel--calendar {
    flex: 1 1 58%;
    min-height: 132px;
  }
}

/* 并排的中等宽度卡（760-959，<760 已堆叠为全宽清单）：清单面板约 460-600px，
   隐藏负责人列并把表格 min-width 归零，彻底消除横向滚动；负责人信息在清单页仍可见。 */
@container (min-width: 760px) and (max-width: 959px) {
  .todo-board__assignees,
  .todo-board__th-owner {
    display: none;
  }

  .todo-board__thead,
  .todo-board__row {
    grid-template-columns: 22px minmax(120px, 2fr) 52px 96px;
    min-width: 0;
  }

  .todo-board__table {
    overflow-x: visible;
  }
}

@container (max-width: 560px) {
  .todo-board__table {
    overflow-x: visible;
  }

  .todo-board__thead {
    display: none;
  }

  .todo-board__row {
    grid-template-columns: 22px minmax(0, 1fr);
    row-gap: var(--todo-sp-1);
    min-width: 0;
  }

  .todo-board__priority,
  .todo-board__due,
  .todo-board__assignees {
    grid-column: 2;
  }

  .todo-board__due {
    grid-auto-flow: column;
    justify-content: start;
    gap: var(--todo-sp-2);
  }

  /* 极窄卡片下日期格只剩 60px 上下：「周几」会和「截止 N」挤在一起，去掉周几、收掉内边距，
     让一周依然保持「一行 7 格」，不再折成多行（折行会与「一周一行」的语义打架）。 */
  .todo-board__day {
    padding: var(--todo-sp-1);
  }

  .todo-board__day-week {
    display: none;
  }
}

/* 手机竖屏全宽卡（≤480px，与脚本端 SINGLE_WEEK_MAX_WIDTH 对应）：
   留白收到最小、翻页/今天按钮加大到 32px 触摸目标、面板高度下限放宽、统计胶囊更紧凑。 */
@container (max-width: 479px) {
  .dashboard-card__content.todo-board {
    padding-inline: var(--todo-sp-3);
  }

  .todo-board__stat {
    padding: 2px var(--todo-sp-2);
  }

  .todo-board__nav,
  .todo-board__today {
    min-width: 32px;
    height: 32px;
  }

  .todo-board__range {
    min-width: 0;
    font-size: 12px;
  }

  .todo-board__panel--list {
    min-height: 104px;
  }

  .todo-board__panel--calendar {
    min-height: 116px;
  }
}
</style>
