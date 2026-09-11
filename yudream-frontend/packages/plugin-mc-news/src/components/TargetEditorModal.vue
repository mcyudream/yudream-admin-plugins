<script setup lang="ts">
import type { PushTargetPayload, PushTargetType, PushTargetView, WebhookHeader } from '../types'
import { Select as ArcoSelect } from '@arco-design/web-vue'
import { FaButton, FaIcon, FaInput, FaModal, FaSwitch, useFaToast } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { createMcNewsApi } from '../api/mc-news-api'
import { errorMessage } from '../composables/utils'

const props = defineProps<{
  sdk: Parameters<typeof createMcNewsApi>[0]
  open: boolean
  target: PushTargetView | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'saved': []
}>()

const api = createMcNewsApi(props.sdk)
const toast = useFaToast()
const saving = ref(false)

const modalOpen = computed({
  get: () => props.open,
  set: value => emit('update:open', value),
})

const form = reactive({
  name: '',
  type: 'messaging' as PushTargetType,
  enabled: true,
  connectionId: '',
  channelId: '',
  channelName: '',
  webhookUrl: '',
  headers: [] as WebhookHeader[],
  markdown: true,
})

const connections = ref<{ id: string, name: string, platform: string }[]>([])
const groups = ref<{ id: string, name: string }[]>([])

watch(() => props.open, async (open) => {
  if (!open)
    return
  const target = props.target
  form.name = target?.name ?? ''
  form.type = (target?.type ?? 'messaging') as PushTargetType
  form.enabled = target?.enabled ?? true
  form.connectionId = target?.connectionId ?? ''
  form.channelId = target?.channelId ?? ''
  form.channelName = target?.channelName ?? ''
  form.webhookUrl = target?.webhookUrl ?? ''
  form.headers = (target?.headers ?? []).map(header => ({ ...header }))
  form.markdown = target?.markdown ?? true
  if (form.type === 'messaging') {
    await loadConnections()
    if (form.connectionId)
      await loadGroups(form.connectionId)
  }
})

async function loadConnections() {
  try {
    connections.value = await api.connections()
  }
  catch (error) {
    toast.error(errorMessage(error, '加载消息连接失败'))
  }
}

async function loadGroups(connectionId: string) {
  try {
    groups.value = await api.groups(connectionId)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载群聊列表失败'))
  }
}

async function onConnectionChange(connectionId: string) {
  form.connectionId = connectionId
  form.channelId = ''
  form.channelName = ''
  groups.value = []
  if (connectionId)
    await loadGroups(connectionId)
}

function onGroupChange(channelId: string) {
  form.channelId = channelId
  form.channelName = groups.value.find(group => group.id === channelId)?.name ?? ''
}

function addHeader() {
  form.headers = [...(form.headers ?? []), { key: '', value: '' }]
}

function removeHeader(index: number) {
  form.headers = (form.headers ?? []).filter((_, i) => i !== index)
}

function updateHeader(index: number, patch: Partial<WebhookHeader>) {
  const headers = [...(form.headers ?? [])]
  headers[index] = { ...headers[index], ...patch }
  form.headers = headers
}

