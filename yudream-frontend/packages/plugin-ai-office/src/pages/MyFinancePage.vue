<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { FinanceAttachment, FinanceRecord, OfficeCategory } from '../types'
import { YdButton, YdIcon, YdInput, YdModal, YdPageHeader, YdPageMain, YdSelect, YdTag, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createOfficeMeApi, errorMessage, saveBlob } from '../api/office-api'
import { formatTime, moneyText } from '../types'
import { createOfficeAdminApi } from '../api/office-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const meApi = createOfficeMeApi(props.sdk)
const adminApi = createOfficeAdminApi(props.sdk)

const loading = ref(false)
const records = ref<FinanceRecord[]>([])
const pager = reactive({ page: 1, size: 10, total: 0 })
const categories = ref<OfficeCategory[]>([])

const modalVisible = ref(false)
const saving = ref(false)
const form = reactive({
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
const uploading = ref(false)

const expenseCategories = computed(() => categories.value.filter(item => item.direction === 'expense'))

onMounted(async () => {
  await Promise.all([load(), loadCategories()])
})

async function load() {
  loading.value = true
  try {
    const data = await meApi.finance(pager.page, pager.size)
    records.value = data.records ?? []
    pager.total = data.total ?? 0
  }
  catch (cause) {
    toast.error(errorMessage(cause, '报销记录加载失败'))
  }
  finally {
    loading.value = false
  }
}

async function loadCategories() {
  try {
    categories.value = await adminApi.categories()
  }
  catch {
    categories.value = []
  }
}

function openCreate() {
  form.categoryCode = ''
  form.title = ''
  form.amountYuan = ''
  form.quantity = ''
  form.unitPriceYuan = ''
  form.occurredAt = new Date().toISOString().slice(0, 10)
  form.purpose = ''
  form.fundSource = ''
  attachments.value = []
  modalVisible.value = true
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
      attachments.value = [...attachments.value, await meApi.uploadAttachment(file)]
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

async function submit() {
  if (!form.categoryCode || !form.title.trim() || !form.amountYuan) {
    toast.warning('请填写类别、摘要与金额')
    return
  }
  saving.value = true
  try {
    const amountCents = Math.round(Number(form.amountYuan) * 100)
    const quantity = form.quantity ? Number(form.quantity) : 0
    const unitPriceCents = form.unitPriceYuan ? Math.round(Number(form.unitPriceYuan) * 100) : 0
    await meApi.createFinance({
      direction: 'expense',
      categoryCode: form.categoryCode,
      title: form.title.trim(),
      amountCents,
      quantity,
      unitPriceCents,
      occurredAt: new Date(`${form.occurredAt}T00:00:00`).getTime(),
      purpose: form.purpose.trim(),
      fundSource: form.fundSource.trim(),
      attachments: attachments.value,
    })
    toast.success('已提交，等待审核')
    modalVisible.value = false
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
        await meApi.withdrawFinance(record.id)
        toast.success('已撤回')
        await load()
      }
      catch (cause) {
        toast.error(errorMessage(cause, '撤回失败'))
      }
    },
  })
}

function downloadAttachment(record: FinanceRecord, index: number) {
  saveBlobUrl(`/me/finance/attachment/${record.id}/${index}`, record.attachments[index]?.filename || '凭证')
}

async function saveBlobUrl(path: string, filename: string) {
  const blob = await props.sdk.http.blob(path)
  saveBlob(blob.data, filename)
}

function statusText(status: string) {
  return status === 'accepted' ? '已采纳' : status === 'rejected' ? '未采纳' : '待审核'
}
</script>

<template>
  <section class="office-page">
    <YdPageHeader title="我的报销">
      <YdButton @click="openCreate">
        <YdIcon name="i-ri:add-line" />登记支出
      </YdButton>
    </YdPageHeader>
    <YdPageMain>
      <table v-if="records.length" class="office-preview-table">
        <thead>
          <tr><th>摘要</th><th>类别</th><th>金额</th><th>发生时间</th><th>凭证</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="record in records" :key="record.id">
            <td>{{ record.title }}</td>
            <td>{{ record.categoryCode }}</td>
            <td>¥{{ moneyText(record.amountCents) }}</td>
            <td>{{ formatTime(record.occurredAt) }}</td>
            <td>
              <a
                v-for="(attachment, index) in record.attachments"
                :key="attachment.fileKey"
                class="mr-2 underline decoration-dotted"
                href="javascript:void(0)"
                @click="downloadAttachment(record, index)"
              >{{ attachment.kind === 'invoice' ? '发票' : attachment.kind === 'screenshot' ? '截图' : '凭证' }}</a>
              <span v-if="!record.attachments.length" class="text-muted-foreground">无</span>
            </td>
            <td>
              <YdTag :variant="record.status === 'accepted' ? 'default' : 'outline'">{{ statusText(record.status) }}</YdTag>
              <div v-if="record.reviewNote" class="text-xs text-muted-foreground">{{ record.reviewNote }}</div>
            </td>
            <td>
              <YdButton v-if="record.status === 'pending'" size="sm" variant="outline" @click="withdraw(record)">撤回</YdButton>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!loading" class="text-sm text-muted-foreground">还没有报销记录</p>
    </YdPageMain>

    <YdModal
      v-model="modalVisible"
      title="登记支出"
      confirm-button-text="提交"
      :confirm-button-loading="saving"
      :show-cancel-button="!saving"
      cancel-button-text="取消"
      @confirm="submit"
    >
      <div class="flex flex-col gap-3">
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">支出类别</span>
          <YdSelect
            v-model="form.categoryCode"
            placeholder="选择类别"
            :options="expenseCategories.map(item => ({ label: item.name, value: item.code }))"
          />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">摘要（购买了什么）</span>
          <YdInput v-model="form.title" maxlength="100" placeholder="如：活动奖品采购" />
        </label>
        <div class="grid grid-cols-3 gap-2">
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">金额（元）</span>
            <YdInput v-model="form.amountYuan" placeholder="0.00" />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">数量</span>
            <YdInput v-model="form.quantity" placeholder="选填" />
          </label>
          <label class="flex flex-col gap-1 text-sm">
            <span class="text-secondary-foreground/80">单价（元）</span>
            <YdInput v-model="form.unitPriceYuan" placeholder="选填" />
          </label>
        </div>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">发生日期</span>
          <YdInput v-model="form.occurredAt" placeholder="yyyy-MM-dd" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">用途</span>
          <YdInput v-model="form.purpose" placeholder="选填" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">资金来源</span>
          <YdInput v-model="form.fundSource" placeholder="选填，如：校上拨款-校级活动" />
        </label>
        <div class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">发票 / 支付截图（最多 10 个）</span>
          <input type="file" multiple @change="onFilesChosen">
          <span v-if="uploading" class="text-muted-foreground">上传中…</span>
          <span v-if="attachments.length" class="text-muted-foreground">已上传 {{ attachments.length }} 个凭证</span>
        </div>
      </div>
    </YdModal>
  </section>
</template>
