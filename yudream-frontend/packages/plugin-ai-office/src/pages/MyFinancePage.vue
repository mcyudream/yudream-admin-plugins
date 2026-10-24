<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { CredentialKind, FinanceAttachment, FinanceRecord, OfficeCategory } from '../types'
import { YdAlert, YdFileUpload, YdInput, YdLoadingOverlay, YdModal, YdPagination, YdResponsiveEnhancedTable, YdSelect, YdTablePageLayout, YdTag, useYdModal, useYdToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createOfficeMeApi, errorMessage, saveBlob } from '../api/office-api'
import { formatTime, moneyText } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeMeApi(props.sdk)

const loading = ref(false)
const error = ref('')
const records = ref<FinanceRecord[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const pageSizes = [10, 20, 50]
const categories = ref<OfficeCategory[]>([])
const kinds = ref<CredentialKind[]>([])
const direction = ref('expense')

const columns: TableColumn<FinanceRecord>[] = [
  { id: 'title', header: '摘要', minWidth: 200, enableSorting: false },
  { id: 'categoryCode', header: '类别', width: 130, enableSorting: false },
  { accessorKey: 'amountCents', header: '金额', width: 120 },
  { accessorKey: 'occurredAt', header: '发生时间', width: 170 },
  { id: 'attachments', header: '凭证', width: 150, enableSorting: false },
  { id: 'status', header: '状态', width: 120, enableSorting: false },
]

const rowActions: ActionMenuItem<FinanceRecord>[] = [
  { label: '撤回', icon: 'morph:undo', danger: true, visible: row => row.status === 'pending', onClick: row => withdraw(row) },
]

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.finance(page.value, size.value)
    records.value = data.records ?? []
    total.value = data.total ?? 0
  }
  catch (cause) {
    error.value = errorMessage(cause, '报销记录加载失败')
  }
  finally {
    loading.value = false
  }
  try {
    const [categoryData, kindData] = await Promise.all([
      api.categories(direction.value),
      api.credentialKinds(),
    ])
    categories.value = categoryData
    kinds.value = kindData
  }
  catch {
    categories.value = []
  }
}

// ---------- 登记 ----------

const createVisible = ref(false)
const saving = ref(false)
const uploading = ref(false)
const form = ref({
  direction: 'expense',
  categoryCode: '',
  title: '',
  amountYuan: '',
  quantity: '',
  unitPriceYuan: '',
  occurredAt: new Date().toISOString().slice(0, 10),
  purpose: '',
  fundSource: '',
})
const attachments = ref<FinanceAttachment[]>([])
const uploadFiles = ref<File[]>([])

const categoryOptions = () => categories.value.map(item => ({ label: item.name, value: item.code }))

const kindOptions = () => kinds.value.map(kind => ({ label: kind.label, value: kind.code }))

function openCreate() {
  form.value = {
    direction: direction.value,
    categoryCode: '',
    title: '',
    amountYuan: '',
    quantity: '',
    unitPriceYuan: '',
    occurredAt: new Date().toISOString().slice(0, 10),
    purpose: '',
    fundSource: '',
  }
  attachments.value = []
  createVisible.value = true
}

async function handleUpload(options: { file: File }) {
  if (uploading.value) {
    return
  }
  uploading.value = true
  try {
    attachments.value = [...attachments.value, await api.uploadAttachment(options.file)]
  }
  catch (cause) {
    toast.error(errorMessage(cause, '凭证上传失败'))
  }
  finally {
    uploading.value = false
  }
}

function attachmentKindLabel(kind: string) {
  return kind === 'invoice' ? '发票' : kind === 'screenshot' ? '支付截图' : '其他凭证'
}

function downloadAttachment(record: FinanceRecord, filename: string) {
  const attachment = record.attachments.find(item => item.filename === filename)
  if (!attachment) {
    return
  }
  const index = record.attachments.indexOf(attachment)
  props.sdk.http.blob(`/me/finance/${encodeURIComponent(record.id)}/attachments/${index}`)
    .then(blob => saveBlob(blob.data, filename))
    .catch(() => toast.error('凭证下载失败'))
}

async function submit() {
  if (!form.value.categoryCode || !form.value.title.trim() || !form.value.amountYuan) {
    toast.warning('请填写类别、摘要与金额')
    return
  }
  saving.value = true
  try {
    await api.createFinance({
      direction: form.value.direction as 'income' | 'expense',
      categoryCode: form.value.categoryCode,
      title: form.value.title.trim(),
      amountCents: Math.round(Number(form.value.amountYuan) * 100),
      quantity: form.value.quantity ? Number(form.value.quantity) : 0,
      unitPriceCents: form.value.unitPriceYuan ? Math.round(Number(form.value.unitPriceYuan) * 100) : 0,
      occurredAt: new Date(`${form.value.occurredAt}T00:00:00`).getTime(),
      purpose: form.value.purpose.trim(),
      fundSource: form.value.fundSource.trim(),
      attachments: attachments.value,
    })
    toast.success('已提交，等待审核')
    createVisible.value = false
    await load()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '提交失败'))
  }
  finally {
    saving.value = false
  }
}

