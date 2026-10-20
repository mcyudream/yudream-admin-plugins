<script setup lang="ts">
import { YdLoadingOverlay, type ActionMenuItem, YdTablePageLayout } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { YdAlert, YdButton, YdIcon, YdInput, YdLabel, YdModal, YdNumberField, YdPagination, YdSelect, YdSwitch, useYdModal, useYdToast, type TableColumn, YdResponsiveEnhancedTable } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createWebCardApi } from '../api/web-card-api'
import type { GroupBinding, Option, Site } from '../types'
import { errorText, uid } from '../ui'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createWebCardApi(props.sdk)
const toast = useYdToast()
const modal = useYdModal()
const rows = ref<GroupBinding[]>([])
const sites = ref<Site[]>([])
const connections = ref<Option[]>([])
const groupOptions = ref<Option[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const open = ref(false)
const saving = ref(false)
const toggling = ref('')
const extraGroupId = ref('')
const form = ref<GroupBinding>(empty())
const columns: TableColumn<GroupBinding>[] = [{ accessorKey: 'siteId', header: '站点', minWidth: 180 }, { accessorKey: 'connectionId', header: '连接', minWidth: 150 }, { accessorKey: 'channelId', header: '目标群', minWidth: 180 }, { id: 'enabled', header: '状态', width: 110 }, { accessorKey: 'cooldownSeconds', header: '冷却/秒', width: 100 }]

function protocolLabel(item: Option) {
  if (item.protocol === 'official') return '官方 QQ'
  if (item.protocol === 'milky') return 'Milky'
  return item.platform || '未知平台'
}
const connectionSelectOptions = computed(() => connections.value.map(value => ({ label: `${value.name}（${protocolLabel(value)}）`, value: value.id })))
const groupSelectOptions = computed(() => {
  const known = groupOptions.value.map(value => ({ label: value.name || value.id, value: value.id }))
  const knownIds = new Set(known.map(item => item.value))
  if (form.value.channelId && !knownIds.has(form.value.channelId)) {
    known.push({ label: form.value.channelId, value: form.value.channelId })
  }
  return known
})

function empty(): GroupBinding { return { id: uid(), siteId: '', connectionId: '', platform: 'milky', selfId: '', channelId: '', enabled: true, quietStart: '', quietEnd: '', cooldownSeconds: 0, hourlyLimit: 0, lastDeliveryAt: 0, createdAt: 0, updatedAt: 0 } }
async function load() { loading.value = true; error.value = ''; try { const [result, sitePage, connectionOptions] = await Promise.all([api.bindings(page.value, size.value), api.sites(1, 200), api.connections()]); rows.value = result.records; total.value = result.total; sites.value = sitePage.records; connections.value = connectionOptions } catch (cause) { error.value = errorText(cause, '加载定时推送目标失败') } finally { loading.value = false } }
async function changeConnection(connectionId: unknown) { groupOptions.value = connectionId ? await api.groups(String(connectionId)) : []; form.value.channelId = ''; extraGroupId.value = '' }
function create() { form.value = empty(); groupOptions.value = []; extraGroupId.value = ''; open.value = true }
async function edit(value: GroupBinding) { form.value = { ...value }; extraGroupId.value = ''; groupOptions.value = await api.groups(value.connectionId); open.value = true }
function addExtraGroup() {
  const id = extraGroupId.value.trim()
  if (!id) return
  form.value.channelId = id
  extraGroupId.value = ''
}
async function save() { saving.value = true; try { const connection = connections.value.find(value => value.id === form.value.connectionId); form.value.platform = connection?.protocol || connection?.platform || form.value.platform; form.value.selfId = connection?.userId || connection?.selfId || form.value.selfId; await api.saveBinding(form.value); toast.success('定时推送目标已保存'); open.value = false; await load() } catch (cause) { toast.error(errorText(cause, '保存定时推送目标失败')) } finally { saving.value = false } }
async function toggle(value: GroupBinding) { toggling.value = value.id; try { await api.saveBinding({ ...value, enabled: !value.enabled }); toast.success(value.enabled ? '定时推送已停用' : '定时推送已启用'); await load() } catch (cause) { toast.error(errorText(cause, '更新定时推送状态失败')) } finally { toggling.value = '' } }
function remove(value: GroupBinding) { modal.confirm({ title: '删除定时推送目标', content: '删除后定时采集任务不再主动推送到此群。即时链接回复不受影响。', onConfirm: async () => { await api.deleteBinding(value.id); toast.success('定时推送目标已删除'); await load() } }) }
onMounted(load)

/** 行操作统一收进操作菜单 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => edit(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => remove(row as never) },
]

</script>

<template>
  <YdTablePageLayout title="定时推送目标" :show-search="false">
            <template #actions>
      <YdButton @click="create"><YdIcon name="i-ri:add-line"/>新增目标</YdButton>
            </template>
            <template #table>
      <YdAlert v-if="error" variant="destructive" title="加载失败" :description="error"/>
<YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
        :actions="rowActions"
  row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[900px]" border stripe :columns="columns" :data="rows">
        <template #cell-siteId="{row}">{{ sites.find(value => value.id === row.original.siteId)?.name || row.original.siteId }}</template>
        <template #cell-connectionId="{row}">{{ connections.find(value => value.id === row.original.connectionId)?.name || row.original.connectionId }}</template>
        <template #cell-channelId="{row}">{{ row.original.channelId }}</template>
        <template #cell-enabled="{row}"><div class="status-toggle"><YdSwitch :model-value="row.original.enabled" :disabled="toggling===row.original.id" @update:model-value="toggle(row.original)"/><small>{{ row.original.enabled ? '已启用' : '已停用' }}</small></div></template>
                </YdResponsiveEnhancedTable></YdLoadingOverlay>
            </template>
            <template #footer>
      <YdPagination v-model:page="page" v-model:size="size" :total="total" @page-change="load" @size-change="load"/>
            </template>
          </YdTablePageLayout>
    <YdModal v-model="open" title="定时推送目标" class="binding-modal" :show-cancel-button="true" :confirm-button-loading="saving" @confirm="save">
      <div class="binding-form">
        <div class="field"><YdLabel>站点</YdLabel><YdSelect v-model="form.siteId" :options="sites.map(value => ({ label: value.name, value: value.id }))" placeholder="选择站点"/></div>
        <div class="field"><YdLabel>连接</YdLabel><YdSelect v-model="form.connectionId" :options="connectionSelectOptions" placeholder="选择连接" @update:model-value="changeConnection"/></div>
        <div class="field"><YdLabel>目标群</YdLabel><YdSelect v-model="form.channelId" :options="groupSelectOptions" placeholder="选择群"/><YdInput v-model="extraGroupId" class="mt-2" placeholder="官方群 openid，回车添加" @keydown.enter.prevent="addExtraGroup"/><small class="text-muted-foreground">官方 QQ 没有历史群列表，选项来自本进程收到过的群消息；已保存的群会保留。</small></div>
        <div class="two-columns"><div class="field"><YdLabel>静默开始</YdLabel><YdInput v-model="form.quietStart" placeholder="23:00"/></div><div class="field"><YdLabel>静默结束</YdLabel><YdInput v-model="form.quietEnd" placeholder="07:00"/></div></div>
        <div class="two-columns"><div class="field"><YdLabel>冷却秒数</YdLabel><YdNumberField v-model="form.cooldownSeconds" :min="0" class="w-full"/></div><div class="field"><YdLabel>每小时上限</YdLabel><YdNumberField v-model="form.hourlyLimit" :min="0" class="w-full"/></div></div>
        <YdSwitch v-model="form.enabled">启用定时推送</YdSwitch>
      </div>
    </YdModal>
</template>

<style scoped>.status-toggle{display:flex;align-items:center;gap:8px}.status-toggle small{color:var(--color-text-3)}.row-actions{display:flex;flex-wrap:wrap;gap:8px}.binding-form{display:grid;gap:18px}.field{display:grid;gap:7px}.two-columns{display:grid;grid-template-columns:1fr 1fr;gap:14px}.binding-modal{width:min(620px,calc(100vw - 32px))}@media(max-width:560px){.two-columns{grid-template-columns:1fr}}</style>
