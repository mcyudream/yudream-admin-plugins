<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  FaAlert,
  FaButton,
  FaIcon,
  FaInput,
  FaModal,
  FaPageHeader,
  FaPageMain,
  FaPagination,
  FaSelect,
  FaTable,
  FaTag,
  useFaToast,
  type TableColumn,
} from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createQqbotAutomationApi } from '../api/qqbot-automation-api'
import type { MediaJob, Option } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createQqbotAutomationApi(props.sdk)
const toast = useFaToast()

const jobs = ref<MediaJob[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const testOpen = ref(false)
const submittingTest = ref(false)
const connections = ref<Option[]>([])
const groups = ref<Option[]>([])
const testConnectionId = ref('')
const testChannelId = ref('')
const testUrl = ref('')

const connectionOptions = computed(() => connections.value.map(item => ({ label: item.name, value: item.id })))
const groupOptions = computed(() => groups.value.map(item => ({ label: item.name, value: item.id })))

const columns: TableColumn<MediaJob>[] = [
  { accessorKey: 'sourceUrl', header: '媒体链接', minWidth: 280, fixed: 'left' },
  { accessorKey: 'connectionId', header: '连接', width: 150 },
  { accessorKey: 'channelId', header: '群聊', width: 150 },
  { id: 'trigger', header: '触发方式', width: 120, align: 'center' },
  { id: 'status', header: '状态', width: 110, align: 'center' },
  { id: 'result', header: '结果', minWidth: 180 },
  { id: 'createdAt', header: '创建时间', width: 180 },
]

function showError(cause: unknown, fallback: string) {
  error.value = responseMessage(cause) || (cause instanceof Error && cause.message ? cause.message : fallback)
  toast.error(error.value)
}

function responseMessage(cause: unknown) {
  if (!cause || typeof cause !== 'object') return ''
  const data = (cause as { response?: { data?: unknown } }).response?.data
  if (typeof data === 'string') return data
  if (!data || typeof data !== 'object') return ''
  const payload = data as { message?: unknown; error?: unknown; data?: { message?: unknown } }
  if (typeof payload.message === 'string') return payload.message
  if (typeof payload.error === 'string') return payload.error
  return typeof payload.data?.message === 'string' ? payload.data.message : ''
}

function statusVariant(status: string) {
  if (status === 'COMPLETED') return 'default' as const
  if (status === 'FAILED' || status === 'SEND_FAILED') return 'destructive' as const
  return 'secondary' as const
}

function statusLabel(status: string) {
  if (status === 'COMPLETED') return '已完成'
  if (status === 'FAILED') return '失败'
  if (status === 'SEND_FAILED') return '发送失败'
  if (status === 'RUNNING') return '处理中'
  if (status === 'PENDING') return '等待中'
  return status || '-'
}

function triggerLabel(trigger?: string) {
  if (trigger === 'MANUAL_TEST') return '手动测试'
  if (trigger === 'EVENT') return '群消息触发'
  return trigger || '群消息触发'
}

function formatTime(value: number) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api.mediaJobs(page.value, size.value)
    jobs.value = result.records
    total.value = result.total
  }
  catch (cause) {
    showError(cause, '加载媒体任务失败')
  }
  finally {
    loading.value = false
  }
}

async function openTest() {
  testConnectionId.value = ''
  testChannelId.value = ''
  testUrl.value = ''
  groups.value = []
  testOpen.value = true
  if (connections.value.length) return
  try {
    connections.value = await api.connections()
  }
  catch (cause) {
    showError(cause, '加载 QQ 连接失败')
  }
}

async function loadTestGroups() {
  testChannelId.value = ''
  groups.value = []
  if (!testConnectionId.value) return
  try {
    groups.value = await api.groups(testConnectionId.value)
  }
  catch (cause) {
    showError(cause, '加载群聊列表失败')
  }
}

function closeTest() {
  testOpen.value = false
  testConnectionId.value = ''
  testChannelId.value = ''
  testUrl.value = ''
  groups.value = []
}

async function monitorTest(id: string) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    await new Promise(resolve => window.setTimeout(resolve, 1000))
    try {
      const job = await api.mediaJob(id)
      if (!job || job.status === 'QUEUED') continue
      await load()
      if (job.status === 'FAILED') {
        toast.error(job.error || '媒体解析失败')
      }
      else if (job.status === 'COMPLETED') {
        toast.success('媒体解析完成')
      }
      return
    }
    catch {
      // The task remains available from the table even if polling is interrupted.
      return
    }
  }
}

