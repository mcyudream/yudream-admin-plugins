<script setup lang="ts">
import { YdButton, YdIcon, YdPagination, YdProgress, YdTag } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import type { UploadTaskView } from '../composables/useInstanceUploads.ts'
import { formatBytes } from '../composables/utils.ts'

/**
 * 实例上传任务面板（异步分片上传）：running 显示实时进度条（可取消），
 * 近期终态短暂保留提示；文件管理页与实例详情页共用——重进页面后
 * running 任务从后端恢复，进度继续更新。
 */
const props = withDefaults(defineProps<{
  tasks: UploadTaskView[]
  hashing?: (taskId: string) => boolean
  canCancel?: boolean
  busy?: boolean
}>(), {
  hashing: () => false,
  canCancel: true,
  busy: false,
})

const emit = defineEmits<{ cancel: [taskId: string] }>()

const page = ref(1)
const rank = (task: UploadTaskView) => ['running', 'queued'].includes(task.state) ? 0 : task.state === 'failed' ? 1 : 2
const visible = computed(() => [...props.tasks].sort((a, b) => rank(a) - rank(b)).slice((page.value - 1) * 8, page.value * 8))
watch(() => props.tasks.length, length => { page.value = Math.min(page.value, Math.max(1, Math.ceil(length / 8))) })

function percent(task: UploadTaskView): number {
  if (task.size <= 0) {
    return 0
  }
  return Math.min(100, Math.max(0, Math.round((task.uploaded / task.size) * 100)))
}

function sizeText(task: UploadTaskView): string {
  return `${formatBytes(task.uploaded)} / ${formatBytes(task.size)}`
}

function stateTag(task: UploadTaskView): { text: string, variant: 'default' | 'secondary' | 'outline' | 'destructive' } {
  if (props.hashing(task.taskId)) {
    return { text: '校验中', variant: 'secondary' }
  }
  switch (task.state) {
    case 'queued':
      return { text: '等待上传', variant: 'outline' }
    case 'running':
      return { text: `${percent(task)}%`, variant: 'default' }
    case 'done':
      return { text: '已完成', variant: 'secondary' }
    case 'canceled':
      return { text: '已取消', variant: 'outline' }
    default:
      return { text: '失败', variant: 'destructive' }
  }
}
</script>

<template>
  <div class="rounded-xl border bg-card p-4">
    <div class="space-y-3">
      <div class="flex flex-wrap items-center gap-2 text-sm">
        <YdIcon name="i-ri:upload-cloud-2-line" class="text-primary" />
        <span class="font-medium">上传任务 · {{ tasks.filter(task => task.state === 'done').length }} / {{ tasks.length }} 已完成</span>
        <span v-if="tasks.some(task => task.state === 'failed')" class="text-xs text-destructive">{{ tasks.filter(task => task.state === 'failed').length }} 个失败</span>
        <span class="text-xs text-muted-foreground">保留目录结构 · 同时上传 2 个文件</span>
      </div>
      <div v-for="task in visible" :key="task.taskId" class="space-y-1.5">
        <div class="flex flex-wrap items-center gap-2 text-sm">
          <span class="min-w-0 truncate font-medium">{{ task.name }}</span>
          <YdTag :variant="stateTag(task).variant">{{ stateTag(task).text }}</YdTag>
          <span class="text-xs text-muted-foreground">{{ sizeText(task) }}</span>
          <span class="min-w-0 truncate text-xs text-muted-foreground">{{ task.path }}</span>
          <span class="grow" />
          <YdButton
            v-if="canCancel && (task.state === 'running' || task.state === 'queued')"
            size="sm"
            variant="outline"
            :loading="busy"
            @click="emit('cancel', task.taskId)"
          >
            取消
          </YdButton>
        </div>
        <YdProgress v-if="task.state === 'running'" :model-value="percent(task)" :aria-label="`${task.name} 上传进度`" />
        <p v-if="task.state === 'failed'" class="text-xs text-destructive">{{ task.error || '上传失败' }}</p>
      </div>
      <YdPagination v-if="tasks.length > 8" v-model:page="page" :total="tasks.length" :size="8" layout="total, ->, pager" />
    </div>
  </div>
</template>
