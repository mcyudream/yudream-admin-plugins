<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectWorkDetail } from '../types'
import { computed } from 'vue'
import { FaButton, FaTable, FaTag } from '@yudream/components'
import ProgressPanel from '../components/ProgressPanel.vue'

defineProps<{
  model: ProjectProgressModel
}>()

const detailColumns = computed<TableColumn<ProjectWorkDetail>[]>(() => [
  { accessorKey: 'title', header: '任务', width: 220, fixed: 'left' },
  { id: 'status', header: '状态', width: 110, align: 'center' },
  { id: 'assignees', header: '负责人', width: 220 },
  { id: 'acceptors', header: '验收人', width: 220 },
])
</script>

<template>
  <section class="pp-page">
    <section class="pp-toolbar">
      <div>
        <span>实时进度</span>
        <h2>项目进度看板</h2>
      </div>
      <FaButton variant="outline" @click="model.load">刷新</FaButton>
    </section>

    <section class="pp-metrics">
      <article>
        <span>项目数</span>
        <strong>{{ model.projects.length }}</strong>
      </article>
      <article>
        <span>当前项目进度</span>
        <strong>{{ model.completion }}%</strong>
      </article>
      <article>
        <span>工作细节</span>
        <strong>{{ model.details.length }}</strong>
      </article>
      <article>
        <span>Minecraft</span>
        <strong>{{ model.status?.minecraftReady ? '已联动' : '未启用' }}</strong>
      </article>
    </section>

    <section class="pp-grid">
      <ProgressPanel title="项目列表" subtle="选择一个项目查看细节">
        <div class="pp-list">
          <button
            v-for="project in model.projects"
            :key="project.id"
            type="button"
            :class="{ active: project.id === model.selectedProjectId }"
            @click="model.selectProject(project); model.reloadProjectData()"
          >
            <strong>{{ project.name }}</strong>
            <span>{{ project.enabled ? '启用' : '停用' }} · {{ model.projectMemberCount(project) }} 名成员</span>
          </button>
          <div v-if="!model.projects.length" class="pp-empty">暂无项目</div>
        </div>
      </ProgressPanel>

      <ProgressPanel title="细节进度" :subtle="model.selectedProject?.name || '未选择项目'">
        <div class="pp-progress-line">
          <span :style="{ width: `${model.completion}%` }" />
        </div>
        <FaTable
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[760px]"
          border
          stripe
          column-visibility
          :columns="detailColumns"
          :data="model.details"
          empty-text="暂无工作细节"
        >
          <template #cell-status="{ row }">
            <FaTag variant="secondary">{{ model.statusLabel(row.original.statusCode) }}</FaTag>
          </template>
          <template #cell-assignees="{ row }">
            <div class="pp-chip-list">
              <span v-if="!row.original.assigneeUserIds.length" class="pp-muted">未分配</span>
              <span v-for="user in model.userOptionsForIds(row.original.assigneeUserIds)" :key="user.id" class="pp-chip">
                {{ model.userLabel(user) }}
              </span>
            </div>
          </template>
          <template #cell-acceptors="{ row }">
            <div class="pp-chip-list">
              <span v-if="!row.original.acceptorUserIds.length" class="pp-muted">项目负责人</span>
              <span v-for="user in model.userOptionsForIds(row.original.acceptorUserIds)" :key="user.id" class="pp-chip">
                {{ model.userLabel(user) }}
              </span>
            </div>
          </template>
        </FaTable>
      </ProgressPanel>
    </section>

    <ProgressPanel title="实时动态" subtle="最近 20 条项目事件">
      <div class="pp-feed">
        <article v-for="event in model.recentEvents" :key="event.id">
          <strong>{{ event.message }}</strong>
          <span>{{ event.type }} · {{ model.formatTime(event.createdAt) }}</span>
        </article>
        <div v-if="!model.recentEvents.length" class="pp-empty">暂无动态</div>
      </div>
    </ProgressPanel>
  </section>
</template>
