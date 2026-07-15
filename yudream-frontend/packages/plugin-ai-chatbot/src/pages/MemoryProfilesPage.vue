<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { MemoryProfile } from '../types'
import { onMounted, reactive, ref } from 'vue'
import { FaButton, FaPageHeader, FaPageMain, FaPagination, FaTable, FaTag } from '@yudream/components'
import { createAiChatbotApi } from '../api/ai-chatbot-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createAiChatbotApi(props.sdk)
const rows = ref<MemoryProfile[]>([])
const loading = ref(false)
const pager = reactive({ page: 1, size: 10, total: 0 })
const columns = [
  { id: 'nickname', header: '用户', accessorKey: 'nickname' },
  { id: 'platformUserId', header: 'QQ', accessorKey: 'platformUserId' },
  { id: 'scope', header: '群聊范围' },
  { id: 'enabled', header: '状态' },
  { id: 'operation', header: '操作', width: 160 },
]

async function load() {
  loading.value = true
  try {
    const page = await api.memoryProfiles(pager.page, pager.size)
    rows.value = page.records
    pager.total = page.total
  } finally { loading.value = false }
}
async function toggle(row: MemoryProfile) { await api.setMemoryProfileEnabled(row.id, !row.enabled); await load() }
async function remove(row: MemoryProfile) { if (!window.confirm(`删除 ${row.nickname || row.platformUserId} 的画像？`)) return; await api.deleteMemoryProfile(row.id); if (rows.value.length === 1 && pager.page > 1) pager.page--; await load() }
onMounted(load)
</script>
<template>
  <FaPageHeader title="记忆画像管理" class="mb-0"><FaButton variant="outline" :loading="loading" @click="load">刷新</FaButton></FaPageHeader>
  <FaPageMain>
    <FaTable v-loading="loading" row-key="id" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[900px]" border stripe column-visibility :columns="columns" :data="rows" empty-text="暂无记忆画像">
      <template #cell-scope="{ row }">{{ row.original.connectionId }} / {{ row.original.channelId }}</template>
      <template #cell-enabled="{ row }"><FaTag>{{ row.original.enabled ? '已启用' : '已停用' }}</FaTag></template>
      <template #cell-operation="{ row }"><div class="flex gap-2"><FaButton size="sm" variant="outline" @click="toggle(row.original)">{{ row.original.enabled ? '停用' : '启用' }}</FaButton><FaButton size="sm" variant="destructive" @click="remove(row.original)">删除</FaButton></div></template>
    </FaTable>
    <FaPagination v-model:page="pager.page" v-model:size="pager.size" :total="pager.total" class="mt-3" @page-change="load" @size-change="load" />
  </FaPageMain>
</template>
