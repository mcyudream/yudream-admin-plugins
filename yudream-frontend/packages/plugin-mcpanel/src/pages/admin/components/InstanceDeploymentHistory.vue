<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { DeploymentJob } from '../../../api/deployment-api'
import { YdAlert, YdButton, YdEmpty, YdListToolbar, YdLoadingOverlay, YdPagination, YdResponsiveEnhancedTable, YdResult, YdTag } from '@yudream/components'
import { computed } from 'vue'
import { deploymentInProgress, deploymentLabels } from '../../../composables/useInstanceDeployments'
import { formatDateTime } from '../../../composables/utils'

const props = defineProps<{ jobs: DeploymentJob[], total: number, loading: boolean, error: string, canManage: boolean, stopped: boolean, busy: boolean }>()
const emit = defineEmits<{ retry: [], rollback: [job: DeploymentJob] }>()
const page = defineModel<number>('page', { required: true })
const size = defineModel<number>('size', { required: true })
const columns: TableColumn<DeploymentJob>[] = [
  { accessorKey: 'title', header: '操作', minWidth: 180, fixed: 'left' },
  { accessorKey: 'state', header: '结果', minWidth: 170 },
  { accessorKey: 'createdAt', header: '创建时间', width: 180, defaultHidden: true },
]
const actions = computed<ActionMenuItem<DeploymentJob>[]>(() => [{
  label: '回退', icon: 'morph:undo-2', danger: true,
  visible: job => props.canManage && Boolean(job.rollbackAvailable),
  disabled: job => props.busy || deploymentInProgress(job) || !props.stopped,
  onClick: job => emit('rollback', job),
}])
</script>

<template>
  <section class="space-y-4" aria-label="部署与回退记录">
    <YdListToolbar title="当前实例的部署与回退记录">
      <template #search><p class="text-sm text-muted-foreground">记录包含写入当前实例的覆盖与安装任务，回退从最近一次开始。</p></template>
    </YdListToolbar>
    <YdResult v-if="error && !jobs.length" status="error" title="记录读取失败" :description="error">
      <YdButton variant="outline" @click="emit('retry')">重试</YdButton>
    </YdResult>
    <template v-else>
      <YdAlert v-if="error" variant="destructive" title="记录未能更新" :description="error">
        <YdButton variant="outline" @click="emit('retry')">重试</YdButton>
      </YdAlert>
      <YdLoadingOverlay :loading="loading">
        <YdEmpty v-if="!loading && !jobs.length" title="暂无部署记录" description="完成文件覆盖或资源安装后，可在这里回退。" />
        <YdResponsiveEnhancedTable v-else :columns="columns" :data="jobs" :actions="actions" row-key="id" column-visibility>
          <template #cell-state="{ row }">
            <YdTag :variant="row.original.state === 'recovery-required' || row.original.state === 'failed' ? 'destructive' : 'secondary'">{{ deploymentLabels[row.original.state] }}</YdTag>
            <p v-if="row.original.error" class="text-xs text-muted-foreground">{{ row.original.error }}</p>
          </template>
          <template #cell-createdAt="{ row }">{{ formatDateTime(row.original.createdAt) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
      <p v-if="jobs.some(job => job.rollbackAvailable) && !stopped" class="text-sm text-muted-foreground">停止当前实例后可执行回退。</p>
      <YdPagination v-model:page="page" v-model:size="size" :total="total" :sizes="[10, 20, 50]" />
    </template>
  </section>
</template>