function withdraw(record: FinanceRecord) {
  confirm.confirm({
    title: '撤回记录',
    content: `确认撤回「${record.title}」吗？凭证将一并清理。`,
    onConfirm: async () => {
      try {
        await api.withdrawFinance(record.id)
        toast.success('已撤回')
        await load()
      }
      catch (cause) {
        toast.error(errorMessage(cause, '撤回失败'))
      }
    },
  })
}

function statusText(status: string) {
  return status === 'accepted' ? '已采纳' : status === 'rejected' ? '未采纳' : '待审核'
}
</script>

<template>
  <YdTablePageLayout title="我的报销" :show-search="false">
    <template #actions>
      <YdButton @click="openCreate">登记支出</YdButton>
    </template>
    <template #table>
      <YdAlert v-if="error" class="mb-3" variant="destructive" title="加载失败" :description="error" />
      <YdLoadingOverlay :loading="loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="records"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[900px]"
          border
          stripe
          :actions="rowActions"
          empty-text="还没有报销记录"
        >
          <template #cell-title="{ row }">
            <strong class="truncate" :title="row.original.title">{{ row.original.title }}</strong>
          </template>
          <template #cell-amountCents="{ row }">¥{{ moneyText(row.original.amountCents) }}</template>
          <template #cell-occurredAt="{ row }">{{ formatTime(row.original.occurredAt) }}</template>
          <template #cell-attachments="{ row }">
            <div class="flex flex-col">
              <a
                v-for="attachment in row.original.attachments"
                :key="attachment.fileKey"
                class="truncate text-xs underline decoration-dotted"
                :title="attachmentKindLabel(attachment.kind)"
                href="javascript:void(0)"
                @click="downloadAttachment(row.original, attachment.filename)"
              >{{ attachmentKindLabel(attachment.kind) }}</a>
              <span v-if="!row.original.attachments.length" class="text-muted-foreground">无</span>
            </div>
          </template>
          <template #cell-status="{ row }">
            <YdTag :variant="row.original.status === 'accepted' ? 'default' : 'outline'">
              {{ statusText(row.original.status) }}
            </YdTag>
            <div v-if="row.original.reviewNote" class="max-w-40 truncate text-xs text-muted-foreground" :title="row.original.reviewNote">
              {{ row.original.reviewNote }}
            </div>
          </template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>

    <template #footer>
      <YdPagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        :sizes="pageSizes"
        @page-change="load"
        @size-change="load"
      />
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="createVisible"
    title="登记支出"
    confirm-button-text="提交"
    :confirm-button-loading="saving"
    :show-cancel-button="!saving"
    cancel-button-text="取消"
    @confirm="submit"
  >
    <div class="flex flex-col gap-3">
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">方向</span>
        <YdSelect
          v-model="direction"
          :options="[{ label: '支出', value: 'expense' }, { label: '收入', value: 'income' }]"
          @change="load"
        />
      </label>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">类别</span>
        <YdSelect v-model="form.categoryCode" :options="categoryOptions()" placeholder="选择类别" />
      </label>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">摘要</span>
        <YdInput v-model="form.title" maxlength="100" placeholder="如：活动奖品采购" />
      </label>
      <div class="grid grid-cols-[repeat(3,minmax(0,1fr))] gap-3">
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">金额（元）</span>
          <YdInput v-model="form.amountYuan" placeholder="0.00" />
        </label>
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">数量</span>
          <YdInput v-model="form.quantity" placeholder="选填" />
        </label>
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">单价（元）</span>
          <YdInput v-model="form.unitPriceYuan" placeholder="选填" />
        </label>
      </div>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">发生日期</span>
        <YdInput v-model="form.occurredAt" placeholder="yyyy-MM-dd" />
      </label>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">用途</span>
        <YdInput v-model="form.purpose" placeholder="选填" />
      </label>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">资金来源</span>
        <YdInput v-model="form.fundSource" placeholder="选填，如：校上拨款-校级活动" />
      </label>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">发票 / 支付截图（最多 10 个，单个不超过 50MB）</span>
        <YdFileUpload
          v-model="uploadFiles"
          multiple
          :max="10"
          :http-request="handleUpload"
          description="拖放或点击上传"
        />
      </label>
      <div v-if="attachments.length" class="flex flex-col gap-2">
        <div v-for="attachment in attachments" :key="attachment.fileKey"
          class="flex items-center gap-2 rounded border p-2">
          <span class="w-56 truncate text-xs" :title="attachment.filename">{{ attachment.filename }}</span>
          <YdSelect v-model="attachment.kind" :options="kindOptions()" class="w-40" />
        </div>
        <p v-if="uploading" class="text-sm text-muted-foreground">凭证上传中…</p>
      </div>
    </div>
  </YdModal>
</template>