async function submitTest() {
  if (!testConnectionId.value || !testChannelId.value || !testUrl.value.trim()) {
    toast.error('请选择连接和群聊，并输入媒体链接')
    return
  }
  submittingTest.value = true
  try {
    const result = await api.startMediaTest({
      connectionId: testConnectionId.value,
      channelId: testChannelId.value,
      sourceUrl: testUrl.value.trim(),
Media provider returned an unsupported media filename: douyin_7663032596767428986.mp4    })
    toast.success('测试任务已创建，解析完成后将发送到所选 QQ 群')
    closeTest()
    page.value = 1
    await load()
    if (!jobs.value.some(item => item.id === result.id)) await load()
    void monitorTest(result.id)
  }
  catch (cause) {
    showError(cause, '创建测试任务失败')
  }
  finally {
    submittingTest.value = false
  }
}

watch([page, size], () => void load())
watch(testConnectionId, () => void loadTestGroups())
onMounted(load)
</script>

<template>
  <section>
    <FaPageHeader title="媒体任务" description="查看群消息触发和手动测试的媒体解析任务。">
      <div class="flex gap-2">
        <FaButton variant="outline" :disabled="loading" @click="load">
          <FaIcon name="i-lucide:refresh-cw" />
          刷新
        </FaButton>
        <FaButton @click="openTest">
          <FaIcon name="i-lucide:flask-conical" />
          发起测试
        </FaButton>
      </div>
    </FaPageHeader>

    <FaPageMain class="space-y-4">
      <FaAlert v-if="error" variant="destructive" title="操作未完成" :description="error" />

      <FaTable
        v-loading="loading"
        row-key="id"
        table-root-class="overflow-hidden rounded-lg"
        table-class="min-w-[1080px]"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="jobs"
        empty-text="暂无媒体任务"
      >
        <template #cell-sourceUrl="{ row }">
          <a class="break-all text-primary hover:underline" :href="row.original.sourceUrl" target="_blank" rel="noreferrer">{{ row.original.sourceUrl }}</a>
        </template>
        <template #cell-trigger="{ row }">{{ triggerLabel(row.original.trigger) }}</template>
        <template #cell-status="{ row }"><FaTag :variant="statusVariant(row.original.status)">{{ statusLabel(row.original.status) }}</FaTag></template>
        <template #cell-result="{ row }">
          <div class="space-y-1">
            <a v-if="row.original.downloadUrl" class="block text-primary hover:underline" :href="row.original.downloadUrl" target="_blank" rel="noreferrer">打开解析结果</a>
            <span v-if="row.original.error" class="block break-all text-sm text-destructive">{{ row.original.error }}</span>
            <span v-if="!row.original.downloadUrl && !row.original.error" class="text-sm text-muted-foreground">-</span>
          </div>
        </template>
        <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
      </FaTable>

      <FaPagination v-model:page="page" v-model:size="size" :total="total" class="mt-3" />
    </FaPageMain>

    <FaModal
      v-model="testOpen"
      title="发起媒体解析测试"
      description="测试会解析链接，并将解析出的媒体文件发送到所选 QQ 群。"
      :show-cancel-button="true"
      cancel-button-text="取消"
      confirm-button-text="开始测试"
      :confirm-button-loading="submittingTest"
      :confirm-button-disabled="!testConnectionId || !testChannelId || !testUrl.trim()"
      class="w-[min(560px,calc(100%-2rem))]"
      @confirm="submitTest"
      @cancel="closeTest"
      @close="closeTest"
    >
      <div class="grid grid-cols-1 gap-4">
        <div class="space-y-2">
          <label class="text-sm font-medium">QQ 连接</label>
          <FaSelect v-model="testConnectionId" class="w-full" placeholder="选择连接" :options="connectionOptions" :disabled="submittingTest" />
        </div>
        <div class="space-y-2">
          <label class="text-sm font-medium">群聊</label>
          <FaSelect v-model="testChannelId" class="w-full" placeholder="选择群聊" :options="groupOptions" :disabled="!testConnectionId || submittingTest" />
        </div>
        <div class="space-y-2">
          <label class="text-sm font-medium">媒体链接</label>
          <FaInput v-model="testUrl" class="w-full" type="url" placeholder="粘贴抖音或 Bilibili 链接" :disabled="submittingTest" />
        </div>
      </div>
    </FaModal>
  </section>
</template>
