<script setup lang="ts">
import { FaButton, FaIcon, FaTag } from '@yudream/components'
import { computed } from 'vue'
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

const visible = computed(() => props.tasks.slice(0, 6))

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
        <FaIcon name="i-ri:upload-cloud-2-line" class="text-primary" />
        <span class="font-medium">上传任务</span>
        <span class="text-xs text-muted-foreground">分片直传面板中转节点，异步执行不阻塞页面</span>
      </div>
      <div v-for="task in visible" :key="task.taskId" class="space-y-1.5">
        <div class="flex flex-wrap items-center gap-2 text-sm">
          <span class="min-w-0 truncate font-medium">{{ task.name }}</span>
          <FaTag :variant="stateTag(task).variant">{{ stateTag(task).text }}</FaTag>
          <span class="text-xs text-muted-foreground">{{ sizeText(task) }}</span>
          <span class="min-w-0 truncate text-xs text-muted-foreground">{{ task.path }}</span>
          <span class="grow" />
          <FaButton
            v-if="canCancel && task.state === 'running' && !hashing(task.taskId)"
            size="sm"
            variant="outline"
            :loading="busy"
            @click="emit('cancel', task.taskId)"
          >
            取消
          </FaButton>
        </div>
        <div
          v-if="task.state === 'running'"
          class="h-2 w-full overflow-hidden rounded-full bg-muted"
        >
          <div
            class="h-full rounded-full bg-primary transition-all"
            :style="{ width: `${percent(task)}%` }"
          />
        </div>
        <p v-if="task.state === 'failed'" class="text-xs text-destructive">{{ task.error || '上传失败' }}</p>
      </div>
    </div>
  </div>
</template>
