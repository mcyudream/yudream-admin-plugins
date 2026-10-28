<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn, YdTablePickerQuery } from '@yudream/components'
import type { DeploymentPreview as Preview } from '../../api/deployment-api'
import { YdAlert, YdButton, YdForm, YdFormItem, YdLoadingOverlay, YdPageHeader, YdPageMain, YdTablePicker, YdTextarea } from '@yudream/components'
import { reactive, ref, watch } from 'vue'
import { createMcPanelExtra } from '../../api/api-extra'
import DeploymentPreview from './components/DeploymentPreview.vue'
import InstanceDeploymentHistory from './components/InstanceDeploymentHistory.vue'
import InstanceDeploymentNav from './components/InstanceDeploymentNav.vue'
import InstanceDeploymentStatus from './components/InstanceDeploymentStatus.vue'
import { useInstanceDeployments } from '../../composables/useInstanceDeployments'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const { id, api, instance, title, canManage, stopped, canSubmit, loading, busy, error, history, historyError, historyLoading, total, page, size, activeJob, load, loadHistory, operate, confirm, rollback } = useInstanceDeployments(props.sdk)
const extra = createMcPanelExtra(props.sdk)
const form = reactive({ targets: [] as string[], paths: 'plugins\nconfig' })
const preview = ref<Preview | null>(null)
const pickerColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '实例', minWidth: 180 },
  { accessorKey: 'nodeId', header: '节点', defaultHidden: true },
  { accessorKey: 'state', header: '状态' },
]
watch(form, () => { preview.value = null })
watch(id, () => { form.targets = []; form.paths = 'plugins\nconfig'; preview.value = null })

async function fetchTargets(query: YdTablePickerQuery) {
  const result = await extra.pageInstances(query.page, query.size, query.keyword) as { records: Record<string, unknown>[], total: number }
  return { list: result.records, total: result.total }
}
async function inspect() {
  const target = form.targets[0]
  if (!target || target === id.value) { error.value = '请选择另一个目标实例'; return }
  const selected = form.paths.split('\n').map(value => value.trim()).filter(Boolean)
  await operate(current => api.preview(current, target, selected), (result) => { preview.value = result })
}
function deploy() {
  const plan = preview.value?.planId
  if (!plan) return
  confirm('覆盖目标实例文件', `将覆盖 ${preview.value?.replaceCount ?? 0} 个同名文件，并新增 ${preview.value?.addCount ?? 0} 个文件。先暂存全部内容，旧文件会保留用于回退。`, current => api.deploy(current, plan), () => { preview.value = null })
}
</script>

<template>
  <YdPageHeader :title="`覆盖与回退：${title}`" description="预览文件差异后覆盖到目标实例。后台任务离开页面后继续执行。">
    <InstanceDeploymentNav :id="id" current="deployments" :can-manage="canManage" />
  </YdPageHeader>
  <YdPageMain class="space-y-6">
    <InstanceDeploymentStatus :id="id" :instance="instance" :stopped="stopped" :can-manage="canManage" :error="error" :job="activeJob" @retry="load" />
    <YdLoadingOverlay :loading="loading || busy">
      <YdForm v-if="instance" :model="form" :disabled="!canSubmit || busy" @submit-success="inspect">
        <YdFormItem field="targets" label="目标实例" required extra="选择另一个已停止的实例，可跨节点覆盖。">
          <YdTablePicker v-model="form.targets" :columns="pickerColumns" :fetcher="fetchTargets" :multiple="false" label-key="name" title="选择目标实例" placeholder="选择要接收文件的实例" />
        </YdFormItem>
        <YdFormItem field="paths" label="要覆盖的文件或目录" required extra="每行一个相对实例根的路径；例如 plugins/MyPlugin 或 config。">
          <YdTextarea v-model="form.paths" :rows="4" placeholder="plugins/MyPlugin&#10;config" />
        </YdFormItem>
        <YdAlert title="合并覆盖，并保留回退副本" description="只处理所选路径内的源文件，目标中独有的文件保留。世界目录需明确加入清单；源和目标实例都必须停止。" />
        <div class="mt-4"><YdButton type="submit" variant="outline" :disabled="!canSubmit || busy">预览覆盖内容</YdButton></div>
      </YdForm>
      <section v-if="preview" class="mt-4 space-y-4" aria-label="覆盖内容预览">
        <DeploymentPreview :preview="preview" />
        <YdButton :disabled="!canSubmit || busy" @click="deploy">确认覆盖并保留回退副本</YdButton>
      </section>
    </YdLoadingOverlay>
    <InstanceDeploymentHistory v-model:page="page" v-model:size="size" :jobs="history" :total="total" :loading="historyLoading" :error="historyError" :can-manage="canManage" :stopped="stopped" :busy="busy" @retry="loadHistory" @rollback="rollback" />
  </YdPageMain>
</template>
