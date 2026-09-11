<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { PushTargetView } from '../types'
import { FaButton, FaIcon, FaPageHeader, FaPageMain, FaSwitch, FaTable, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createMcNewsApi } from '../api/mc-news-api'
import { errorMessage } from '../composables/utils'
import TargetEditorModal from '../components/TargetEditorModal.vue'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createMcNewsApi(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()

const loading = ref(false)
const testingId = ref('')
const rows = ref<PushTargetView[]>([])
const editorOpen = ref(false)
const editing = ref<PushTargetView | null>(null)

const columns: TableColumn<PushTargetView>[] = [
  { accessorKey: 'name', header: '名称', minWidth: 160, fixed: 'left' },
  { id: 'type', header: '类型', width: 110 },
  { id: 'target', header: '推送位置', minWidth: 240 },
  { id: 'enabled', header: '启用', width: 80 },
  { accessorKey: 'markdown', header: 'Markdown', width: 100 },
  { id: 'operation', header: '操作', width: 200, fixed: 'right' },
]

async function load() {
  loading.value = true
  try {
    rows.value = await api.targets()
  }
  catch (error) {
    toast.error(errorMessage(error, '加载推送目标失败'))
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  editorOpen.value = true
}

function openEdit(row: PushTargetView) {
  editing.value = row
  editorOpen.value = true
}

function targetLabel(row: PushTargetView) {
  if (row.type === 'messaging')
    return row.channelName || row.channelId
  return row.webhookUrl
}

async function toggleEnabled(row: PushTargetView, enabled: boolean) {
  try {
    await api.updateTarget(row.id, { enabled })
    row.enabled = enabled
    toast.success(enabled ? '已启用' : '已停用')
  }
  catch (error) {
    row.enabled = !enabled
    toast.error(errorMessage(error, '更新失败'))
  }
}

async function test(row: PushTargetView) {
  testingId.value = row.id
  try {
    const result = await api.testTarget(row.id)
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
    title: '删除推送目标',
    content: `确认删除「${row.name}」吗？删除后新新闻不再推送到该目标。`,
    onConfirm: async () => {
      try {
        await api.deleteTarget(row.id)
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
  <FaPageHeader title="推送目标" description="配置新新闻的推送目的地：机器人群聊或通用 Webhook">
    <FaButton @click="openCreate">
      <FaIcon name="i-ri:add-line" />新增目标
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaTable
      v-loading="loading"
      :columns="columns"
      :data="rows"
      row-key="id"
      table-root-class="mc-news-table-scroll"
      table-class="mc-news-table-w900"
      border
      stripe
      column-visibility
      empty-text="暂无推送目标，点击右上角新增"
    >
      <template #cell-type="{ row }">
        <FaTag :color="row.original.type === 'messaging' ? 'arcoblue' : 'green'">
          {{ row.original.type === 'messaging' ? '机器人群聊' : 'Webhook' }}
        </FaTag>
      </template>
      <template #cell-target="{ row }">
        <span class="mc-news-url" :title="targetLabel(row.original)">{{ targetLabel(row.original) || '-' }}</span>
      </template>
      <template #cell-enabled="{ row }">
        <FaSwitch :model-value="row.original.enabled" @update:model-value="(value?: boolean) => toggleEnabled(row.original, value === true)" />
      </template>
      <template #cell-markdown="{ row }">
        <span v-if="row.original.type === 'messaging'">{{ row.original.markdown ? '开' : '关' }}</span>
        <span v-else class="mc-news-form-hint">-</span>
      </template>
      <template #cell-operation="{ row }">
        <div class="flex-center gap-2">
          <FaButton size="sm" variant="outline" :loading="testingId === row.original.id" @click="test(row.original)">测试</FaButton>
          <FaButton size="sm" variant="outline" @click="openEdit(row.original)">编辑</FaButton>
          <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton>
        </div>
      </template>
    </FaTable>

    <TargetEditorModal v-model:open="editorOpen" :sdk="props.sdk" :target="editing" @saved="load" />
  </FaPageMain>
</template>
