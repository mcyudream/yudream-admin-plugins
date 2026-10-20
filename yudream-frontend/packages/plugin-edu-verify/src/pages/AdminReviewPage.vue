<script setup lang="ts">
import type { ActionMenuItem, SearchConfig, SearchFieldConfig, SearchOptionsSource, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { MaterialItem, VerificationRecord } from '../types'
import { YdAlert, YdButton, YdDrawer, YdIcon, YdInput, YdLabel, YdLoadingOverlay, YdModal, YdDynamicSearchForm, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout, YdTag, YdTextarea, useYdToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createEduVerifyApi } from '../api/edu-verify-api'
import { channelLabel, errorMessage, formatSize, formatTime, isImageMaterial, kindLabel, statusLabel, statusVariant } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createEduVerifyApi(props.sdk)
const toast = useYdToast()

const loading = ref(false)
const acting = ref(false)
const error = ref('')
const records = ref<VerificationRecord[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const pageSizes = [10, 20, 50]
const status = ref('')
const channel = ref('')
const keyword = ref('')
const drawerOpen = ref(false)
const detail = ref<VerificationRecord | null>(null)
const approveOpen = ref(false)
const approveTarget = ref<VerificationRecord | null>(null)
const approveRealName = ref('')
const approveSchoolName = ref('')
const reasonOpen = ref(false)
const reasonAction = ref<'reject' | 'revoke'>('reject')
const reason = ref('')
const reasonTarget = ref<VerificationRecord | null>(null)

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '待审核', value: 'PENDING' },
  { label: '等待学信网邮件', value: 'PENDING_MAIL' },
  { label: '已通过', value: 'PASSED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已过期', value: 'EXPIRED' },
  { label: '已撤销', value: 'REVOKED' },
]
const channelOptions = [
  { label: '全部渠道', value: '' },
  { label: '教育邮箱', value: 'EMAIL' },
  { label: '学信网', value: 'CHSI' },
  { label: '人工审核', value: 'MANUAL' },
]
const columns: TableColumn<VerificationRecord>[] = [
  { id: 'email', header: '申请人', minWidth: 220 },
  { accessorKey: 'channel', header: '渠道', width: 110 },
  { accessorKey: 'realName', header: '姓名', width: 110 },
  { accessorKey: 'schoolName', header: '学校', minWidth: 160 },
  { accessorKey: 'submittedAt', header: '提交时间', width: 170 },
]

/** 行操作统一收进操作菜单；通过/驳回/撤销按行状态出现并保持确认流程 */
const rowActions: ActionMenuItem<VerificationRecord>[] = [
  { label: '详情', icon: 'morph:eye', onClick: row => openDetail(row) },
  { label: '通过', icon: 'morph:check', visible: row => row.status === 'PENDING' || row.status === 'REJECTED', disabled: () => acting.value, onClick: row => openApprove(row) },
  { label: '驳回', icon: 'morph:ban', danger: true, visible: row => row.status === 'PENDING', disabled: () => acting.value, onClick: row => openReason(row, 'reject') },
  { label: '撤销', icon: 'morph:undo', danger: true, visible: row => row.status === 'PASSED' && !row.userId, disabled: () => acting.value, onClick: row => openReason(row, 'revoke') },
]

/** ControlType 是 components 内部的字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({ keyword: keyword.value, status: status.value, channel: channel.value })

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'keyword', label: '关键词', placeholder: '邮箱 / 姓名 / 学校', order: 1, showWhenFolded: true, width: '240px' }),
    searchField({ fieldName: 'status', label: '状态', controlType: 'SELECT', order: 2, width: '200px' }),
    searchField({ fieldName: 'channel', label: '渠道', controlType: 'SELECT', order: 3, width: '180px' }),
  ],
}

const optionsSources: SearchOptionsSource[] = [
  { id: 'statusOptions', loader: () => statusOptions },
  { id: 'channelOptions', loader: () => channelOptions },
]

function applySearch() {
  keyword.value = String(searchQuery.value.keyword ?? '')
  status.value = String(searchQuery.value.status ?? '')
  channel.value = String(searchQuery.value.channel ?? '')
  search()
}

function resetSearch() {
  keyword.value = ''
  status.value = ''
  channel.value = ''
  searchQuery.value = { keyword: '', status: '', channel: '' }
  search()
}

/** 状态语义色：通过绿、待处理橙、拒绝/撤销红、过期与待邮灰 */
function statusTone(status: string): 'info' | 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'PASSED') return 'success'
  if (status === 'PENDING') return 'warning'
  if (status === 'REJECTED' || status === 'REVOKED') return 'danger'
  if (status === 'PENDING_MAIL') return 'info'
  return 'neutral'
}

