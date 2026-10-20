<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { ActionMenuItem, StatusColumn, TableColumn, YdTablePageAction } from '@yudream/components'
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import type { ProjectProgressProject } from '../types'
import { YdButton, YdCheckboxGroup, YdInput, YdModal, YdNumberField, YdPagination, YdResponsiveEnhancedTable, YdSelect, YdSwitch, YdTag, YdTextarea, useYdModal } from '@yudream/components'
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PeoplePicker from '../components/PeoplePicker.vue'

const props = defineProps<{ model: ProjectProgressModel }>()
const modalVisible = ref(false)
const router = useRouter()
const confirm = useYdModal()
const pagination = reactive({ page: 1, size: 10 })
const modalTitle = computed(() => props.model.selectedProjectId ? '编辑项目' : '新建项目')
const checkInTypeOptions = [
  { label: '图片', value: 'IMAGE' }, { label: '文件', value: 'FILE' }, { label: '定位', value: 'LOCATION' }, { label: 'MC 在线时长', value: 'MINECRAFT_ONLINE' },
]
const statusOptions = computed(() => props.model.projectStatusOptions.map(item => ({ label: item.label, value: item.code })))
const serverOptions = computed(() => [{ label: '未选择', value: '' }, ...props.model.minecraftServers.map(server => ({ label: `${server.name}${server.currentSeasonName ? ` · ${server.currentSeasonName}` : ''}`, value: server.id }))])

/**
 * 子服选项；默认入口与未装传感器的子服在标签里标出来。
 *
 * 首项是空值的「整服」：YdSelect 没有 clearable，如果把「整服」只写成 placeholder，一旦选了
 * 某台子服就再也回不到整服口径——placeholder 只在无值时显示，点不到。
 */
const minecraftSubServerOptions = computed(() => [
  { label: '整服（不限子服）', value: '' },
  ...(props.model.subServersOf(props.model.projectForm.minecraftPolicy.serverId) || []).map(
    (sub: { name: string, defaultServer: boolean, sensor: boolean }) => ({
      value: sub.name,
      label: sub.name
        + (sub.defaultServer ? '（默认入口）' : '')
        + (sub.sensor ? '' : '（未装传感器）'),
    }),
  ),
])
const pagedRows = computed(() => props.model.projects.slice((pagination.page - 1) * pagination.size, pagination.page * pagination.size))
const columns: TableColumn<ProjectProgressProject>[] = [
  { id: 'project', header: '项目', width: 300 }, { id: 'managers', header: '负责人', width: 220 }, { id: 'members', header: '成员', width: 90 }, { id: 'checkInTypes', header: '打卡方式', width: 240 }, { id: 'server', header: 'MC 服务器', width: 170 }, { accessorKey: 'updatedAt', header: '更新时间', width: 180 },
]

/** 项目状态：语义色胶囊 */
const projectStatusColumn: StatusColumn<ProjectProgressProject> = {
  statusKey: 'enabled',
  header: '状态',
  width: 110,
  statusMap: {
    'true': { label: '启用中', variant: 'default', tone: 'success' },
    'false': { label: '已停用', variant: 'secondary', tone: 'neutral' },
  },
}

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<ProjectProgressProject>[] = [
  { label: '查看', icon: 'morph:eye', onClick: row => void openProject(row) },
  { label: '编辑', icon: 'morph:pencil', onClick: row => void editProject(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row) },
]

const pageActions: YdTablePageAction[] = [
  { label: '新建项目', icon: 'morph:plus', onClick: () => void createProject() },
]

watch(() => props.model.projects.length, total => { pagination.page = Math.min(pagination.page, Math.max(1, Math.ceil(total / pagination.size))) })
watch(() => pagination.size, () => { pagination.page = 1 })
function typeLabel(value: string) { return checkInTypeOptions.find(item => item.value === value)?.label || value }
async function createProject() { props.model.newProject(); await Promise.all([props.model.loadMinecraftServers(), props.model.loadNotificationConnections()]); modalVisible.value = true }
async function editProject(project: ProjectProgressProject) { props.model.selectProject(project); await Promise.all([props.model.loadMinecraftServers(), props.model.loadNotificationConnections()]); modalVisible.value = true }
async function openProject(project: ProjectProgressProject) { await props.model.selectProjectById(project.id); await router.push('/platform/plugins/project-progress/admin/details') }
async function saveProject() { await props.model.saveProject(); modalVisible.value = false }
function confirmDelete(project: ProjectProgressProject) { confirm.confirm({ title: '删除项目', content: `确认删除“${project.name}”吗？相关工作细节与打卡记录可能受到影响。`, onConfirm: () => props.model.deleteProject(project) }) }
</script>

