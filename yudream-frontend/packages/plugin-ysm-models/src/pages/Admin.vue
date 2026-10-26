<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdTablePageLayout, YdResponsiveEnhancedTable, YdPagination, YdButton, YdAlert, YdTag, YdModal, useYdToast, useYdModal, type ActionMenuItem } from '@yudream/components'
import type { Bundle, ModelRow } from '../types'
import { adminApi } from '../api/admin'
import { usePaged } from '../composables/usePaged'
import ModelPreview from '../components/ModelPreview.vue'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = adminApi(props.sdk), model = usePaged(api.list), toast = useYdToast(), confirm = useYdModal()
const preview = ref(false), bundle = ref<Bundle>(), viewing = ref('')
const columns = [{ accessorKey: 'name', header: '名称', fixed: 'left' as const }, { accessorKey: 'ownerId', header: '所有者' }, { id: 'status', header: '状态' }]
async function manage(row: ModelRow, enabled: boolean, shared: boolean) {
  try { await api.manage(row.id, enabled, shared); await model.load(); toast.success('模型设置已保存') }
  catch (e) { toast.error(e instanceof Error ? e.message : '设置保存失败') }
}
const actions: ActionMenuItem<ModelRow>[] = [
  { label: '预览', onClick: async row => { try { bundle.value = await api.preview(row.id); viewing.value = row.name; preview.value = true } catch (e) { toast.error(e instanceof Error ? e.message : '预览失败') } } },
  { label: '切换服务器共享', onClick: row => manage(row, row.enabled, !row.serverEnabled) },
  { label: '切换启用状态', onClick: row => confirm.confirm({ title: row.enabled ? '停用模型' : '启用模型', content: row.enabled ? `停用「${row.name}」会使正在使用它的角色恢复皮肤外观。` : `启用「${row.name}」后可以立即使用。`, onConfirm: () => manage(row, !row.enabled, row.serverEnabled) }) },
  { label: '删除', danger: true, onClick: row => confirm.confirm({ title: '删除模型', content: `删除「${row.name}」后，使用它的角色将恢复皮肤外观。`, onConfirm: async () => { try { await api.remove(row.id); await model.load(); toast.success('模型已删除') } catch (e) { toast.error(e instanceof Error ? e.message : '删除失败') } } }) },
]
onMounted(model.load)
watch(model.page, model.load)
</script>
<template>
  <YdTablePageLayout title="模型管理" :show-search="false">
    <template #actions><YdButton variant="outline" @click="model.load">刷新</YdButton></template>
    <template #table>
      <YdAlert v-if="model.error.value" type="error">{{ model.error.value }}</YdAlert>
      <YdResponsiveEnhancedTable :data="model.rows.value" :columns="columns" :actions="actions" :loading="model.loading.value" row-key="id" empty-text="暂无模型">
        <template #cell-status="{ row }"><YdTag variant="outline">{{ !row.original.enabled ? '已停用' : row.original.serverEnabled ? '服务器共享' : '仅所有者' }}</YdTag></template>
      </YdResponsiveEnhancedTable>
    </template>
    <template #footer><YdPagination v-model:page="model.page.value" :size="model.size" :sizes="[model.size]" :total="model.total.value" /></template>
  </YdTablePageLayout>
  <YdModal v-model="preview" :title="viewing" :show-confirm-button="false" class="sm:max-w-3xl"><ModelPreview v-if="preview && bundle" :bundle="bundle" /></YdModal>
</template>