const reasonTitle = computed(() => reasonAction.value === 'reject' ? '驳回认证' : '撤销认证')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api.adminPage(status.value, channel.value, keyword.value.trim(), page.value, size.value)
    records.value = result.records || []
    total.value = result.total || 0
    if (records.value.length === 0 && total.value > 0 && page.value > 1) {
      page.value = Math.max(1, page.value - 1)
      await load()
    }
  }
  catch (cause) {
    error.value = errorMessage(cause, '加载认证列表失败')
  }
  finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

async function openDetail(row: VerificationRecord) {
  try {
    detail.value = await api.adminDetail(row.id)
    drawerOpen.value = true
  }
  catch (cause) {
    toast.error(errorMessage(cause, '加载详情失败'))
  }
}

function openApprove(row: VerificationRecord) {
  approveTarget.value = row
  approveRealName.value = String(row.realName || '').trim()
  approveSchoolName.value = String(row.schoolName || '').trim()
  approveOpen.value = true
}

async function handleApproveClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    if (!acting.value) {
      done()
    }
    return
  }
  const target = approveTarget.value
  const name = approveRealName.value.trim()
  const school = approveSchoolName.value.trim()
  if (!target || acting.value) {
    return
  }
  if (!name) {
    toast.error('请填写真实姓名')
    return
  }
  if (!school) {
    toast.error('请填写学校名称')
    return
  }
  acting.value = true
  try {
    await api.approve(target.id, { realName: name, schoolName: school })
    toast.success('已通过，姓名与学校已写入人员管理留档')
    done()
    await load()
    if (detail.value?.id === target.id) {
      detail.value = await api.adminDetail(target.id)
    }
  }
  catch (cause) {
    toast.error(errorMessage(cause, '通过失败'))
  }
  finally {
    acting.value = false
  }
}

function openReason(row: VerificationRecord, action: 'reject' | 'revoke') {
  reasonTarget.value = row
  reasonAction.value = action
  reason.value = ''
  reasonOpen.value = true
}

async function handleReasonClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    if (!acting.value) {
      done()
    }
    return
  }
  const target = reasonTarget.value
  const text = reason.value.trim()
  if (!target || acting.value) {
    return
  }
  if (!text) {
    toast.error('请填写原因')
    return
  }
  acting.value = true
  try {
    if (reasonAction.value === 'reject') {
      await api.reject(target.id, text)
      toast.success('已驳回')
    }
    else {
      await api.revoke(target.id, text)
      toast.success('已撤销')
    }
    done()
    await load()
    if (detail.value?.id === target.id) {
      detail.value = await api.adminDetail(target.id)
    }
  }
  catch (cause) {
    toast.error(errorMessage(cause, '操作失败'))
  }
  finally {
    acting.value = false
  }
}

function previewHref(item: MaterialItem) {
  return item.previewMode === 'KKFILE' || item.previewMode === 'DIRECT' ? item.previewUrl || '' : ''
}

function isPreviewable(item: MaterialItem) {
  return Boolean(previewHref(item))
}

function imagePreviewUrl(item: MaterialItem) {
  return item.previewMode === 'DIRECT' && isImageMaterial(item) ? item.thumbnailUrl || item.previewUrl || '' : ''
}

function onThumbnailError(event: Event) {
  const target = event.target as HTMLImageElement
  target.style.display = 'none'
}

onMounted(load)
</script>

