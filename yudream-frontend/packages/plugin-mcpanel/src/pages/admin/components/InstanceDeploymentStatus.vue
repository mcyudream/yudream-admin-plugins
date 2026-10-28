<script setup lang="ts">
import type { DeploymentJob } from '../../../api/deployment-api'
import { YdAlert, YdButton, YdProgress, YdResult } from '@yudream/components'
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { deploymentInProgress, deploymentLabels } from '../../../composables/useInstanceDeployments'

const props = defineProps<{ id: string, instance: Record<string, unknown> | null, stopped: boolean, canManage: boolean, error: string, job: DeploymentJob | null }>()
defineEmits<{ retry: [] }>()
const router = useRouter()
const progress = computed(() => !props.job?.fileCount ? 0 : Math.min(100, Math.round(((props.job.stagedFiles ?? 0) + (props.job.completedFiles ?? 0)) / (2 * props.job.fileCount) * 100)))
</script>

<template>
  <YdResult v-if="!instance && error" status="error" title="无法读取实例" :description="error">
    <YdButton variant="outline" @click="$emit('retry')">重试</YdButton>
  </YdResult>
  <YdAlert v-else-if="error" variant="destructive" title="操作未完成" :description="error" />
  <YdAlert v-if="instance && !canManage" title="当前为只读访问" description="执行复制、覆盖、资源安装和回退需要实例管理权限。" />
  <YdAlert v-if="instance && !stopped" title="请先停止实例" description="复制、覆盖、安装与回退都需要停止涉及的实例，避免写入中的世界和配置不一致。" />
  <YdAlert v-if="instance?.maintenanceJobId" title="实例正在部署或等待恢复" description="电源与文件写入已暂停，请等待完成，或在下方记录中执行回退恢复。" />
  <section v-if="job" class="space-y-3" aria-label="当前部署任务">
    <h2 class="text-base font-semibold">{{ job.title }} · {{ deploymentLabels[job.state] }}</h2>
    <YdProgress v-if="deploymentInProgress(job)" :model-value="progress" aria-label="部署文件进度" />
    <p class="text-sm text-muted-foreground">已暂存 {{ job.stagedFiles ?? 0 }} / {{ job.fileCount ?? 0 }} · 已写入 {{ job.completedFiles ?? 0 }}</p>
    <YdAlert v-if="job.error" :title="job.state === 'rolled-back' ? '执行失败，原文件已恢复' : '任务提示'" :description="job.error" />
    <YdButton v-if="job.targetId !== id" variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/instances/${encodeURIComponent(job.targetId)}/deployments`)">查看目标实例与回退记录</YdButton>
  </section>
</template>
