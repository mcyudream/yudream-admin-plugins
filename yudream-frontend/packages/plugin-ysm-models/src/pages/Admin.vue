<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdTablePageLayout, YdResponsiveEnhancedTable, YdPagination, YdButton, YdAlert, YdTag, YdModal, YdTextarea, YdLabel, YdListToolbar, YdFilterSelect, useYdToast, useYdModal, type ActionMenuItem } from '@yudream/components'
import type { Bundle, ModelRow } from '../types'
import { adminApi } from '../api/admin'
import { usePaged } from '../composables/usePaged'
import { useModelCovers } from '../composables/useModelCovers'
import ModelCover from '../components/ModelCover.vue'
import ModelPreview from '../components/ModelPreview.vue'
import { reviewLabel } from '../moderation'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const filter = ref('ALL')
const filters = [{ label: '全部审核状态', value: 'ALL' }, { label: '待审核', value: 'PENDING' }, { label: '已通过', value: 'APPROVED' }, { label: '已驳回', value: 'REJECTED' }]
const api = adminApi(props.sdk), model = usePaged((page, size) => api.list(page, size, filter.value)), toast = useYdToast(), confirm = useYdModal()
const cover = useModelCovers(api.preview)
const preview = ref(false), bundle = ref<Bundle>(), viewing = ref('')
const reviewOpen = ref(false), reviewRow = ref<ModelRow>(), approving = ref(true), note = ref(''), reviewBusy = ref(false), reviewError = ref('')
const columns = [{ accessorKey: 'name', header: '名称', width: 360, fixed: 'left' as const }, { accessorKey: 'ownerId', header: '所有者', width: 220 }, { id: 'review', header: '审核', width: 140 }, { id: 'status', header: '状态', width: 140 }]
function applyFilter() { if (model.page.value !== 1) model.page.value = 1; else void model.load() }
function review(row: ModelRow, approved: boolean) { reviewRow.value = row; approving.value = approved; note.value = ''; reviewError.value = ''; reviewOpen.value = true }
async function reviewBeforeClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (reviewBusy.value) return
  if (action !== 'confirm') { done(); return }
  if (!reviewRow.value) return
  reviewError.value = ''
  if (!approving.value && !note.value.trim()) { reviewError.value = '请填写驳回原因，便于用户修改后重新提交'; return }
  reviewBusy.value = true
  try { await api.review(reviewRow.value.id, reviewRow.value.hash, approving.value, note.value.trim()); done(); await model.load(); toast.success(approving.value ? '模型审核已通过' : '模型已驳回') }
  catch (e) { reviewError.value = e instanceof Error ? e.message : '审核失败' }
  finally { reviewBusy.value = false }
}
async function manage(row: ModelRow, enabled: boolean, shared: boolean) {
  try { await api.manage(row.id, enabled, shared); await model.load(); toast.success('模型设置已保存') }
  catch (e) { toast.error(e instanceof Error ? e.message : '设置保存失败') }
}
const actions: ActionMenuItem<ModelRow>[] = [
  { label: '预览', onClick: async row => { try { bundle.value = await api.preview(row.id); viewing.value = row.name; preview.value = true } catch (e) { toast.error(e instanceof Error ? e.message : '预览失败') } } },
  { label: '审核通过', visible: row => row.reviewStatus === 'PENDING', onClick: row => review(row, true) },
  { label: '驳回', visible: row => row.reviewStatus === 'PENDING', onClick: row => review(row, false) },
  { label: '切换服务器共享', disabled: row => row.reviewStatus === 'PENDING' || row.reviewStatus === 'REJECTED', onClick: row => manage(row, row.enabled, !row.serverEnabled) },
  { label: '切换启用状态', onClick: row => confirm.confirm({ title: row.enabled ? '停用模型' : '启用模型', content: row.enabled ? `停用「${row.name}」会使正在使用它的角色恢复皮肤外观。` : `启用「${row.name}」后可以立即使用。`, onConfirm: () => manage(row, !row.enabled, row.serverEnabled) }) },
  { label: '删除', danger: true, onClick: row => confirm.confirm({ title: '删除模型', content: `删除「${row.name}」后，使用它的角色将恢复皮肤外观。`, onConfirm: async () => { try { await api.remove(row.id); await model.load(); toast.success('模型已删除') } catch (e) { toast.error(e instanceof Error ? e.message : '删除失败') } } }) },
]
onMounted(model.load)
watch(model.page, model.load)
</script>
<template>
  <section class="ysm-admin">
  <YdTablePageLayout title="模型管理">
    <template #search>
      <YdListToolbar title="模型审核与共享">
        <template #search><YdFilterSelect v-model="filter" :options="filters" :width="200" aria-label="审核状态" /></template>
        <template #actions><YdButton variant="outline" :loading="model.loading.value" @click="applyFilter">筛选</YdButton></template>
      </YdListToolbar>
    </template>
    <template #table>
      <YdAlert v-if="model.error.value" type="error">{{ model.error.value }}</YdAlert>
      <YdResponsiveEnhancedTable :data="model.rows.value" :columns="columns" :actions="actions" :loading="model.loading.value" row-key="id" empty-text="暂无模型">
        <template #cell-name="{ row }"><div class="ysm-model-identity"><ModelCover :name="row.original.name" :revision="row.original.hash" :load="() => cover(row.original)" compact /><strong :title="row.original.name">{{ row.original.name }}</strong></div></template>
        <template #cell-status="{ row }"><YdTag variant="outline">{{ !row.original.enabled ? '已停用' : row.original.serverEnabled ? '服务器共享' : '仅所有者' }}</YdTag></template>
        <template #cell-review="{ row }"><div class="ysm-review-status"><YdTag variant="outline">{{ reviewLabel(row.original) }}</YdTag><span v-if="row.original.reviewNote" :title="row.original.reviewNote">{{ row.original.reviewNote }}</span></div></template>
      </YdResponsiveEnhancedTable>
    </template>
    <template #footer><YdPagination v-model:page="model.page.value" :size="model.size" :sizes="[model.size]" :total="model.total.value" /></template>
  </YdTablePageLayout>
  <YdModal v-model="preview" :title="viewing" :show-confirm-button="false" class="sm:max-w-3xl"><ModelPreview v-if="preview && bundle" :bundle="bundle" /></YdModal>
  <YdModal v-model="reviewOpen" :title="approving ? '审核通过' : '驳回模型'" :confirm-button-loading="reviewBusy" :before-close="reviewBeforeClose" :close-on-click-overlay="!reviewBusy" :show-cancel-button="!reviewBusy">
    <div class="ysm-form">
      <p>模型：{{ reviewRow?.name }}</p>
      <p>{{ approving ? '通过后可装备此版本；只有已开启服务器共享的模型才会进入公共模型库。' : '驳回后此版本不能装备或同步到游戏，用户可根据原因修改后重新上传。' }}</p>
      <YdAlert v-if="reviewError" type="error">{{ reviewError }}</YdAlert>
      <YdLabel :label="approving ? '审核说明（可选）' : '驳回原因（必填）'"><YdTextarea v-model="note" :maxlength="500" :disabled="reviewBusy" :rows="3" /></YdLabel>
    </div>
  </YdModal>
  </section>
</template>
