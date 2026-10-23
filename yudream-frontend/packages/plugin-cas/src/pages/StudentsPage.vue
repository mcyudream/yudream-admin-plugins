<script setup lang="ts">
import type { ActionMenuItem, SearchConfig, SearchFieldConfig, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { StudentProfile } from '../types'
import { YdAlert, YdButton, YdDrawer, YdDynamicSearchForm, YdLoadingOverlay, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout, YdTag, useYdToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createCasApi } from '../api/cas-api'
import { errorMessage } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createCasApi(props.sdk)
const toast = useYdToast()

const loading = ref(false)
const error = ref('')
const keyword = ref('')
const items = ref<StudentProfile[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const pageSizes = [10, 20, 50]
const archiveAvailable = ref(true)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<StudentProfile | null>(null)

const rawAttributesJson = computed(() => {
  if (!detail.value?.rawAttributes) {
    return ''
  }
  try {
    return JSON.stringify(JSON.parse(detail.value.rawAttributes), null, 2)
  }
  catch {
    return detail.value.rawAttributes
  }
})

/** 本页绑定统计：已绑定数量；宿主不支持查询时不展示统计，避免误导。 */
const boundCount = computed(() => items.value.filter(row => row.binding?.bound).length)
const bindingQueryable = computed(() => items.value.some(row => row.binding?.available))

const columns: TableColumn<StudentProfile>[] = [
  { accessorKey: 'socialUid', header: '学工号', width: 140 },
  { id: 'binding', header: '绑定账号', width: 150, enableSorting: false },
  { accessorKey: 'name', header: '姓名', width: 110 },
  { id: 'college', header: '学院', minWidth: 180, enableSorting: false },
  { id: 'className', header: '班级', minWidth: 180, enableSorting: false },
  { accessorKey: 'loginCount', header: '登录次数', width: 90 },
  { accessorKey: 'lastSeenAt', header: '最近登录', width: 170 },
]

/** 行操作：详情抽屉 */
const rowActions: ActionMenuItem<StudentProfile>[] = [
  { label: '详情', icon: 'morph:eye', onClick: row => openDetail(row.socialUid) },
]

/** ControlType 是 components 内部的字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({ keyword: '' })

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'keyword', label: '关键词', placeholder: '学工号 / 姓名 / 邮箱 / 电话', order: 1, showWhenFolded: true, width: '260px' }),
  ],
}

function applySearch() {
  keyword.value = String(searchQuery.value.keyword ?? '')
  page.value = 1
  void load()
}

function resetSearch() {
  keyword.value = ''
  searchQuery.value = { keyword: '' }
  page.value = 1
  void load()
}

function bindingLabel(row: StudentProfile) {
  const binding = row.binding
  if (binding?.bound) {
    return binding.username || binding.nickname || binding.userId || '已绑定'
  }
  return ''
}

/** 学院 / 班级来自学生档案插件；对方不可用或该学号未填写时显示 —。 */
function archiveValue(row: StudentProfile, key: 'college' | 'className') {
  return row.archive?.filled ? (row.archive[key] || '—') : '—'
}

function formatTime(value: number | string) {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  // 宿主会把 Long 序列化成字符串，必须先转数字，否则 Invalid Date
  const timestamp = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(timestamp) || timestamp <= 0) {
    return '—'
  }
  return new Date(timestamp).toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api.students(page.value, size.value, keyword.value.trim() || undefined)
    items.value = result.items || []
    total.value = result.total || 0
    archiveAvailable.value = result.archiveAvailable !== false
    if (page.value > 1 && items.value.length === 0 && total.value > 0) {
      page.value = Math.max(1, page.value - 1)
      await load()
    }
  }
  catch (caught) {
    error.value = errorMessage(caught, '加载学生信息失败')
  }
  finally {
    loading.value = false
  }
}

