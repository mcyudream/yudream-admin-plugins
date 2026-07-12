<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectProgressProject } from '../types'
import { FaButton, FaCheckboxGroup, FaInput, FaModal, FaNumberField, FaPagination, FaSelect, FaSwitch, FaTable, FaTag, FaTextarea, useFaModal } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PeoplePicker from '../components/PeoplePicker.vue'

const props = defineProps<{ model: ProjectProgressModel }>()
const modalVisible = ref(false)
const router = useRouter()
const confirm = useFaModal()
const pagination = reactive({ page: 1, size: 10 })
const modalTitle = computed(() => props.model.selectedProjectId ? '编辑项目' : '新建项目')
const checkInTypeOptions = [
  { label: '图片', value: 'IMAGE' }, { label: '文件', value: 'FILE' }, { label: '定位', value: 'LOCATION' }, { label: 'MC 在线时长', value: 'MINECRAFT_ONLINE' },
]
const statusOptions = computed(() => props.model.projectStatusOptions.map(item => ({ label: item.label, value: item.code })))
const serverOptions = computed(() => [{ label: '未选择', value: '' }, ...props.model.minecraftServers.map(server => ({ label: `${server.name}${server.currentSeasonName ? ` · ${server.currentSeasonName}` : ''}`, value: server.id }))])
const pagedRows = computed(() => props.model.projects.slice((pagination.page - 1) * pagination.size, pagination.page * pagination.size))
const columns: TableColumn<ProjectProgressProject>[] = [
  { id: 'project', header: '项目', width: 300, fixed: 'left' }, { id: 'status', header: '状态', width: 110 }, { id: 'managers', header: '负责人', width: 220 }, { id: 'members', header: '成员', width: 90 }, { id: 'checkInTypes', header: '打卡方式', width: 240 }, { id: 'server', header: 'MC 服务器', width: 170 }, { id: 'updatedAt', header: '更新时间', width: 180 }, { id: 'operation', header: '操作', width: 220, align: 'center', fixed: 'right' },
]

watch(() => props.model.projects.length, total => { pagination.page = Math.min(pagination.page, Math.max(1, Math.ceil(total / pagination.size))) })
watch(() => pagination.size, () => { pagination.page = 1 })
function typeLabel(value: string) { return checkInTypeOptions.find(item => item.value === value)?.label || value }
async function createProject() { props.model.newProject(); await props.model.loadMinecraftServers(); modalVisible.value = true }
async function editProject(project: ProjectProgressProject) { props.model.selectProject(project); await props.model.loadMinecraftServers(); modalVisible.value = true }
async function openProject(project: ProjectProgressProject) { await props.model.selectProjectById(project.id); await router.push('/platform/plugins/project-progress/admin/details') }
async function saveProject() { await props.model.saveProject(); modalVisible.value = false }
function confirmDelete(project: ProjectProgressProject) { confirm.confirm({ title: '删除项目', content: `确认删除“${project.name}”吗？相关工作细节与打卡记录可能受到影响。`, onConfirm: () => props.model.deleteProject(project) }) }
</script>