<template>

  <YdTablePageLayout title="项目管理" :show-search="false" :actions="pageActions">
    <template #table>
      <YdLoadingOverlay :loading="model.loading"><YdResponsiveEnhancedTable row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[1240px]" border stripe column-visibility :status-column="projectStatusColumn" :actions="rowActions" :columns="columns" :data="pagedRows" empty-text="暂无项目">
        <template #cell-project="{ row }"><strong>{{ row.original.name }}</strong><div class="pp-table-sub">{{ row.original.description || '暂无描述' }}</div></template>
        <template #cell-managers="{ row }"><div class="pp-chip-list"><YdTag v-for="user in model.userOptionsForIds(row.original.managerUserIds)" :key="user.id" variant="secondary">{{ model.userLabel(user) }}</YdTag></div></template>
        <template #cell-members="{ row }">{{ model.projectMemberCount(row.original) }} 人</template>
        <template #cell-checkInTypes="{ row }"><div class="pp-chip-list"><YdTag v-for="type in row.original.allowedCheckInTypes" :key="type" variant="secondary">{{ typeLabel(type) }}</YdTag></div></template>
        <template #cell-server="{ row }">{{ row.original.minecraftPolicy.enabled ? model.serverLabel(row.original.minecraftPolicy.serverId) : '未启用' }}</template>
        <template #cell-updatedAt="{ row }">{{ model.formatTime(row.original.updatedAt) }}</template>
      </YdResponsiveEnhancedTable></YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination v-model:page="pagination.page" v-model:size="pagination.size" :total="model.projects.length" />
    </template>
  </YdTablePageLayout>

    <YdModal v-model="modalVisible" :title="modalTitle" class="max-w-[860px]" :show-confirm-button="false" :show-cancel-button="false">
      <div class="pp-form">
        <div class="pp-form-grid two"><label><span>项目名称</span><YdInput v-model="model.projectForm.name" class="w-full" placeholder="例如：主城建设一期" /></label><label><span>启用状态</span><YdSwitch v-model="model.projectForm.enabled" /></label></div>
        <label><span>项目描述</span><YdTextarea v-model="model.projectForm.description" class="w-full" /></label>
        <label><span>协同管理员</span><PeoplePicker v-model="model.projectForm.managerUserIds" :model="model" title="选择协同管理员" placeholder="添加协同管理员" /><p class="pp-help">发布人会自动成为项目负责人，这里只需要选择额外协同管理员。</p></label>
        <label><span>项目成员</span><PeoplePicker v-model="model.projectForm.memberUserIds" :model="model" title="选择项目成员" placeholder="添加成员或按部门全选" /></label>
        <label><span>进度状态</span><YdTextarea v-model="model.projectForm.statusesText" class="w-full" placeholder="每行一个状态：编码,显示名称,是否完成,排序" /></label>
        <div class="pp-form-grid three"><label><span>默认状态</span><YdSelect v-model="model.projectForm.defaultStatusCode" :options="statusOptions" class="w-full" /></label><label><span>完成状态</span><YdSelect v-model="model.projectForm.doneStatusCode" :options="statusOptions" class="w-full" /></label><label><span>返工状态</span><YdSelect v-model="model.projectForm.reworkStatusCode" :options="[{ label: '未设置', value: '' }, ...statusOptions]" class="w-full" /></label></div>
        <div class="pp-form-grid two"><label><span>打卡周期（分钟）</span><YdNumberField v-model="model.projectForm.minCheckInIntervalMinutes" :min="0" class="w-full" /><p class="pp-help">1440 表示每天至少打卡一次，0 表示不限制周期。</p></label><label><span>允许打卡方式</span><YdCheckboxGroup v-model="model.projectForm.allowedCheckInTypes" :options="checkInTypeOptions" class="pp-checkbox-grid" /></label></div>
        <div class="pp-form-grid three"><label><span>本项目 MC 打卡</span><YdSwitch v-model="model.projectForm.minecraftPolicy.enabled" /></label><label><span>满足时长自动打卡</span><YdSwitch v-model="model.projectForm.minecraftPolicy.autoCheckInEnabled" /></label><label><span>AFK 计入在线时长</span><YdSwitch v-model="model.projectForm.minecraftPolicy.includeAfk" /></label></div>
        <div class="pp-form-grid two"><label><span>Minecraft 服务器</span><YdSelect :model-value="model.projectForm.minecraftPolicy.serverId" :options="serverOptions" class="w-full" :disabled="!model.projectForm.minecraftPolicy.enabled" @update:model-value="value => model.changeMinecraftServer(String(value || ''))" /></label><label><span>要求在线分钟</span><YdNumberField v-model="model.projectForm.minecraftPolicy.requiredOnlineMinutes" :min="1" class="w-full" :disabled="!model.projectForm.minecraftPolicy.enabled" /></label></div>
        <div v-if="model.projectForm.minecraftPolicy.enabled && model.subServersOf(model.projectForm.minecraftPolicy.serverId).length" class="pp-form-grid two"><label><span>子服</span><YdSelect v-model="model.projectForm.minecraftPolicy.subServer" :options="minecraftSubServerOptions" placeholder="整服（不限子服）" class="w-full" /><p class="pp-help">留空表示整服口径：把该玩家在这台服务器全部子服上的时长相加。选择某一台后只按那台计时长。</p></label></div>
        <div class="pp-form-grid two"><label><span>发布通知连接</span><YdSelect v-model="model.projectForm.notificationConnectionId" :options="model.notificationConnections.map(item => ({ label: `${item.name}（${item.protocol === 'official' ? '官方 QQ' : item.protocol === 'milky' ? 'Milky' : item.platform || '未知平台'}）`, value: item.id }))" class="w-full" clearable /></label><label><span>发布通知群号</span><YdInput v-model="model.projectForm.notificationChannelId" class="w-full" placeholder="Milky 群号或官方群 openid" /></label></div>
      </div>
      <template #footer><YdButton variant="outline" @click="modalVisible = false">取消</YdButton><YdButton :loading="model.saving" @click="saveProject">保存项目</YdButton></template>
    </YdModal>
</template>