async function openDetail(socialUid: string) {
  detailLoading.value = true
  detail.value = null
  detailVisible.value = true
  try {
    detail.value = await api.studentDetail(socialUid)
  }
  catch (caught) {
    toast.error(errorMessage(caught, '加载学生详情失败'))
    detailVisible.value = false
  }
  finally {
    detailLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <YdTablePageLayout title="学生信息">
    <template #search="{ fold }">
      <YdDynamicSearchForm
        v-model="searchQuery"
        :config="searchConfig"
        :fold="fold"
        @search="applySearch"
        @reset="resetSearch"
      />
    </template>

    <template #table>
      <div class="flex h-full min-h-0 flex-col">
        <YdAlert v-if="error" class="mb-3 shrink-0" variant="destructive" title="加载失败" :description="error" />
        <YdAlert
          v-if="!archiveAvailable"
          class="mb-3 shrink-0"
          title="学生档案插件不可用"
          description="未安装或未启用学生档案插件（yudream-student-info），学院 / 班级列暂时为空；安装后自动读取。"
        />
        <YdLoadingOverlay :loading="detailLoading || loading" class="min-h-0 flex-1">
          <YdResponsiveEnhancedTable
            :columns="columns"
            :data="items"
            row-key="socialUid"
            table-root-class="rounded-lg overflow-hidden"
            table-class="min-w-[960px]"
            border
            stripe
            column-visibility
            :actions="rowActions"
            empty-text="暂无数据：成员通过统一身份认证登录一次后，认证身份会自动出现在这里"
          >
            <template #cell-socialUid="{ row }">
              <span class="cas-mono" :title="row.original.socialUid">{{ row.original.socialUid }}</span>
            </template>
            <template #cell-binding="{ row }">
              <YdTag v-if="row.original.binding?.bound">{{ bindingLabel(row.original) }}</YdTag>
              <span v-else-if="row.original.binding && !row.original.binding.available" class="cas-time" :title="row.original.binding.message || ''">无法查询</span>
              <span v-else class="cas-time">未绑定</span>
            </template>
            <template #cell-name="{ row }">{{ row.original.name || '—' }}</template>
            <template #cell-college="{ row }">{{ archiveValue(row.original, 'college') }}</template>
            <template #cell-className="{ row }">{{ archiveValue(row.original, 'className') }}</template>
            <template #cell-lastSeenAt="{ row }">
              <span class="cas-time">{{ formatTime(row.original.lastSeenAt) }}</span>
            </template>
          </YdResponsiveEnhancedTable>
        </YdLoadingOverlay>
        <p v-if="bindingQueryable" class="cas-page-meta shrink-0">
          本页已绑定 {{ boundCount }} / {{ items.length }}
        </p>
      </div>
    </template>

    <template #footer>
      <YdPagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        :sizes="pageSizes"
        @page-change="load"
        @size-change="applySearch"
      />
    </template>
  </YdTablePageLayout>

  <YdDrawer
    v-model="detailVisible"
    :title="detail ? `学生详情 · ${detail.socialUid}` : '学生详情'"
    :show-confirm-button="false"
    :footer="false"
    content-class="cas-detail"
  >
    <div v-if="detailLoading" class="cas-empty">
      加载中…
    </div>
    <div v-else-if="detail" class="cas-detail">
      <dl class="cas-dl">
        <div><dt>学工号</dt><dd><span class="cas-mono">{{ detail.socialUid }}</span></dd></div>
        <div><dt>姓名</dt><dd>{{ detail.name || '—' }}</dd></div>
        <div><dt>邮箱</dt><dd>{{ detail.email || '—' }}</dd></div>
        <div><dt>电话</dt><dd>{{ detail.phone || '—' }}</dd></div>
        <div><dt>协议</dt><dd><YdTag>{{ detail.protocol || '—' }}</YdTag></dd></div>
        <div><dt>登录次数</dt><dd>{{ detail.loginCount }}</dd></div>
        <div><dt>首次记录</dt><dd>{{ formatTime(detail.firstSeenAt) }}</dd></div>
        <div><dt>最近登录</dt><dd>{{ formatTime(detail.lastSeenAt) }}</dd></div>
      </dl>

      <div class="cas-section">
        <p class="cas-hint">
          学生档案（来自 yudream-student-info 插件，本插件只读、不写入）：学院与班级以那里的记录为准。
        </p>
        <dl v-if="detail.archive?.filled" class="cas-dl">
          <div><dt>姓名</dt><dd>{{ detail.archive.studentName || '—' }}</dd></div>
          <div><dt>学院</dt><dd>{{ detail.archive.college || '—' }}</dd></div>
          <div><dt>班级</dt><dd>{{ detail.archive.className || '—' }}</dd></div>
        </dl>
        <p v-else-if="detail.archive && !detail.archive.available" class="cas-hint">
          {{ detail.archive.message || '未安装学生档案插件' }}
        </p>
        <p v-else class="cas-empty">
          该学号尚未在学生档案插件里填写学院 / 班级。
        </p>
      </div>

      <div class="cas-section">
        <p class="cas-hint">
          本站账号绑定：宿主 external account 表里 (登录通道, 协议, 学工号) 命中的那条绑定记录。
        </p>
        <dl v-if="detail.binding?.bound" class="cas-dl">
          <div><dt>本站账号</dt><dd><span class="cas-mono">{{ detail.binding.username || '—' }}</span></dd></div>
          <div><dt>用户 ID</dt><dd><span class="cas-mono">{{ detail.binding.userId || '—' }}</span></dd></div>
          <div><dt>昵称</dt><dd>{{ detail.binding.nickname || '—' }}</dd></div>
          <div><dt>邮箱</dt><dd>{{ detail.binding.email || '—' }}</dd></div>
          <div><dt>手机</dt><dd>{{ detail.binding.phone || '—' }}</dd></div>
          <div><dt>账号状态</dt><dd>{{ detail.binding.status || '—' }}</dd></div>
        </dl>
        <p v-else-if="detail.binding && !detail.binding.available" class="cas-hint cas-hint--danger">
          {{ detail.binding.message || '宿主不支持查询绑定信息' }}
        </p>
        <p v-else class="cas-empty">
          该学工号尚未绑定本站账号。
        </p>
      </div>

      <div class="cas-section">
        <p class="cas-hint">
          认证服务返回的原始属性，用于核对学校到底返回了哪些字段。
        </p>
        <pre v-if="rawAttributesJson" class="cas-raw-json">{{ rawAttributesJson }}</pre>
        <p v-else class="cas-empty">
          本次登录未返回属性（CAS attributes 为空、OIDC userinfo 无文本字段或 SSO 中转未返回学院 / 班级）。
        </p>
      </div>
    </div>
  </YdDrawer>
</template>

<style scoped>
.cas-mono {
  font-family: var(--font-family-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}

.cas-time {
  color: var(--color-text-3);
  font-size: 12px;
}

.cas-page-meta {
  margin: 8px 0 0;
  color: var(--color-text-3);
  font-size: 12px;
}

.cas-detail {
  display: grid;
  gap: 16px;
}

.cas-dl {
  display: grid;
  gap: 8px 12px;
  margin: 0;
  font-size: 13px;
}

.cas-dl > div {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  gap: 8px 12px;
}

.cas-dl dt {
  color: var(--color-text-3);
}

.cas-dl dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.cas-section {
  display: grid;
  gap: 8px;
}

.cas-hint {
  color: var(--color-text-3);
  font-size: 12px;
  line-height: 1.5;
}

.cas-hint--danger {
  color: var(--color-danger, #f53f3f);
}

.cas-empty {
  color: var(--color-text-3);
  font-size: 13px;
}

.cas-raw-json {
  max-height: 320px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  font-size: 12px;
  line-height: 1.6;
  border-radius: 8px;
  background: var(--color-fill-2, #f2f3f5);
  border: 1px solid var(--color-border, #e5e6eb);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