async function save() {
  if (!form.name.trim()) {
    toast.error('请填写推送目标名称')
    return
  }
  if (form.type === 'messaging' && (!form.connectionId || !form.channelId)) {
    toast.error('群聊推送需要选择消息连接与群聊')
    return
  }
  if (form.type === 'webhook' && !form.webhookUrl.trim()) {
    toast.error('请填写 Webhook 地址')
    return
  }
  saving.value = true
  try {
    if (props.target) {
      await api.updateTarget(props.target.id, { ...form })
    }
    else {
      await api.createTarget({ ...form })
    }
    toast.success(props.target ? '推送目标已更新' : '推送目标已创建')
    modalOpen.value = false
    emit('saved')
  }
  catch (error) {
    toast.error(errorMessage(error, '保存推送目标失败'))
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <FaModal v-model="modalOpen" :title="props.target ? '编辑推送目标' : '新增推送目标'" :show-confirm-button="false" :close-on-click-overlay="false">
    <div class="mc-news-form">
      <div class="mc-news-form-row">
        <div class="mc-news-form-item">
          <span class="mc-news-form-label">名称</span>
          <FaInput v-model="form.name" placeholder="如：MC 交流群" />
        </div>
        <div class="mc-news-form-item">
          <span class="mc-news-form-label">类型</span>
          <div class="mc-news-toolbar">
            <FaButton :variant="form.type === 'messaging' ? 'secondary' : 'outline'" @click="form.type = 'messaging'">
              机器人群聊
            </FaButton>
            <FaButton :variant="form.type === 'webhook' ? 'secondary' : 'outline'" @click="form.type = 'webhook'">
              Webhook
            </FaButton>
          </div>
        </div>
      </div>

      <template v-if="form.type === 'messaging'">
        <div class="mc-news-form-row mc-news-form-row-2">
          <div class="mc-news-form-item">
            <span class="mc-news-form-label">消息连接</span>
            <ArcoSelect
              :model-value="form.connectionId"
              placeholder="选择机器人连接"
              allow-clear
              @change="(value: unknown) => onConnectionChange(String(value ?? ''))"
            >
              <ArcoSelect.Option v-for="connection in connections" :key="connection.id" :value="connection.id">
                {{ connection.name }}（{{ connection.platform }}）
              </ArcoSelect.Option>
            </ArcoSelect>
          </div>
          <div class="mc-news-form-item">
            <span class="mc-news-form-label">群聊</span>
            <ArcoSelect
              :model-value="form.channelId"
              placeholder="选择群聊"
              allow-search
              allow-clear
              :disabled="!form.connectionId"
              @change="(value: unknown) => onGroupChange(String(value ?? ''))"
            >
              <ArcoSelect.Option v-for="group in groups" :key="group.id" :value="group.id">
                {{ group.name }}
              </ArcoSelect.Option>
            </ArcoSelect>
          </div>
        </div>
        <div class="mc-news-form-item">
          <span class="mc-news-form-label">Markdown 渲染</span>
          <FaSwitch v-model="form.markdown" />
          <span class="mc-news-form-hint">官方 QQ 机器人协议支持 Markdown；其他协议建议关闭，按纯文本发送。</span>
        </div>
      </template>

      <template v-else>
        <div class="mc-news-form-item">
          <span class="mc-news-form-label">Webhook 地址</span>
          <FaInput v-model="form.webhookUrl" placeholder="https://..." />
          <span class="mc-news-form-hint">POST JSON 载荷：title / content / body / sourceName / category / url / imageUrl / timestamp 等。</span>
        </div>
        <div class="mc-news-form-item">
          <div class="mc-news-toolbar">
            <span class="mc-news-form-label">自定义 Header</span>
            <FaButton size="sm" variant="outline" @click="addHeader">
              <FaIcon name="i-ri:add-line" />添加 Header
            </FaButton>
          </div>
          <div v-for="(header, index) in form.headers" :key="index" class="mc-news-toolbar">
            <FaInput :model-value="header.key" class="mc-news-grow" placeholder="Header 名，如 Authorization" @update:model-value="(value?: string) => updateHeader(index, { key: value ?? '' })" />
            <FaInput :model-value="header.value" class="mc-news-grow" placeholder="Header 值" @update:model-value="(value?: string) => updateHeader(index, { value: value ?? '' })" />
            <FaButton size="sm" variant="destructive" @click="removeHeader(index)">移除</FaButton>
          </div>
        </div>
      </template>

      <div class="mc-news-form-item">
        <span class="mc-news-form-label">启用</span>
        <FaSwitch v-model="form.enabled" />
      </div>
    </div>
    <template #footer>
      <FaButton variant="outline" :disabled="saving" @click="modalOpen = false">
        取消
      </FaButton>
      <FaButton :loading="saving" @click="save">
        保存
      </FaButton>
    </template>
  </FaModal>
</template>
