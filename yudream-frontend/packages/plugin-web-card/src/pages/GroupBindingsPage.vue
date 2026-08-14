<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { FaAlert, FaButton, FaCard, FaIcon, FaInput, FaLabel, FaModal, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSelect, FaSwitch, useFaModal, useFaToast, type TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createWebCardApi } from '../api/web-card-api'
import type { GroupBinding, Option, Site } from '../types'
import { errorText, uid } from '../ui'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createWebCardApi(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
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
const form = ref<GroupBinding>(empty())
const columns: TableColumn<GroupBinding>[] = [{ accessorKey: 'siteId', header: '站点', minWidth: 180 }, { accessorKey: 'connectionId', header: '连接', minWidth: 150 }, { accessorKey: 'channelId', header: '目标群', minWidth: 180 }, { id: 'enabled', header: '状态', width: 110 }, { accessorKey: 'cooldownSeconds', header: '冷却/秒', width: 100 }, { id: 'operation', header: '操作', width: 170 }]

function empty(): GroupBinding { return { id: uid(), siteId: '', connectionId: '', platform: 'milky', selfId: '', channelId: '', enabled: true, quietStart: '', quietEnd: '', cooldownSeconds: 0, hourlyLimit: 0, lastDeliveryAt: 0, createdAt: 0, updatedAt: 0 } }
async function load() { loading.value = true; error.value = ''; try { const [result, sitePage, connectionOptions] = await Promise.all([api.bindings(page.value, size.value), api.sites(1, 200), api.connections()]); rows.value = result.records; total.value = result.total; sites.value = sitePage.records; connections.value = connectionOptions } catch (cause) { error.value = errorText(cause, '加载定时推送目标失败') } finally { loading.value = false } }
async function changeConnection(connectionId: unknown) { groupOptions.value = connectionId ? await api.groups(String(connectionId)) : []; form.value.channelId = '' }
function create() { form.value = empty(); groupOptions.value = []; open.value = true }
async function edit(value: GroupBinding) { form.value = { ...value }; groupOptions.value = await api.groups(value.connectionId); open.value = true }
async function save() { saving.value = true; try { const connection = connections.value.find(value => value.id === form.value.connectionId); form.value.platform = connection?.platform ?? form.value.platform; form.value.selfId = connection?.selfId ?? form.value.selfId; await api.saveBinding(form.value); toast.success('定时推送目标已保存'); open.value = false; await load() } catch (cause) { toast.error(errorText(cause, '保存定时推送目标失败')) } finally { saving.value = false } }
async function toggle(value: GroupBinding) { toggling.value = value.id; try { await api.saveBinding({ ...value, enabled: !value.enabled }); toast.success(value.enabled ? '定时推送已停用' : '定时推送已启用'); await load() } catch (cause) { toast.error(errorText(cause, '更新定时推送状态失败')) } finally { toggling.value = '' } }
function remove(value: GroupBinding) { modal.confirm({ title: '删除定时推送目标', content: '删除后定时采集任务不再主动推送到此群。即时链接回复不受影响。', onConfirm: async () => { await api.deleteBinding(value.id); toast.success('定时推送目标已删除'); await load() } }) }
onMounted(load)
</script>

<template>
  <section>
    <FaPageHeader title="定时推送目标" description="仅供定时采集任务主动推送使用。即时链接始终回复到用户发送链接的原群。"><FaButton @click="create"><FaIcon name="i-ri:add-line"/>新增目标</FaButton></FaPageHeader>
    <FaPageMain class="space-y-4">
      <FaAlert v-if="error" variant="destructive" title="加载失败" :description="error"/>
      <FaResponsiveTable v-loading="loading" row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[900px]" border stripe :columns="columns" :data="rows">
        <template #cell-siteId="{row}">{{ sites.find(value => value.id === row.original.siteId)?.name || row.original.siteId }}</template>
        <template #cell-connectionId="{row}">{{ connections.find(value => value.id === row.original.connectionId)?.name || row.original.connectionId }}</template>
        <template #cell-channelId="{row}">{{ row.original.channelId }}</template>
        <template #cell-enabled="{row}"><div class="status-toggle"><FaSwitch :model-value="row.original.enabled" :disabled="toggling===row.original.id" @update:model-value="toggle(row.original)"/><small>{{ row.original.enabled ? '已启用' : '已停用' }}</small></div></template>
        <template #cell-operation="{row}"><div class="row-actions"><FaButton size="sm" variant="outline" @click="edit(row.original)">编辑</FaButton><FaButton size="sm" variant="destructive" @click="remove(row.original)">删除</FaButton></div></template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ sites.find(value => value.id === row.siteId)?.name || row.siteId }}</span>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">连接</span><span class="break-all">{{ connections.find(value => value.id === row.connectionId)?.name || row.connectionId }}</span></div>
                <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">目标群</span><span class="break-all">{{ row.channelId }}</span></div>
                <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">状态</span><span>{{ row.enabled ? '已启用' : '已停用' }}</span></div>
                <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">冷却/秒</span><span>{{ row.cooldownSeconds }}</span></div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" @click="edit(row)">编辑</FaButton>
                <FaButton size="sm" variant="destructive" @click="remove(row)">删除</FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
      <FaPagination v-model:page="page" v-model:size="size" :total="total" class="mt-3" @page-change="load" @size-change="load"/>
    </FaPageMain>
    <FaModal v-model="open" title="定时推送目标" class="binding-modal" :show-cancel-button="true" :confirm-button-loading="saving" @confirm="save">
      <div class="binding-form">
        <div class="field"><FaLabel>站点</FaLabel><FaSelect v-model="form.siteId" :options="sites.map(value => ({ label: value.name, value: value.id }))" placeholder="选择站点"/></div>
        <div class="field"><FaLabel>连接</FaLabel><FaSelect v-model="form.connectionId" :options="connections.map(value => ({ label: value.name, value: value.id }))" placeholder="选择连接" @update:model-value="changeConnection"/></div>
        <div class="field"><FaLabel>目标群</FaLabel><FaSelect v-model="form.channelId" :options="groupOptions.map(value => ({ label: value.name, value: value.id }))" placeholder="选择群"/></div>
        <div class="two-columns"><div class="field"><FaLabel>静默开始</FaLabel><FaInput v-model="form.quietStart" placeholder="23:00"/></div><div class="field"><FaLabel>静默结束</FaLabel><FaInput v-model="form.quietEnd" placeholder="07:00"/></div></div>
        <div class="two-columns"><div class="field"><FaLabel>冷却秒数</FaLabel><FaInput v-model.number="form.cooldownSeconds" type="number" min="0"/></div><div class="field"><FaLabel>每小时上限</FaLabel><FaInput v-model.number="form.hourlyLimit" type="number" min="0"/></div></div>
        <FaSwitch v-model="form.enabled">启用定时推送</FaSwitch>
      </div>
    </FaModal>
  </section>
</template>

<style scoped>.status-toggle{display:flex;align-items:center;gap:8px}.status-toggle small{color:#667281}.row-actions{display:flex;flex-wrap:wrap;gap:8px}.binding-form{display:grid;gap:18px}.field{display:grid;gap:7px}.two-columns{display:grid;grid-template-columns:1fr 1fr;gap:14px}.binding-modal{width:min(620px,calc(100vw - 32px))}@media(max-width:560px){.two-columns{grid-template-columns:1fr}}</style>
