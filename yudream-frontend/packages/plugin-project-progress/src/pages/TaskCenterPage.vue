<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectWorkDetail } from '../types'
import { computed, reactive, watch } from 'vue'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable } from '@yudream/components'

const props = defineProps<{ model: ProjectProgressModel }>()
const pagination = reactive({ page: 1, size: 10 })
const rows = computed(() => props.model.claimableTasks.slice((pagination.page - 1) * pagination.size, pagination.page * pagination.size))
const columns: TableColumn<ProjectWorkDetail>[] = [
  { id: 'project', header: '项目', width: 180, fixed: 'left' },
  { id: 'task', header: '任务', width: 360 },
  { id: 'quota', header: '名额', width: 110 },
  { id: 'dueAt', header: '截止时间', width: 170 },
  { id: 'operation', header: '操作', width: 120, align: 'center', fixed: 'right' },
]
watch(() => props.model.claimableTasks.length, total => { pagination.page = Math.min(pagination.page, Math.max(1, Math.ceil(total / pagination.size))) })
watch(() => pagination.size, () => { pagination.page = 1 })
</script>

<template>
  <FaPageHeader title="任务认领" class="mb-0">
    <FaButton variant="outline" :loading="model.loading" @click="model.loadTaskCenter"><FaIcon name="i-ri:refresh-line" />刷新</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div class="grid gap-4">
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div class="rounded-lg border p-4"><span class="text-sm text-muted-foreground">可认领任务</span><strong class="mt-2 block text-2xl">{{ model.claimableTasks.length }}</strong></div>
        <div class="rounded-lg border p-4"><span class="text-sm text-muted-foreground">Minecraft 联动</span><strong class="mt-2 block">{{ model.status?.minecraftReady ? '已启用' : '未启用' }}</strong></div>
        <div class="rounded-lg border p-4"><span class="text-sm text-muted-foreground">邮件通知</span><strong class="mt-2 block">{{ model.status?.mailReady ? '可用' : '未配置' }}</strong></div>
      </div>
      <FaResponsiveTable row-key="id" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[940px]" border stripe column-visibility :columns="columns" :data="rows" empty-text="暂无可认领任务">
        <template #cell-project="{ row }">{{ model.projectName(row.original.projectId) }}</template>
        <template #cell-task="{ row }"><div class="grid gap-1"><strong>{{ row.original.title }}</strong><span class="text-sm text-muted-foreground">{{ row.original.description || '暂无说明' }}</span></div></template>
        <template #cell-quota="{ row }">{{ row.original.assigneeUserIds.length }} / {{ row.original.requiredAssigneeCount }}</template>
        <template #cell-dueAt="{ row }">{{ model.formatTime(row.original.dueAt) }}</template>
        <template #cell-operation="{ row }"><FaButton size="sm" :loading="model.saving" @click="model.claim(row.original)">认领</FaButton></template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="text-base font-semibold">{{ row.title }}</span>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">项目</span><span class="break-all">{{ model.projectName(row.projectId) }}</span></div>
                <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">名额</span><span class="break-all">{{ row.assigneeUserIds.length }} / {{ row.requiredAssigneeCount }}</span></div>
                <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">截止时间</span><span class="break-all">{{ model.formatTime(row.dueAt) }}</span></div>
                <div v-if="row.description" class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">说明</span><span class="break-all">{{ row.description }}</span></div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" :loading="model.saving" @click="model.claim(row)">认领</FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
      <FaPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="model.claimableTasks.length" class="mt-3" />
    </div>
  </FaPageMain>
</template>
