<script setup lang="ts">
import type { ActionMenuItem, SearchConfig, SearchFieldConfig, SearchOptionsSource, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { CredentialKind, FinanceAttachment, FinanceRecord, OfficeCategory } from '../types'
import { YdAlert, YdDynamicSearchForm, YdFileUpload, YdInput, YdLoadingOverlay, YdModal, YdPagination, YdResponsiveEnhancedTable, YdSelect, YdTablePageLayout, YdTag, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createOfficeAdminApi, createOfficeMeApi, errorMessage } from '../api/office-api'
import { formatTime, moneyText } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)
const meApi = createOfficeMeApi(props.sdk)

const loading = ref(false)
const acting = ref(false)
const error = ref('')
const records = ref<FinanceRecord[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const pageSizes = [10, 20, 50]
const categories = ref<OfficeCategory[]>([])
const kinds = ref<CredentialKind[]>([])
const direction = ref('')
const status = ref('')
const categoryCode = ref('')

const searchQuery = ref<Record<string, any>>({ direction: '', status: '', categoryCode: '' })

type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const directionOptions = [
  { label: '收入', value: 'income' },
  { label: '支出', value: 'expense' },
]
const statusOptions = [
  { label: '待审核', value: 'pending' },
  { label: '已采纳', value: 'accepted' },
  { label: '未采纳', value: 'rejected' },
]

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'direction', label: '方向', controlType: 'SELECT', order: 1, width: '160px' }),
    searchField({ fieldName: 'status', label: '状态', controlType: 'SELECT', order: 2, width: '160px' }),
    searchField({ fieldName: 'categoryCode', label: '类别', controlType: 'SELECT', order: 3, width: '200px' }),
  ],
}

const categoryOptions = () => [
  { label: '全部类别', value: '' },
  ...categories.value
    .filter(item => !direction.value || item.direction === direction.value)
    .map(item => ({ label: `${item.name}（${item.direction === 'income' ? '收入' : '支出'}）`, value: item.code })),
]

const optionsSources: SearchOptionsSource[] = [
  { id: 'directionOptions', loader: () => directionOptions },
  { id: 'statusOptions', loader: () => statusOptions },
  { id: 'categoryCodeOptions', loader: categoryOptions },
]

function applySearch() {
  direction.value = String(searchQuery.value.direction ?? '')
  status.value = String(searchQuery.value.status ?? '')
  categoryCode.value = String(searchQuery.value.categoryCode ?? '')
  page.value = 1
  load()
}

function resetSearch() {
  direction.value = ''
  status.value = ''
  categoryCode.value = ''
  searchQuery.value = { direction: '', status: '', categoryCode: '' }
  page.value = 1
  load()
}

const columns: TableColumn<FinanceRecord>[] = [
  { id: 'title', header: '摘要', minWidth: 200, enableSorting: false },
  { id: 'direction', header: '方向/类别', width: 150, enableSorting: false },
  { accessorKey: 'amountCents', header: '金额', width: 120 },
  { id: 'buyer', header: '经办', width: 110, enableSorting: false },
  { accessorKey: 'occurredAt', header: '发生时间', width: 170 },
  { id: 'attachments', header: '凭证', width: 90, enableSorting: false },
  { id: 'status', header: '状态', width: 110, enableSorting: false },
]

const rowActions: ActionMenuItem<FinanceRecord>[] = [
  { label: '采纳', icon: 'morph:check', visible: row => row.status === 'pending', disabled: () => acting.value, onClick: row => openReview(row, true) },
  { label: '驳回', icon: 'morph:ban', danger: true, visible: row => row.status === 'pending', disabled: () => acting.value, onClick: row => openReview(row, false) },
  { label: '删除', icon: 'morph:trash-2', danger: true, visible: row => !row.settlementBatchId, disabled: () => acting.value, onClick: row => remove(row) },
]

// ---------- 登记收支 ----------

const createVisible = ref(false)
const saving = ref(false)
const createForm = ref({
  direction: 'expense',
  categoryCode: '',
  title: '',
  amountYuan: '',
  quantity: '',
  unitPriceYuan: '',
  occurredAt: new Date().toISOString().slice(0, 10),
  purpose: '',
  fundSource: '',
  buyerName: '',
})
const attachments = ref<FinanceAttachment[]>([])
const uploadFiles = ref<File[]>([])
const uploading = ref(false)

const kindOptions = () => kinds.value.map(kind => ({ label: kind.label, value: kind.code }))

