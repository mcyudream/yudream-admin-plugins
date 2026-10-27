<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdPageHeader, YdPageMain, YdPagination, YdButton, YdAlert, YdModal, YdInput, useYdToast, useYdModal, type ActionMenuItem } from '@yudream/components'
import type { ModelRow, ModelSettings } from '../types'
import { userApi } from '../api/user'
import { usePaged } from '../composables/usePaged'
import { useModelCovers } from '../composables/useModelCovers'
import ModelGallery from '../components/ModelGallery.vue'
import UploadDialog from '../components/UploadDialog.vue'
import OutfitDialog from '../components/OutfitDialog.vue'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = userApi(props.sdk), model = usePaged(api.list), toast = useYdToast(), modal = useYdModal()
const cover = useModelCovers(api.preview)
const policy = ref<ModelSettings>(), policyError = ref(''), policyLoading = ref(false)
async function loadPolicy() { policyLoading.value = true; policyError.value = ''; try { policy.value = await api.policy() } catch (e) { policy.value = undefined; policyError.value = e instanceof Error ? e.message : '上传策略加载失败' } finally { policyLoading.value = false } }
async function refresh() { await Promise.all([model.load(), loadPolicy()]) }
const upload = ref(false), existing = ref<ModelRow>(), viewing = ref<ModelRow>(), preview = ref(false), renaming = ref<ModelRow>(), renameOpen = ref(false), name = ref(''), saving = ref(false)
function view(row: ModelRow) { viewing.value = row; preview.value = true }
async function uploaded(row: ModelRow) { await model.load(); view(row) }
const actions: ActionMenuItem<ModelRow>[] = [
  { label: '预览 / 装备', onClick: view },
  { label: '上传新版本', disabled: () => policyLoading.value || !policy.value?.uploadEnabled, onClick: row => { existing.value = row; upload.value = true } },
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
onMounted(refresh)
watch(model.page, model.load)
</script>
<template>
  <section class="ysm-library">
    <YdPageHeader title="我的模型" description="收藏你的另一种模样。上传模型，选择贴图，再为角色换装。">
      <YdButton variant="outline" :loading="model.loading.value || policyLoading" @click="refresh">刷新</YdButton><YdButton :disabled="policyLoading || !policy?.uploadEnabled" @click="existing = undefined; upload = true">上传模型</YdButton>
    </YdPageHeader>
    <YdPageMain>
      <div class="ysm-library-heading"><h2>模型收藏</h2><span>{{ model.total.value }} 个模型</span></div>
      <YdAlert v-if="model.error.value" type="error">{{ model.error.value }}</YdAlert>
      <YdAlert v-if="policyError" type="error">{{ policyError }}，请刷新重试。</YdAlert>
      <YdAlert v-else-if="policy && !policy.uploadEnabled" type="warning">管理员已关闭模型上传，暂不能上传新模型或新版本。已有可用模型仍可装备。</YdAlert>
      <YdAlert v-else-if="policy?.reviewRequired">模型上传后需要管理员审核，通过后即可装备。上传新版本也需要重新审核。</YdAlert>
      <ModelGallery :rows="model.rows.value" :loading="model.loading.value" :failed="!!model.error.value" :cover="cover" :actions="actions" empty-title="还没有自己的模型" :empty-description="!policy?.uploadEnabled ? '当前暂不接受上传，可以先使用服务器共享模型。' : policy.reviewRequired ? '上传 .ysm 或 ZIP，预览后等待管理员审核。' : '上传 .ysm 或明文 ZIP，转换完成后即可预览和装备。'" @preview="view" />
      <YdPagination v-model:page="model.page.value" :size="model.size" :sizes="[model.size]" :total="model.total.value" class="ysm-pagination" />
    </YdPageMain>
  <UploadDialog v-model="upload" :sdk="sdk" :existing="existing" :policy="policy" @uploaded="uploaded" />
  <OutfitDialog v-model="preview" :sdk="sdk" :row="viewing" :load="api.preview" />
  <YdModal v-model="renameOpen" title="重命名模型" :confirm-button-loading="saving" @confirm="rename"><YdInput v-model="name" :maxlength="100" aria-label="模型名称" /></YdModal>
  </section>
</template>
