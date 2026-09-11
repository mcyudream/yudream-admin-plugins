<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { MySubscriptionView, PushTargetView, WebhookHeader } from '../types'
import { FaButton, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaSwitch, FaTable, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { createMcNewsApi } from '../api/mc-news-api'
import { errorMessage } from '../composables/utils'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createMcNewsApi(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()

const loading = ref(false)
const view = ref<MySubscriptionView | null>(null)
const directBusy = ref(false)
const testingId = ref('')

const editorOpen = ref(false)
const editing = ref<PushTargetView | null>(null)
const saving = ref(false)

const webhookForm = reactive({
  name: '',
  url: '',
  enabled: true,
  headers: [] as WebhookHeader[],
})

const columns: TableColumn<PushTargetView>[] = [
  { accessorKey: 'name', header: '名称', minWidth: 140, fixed: 'left' },
  { id: 'url', header: 'Webhook 地址', minWidth: 240 },
  { id: 'enabled', header: '启用', width: 80 },
  { id: 'operation', header: '操作', width: 190, fixed: 'right' },
]

const canAddMore = computed(() => (view.value?.webhooks.length ?? 0) < (view.value?.maxWebhooks ?? 5))

async function load() {
  loading.value = true
  try {
    view.value = await api.mySubscription()
  }
  catch (error) {
    toast.error(errorMessage(error, '加载订阅信息失败'))
  }
  finally {
    loading.value = false
  }
}

async function toggleDirect(enabled: boolean) {
  directBusy.value = true
  try {
    await api.setDirectEnabled(enabled)
    if (view.value)
      view.value.directEnabled = enabled
    toast.success(enabled ? '已开启私信推送，新新闻将发送到绑定会话' : '已关闭私信推送')
  }
  catch (error) {
    toast.error(errorMessage(error, '更新订阅失败'))
  }
  finally {
    directBusy.value = false
  }
}

async function testDirect() {
  testingId.value = 'direct'
  try {
    const result = await api.testMyDirect()
    if (result.ok)
      toast.success('测试私信已发送，请注意查收')
    else
      toast.error(`测试私信发送失败：${result.error || '未知错误'}`)
  }
  catch (error) {
    toast.error(errorMessage(error, '测试私信发送失败'))
  }
  finally {
    testingId.value = ''
  }
}

function openCreate() {
  editing.value = null
  webhookForm.name = ''
  webhookForm.url = ''
  webhookForm.enabled = true
  webhookForm.headers = []
  editorOpen.value = true
}

function openEdit(row: PushTargetView) {
  editing.value = row
  webhookForm.name = row.name
  webhookForm.url = row.webhookUrl
  webhookForm.enabled = row.enabled
  webhookForm.headers = row.headers.map(header => ({ ...header }))
  editorOpen.value = true
}

function addHeader() {
  webhookForm.headers = [...webhookForm.headers, { key: '', value: '' }]
}

function removeHeader(index: number) {
  webhookForm.headers = webhookForm.headers.filter((_, i) => i !== index)
}

function updateHeader(index: number, patch: Partial<WebhookHeader>) {
  const headers = [...webhookForm.headers]
  headers[index] = { ...headers[index], ...patch }
  webhookForm.headers = headers
}

async function saveWebhook() {
  if (!webhookForm.name.trim() || !webhookForm.url.trim()) {
    toast.error('请填写名称与 Webhook 地址')
    return
  }
  saving.value = true
  try {
    const payload = { name: webhookForm.name, url: webhookForm.url, enabled: webhookForm.enabled, headers: webhookForm.headers }
    if (editing.value)
      await api.updateMyWebhook(editing.value.id, payload)
    else
      await api.createMyWebhook(payload)
    toast.success(editing.value ? 'Webhook 已更新' : 'Webhook 已创建')
    editorOpen.value = false
    await load()
  }
  catch (error) {
    toast.error(errorMessage(error, '保存 Webhook 失败'))
  }
  finally {
    saving.value = false
  }
}

async function testWebhook(row: PushTargetView) {
  testingId.value = row.id
  try {
    const result = await api.testMyWebhook(row.id)
    if (result.ok)
      toast.success(`已向「${row.name}」发送测试推送`)
    else
      toast.error(`测试推送失败：${result.error || '未知错误'}`)
  }
  catch (error) {
    toast.error(errorMessage(error, '测试推送失败'))
  }
  finally {
    testingId.value = ''
  }
}

function confirmDelete(row: PushTargetView) {
  confirm.confirm({
    title: '删除 Webhook',
    content: `确认删除「${row.name}」吗？删除后新新闻不再推送到该地址。`,
    onConfirm: async () => {
      try {
        await api.deleteMyWebhook(row.id)
        toast.success('已删除')
        await load()
      }
      catch (error) {
        toast.error(errorMessage(error, '删除失败'))
      }
    },
  })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <FaPageHeader title="我的新闻订阅" description="订阅 Minecraft 新闻：绑定私信推送或配置个人 Webhook" />
  <FaPageMain>
    <div v-loading="loading" class="mc-news-form">
      <div class="mc-news-form-item">
        <div class="mc-news-toolbar">
          <span class="mc-news-form-label">私信推送</span>
          <FaSwitch
            :model-value="view?.directEnabled ?? false"
            :disabled="directBusy"
            @update:model-value="(value?: boolean) => toggleDirect(value === true)"
          />
          <FaButton size="sm" variant="outline" :loading="testingId === 'direct'" @click="testDirect">
            发送测试私信
          </FaButton>
        </div>
        <span class="mc-news-form-hint">开启后，每条新新闻都会通过平台消息绑定（如 QQ 绑定）私信发送给你。</span>
      </div>

      <div class="mc-news-form-item">
        <div class="mc-news-toolbar">
          <span class="mc-news-form-label">我的 Webhook（{{ view?.webhooks.length ?? 0 }}/{{ view?.maxWebhooks ?? 5 }}）</span>
          <span style="flex: 1" />
          <FaButton :disabled="!canAddMore" @click="openCreate">
            <FaIcon name="i-ri:add-line" />新增 Webhook
          </FaButton>
        </div>
        <FaTable
          :columns="columns"
          :data="view?.webhooks ?? []"
          row-key="id"
          table-root-class="mc-news-table-scroll"
          table-class="mc-news-table-w700"
          border
          stripe
          empty-text="还没有个人 Webhook，点击上方按钮新增"
        >
          <template #cell-url="{ row }">
            <span class="mc-news-url" :title="row.original.webhookUrl">{{ row.original.webhookUrl || '-' }}</span>
          </template>
          <template #cell-enabled="{ row }">
            <FaTag :color="row.original.enabled ? 'green' : 'gray'">{{ row.original.enabled ? '启用' : '停用' }}</FaTag>
          </template>
          <template #cell-operation="{ row }">
            <div class="flex-center gap-2">
              <FaButton size="sm" variant="outline" :loading="testingId === row.original.id" @click="testWebhook(row.original)">测试</FaButton>
              <FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
              <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
            </div>
          </template>
        </FaTable>
      </div>
    </div>

    <FaModal v-model="editorOpen" :title="editing ? '编辑 Webhook' : '新增 Webhook'" :show-confirm-button="false" :close-on-click-overlay="false">
      <div class="mc-news-form">
        <div class="mc-news-form-item">
          <span class="mc-news-form-label">名称</span>
          <FaInput v-model="webhookForm.name" placeholder="如：我的频道通知" />
        </div>
        <div class="mc-news-form-item">
          <span class="mc-news-form-label">Webhook 地址</span>
          <FaInput v-model="webhookForm.url" placeholder="https://..." />
          <span class="mc-news-form-hint">新新闻时会 POST JSON：title / content / body / sourceName / category / url / timestamp 等。</span>
        </div>
        <div class="mc-news-form-item">
          <div class="mc-news-toolbar">
            <span class="mc-news-form-label">自定义 Header</span>
            <FaButton size="sm" variant="outline" @click="addHeader">
              <FaIcon name="i-ri:add-line" />添加
            </FaButton>
          </div>
          <div v-for="(header, index) in webhookForm.headers" :key="index" class="mc-news-toolbar">
            <FaInput :model-value="header.key" class="mc-news-grow" placeholder="Header 名" @update:model-value="(value?: string) => updateHeader(index, { key: value ?? '' })" />
            <FaInput :model-value="header.value" class="mc-news-grow" placeholder="Header 值" @update:model-value="(value?: string) => updateHeader(index, { value: value ?? '' })" />
            <FaButton size="sm" variant="destructive" @click="removeHeader(index)">移除</FaButton>
          </div>
        </div>
        <div class="mc-news-form-item">
          <span class="mc-news-form-label">启用</span>
          <FaSwitch v-model="webhookForm.enabled" />
        </div>
      </div>
      <template #footer>
        <FaButton variant="outline" :disabled="saving" @click="editorOpen = false">
          取消
        </FaButton>
        <FaButton :loading="saving" @click="saveWebhook">
          保存
        </FaButton>
      </template>
    </FaModal>
  </FaPageMain>
</template>