<template>
  <section class="pp-page">
    <section class="pp-toolbar"><div><span>项目配置</span><h2>项目管理</h2></div><div class="pp-actions"><FaButton variant="outline" :loading="model.loading" @click="model.load">刷新</FaButton><FaButton @click="createProject">新建项目</FaButton></div></section>
    <section class="pp-panel">
      <FaTable v-loading="model.loading" row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[1530px]" border stripe column-visibility :columns="columns" :data="pagedRows" empty-text="暂无项目">
        <template #cell-project="{ row }"><strong>{{ row.original.name }}</strong><div class="pp-table-sub">{{ row.original.description || '暂无描述' }}</div></template>
        <template #cell-status="{ row }"><FaTag :variant="row.original.enabled ? 'default' : 'secondary'">{{ row.original.enabled ? '启用中' : '已停用' }}</FaTag></template>
        <template #cell-managers="{ row }"><div class="pp-chip-list"><FaTag v-for="user in model.userOptionsForIds(row.original.managerUserIds)" :key="user.id" variant="secondary">{{ model.userLabel(user) }}</FaTag></div></template>
        <template #cell-members="{ row }">{{ model.projectMemberCount(row.original) }} 人</template>
        <template #cell-checkInTypes="{ row }"><div class="pp-chip-list"><FaTag v-for="type in row.original.allowedCheckInTypes" :key="type" variant="secondary">{{ typeLabel(type) }}</FaTag></div></template>
        <template #cell-server="{ row }">{{ row.original.minecraftPolicy.enabled ? model.serverLabel(row.original.minecraftPolicy.serverId) : '未启用' }}</template>
        <template #cell-updatedAt="{ row }">{{ model.formatTime(row.original.updatedAt) }}</template>
        <template #cell-operation="{ row }"><div class="pp-actions"><FaButton size="sm" variant="outline" @click="openProject(row.original)">查看</FaButton><FaButton size="sm" variant="outline" @click="editProject(row.original)">编辑</FaButton><FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">删除</FaButton></div></template>
      </FaTable>
      <FaPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="model.projects.length" class="mt-3" />
    </section>

    <FaModal v-model="modalVisible" :title="modalTitle" class="max-w-[860px]" :show-confirm-button="false" :show-cancel-button="false">
      <div class="pp-form">
        <div class="pp-form-grid two"><label><span>项目名称</span><FaInput v-model="model.projectForm.name" class="w-full" placeholder="例如：主城建设一期" /></label><label><span>启用状态</span><FaSwitch v-model="model.projectForm.enabled" /></label></div>
        <label><span>项目描述</span><FaTextarea v-model="model.projectForm.description" class="w-full" /></label>
        <label><span>协同管理员</span><PeoplePicker v-model="model.projectForm.managerUserIds" :model="model" title="选择协同管理员" placeholder="添加协同管理员" /><p class="pp-help">发布人会自动成为项目负责人，这里只需要选择额外协同管理员。</p></label>
        <label><span>项目成员</span><PeoplePicker v-model="model.projectForm.memberUserIds" :model="model" title="选择项目成员" placeholder="添加成员或按部门全选" /></label>
        <label><span>进度状态</span><FaTextarea v-model="model.projectForm.statusesText" class="w-full" placeholder="每行一个状态：编码,显示名称,是否完成,排序" /></label>
        <div class="pp-form-grid three"><label><span>默认状态</span><FaSelect v-model="model.projectForm.defaultStatusCode" :options="statusOptions" class="w-full" /></label><label><span>完成状态</span><FaSelect v-model="model.projectForm.doneStatusCode" :options="statusOptions" class="w-full" /></label><label><span>返工状态</span><FaSelect v-model="model.projectForm.reworkStatusCode" :options="[{ label: '未设置', value: '' }, ...statusOptions]" class="w-full" /></label></div>
        <div class="pp-form-grid two"><label><span>打卡周期（分钟）</span><FaNumberField v-model="model.projectForm.minCheckInIntervalMinutes" :min="0" class="w-full" /><p class="pp-help">1440 表示每天至少打卡一次，0 表示不限制周期。</p></label><label><span>允许打卡方式</span><FaCheckboxGroup v-model="model.projectForm.allowedCheckInTypes" :options="checkInTypeOptions" class="pp-checkbox-grid" /></label></div>
        <div class="pp-form-grid three"><label><span>本项目 MC 打卡</span><FaSwitch v-model="model.projectForm.minecraftPolicy.enabled" /></label><label><span>满足时长自动打卡</span><FaSwitch v-model="model.projectForm.minecraftPolicy.autoCheckInEnabled" /></label><label><span>AFK 计入在线时长</span><FaSwitch v-model="model.projectForm.minecraftPolicy.includeAfk" /></label></div>
        <div class="pp-form-grid two"><label><span>Minecraft 服务器</span><FaSelect v-model="model.projectForm.minecraftPolicy.serverId" :options="serverOptions" class="w-full" :disabled="!model.projectForm.minecraftPolicy.enabled" /></label><label><span>要求在线分钟</span><FaNumberField v-model="model.projectForm.minecraftPolicy.requiredOnlineMinutes" :min="1" class="w-full" :disabled="!model.projectForm.minecraftPolicy.enabled" /></label></div>
        <div class="pp-form-grid two"><label><span>发布通知连接</span><FaSelect v-model="model.projectForm.notificationConnectionId" :options="model.notificationConnections.map(item => ({ label: `${item.name} (${item.platform})`, value: Number(item.id) }))" class="w-full" clearable /></label><label><span>发布通知群号</span><FaInput v-model="model.projectForm.notificationChannelId" class="w-full" placeholder="Milky 群号" /></label></div>
      </div>
      <template #footer><FaButton variant="outline" @click="modalVisible = false">取消</FaButton><FaButton :loading="model.saving" @click="saveProject">保存项目</FaButton></template>
    </FaModal>
  </section>
</template>
