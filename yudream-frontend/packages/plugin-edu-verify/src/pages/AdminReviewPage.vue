<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { MaterialItem, VerificationRecord } from '../types'
import { FaAlert, FaButton, FaCard, FaDrawer, FaIcon, FaInput, FaLabel, FaModal, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaTable, FaTag, FaTextarea, useFaToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createEduVerifyApi } from '../api/edu-verify-api'
import { channelLabel, errorMessage, formatSize, formatTime, isImageMaterial, kindLabel, statusLabel, statusVariant } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createEduVerifyApi(props.sdk)
const toast = useFaToast()

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
  { id: 'email', header: '申请人', minWidth: 220, fixed: 'left' },
  { accessorKey: 'channel', header: '渠道', width: 110 },
  { accessorKey: 'status', header: '状态', width: 100 },
  { accessorKey: 'realName', header: '姓名', width: 110 },
  { accessorKey: 'schoolName', header: '学校', minWidth: 160 },
  { accessorKey: 'submittedAt', header: '提交时间', width: 170 },
  { id: 'operation', header: '操作', width: 240, fixed: 'right' },
]

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
  <section class="ev-page">
    <FaPageHeader title="认证审核" :description="`共 ${total} 条认证记录`">
      <FaButton variant="outline" :loading="loading" @click="load">
        <FaIcon name="i-ri:refresh-line" />
        刷新
      </FaButton>
    </FaPageHeader>

    <FaPageMain>
      <FaAlert v-if="error" variant="destructive" title="加载失败" :description="error" />
      <FaSearchBar class="w-full">
        <div class="ev-toolbar">
          <FaInput v-model="keyword" placeholder="邮箱 / 姓名 / 学校" clearable @keydown.enter="search" @clear="search" />
          <FaSelect v-model="status" :options="statusOptions" @change="search" />
          <FaSelect v-model="channel" :options="channelOptions" @change="search" />
          <FaButton variant="outline" @click="search">
            <FaIcon name="i-ri:search-line" />
            查询
          </FaButton>
        </div>
      </FaSearchBar>

      <div class="ev-desktop-only">
        <FaTable
          v-loading="loading"
          :columns="columns"
          :data="records"
          row-key="id"
          table-root-class="ev-table-scroll rounded-lg overflow-hidden"
          table-class="ev-table-w1080"
          border
          stripe
          column-visibility
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
            <FaTag :variant="statusVariant(row.original.status)">
              {{ statusLabel(row.original.status, row.original.statusName) }}
            </FaTag>
          </template>
          <template #cell-realName="{ row }">{{ row.original.realName || '-' }}</template>
          <template #cell-schoolName="{ row }">{{ row.original.schoolName || '-' }}</template>
          <template #cell-submittedAt="{ row }">{{ formatTime(row.original.submittedAt) }}</template>
          <template #cell-operation="{ row }">
            <div class="ev-row-actions">
              <FaButton size="sm" variant="outline" @click="openDetail(row.original)">详情</FaButton>
              <FaButton
                v-if="row.original.status === 'PENDING' || row.original.status === 'REJECTED'"
                size="sm"
                :disabled="acting"
                @click="openApprove(row.original)"
              >
                通过
              </FaButton>
              <FaButton
                v-if="row.original.status === 'PENDING'"
                size="sm"
                variant="destructive"
                :disabled="acting"
                @click="openReason(row.original, 'reject')"
              >
                驳回
              </FaButton>
              <FaButton
                v-if="row.original.status === 'PASSED' && !row.original.userId"
                size="sm"
                variant="destructive"
                :disabled="acting"
                @click="openReason(row.original, 'revoke')"
              >
                撤销
              </FaButton>
            </div>
          </template>
        </FaTable>
      </div>

      <div class="ev-mobile-only">
        <div v-loading="loading" class="ev-mobile-list">
          <FaCard v-for="row in records" :key="row.id">
            <div class="ev-record-card">
              <div class="ev-record-head">
                <div class="ev-record-title">
                  <FaTag :variant="statusVariant(row.status)">{{ statusLabel(row.status, row.statusName) }}</FaTag>
                  <strong>{{ row.email || '-' }}</strong>
                </div>
                <span class="ev-record-meta">{{ channelLabel(row.channel, row.channelName) }}</span>
              </div>
              <div class="ev-muted">{{ row.realName || '-' }} · {{ row.schoolName || '-' }}</div>
              <div class="ev-record-meta">提交于 {{ formatTime(row.submittedAt) }}</div>
              <div class="ev-actions ev-actions-end">
                <FaButton size="sm" variant="outline" @click="openDetail(row)">详情</FaButton>
                <FaButton v-if="row.status === 'PENDING' || row.status === 'REJECTED'" size="sm" :disabled="acting" @click="openApprove(row)">通过</FaButton>
                <FaButton v-if="row.status === 'PENDING'" size="sm" variant="destructive" :disabled="acting" @click="openReason(row, 'reject')">驳回</FaButton>
                <FaButton v-if="row.status === 'PASSED'" size="sm" variant="destructive" :disabled="acting" @click="openReason(row, 'revoke')">撤销</FaButton>
              </div>
            </div>
          </FaCard>
          <div v-if="!loading && records.length === 0" class="ev-mobile-empty">暂无认证记录</div>
        </div>
      </div>

      <FaPagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        :sizes="pageSizes"
        class="mt-3"
        @page-change="load"
        @size-change="search"
      />
    </FaPageMain>

    <FaDrawer
      v-model="drawerOpen"
      :title="detail ? `${detail.email || '认证详情'}` : '认证详情'"
      :show-confirm-button="false"
      :footer="false"
      content-class="ev-drawer"
    >
      <div v-if="detail" class="ev-stack">
        <dl class="ev-detail">
          <div><dt>状态</dt><dd><FaTag :variant="statusVariant(detail.status)">{{ statusLabel(detail.status, detail.statusName) }}</FaTag></dd></div>
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
          <FaButton
            v-if="detail.status === 'PENDING' || detail.status === 'REJECTED'"
            :disabled="acting"
            @click="openApprove(detail)"
          >
            通过
          </FaButton>
          <FaButton
            v-if="detail.status === 'PENDING'"
            variant="destructive"
            :disabled="acting"
            @click="openReason(detail, 'reject')"
          >
            驳回
          </FaButton>
          <FaButton
            v-if="detail.status === 'PASSED'"
            variant="destructive"
            :disabled="acting"
            @click="openReason(detail, 'revoke')"
          >
            撤销
          </FaButton>
        </div>
      </div>
    </FaDrawer>

    <FaModal
      v-model="approveOpen"
      title="通过认证"
      :confirm-button-loading="acting"
      :before-close="handleApproveClose"
    >
      <div class="ev-stack">
        <p class="ev-muted">通过前必须核对并填写姓名、学校，写入人员管理留档。学信网转入的申请若申请人未填，请管理员在此补全或纠正。</p>
        <FaLabel label="真实姓名" class="ev-field">
          <FaInput v-model="approveRealName" class="w-full" maxlength="40" placeholder="与证件或学信网报告一致" />
        </FaLabel>
        <FaLabel label="学校名称" class="ev-field">
          <FaInput v-model="approveSchoolName" class="w-full" maxlength="80" placeholder="如：某某大学" />
        </FaLabel>
      </div>
    </FaModal>

    <FaModal
      v-model="reasonOpen"
      :title="reasonTitle"
      :confirm-button-loading="acting"
      :before-close="handleReasonClose"
    >
      <FaLabel :label="reasonAction === 'reject' ? '驳回原因' : '撤销原因'" class="ev-field">
        <FaTextarea v-model="reason" :rows="4" maxlength="200" placeholder="必填，将通知申请人" />
      </FaLabel>
    </FaModal>
  </section>
</template>
