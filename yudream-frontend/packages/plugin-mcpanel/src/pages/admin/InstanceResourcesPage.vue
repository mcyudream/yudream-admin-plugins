<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DeploymentPreview as Preview } from '../../api/deployment-api'
import { YdAlert, YdButton, YdFilePicker, YdLoadingOverlay, YdPageHeader, YdPageMain } from '@yudream/components'
import { ref, watch } from 'vue'
import DeploymentPreview from './components/DeploymentPreview.vue'
import InstanceDeploymentHistory from './components/InstanceDeploymentHistory.vue'
import InstanceDeploymentNav from './components/InstanceDeploymentNav.vue'
import InstanceDeploymentStatus from './components/InstanceDeploymentStatus.vue'
import { useInstanceDeployments } from '../../composables/useInstanceDeployments'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const { id, api, instance, title, canManage, stopped, canSubmit, loading, busy, error, history, historyError, historyLoading, total, page, size, activeJob, load, loadHistory, operate, confirm, rollback } = useInstanceDeployments(props.sdk)
const preview = ref<Preview | null>(null)
watch(id, () => { preview.value = null })
async function select(files: File[]) {
  const file = files[0]
  if (!file) return
  if (file.size > 64 * 1024 ** 2) { error.value = '资源安装包不能超过 64MiB'; return }
  preview.value = null
  await operate(current => api.inspectPackage(current, file), (result) => { preview.value = result })
}
function install() {
  const packageId = preview.value?.packageId
  if (!packageId) return
  confirm('安装 CE 资源包', `安装 ${preview.value?.name} ${preview.value?.version}，覆盖的旧文件会保留，完成后可在此页回退。`, current => api.installPackage(current, packageId), () => { preview.value = null })
}
</script>

<template>
  <YdPageHeader :title="`安装 CE 资源：${title}`" description="上传规范资源包，一次安装 CraftEngine contents、插件配置与可选 JAR。">
    <InstanceDeploymentNav :id="id" current="resources" :can-manage="canManage" />
  </YdPageHeader>
  <YdPageMain class="space-y-6">
    <InstanceDeploymentStatus :id="id" :instance="instance" :stopped="stopped" :can-manage="canManage" :error="error" :job="activeJob" @retry="load" />
    <YdLoadingOverlay :loading="loading || busy">
      <section v-if="instance" class="space-y-4" aria-label="上传与预览资源包">
        <p>选择 mcpanel-ce/1 ZIP。面板检查清单、SHA-256 和命名空间，预览通过后再安装。</p>
        <YdFilePicker accept=".zip" label="选择并校验资源包" :disabled="!canSubmit || busy" @select="select">
          <template #default="{ open, triggerAttrs, disabled }"><YdButton v-bind="triggerAttrs" variant="outline" :disabled="disabled" @click="open">选择并校验资源包</YdButton></template>
        </YdFilePicker>
        <template v-if="preview">
          <h2 class="text-base font-semibold">{{ preview.name }} · {{ preview.version }} · CE 命名空间 {{ preview.namespace }}</h2>
          <DeploymentPreview :preview="preview" />
          <YdButton :disabled="!canSubmit || busy" @click="install">安装并保留回退副本</YdButton>
        </template>
        <YdAlert title="安装后启动验证" description="安装完成后保持停机。启动服务器后按 CraftEngine 工作流重建、上传并发送资源包。ZIP 不超过 64MiB，解压后不超过 512MiB。" />
      </section>
    </YdLoadingOverlay>
    <InstanceDeploymentHistory v-model:page="page" v-model:size="size" :jobs="history" :total="total" :loading="historyLoading" :error="historyError" :can-manage="canManage" :stopped="stopped" :busy="busy" @retry="loadHistory" @rollback="rollback" />
  </YdPageMain>
</template>
