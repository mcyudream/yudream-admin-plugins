<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { FinanceAttachment, FinanceRecord, OfficeCategory } from '../types'
import { YdButton, YdIcon, YdInput, YdModal, YdPageHeader, YdPageMain, YdPagination, YdSelect, YdTag, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createOfficeAdminApi, errorMessage } from '../api/office-api'
import { formatTime, moneyText } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const loading = ref(false)
const records = ref<FinanceRecord[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const filters = reactive({ direction: '', status: '', categoryCode: '' })
const categories = ref<OfficeCategory[]>([])

const createVisible = ref(false)
const saving = ref(false)
const createForm = reactive({
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
  acceptNow: false,
})
const attachments = ref<FinanceAttachment[]>([])
const uploading = ref(false)

const reviewVisible = ref(false)
const reviewTarget = ref<FinanceRecord | null>(null)
const reviewAccept = ref(true)
const reviewNote = ref('')
const reviewSaving = ref(false)

const directionOptions = [
  { label: '全部方向', value: '' },
  { label: '收入', value: 'income' },
  { label: '支出', value: 'expense' },
]
const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '待审核', value: 'pending' },
  { label: '已采纳', value: 'accepted' },
  { label: '未采纳', value: 'rejected' },
]

const categoryOptions = computed(() =>
  categories.value
    .filter(item => !createForm.direction || item.direction === createForm.direction)
    .map(item => ({ label: `${item.name}（${item.direction === 'income' ? '收入' : '支出'}）`, value: item.code })))

const filterCategoryOptions = computed(() => [
  { label: '全部类别', value: '' },
  ...categories.value
    .filter(item => !filters.direction || item.direction === filters.direction)
    .map(item => ({ label: item.name, value: item.code })),
])

onMounted(load)

async function load() {
  loading.value = true
  try {
    const data = await api.finance({
      direction: filters.direction || undefined,
      status: filters.status || undefined,
      categoryCode: filters.categoryCode || undefined,
      page: pager.page,
      size: pager.size,
    })
    records.value = data.records ?? []
    pager.total = data.total ?? 0
  }
  catch (cause) {
    toast.error(errorMessage(cause, '台账加载失败'))
  }
  finally {
    loading.value = false
  }
  try {
    categories.value = await api.categories()
  }
  catch {
    categories.value = []
  }
}

function openCreate() {
  createForm.direction = 'expense'
  createForm.categoryCode = ''
  createForm.title = ''
  createForm.amountYuan = ''
  createForm.quantity = ''
  createForm.unitPriceYuan = ''
  createForm.occurredAt = new Date().toISOString().slice(0, 10)
  createForm.purpose = ''
  createForm.fundSource = ''
  createForm.buyerName = ''
  createForm.acceptNow = false
  attachments.value = []
  createVisible.value = true
}

async function onFilesChosen(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) {
    return
  }
  uploading.value = true
  try {
    for (const file of files) {
      attachments.value = [...attachments.value, await api.uploadFinanceAttachment(file)]
    }
    toast.success('凭证已上传')
  }
  catch (cause) {
    toast.error(errorMessage(cause, '凭证上传失败'))
  }
  finally {
    uploading.value = false
  }
}