const createCategoryOptions = () => categories.value
  .filter(item => item.direction === createForm.value.direction)
  .map(item => ({ label: item.name, value: item.code }))

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.finance({
      direction: direction.value || undefined,
      status: status.value || undefined,
      categoryCode: categoryCode.value || undefined,
      page: page.value,
      size: size.value,
    })
    records.value = data.records ?? []
    total.value = data.total ?? 0
  }
  catch (cause) {
    error.value = errorMessage(cause, '台账加载失败')
  }
  finally {
    loading.value = false
  }
  try {
    const [categoryData, kindData] = await Promise.all([api.categories(), meApi.credentialKinds()])
    categories.value = categoryData
    kinds.value = kindData
  }
  catch {
    categories.value = []
    kinds.value = []
  }
}

function openCreate() {
  createForm.value = {
    direction: 'expense',
    categoryCode: '',
    title: '',
    amountYuan: '',
    quantity: '',
    unitPriceYuan: '',
    occurredAt: new Date().toISOString().slice(0, 10),
    purpose: '',
    fundSource: '',
    buyerName: '',
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
    attachments.value = [...attachments.value, await api.uploadFinanceAttachment(options.file)]
  }
  catch (cause) {
    toast.error(errorMessage(cause, '凭证上传失败'))
  }
  finally {
    uploading.value = false
  }
}

async function submitCreate() {
  if (!createForm.value.categoryCode || !createForm.value.title.trim() || !createForm.value.amountYuan) {
    toast.warning('请填写方向、类别、摘要与金额')
    return
  }
  saving.value = true
  try {
    await api.createFinance({
      direction: createForm.value.direction as 'income' | 'expense',
      categoryCode: createForm.value.categoryCode,
      title: createForm.value.title.trim(),
      amountCents: Math.round(Number(createForm.value.amountYuan) * 100),
      quantity: createForm.value.quantity ? Number(createForm.value.quantity) : 0,
      unitPriceCents: createForm.value.unitPriceYuan ? Math.round(Number(createForm.value.unitPriceYuan) * 100) : 0,
      occurredAt: new Date(`${createForm.value.occurredAt}T00:00:00`).getTime(),
      purpose: createForm.value.purpose.trim(),
      fundSource: createForm.value.fundSource.trim(),
      buyerName: createForm.value.buyerName.trim(),
      attachments: attachments.value,
    })
    toast.success('已登记')
    createVisible.value = false
    await load()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '登记失败'))
  }
  finally {
    saving.value = false
  }
}

// ---------- 审核 / 删除 ----------

const reviewVisible = ref(false)
const reviewTarget = ref<FinanceRecord | null>(null)
const reviewAccept = ref(true)
const reviewNote = ref('')

function openReview(record: FinanceRecord, accept: boolean) {
  reviewTarget.value = record
  reviewAccept.value = accept
  reviewNote.value = ''
  reviewVisible.value = true
}

async function submitReview() {
  if (!reviewTarget.value || acting.value) {
    return
  }
  acting.value = true
  try {
    await api.reviewFinance(reviewTarget.value.id, { accept: reviewAccept.value, note: reviewNote.value.trim() })
    toast.success(reviewAccept.value ? '已采纳' : '已驳回')
    reviewVisible.value = false
    await load()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '审核失败'))
  }
  finally {
    acting.value = false
  }
}

function remove(record: FinanceRecord) {
  confirm.confirm({
    title: '删除财务记录',
    content: `确认删除「${record.title}」吗？凭证将一并清理，已纳入结算的记录不可删除。`,
    onConfirm: async () => {
      try {
        await api.deleteFinance(record.id)
        toast.success('已删除')
        await load()
      }
      catch (cause) {
        toast.error(errorMessage(cause, '删除失败'))
      }
    },
  })
}

function statusText(status: string) {
  return status === 'accepted' ? '已采纳' : status === 'rejected' ? '未采纳' : '待审核'
}
</script>

