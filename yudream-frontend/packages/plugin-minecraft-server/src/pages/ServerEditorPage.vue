<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { MinecraftEndpoint } from '../types'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
import { FaButton, FaIcon, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTable, useFaModal } from '@yudream/components'
import MarkdownEditor from '../components/MarkdownEditor.vue'

const props = defineProps<{ model: MinecraftServerPluginModel }>()
const modal = useFaModal()
const editionOptions = [{ label: 'Java', value: 'JAVA' }, { label: '基岩版', value: 'BEDROCK' }]
const columns: TableColumn<MinecraftEndpoint>[] = [
  { id: 'name', header: '线路名称', width: 160 }, { id: 'host', header: '主机', width: 220 }, { id: 'port', header: '端口', width: 120 }, { id: 'edition', header: '版本', width: 130 }, { id: 'primary', header: '主线', width: 90 }, { id: 'enabled', header: '启用', width: 90 }, { id: 'operation', header: '操作', width: 90, fixed: 'right' },
]
function confirmDelete() {
  const server = props.model.servers.find(item => item.id === props.model.serverForm.id)
  if (!server) return
  modal.confirm({ title: '删除服务器', content: `确认删除“${server.name}”吗？相关状态、周目操作和玩家记录也会删除。`, onConfirm: () => props.model.deleteServer(server) })
}
</script>

<template>
  <FaPageHeader :title="model.serverForm.id ? '编辑服务器' : '新增服务器'" class="mb-0" />
  <FaPageMain>
    <form class="grid gap-4" @submit.prevent="model.saveServer">
      <div class="grid grid-cols-1 gap-3 md:grid-cols-[minmax(220px,1fr)_160px_120px]">
        <label class="grid gap-2"><span>名称</span><FaInput v-model="model.serverForm.name" /></label>
        <label class="grid gap-2"><span>排序</span><FaNumberField v-model="model.serverForm.sort" /></label>
        <label class="grid gap-2"><span>启用</span><FaSwitch v-model="model.serverForm.enabled" /></label>
      </div>
      <div class="mt-4 grid gap-3"><div class="flex flex-wrap items-center justify-between gap-2"><h3 class="text-base font-semibold">多线路地址</h3><FaButton size="sm" variant="outline" type="button" @click="model.addEndpoint"><FaIcon name="i-ri:add-line" />新增线路</FaButton></div><FaTable :row-key="(_row, index) => String(index)" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[980px]" border stripe :columns="columns" :data="model.serverForm.endpoints"><template #cell-name="{ row }"><FaInput v-model="row.original.name" /></template><template #cell-host="{ row }"><FaInput v-model="row.original.host" /></template><template #cell-port="{ row }"><FaInput :model-value="row.original.port ?? ''" @update:model-value="row.original.port = $event" /></template><template #cell-edition="{ row }"><FaSelect v-model="row.original.edition" :options="editionOptions" /></template><template #cell-primary="{ row }"><FaSwitch v-model="row.original.primaryLine" /></template><template #cell-enabled="{ row }"><FaSwitch v-model="row.original.enabled" /></template><template #cell-operation="{ index }"><FaButton size="sm" variant="destructive" type="button" @click="model.removeEndpoint(index)"><FaIcon name="i-ri:delete-bin-line" /></FaButton></template></FaTable></div>
      <div class="mt-4 grid gap-3"><h3 class="text-base font-semibold">Markdown 描述</h3><MarkdownEditor v-model="model.serverForm.descriptionMarkdown" :upload-image="model.uploadMarkdownImage" /></div>
      <div class="mt-4 flex flex-wrap justify-end gap-2"><FaButton v-if="model.serverForm.id" type="button" variant="destructive" :loading="model.saving" @click="confirmDelete">删除</FaButton><FaButton type="submit" :loading="model.saving"><FaIcon name="i-ri:save-3-line" />保存</FaButton></div>
    </form>
  </FaPageMain>
</template>