async function submitCreate() {
  if (!createForm.categoryCode || !createForm.title.trim() || !createForm.amountYuan) {
    toast.warning('请填写方向、类别、摘要与金额')
    return
  }
  saving.value = true
  try {
    await api.createFinance({
      direction: createForm.direction as 'income' | 'expense',
      categoryCode: createForm.categoryCode,
      title: createForm.title.trim(),
      amountCents: Math.round(Number(createForm.amountYuan) * 100),
      quantity: createForm.quantity ? Number(createForm.quantity) : 0,
      unitPriceCents: createForm.unitPriceYuan ? Math.round(Number(createForm.unitPriceYuan) * 100) : 0,
      occurredAt: new Date(`${createForm.occurredAt}T00:00:00`).getTime(),
      purpose: createForm.purpose.trim(),
      fundSource: createForm.fundSource.trim(),
      buyerName: createForm.buyerName.trim(),
      attachments: attachments.value,
    }, createForm.acceptNow)
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

function openReview(record: FinanceRecord, accept: boolean) {
  reviewTarget.value = record
  reviewAccept.value = accept
  reviewNote.value = ''
  reviewVisible.value = true
}

async function submitReview() {
  if (!reviewTarget.value) {
    return
  }
  reviewSaving.value = true
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
    reviewSaving.value = false
  }
}

function remove(record: FinanceRecord) {
  confirm.confirm({
    title: '删除财务记录',
    content: `确认删除「${record.title}」吗？已纳入结算的记录不可删除。`,
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
  <section class="office-page">
    <YdPageHeader title="财务台账">
      <YdButton @click="openCreate">
        <YdIcon name="i-ri:add-line" />登记收支
      </YdButton>
    </YdPageHeader>
    <YdPageMain>
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <YdSelect v-model="filters.direction" :options="directionOptions" class="w-32" @change="pager.page = 1; load()" />
        <YdSelect v-model="filters.status" :options="statusOptions" class="w-32" @change="pager.page = 1; load()" />
        <YdSelect v-model="filters.categoryCode" :options="filterCategoryOptions" class="w-44" @change="pager.page = 1; load()" />
      </div>
      <table v-if="records.length" class="office-preview-table">
        <thead>
          <tr><th>摘要</th><th>方向/类别</th><th>金额</th><th>经办</th><th>发生时间</th><th>凭证</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="record in records" :key="record.id">
            <td>{{ record.title }}<div v-if="record.purpose" class="text-xs text-muted-foreground">{{ record.purpose }}</div></td>
            <td>{{ record.direction === 'income' ? '收入' : '支出' }} / {{ record.categoryCode }}</td>
            <td>¥{{ moneyText(record.amountCents) }}</td>
            <td>{{ record.buyerName || record.createdBy }}</td>
            <td>{{ formatTime(record.occurredAt) }}</td>
            <td>{{ record.attachments.length ? `${record.attachments.length} 个` : '无' }}</td>
            <td>
              <YdTag :variant="record.status === 'accepted' ? 'default' : 'outline'">{{ statusText(record.status) }}</YdTag>
              <div v-if="record.settlementBatchId" class="text-xs text-muted-foreground">已入结算</div>
            </td>
            <td class="flex flex-wrap gap-1">
              <YdButton v-if="record.status === 'pending'" size="sm" variant="outline" @click="openReview(record, true)">采纳</YdButton>
              <YdButton v-if="record.status === 'pending'" size="sm" variant="outline" @click="openReview(record, false)">驳回</YdButton>
              <YdButton v-if="!record.settlementBatchId" size="sm" variant="outline" @click="remove(record)">删除</YdButton>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!loading" class="text-sm text-muted-foreground">没有符合条件的记录</p>
    </YdPageMain>
    <div v-if="pager.total > pager.size" class="flex justify-end">
      <YdPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        @page-change="load"
        @size-change="load"
      />
    </div>

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
        <div class="grid grid-cols-2 gap-2">
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">方向</span>
            <YdSelect
              v-model="createForm.direction"
              :options="[{ label: '支出', value: 'expense' }, { label: '收入', value: 'income' }]"
            />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">类别</span>
            <YdSelect v-model="createForm.categoryCode" :options="categoryOptions" />
          </label>
        </div>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">摘要</span>
          <YdInput v-model="createForm.title" maxlength="100" />
        </label>
        <div class="grid grid-cols-3 gap-2">
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">金额（元）</span>
            <YdInput v-model="createForm.amountYuan" placeholder="0.00" />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">数量</span>
            <YdInput v-model="createForm.quantity" placeholder="选填" />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">单价（元）</span>
            <YdInput v-model="createForm.unitPriceYuan" placeholder="选填" />
          </label>
        </div>
        <div class="grid grid-cols-2 gap-2">
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">发生日期</span>
            <YdInput v-model="createForm.occurredAt" placeholder="yyyy-MM-dd" />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">经办人</span>
            <YdInput v-model="createForm.buyerName" placeholder="选填，默认本人" />
          </label>
        </div>
        <div class="grid grid-cols-2 gap-2">
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">用途</span>
            <YdInput v-model="createForm.purpose" placeholder="选填" />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">资金来源</span>
            <YdInput v-model="createForm.fundSource" placeholder="选填" />
          </label>
        </div>
        <div class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">发票 / 支付截图</span>
          <input type="file" multiple @change="onFilesChosen">
          <span v-if="uploading" class="text-muted-foreground">上传中…</span>
        </div>
        <span class="text-sm text-muted-foreground">保存后默认待审核；拥有审核权限者可在台账中采纳。</span>
      </div>
    </YdModal>

    <YdModal
      v-model="reviewVisible"
      :title="reviewAccept ? '采纳记录' : '驳回记录'"
      :confirm-button-text="reviewAccept ? '采纳' : '驳回'"
      :confirm-button-loading="reviewSaving"
      :show-cancel-button="!reviewSaving"
      cancel-button-text="取消"
      @confirm="submitReview"
    >
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">审核意见（选填）</span>
        <YdTextarea v-model="reviewNote" :rows="3" maxlength="300" />
      </label>
    </YdModal>
  </section>
</template>
