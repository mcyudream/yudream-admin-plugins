<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdTablePageLayout, YdResponsiveEnhancedTable, YdPagination, YdButton, YdAlert, YdTag, YdModal, YdInput, useYdToast, useYdModal, type ActionMenuItem } from '@yudream/components'
import type { ModelRow } from '../types'
import { userApi } from '../api/user'
import { usePaged } from '../composables/usePaged'
import UploadDialog from '../components/UploadDialog.vue'
import OutfitDialog from '../components/OutfitDialog.vue'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = userApi(props.sdk), model = usePaged(api.list), toast = useYdToast(), modal = useYdModal()
const upload = ref(false), existing = ref<ModelRow>(), viewing = ref<ModelRow>(), preview = ref(false), renaming = ref<ModelRow>(), renameOpen = ref(false), name = ref(''), saving = ref(false)
const columns = [{ accessorKey: 'name', header: '名称', fixed: 'left' as const }, { id: 'status', header: '状态' }, { id: 'hash', header: '资源版本' }]
function view(row: ModelRow) { viewing.value = row; preview.value = true }
async function uploaded(row: ModelRow) { await model.load(); view(row) }
const actions: ActionMenuItem<ModelRow>[] = [
  { label: '预览 / 装备', onClick: view },
  { label: '上传新版本', onClick: row => { existing.value = row; upload.value = true } },
  { label: '重命名', onClick: row => { renaming.value = row; name.value = row.name; renameOpen.value = true } },
  { label: '删除', danger: true, onClick: row => modal.confirm({ title: '删除模型', content: `删除「${row.name}」后，使用它的角色将恢复皮肤外观。`, onConfirm: async () => { try { await api.remove(row.id); await model.load(); toast.success('模型已删除') } catch (e) { toast.error(e instanceof Error ? e.message : '删除失败') } } }) },
]
async function rename() {
  if (!renaming.value || !name.value.trim() || saving.value) return
  saving.value = true
  try { await api.rename(renaming.value.id, name.value.trim()); renameOpen.value = false; await model.load(); toast.success('名称已保存') }
  catch (e) { toast.error(e instanceof Error ? e.message : '保存失败') }
  finally { saving.value = false }
}
onMounted(model.load)
watch(model.page, model.load)
</script>
<template>
  <YdTablePageLayout title="我的模型" :show-search="false">
    <template #actions><YdButton variant="outline" @click="model.load">刷新</YdButton><YdButton @click="existing = undefined; upload = true">上传模型</YdButton></template>
    <template #table>
      <YdAlert v-if="model.error.value" type="error">{{ model.error.value }}</YdAlert>
      <YdResponsiveEnhancedTable :data="model.rows.value" :columns="columns" :actions="actions" :loading="model.loading.value" row-key="id" empty-text="还没有模型，上传后即可预览和装备">
        <template #cell-status="{ row }"><YdTag variant="outline">{{ !row.original.enabled ? '已停用' : row.original.serverEnabled ? '服务器共享' : '仅本人' }}</YdTag></template>
        <template #cell-hash="{ row }"><code>{{ row.original.hash.slice(0, 12) }}</code></template>
      </YdResponsiveEnhancedTable>
    </template>
    <template #footer><YdPagination v-model:page="model.page.value" :size="model.size" :sizes="[model.size]" :total="model.total.value" /></template>
  </YdTablePageLayout>
  <UploadDialog v-model="upload" :sdk="sdk" :existing="existing" @uploaded="uploaded" />
  <OutfitDialog v-model="preview" :sdk="sdk" :row="viewing" :load="api.preview" />
  <YdModal v-model="renameOpen" title="重命名模型" :confirm-button-loading="saving" @confirm="rename"><YdInput v-model="name" :maxlength="100" aria-label="模型名称" /></YdModal>
</template>
