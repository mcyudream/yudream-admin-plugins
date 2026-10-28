<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdAlert, YdButton, YdForm, YdFormItem, YdInput, YdLoadingOverlay, YdPageHeader, YdPageMain, useYdToast } from '@yudream/components'
import { reactive, watch } from 'vue'
import InstanceDeploymentNav from './components/InstanceDeploymentNav.vue'
import InstanceDeploymentStatus from './components/InstanceDeploymentStatus.vue'
import { useInstanceDeployments } from '../../composables/useInstanceDeployments'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const { id, api, instance, title, canManage, stopped, canSubmit, loading, busy, error, activeJob, load, operate, acceptJob } = useInstanceDeployments(props.sdk)
const form = reactive({ name: '' })
const toast = useYdToast()
watch(id, () => { form.name = '' })
watch(instance, (value) => { if (value && !form.name) form.name = `${title.value}-测试` })
async function copy() {
  const name = form.name.trim()
  if (!name) { error.value = '请填写测试服名称'; return }
  await operate(current => api.copy(current, name), (job) => {
    acceptJob(job)
    if (job.state === 'running') toast.success('测试服已创建，正在复制文件')
  })
}
</script>

<template>
  <YdPageHeader :title="`复制测试服：${title}`" description="在当前节点创建独立副本并分配新端口，文件复制完成后保持停止。">
    <InstanceDeploymentNav :id="id" current="copy" :can-manage="canManage" />
  </YdPageHeader>
  <YdPageMain class="space-y-6">
    <InstanceDeploymentStatus :id="id" :instance="instance" :stopped="stopped" :can-manage="canManage" :error="error" :job="activeJob" @retry="load" />
    <YdLoadingOverlay :loading="loading || busy">
      <YdForm v-if="instance" :model="form" :disabled="!canSubmit || busy" @submit-success="copy">
        <YdFormItem field="name" label="测试服名称" required :rules="[{ required: true, match: /\S/, message: '请填写测试服名称' }]" extra="文件、镜像和启动命令从当前实例复制。">
          <YdInput v-model="form.name" placeholder="例如：生存服-测试" :max-length="80" />
        </YdFormItem>
        <YdAlert title="启动前检查外部配置" description="域名、平台子服绑定、P2P、自动启动和自动重启默认关闭。插件文件里的数据库、代理监听端口和外部地址仍会复制，请调整后再启动测试服。" />
        <div class="mt-4"><YdButton type="submit" :disabled="!canSubmit || busy">复制为测试服</YdButton></div>
      </YdForm>
    </YdLoadingOverlay>
  </YdPageMain>
</template>
