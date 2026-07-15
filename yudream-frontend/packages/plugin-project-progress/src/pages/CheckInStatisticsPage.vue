<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import { FaButton, FaPagination, FaSelect, FaTable } from '@yudream/components'
import { computed, reactive, watch } from 'vue'

interface MemberCheckInRow { userId: string, total: number, image: number, file: number, location: number, minecraft: number, lastAt: number }

const props = defineProps<{ model: ProjectProgressModel }>()
const pagination = reactive({ page: 1, size: 10 })
const projectOptions = computed(() => props.model.projects.map(project => ({ label: project.name, value: project.id })))
const typeLabels: Record<string, string> = { IMAGE: '图片', FILE: '文件', LOCATION: '定位', MINECRAFT_ONLINE: 'MC 在线时长' }
const participantCount = computed(() => new Set(props.model.checkIns.map(item => item.userId)).size)
const typeCounts = computed(() => {
  const counts: Record<string, number> = {}
  props.model.checkIns.forEach(item => { counts[item.type] = (counts[item.type] || 0) + 1 })
  return Object.entries(counts).map(([type, count]) => ({ type, label: typeLabels[type] || type, count }))
})
const memberRows = computed<MemberCheckInRow[]>(() => Object.values(props.model.checkIns.reduce<Record<string, MemberCheckInRow>>((rows, item) => {
  const row = rows[item.userId] || (rows[item.userId] = { userId: item.userId, total: 0, image: 0, file: 0, location: 0, minecraft: 0, lastAt: 0 })
  row.total += 1
  row.lastAt = Math.max(row.lastAt, item.createdAt)
  if (item.type === 'IMAGE') row.image += 1
  if (item.type === 'FILE') row.file += 1
  if (item.type === 'LOCATION') row.location += 1
  if (item.type === 'MINECRAFT_ONLINE') row.minecraft += 1
  return rows
}, {})).sort((left, right) => right.total - left.total || right.lastAt - left.lastAt))
const pagedRows = computed(() => memberRows.value.slice((pagination.page - 1) * pagination.size, pagination.page * pagination.size))
const columns: TableColumn<MemberCheckInRow>[] = [
  { id: 'member', header: '成员', width: 220, fixed: 'left' },
  { accessorKey: 'total', header: '总计', width: 100, align: 'center' },
  { accessorKey: 'image', header: '图片', width: 100, align: 'center' },
  { accessorKey: 'file', header: '文件', width: 100, align: 'center' },
  { accessorKey: 'location', header: '定位', width: 100, align: 'center' },
  { accessorKey: 'minecraft', header: 'MC 在线', width: 110, align: 'center' },
  { id: 'lastAt', header: '最近打卡', width: 180 },
]

watch(() => memberRows.value.length, total => { pagination.page = Math.min(pagination.page, Math.max(1, Math.ceil(total / pagination.size))) })
watch(() => pagination.size, () => { pagination.page = 1 })

async function selectProject(projectId: unknown) {
  pagination.page = 1
  await props.model.loadCheckInStatistics(String(projectId || ''))
}
</script>

<template>
  <section class="pp-page">
    <section class="pp-toolbar">
      <div><span>项目打卡</span><h2>打卡统计</h2></div>
      <div class="pp-actions"><FaSelect :model-value="model.selectedProjectId" :options="projectOptions" placeholder="选择项目" class="pp-project-select" @update:model-value="selectProject" /><FaButton variant="outline" :loading="model.loading" @click="model.loadCheckInStatistics()">刷新</FaButton><FaButton variant="outline" :disabled="!model.checkIns.length" @click="model.exportCheckIns">导出</FaButton></div>
    </section>
    <section class="pp-panel"><div class="pp-stat-grid"><article class="pp-stat-card"><span>打卡总数</span><strong>{{ model.checkIns.length }}</strong></article><article class="pp-stat-card"><span>参与成员</span><strong>{{ participantCount }}</strong></article><article v-for="item in typeCounts" :key="item.type" class="pp-stat-card"><span>{{ item.label }}</span><strong>{{ item.count }}</strong></article></div></section>
    <section class="pp-panel">
      <header class="pp-panel-head"><div><h3>成员打卡明细</h3><span>按打卡次数与最近打卡时间排序</span></div></header>
      <FaTable v-loading="model.loading" row-key="userId" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[910px]" border stripe column-visibility :columns="columns" :data="pagedRows" empty-text="暂无打卡记录">
        <template #cell-member="{ row }">{{ model.userLabel(model.usersById[row.original.userId]) }}</template>
        <template #cell-lastAt="{ row }">{{ model.formatTime(row.original.lastAt) }}</template>
      </FaTable>
      <FaPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="memberRows.length" class="mt-3" />
    </section>
  </section>
</template>
