<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectCheckIn } from '../types'
import { FaButton, FaFileUpload, FaInput, FaPagination, FaRadioGroup, FaSelect, FaTable, FaTag, FaTextarea } from '@yudream/components'
import { computed, reactive, watch } from 'vue'

const props = defineProps<{ model: ProjectProgressModel }>()
const pagination = reactive({ page: 1, size: 10 })
const projectOptions = computed(() => props.model.projects.map(project => ({ label: project.name, value: project.id })))
const manualTypeOptions = computed(() => {
  const allowed = props.model.selectedProject?.allowedCheckInTypes || []
  return [
    { label: '图片', value: 'IMAGE' },
    { label: '文件', value: 'FILE' },
    { label: '定位', value: 'LOCATION' },
  ].filter(item => allowed.includes(item.value))
})
const pagedRows = computed(() => props.model.checkIns.slice((pagination.page - 1) * pagination.size, pagination.page * pagination.size))
const columns: TableColumn<ProjectCheckIn>[] = [
  { id: 'user', header: '打卡人', width: 170, fixed: 'left' },
  { id: 'type', header: '类型', width: 130 },
  { id: 'summary', header: '说明', width: 420 },
  { id: 'createdAt', header: '时间', width: 180 },
]

watch(manualTypeOptions, (options) => {
  if (options.length && !options.some(item => item.value === props.model.checkInForm.type)) props.model.checkInForm.type = options[0].value
}, { immediate: true })
watch(() => props.model.checkIns.length, total => { pagination.page = Math.min(pagination.page, Math.max(1, Math.ceil(total / pagination.size))) })
watch(() => pagination.size, () => { pagination.page = 1 })

async function selectProject(projectId: unknown) {
  pagination.page = 1
  await props.model.selectProjectById(String(projectId || ''))
}

function typeLabel(value: string) {
  return { IMAGE: '图片', FILE: '文件', LOCATION: '定位', MINECRAFT_ONLINE: 'MC 在线时长' }[value] || value
}

function fileNames(files: ProjectCheckIn['files']) { return files.map(file => file.filename).join('、') }
function localUploadRequest() { return Promise.resolve({}) }
</script>

<template>
  <section class="pp-page">
    <section class="pp-toolbar">
      <div><span>进度打卡</span><h2>项目打卡</h2></div>
      <div class="pp-actions">
        <FaSelect :model-value="model.selectedProjectId" :options="projectOptions" placeholder="选择项目" class="pp-project-select" @update:model-value="selectProject" />
        <FaButton variant="outline" :loading="model.loading" @click="model.load">刷新</FaButton>
        <FaButton variant="outline" :disabled="!model.checkIns.length" @click="model.exportCheckIns">导出</FaButton>
      </div>
    </section>

    <section class="pp-grid">
      <section class="pp-panel">
        <header class="pp-panel-head"><div><h3>提交打卡</h3><span>{{ model.selectedProject?.name || '请选择项目' }}</span></div></header>
        <div class="pp-form">
          <label><span>打卡类型</span><FaRadioGroup v-model="model.checkInForm.type" :options="manualTypeOptions" :disabled="!model.selectedProjectId" class="pp-radio-grid" /></label>
          <div v-if="model.selectedProjectId && !manualTypeOptions.length" class="pp-empty">该项目未开放手动打卡</div>
          <label><span>说明</span><FaTextarea v-model="model.checkInForm.summary" class="w-full" /></label>
          <label v-if="model.checkInForm.type !== 'LOCATION'"><span>上传证明</span><FaFileUpload v-model="model.evidenceFiles" :max="1" :http-request="localUploadRequest" description="拖放或点击选择打卡证明" /></label>
          <template v-if="model.checkInForm.type === 'LOCATION'">
            <label><span>地址</span><FaInput v-model="model.checkInForm.address" class="w-full" placeholder="定位后可补充地点说明" /></label>
            <div class="pp-actions"><FaButton variant="outline" :loading="model.saving" @click="model.useCurrentLocation">获取当前位置</FaButton><span class="pp-muted">{{ model.checkInForm.latitude && model.checkInForm.longitude ? `${model.checkInForm.latitude}, ${model.checkInForm.longitude}` : '尚未获取定位' }}</span></div>
          </template>
          <div class="pp-actions"><FaButton :loading="model.saving" :disabled="!model.selectedProjectId || !manualTypeOptions.length" @click="model.submitCheckIn">提交打卡</FaButton><FaButton variant="outline" :disabled="!model.canProjectMinecraftCheckIn() || model.saving" @click="model.minecraftCheckIn()">MC 在线时长打卡</FaButton></div>
        </div>
      </section>

      <section class="pp-panel">
        <header class="pp-panel-head"><div><h3>项目打卡记录</h3><span>{{ model.checkIns.length }} 条记录</span></div></header>
        <FaTable row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[900px]" border stripe column-visibility :columns="columns" :data="pagedRows" empty-text="暂无打卡记录">
          <template #cell-user="{ row }">{{ model.userLabel(model.usersById[row.original.userId]) }}</template>
          <template #cell-type="{ row }"><FaTag variant="secondary">{{ typeLabel(row.original.type) }}</FaTag></template>
          <template #cell-summary="{ row }"><strong>{{ row.original.summary || '无说明' }}</strong><div v-if="row.original.location" class="pp-table-sub">{{ row.original.location.address }}</div><div v-if="row.original.minecraft" class="pp-table-sub">{{ model.serverLabel(row.original.minecraft.serverId) }} · 有效在线 {{ model.minutes(row.original.minecraft.effectiveOnlineMillis) }}</div><div v-if="row.original.files.length" class="pp-table-sub">{{ fileNames(row.original.files) }}</div></template>
          <template #cell-createdAt="{ row }">{{ model.formatTime(row.original.createdAt) }}</template>
        </FaTable>
        <FaPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="model.checkIns.length" class="mt-3" />
      </section>
    </section>
  </section>
</template>