<template>
  <YdTablePageLayout title="财务台账">
    <template #actions>
      <YdButton @click="openCreate">
        <YdIcon name="i-ri:add-line" />登记收支
      </YdButton>
    </template>
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
      <YdLoadingOverlay :loading="loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="records"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[1040px]"
          border
          stripe
          column-visibility
          :actions="rowActions"
          empty-text="没有符合条件的记录"
        >
          <template #cell-title="{ row }">
            <div class="flex flex-col">
              <strong class="truncate" :title="row.original.title">{{ row.original.title }}</strong>
              <span v-if="row.original.purpose" class="truncate text-xs text-muted-foreground" :title="row.original.purpose">{{ row.original.purpose }}</span>
            </div>
          </template>
          <template #cell-direction="{ row }">
            {{ row.original.direction === 'income' ? '收入' : '支出' }} / {{ row.original.categoryCode }}
          </template>
          <template #cell-amountCents="{ row }">¥{{ moneyText(row.original.amountCents) }}</template>
          <template #cell-buyer="{ row }">{{ row.original.buyerName || row.original.createdBy || '-' }}</template>
          <template #cell-occurredAt="{ row }">{{ formatTime(row.original.occurredAt) }}</template>
          <template #cell-attachments="{ row }">{{ row.original.attachments.length || '-' }}</template>
          <template #cell-status="{ row }">
            <YdTag :variant="row.original.status === 'accepted' ? 'default' : 'outline'">
              {{ statusText(row.original.status) }}
            </YdTag>
            <div v-if="row.original.settlementBatchId" class="text-xs text-muted-foreground">已入结算</div>
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
    title="登记收支"
    confirm-button-text="保存"
    :confirm-button-loading="saving"
    :show-cancel-button="!saving"
    cancel-button-text="取消"
    @confirm="submitCreate"
  >
    <div class="flex flex-col gap-3">
      <div class="grid grid-cols-[repeat(2,minmax(0,1fr))] gap-3">
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">方向</span>
          <YdSelect
            v-model="createForm.direction"
            :options="[{ label: '支出', value: 'expense' }, { label: '收入', value: 'income' }]"
          />
        </label>
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">类别</span>
          <YdSelect v-model="createForm.categoryCode" :options="createCategoryOptions()" placeholder="选择类别" />
        </label>
      </div>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">摘要</span>
        <YdInput v-model="createForm.title" maxlength="100" placeholder="购买了什么 / 拨款名目" />
      </label>
      <div class="grid grid-cols-[repeat(3,minmax(0,1fr))] gap-3">
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">金额（元）</span>
          <YdInput v-model="createForm.amountYuan" placeholder="0.00" />
        </label>
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">数量</span>
          <YdInput v-model="createForm.quantity" placeholder="选填" />
        </label>
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">单价（元）</span>
          <YdInput v-model="createForm.unitPriceYuan" placeholder="选填" />
        </label>
      </div>
      <div class="grid grid-cols-[repeat(2,minmax(0,1fr))] gap-3">
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">发生日期</span>
          <YdInput v-model="createForm.occurredAt" placeholder="yyyy-MM-dd" />
        </label>
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">经办人</span>
          <YdInput v-model="createForm.buyerName" placeholder="选填，默认本人" />
        </label>
      </div>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">用途</span>
        <YdInput v-model="createForm.purpose" placeholder="选填" />
      </label>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">资金来源</span>
        <YdInput v-model="createForm.fundSource" placeholder="选填，如：校上拨款-校级活动" />
      </label>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">发票 / 支付截图 / 其他凭证</span>
        <YdFileUpload
          v-model="uploadFiles"
          multiple
          :max="10"
          :http-request="handleUpload"
          description="拖放或点击上传，最多 10 个，单个不超过 50MB"
        />
      </label>
      <div v-if="attachments.length" class="flex flex-col gap-2">
        <div v-for="attachment in attachments" :key="attachment.fileKey" class="flex items-center gap-2 rounded border p-2">
          <span class="w-56 truncate text-xs" :title="attachment.filename">{{ attachment.filename }}</span>
          <YdSelect v-model="attachment.kind" :options="kindOptions()" class="w-40" />
        </div>
      </div>
      <p v-if="uploading" class="text-sm text-muted-foreground">凭证上传中…</p>
      <p class="text-sm text-muted-foreground">保存后默认待审核；拥有审核权限者可在台账中采纳。</p>
    </div>
  </YdModal>

  <YdModal
    v-model="reviewVisible"
    :title="reviewAccept ? '采纳记录' : '驳回记录'"
    :confirm-button-text="reviewAccept ? '采纳' : '驳回'"
    :confirm-button-loading="acting"
    :show-cancel-button="!acting"
    cancel-button-text="取消"
    @confirm="submitReview"
  >
    <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">审核意见（选填）</span>
      <YdTextarea v-model="reviewNote" :rows="3" maxlength="300" />
    </label>
  </YdModal>
</template>