<template>
  <YdTablePageLayout title="认证审核">
    <template #search="{ fold }">
        <YdDynamicSearchForm
          v-model="searchQuery"
          :config="searchConfig"
          :fold="fold"
          :options-sources="optionsSources"
          @search="applySearch"
          @reset="resetSearch"
        />
    </template>

      <template #table>
        <YdAlert v-if="error" class="mb-3" variant="destructive" title="加载失败" :description="error" />
        <YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
        :columns="columns"
        :data="records"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[900px]"
        border
        stripe
        column-visibility
        :actions="rowActions"
        empty-text="暂无认证记录"
      >
        <template #cell-email="{ row }">
          <div class="ev-user-cell">
            <strong>{{ row.original.email || '-' }}</strong>
            <span>{{ row.original.userId ? `用户 ${row.original.userId}` : '尚未绑定账号' }}</span>
          </div>
        </template>
        <template #cell-channel="{ row }">
          {{ channelLabel(row.original.channel, row.original.channelName) }}
        </template>
        <template #cell-status="{ row }">
          <span class="yd-status-tone" :data-tone="statusTone(row.original.status)">
            {{ statusLabel(row.original.status, row.original.statusName) }}
          </span>
        </template>
        <template #cell-realName="{ row }">{{ row.original.realName || '-' }}</template>
        <template #cell-schoolName="{ row }">{{ row.original.schoolName || '-' }}</template>
        <template #cell-submittedAt="{ row }">{{ formatTime(row.original.submittedAt) }}</template>
      </YdResponsiveEnhancedTable></YdLoadingOverlay>
      </template>

      <template #footer>
        <YdPagination
          v-model:page="page"
          v-model:size="size"
          :total="total"
          :sizes="pageSizes"
          @page-change="load"
          @size-change="search"
        />
      </template>
    </YdTablePageLayout>

    <YdDrawer
      v-model="drawerOpen"
      :title="detail ? `${detail.email || '认证详情'}` : '认证详情'"
      :show-confirm-button="false"
      :footer="false"
      content-class="ev-drawer"
    >
      <div v-if="detail" class="ev-stack">
        <dl class="ev-detail">
          <div><dt>状态</dt><dd><YdTag :variant="statusVariant(detail.status)">{{ statusLabel(detail.status, detail.statusName) }}</YdTag></dd></div>
          <div><dt>渠道</dt><dd>{{ channelLabel(detail.channel, detail.channelName) }}</dd></div>
          <div><dt>邮箱</dt><dd>{{ detail.email || '-' }}</dd></div>
          <div><dt>用户</dt><dd>{{ detail.userId || '尚未绑定' }}</dd></div>
          <div><dt>姓名</dt><dd>{{ detail.realName || '-' }}</dd></div>
          <div><dt>学校</dt><dd>{{ detail.schoolName || '-' }}</dd></div>
          <div v-if="detail.vcode"><dt>验证码</dt><dd>{{ detail.vcode }}</dd></div>
          <div v-if="detail.note"><dt>备注</dt><dd>{{ detail.note }}</dd></div>
          <div v-if="detail.reason"><dt>处理说明</dt><dd>{{ detail.reason }}</dd></div>
          <div><dt>提交时间</dt><dd>{{ formatTime(detail.submittedAt) }}</dd></div>
          <div><dt>处理时间</dt><dd>{{ formatTime(detail.decidedAt) }}</dd></div>
          <div><dt>有效期至</dt><dd>{{ formatTime(detail.expiresAt) }}</dd></div>
        </dl>
        <div v-if="detail.materials?.length" class="ev-material-grid">
          <a
            v-for="item in detail.materials"
            :key="item.objectKey || item.filename"
            class="ev-material-card"
            :href="previewHref(item) || undefined"
            :target="isPreviewable(item) ? '_blank' : undefined"
            :rel="isPreviewable(item) ? 'noreferrer' : undefined"
          >
            <img v-if="imagePreviewUrl(item)" :src="imagePreviewUrl(item)" :alt="item.filename" class="ev-material-thumb" @error="onThumbnailError">
            <div v-else class="ev-material-file">{{ kindLabel(item.kind) }}</div>
            <div class="ev-material-name">{{ item.filename || kindLabel(item.kind) }}</div>
            <span class="ev-record-meta">{{ kindLabel(item.kind) }} {{ formatSize(item.size) }}</span>
            <span v-if="item.previewMode === 'NONE'" class="ev-record-meta">{{ item.previewMessage || '当前文件不可预览' }}</span>
          </a>
        </div>
        <p v-else class="ev-muted">没有可预览的证明材料。</p>
        <div class="ev-actions ev-actions-end">
          <YdButton
            v-if="detail.status === 'PENDING' || detail.status === 'REJECTED'"
            :disabled="acting"
            @click="openApprove(detail)"
          >
            通过
          </YdButton>
          <YdButton
            v-if="detail.status === 'PENDING'"
            variant="destructive"
            :disabled="acting"
            @click="openReason(detail, 'reject')"
          >
            驳回
          </YdButton>
          <YdButton
            v-if="detail.status === 'PASSED'"
            variant="destructive"
            :disabled="acting"
            @click="openReason(detail, 'revoke')"
          >
            撤销
          </YdButton>
        </div>
      </div>
    </YdDrawer>

    <YdModal
      v-model="approveOpen"
      title="通过认证"
      :confirm-button-loading="acting"
      :before-close="handleApproveClose"
    >
      <div class="ev-stack">
        <p class="ev-muted">通过前必须核对并填写姓名、学校，写入人员管理留档。学信网转入的申请若申请人未填，请管理员在此补全或纠正。</p>
        <YdLabel label="真实姓名" class="ev-field">
          <YdInput v-model="approveRealName" class="w-full" maxlength="40" placeholder="与证件或学信网报告一致" />
        </YdLabel>
        <YdLabel label="学校名称" class="ev-field">
          <YdInput v-model="approveSchoolName" class="w-full" maxlength="80" placeholder="如：某某大学" />
        </YdLabel>
      </div>
    </YdModal>

    <YdModal
      v-model="reasonOpen"
      :title="reasonTitle"
      :confirm-button-loading="acting"
      :before-close="handleReasonClose"
    >
      <YdLabel :label="reasonAction === 'reject' ? '驳回原因' : '撤销原因'" class="ev-field">
        <YdTextarea v-model="reason" :rows="4" maxlength="200" placeholder="必填，将通知申请人" />
      </YdLabel>
    </YdModal>
</template>
