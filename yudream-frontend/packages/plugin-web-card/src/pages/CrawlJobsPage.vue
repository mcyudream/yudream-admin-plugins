<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { FaAlert, FaButton, FaCard, FaIcon, FaInput, FaLabel, FaModal, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSelect, FaSwitch, useFaModal, useFaToast, type TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createWebCardApi } from '../api/web-card-api'
import type { CrawlJob, Site } from '../types'
import { dateTime, errorText, uid } from '../ui'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createWebCardApi(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const rows = ref<CrawlJob[]>([])
const sites = ref<Site[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const open = ref(false)
const saving = ref(false)
const toggling = ref('')
const form = ref<CrawlJob>(empty())
const columns: TableColumn<CrawlJob>[] = [{ accessorKey: 'sourceUrl', header: '采集入口', minWidth: 300 }, { accessorKey: 'sourceType', header: '类型', width: 100 }, { accessorKey: 'intervalMinutes', header: '周期/分钟', width: 120 }, { accessorKey: 'initialItemCount', header: '首次条数', width: 100 }, { id: 'nextRunAt', header: '下次运行', width: 180 }, { id: 'enabled', header: '状态', width: 110 }, { id: 'operation', header: '操作', width: 160 }]
function empty(): CrawlJob { return { id: uid(), siteId: '', sourceUrl: '', sourceType: 'RSS', enabled: true, intervalMinutes: 30, initialItemCount: 3, nextRunAt: Date.now(), initialized: false, createdAt: 0, updatedAt: 0 } }
async function load() { loading.value = true; error.value = ''; try { const [result, sitePage] = await Promise.all([api.jobs(page.value, size.value), api.sites(1, 200)]); rows.value = result.records; total.value = result.total; sites.value = sitePage.records } catch (cause) { error.value = errorText(cause, '加载定时任务失败') } finally { loading.value = false } }
function create() { form.value = empty(); open.value = true }
function edit(value: CrawlJob) { form.value = { ...value }; open.value = true }
async function save() { saving.value = true; try { await api.saveJob(form.value); toast.success('定时任务已保存'); open.value = false; await load() } catch (cause) { toast.error(errorText(cause, '保存定时任务失败')) } finally { saving.value = false } }
async function toggle(value: CrawlJob) { toggling.value = value.id; try { await api.saveJob({ ...value, enabled: !value.enabled }); toast.success(value.enabled ? '定时任务已停用' : '定时任务已启用'); await load() } catch (cause) { toast.error(errorText(cause, '更新定时任务状态失败')) } finally { toggling.value = '' } }
function remove(value: CrawlJob) { modal.confirm({ title: '删除定时任务', content: '历史内容和投递记录将保留。', onConfirm: async () => { await api.deleteJob(value.id); toast.success('定时任务已删除'); if (rows.value.length === 1 && page.value > 1) page.value--; await load() } }) }
onMounted(load)
</script>

<template>
  <section><FaPageHeader title="定时任务" description="仅在需要持续监控时启用。用户即时链接投递不依赖此处任务。"><FaButton @click="create"><FaIcon name="i-ri:add-line"/>新增任务</FaButton></FaPageHeader><FaPageMain class="space-y-4"><FaAlert v-if="error" variant="destructive" title="加载失败" :description="error"/><FaResponsiveTable v-loading="loading" row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[1080px]" border stripe :columns="columns" :data="rows"><template #cell-siteId="{row}">{{ sites.find(value => value.id === row.original.siteId)?.name || row.original.siteId }}</template><template #cell-nextRunAt="{row}">{{ dateTime(row.original.nextRunAt) }}</template><template #cell-enabled="{row}"><div class="status-toggle"><FaSwitch :model-value="row.original.enabled" :disabled="toggling===row.original.id" @update:model-value="toggle(row.original)"/><small>{{ row.original.enabled ? '已启用' : '已停用' }}</small></div></template><template #cell-operation="{row}"><div class="row-actions"><FaButton size="sm" variant="outline" @click="edit(row.original)">编辑</FaButton><FaButton size="sm" variant="destructive" @click="remove(row.original)">删除</FaButton></div></template>
<template #card="{ row }">
  <FaCard class="w-full">
    <div class="flex flex-col gap-3">
      <div class="flex items-center justify-between gap-2">
        <span class="text-base font-semibold">{{ row.sourceUrl }}</span>
      </div>
      <div class="flex flex-col gap-1 text-sm">
        <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">站点</span><span class="break-all">{{ sites.find(value => value.id === row.siteId)?.name || row.siteId }}</span></div>
        <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">类型</span><span class="break-all">{{ row.sourceType }}</span></div>
        <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">状态</span><span>{{ row.enabled ? '已启用' : '已停用' }}</span></div>
        <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">下次运行</span><span>{{ dateTime(row.nextRunAt) }}</span></div>
      </div>
      <div class="flex flex-wrap gap-2 border-t pt-3">
        <FaButton size="sm" variant="outline" @click="edit(row)">编辑</FaButton>
        <FaButton size="sm" variant="destructive" @click="remove(row)">删除</FaButton>
      </div>
    </div>
  </FaCard>
</template></FaResponsiveTable><FaPagination v-model:page="page" v-model:size="size" :total="total" class="mt-3" @page-change="load" @size-change="load"/></FaPageMain><FaModal v-model="open" title="定时采集任务" class="job-modal" :show-cancel-button="true" :confirm-button-loading="saving" @confirm="save"><div class="job-form"><div class="field"><FaLabel>站点</FaLabel><FaSelect v-model="form.siteId" :options="sites.map(value => ({ label: value.name, value: value.id }))" placeholder="选择站点"/></div><div class="field"><FaLabel>采集入口</FaLabel><FaInput v-model="form.sourceUrl" type="url" placeholder="RSS、Sitemap 或列表页 URL"/></div><div class="field"><FaLabel>入口类型</FaLabel><FaSelect v-model="form.sourceType" :options="['RSS','SITEMAP','HTML','JSON'].map(value => ({ label: value, value }))"/></div><div class="grid grid-cols-1 gap-3 sm:grid-cols-2"><div class="field"><FaLabel>周期（分钟）</FaLabel><FaInput v-model.number="form.intervalMinutes" type="number" min="1"/></div><div class="field"><FaLabel>首次推送条数</FaLabel><FaInput v-model.number="form.initialItemCount" type="number" min="1" max="50"/></div></div><FaSwitch v-model="form.enabled">启用定时采集</FaSwitch></div></FaModal></section>
</template>

<style scoped>.status-toggle{display:flex;align-items:center;gap:8px}.status-toggle small{color:#667281}.row-actions{display:flex;flex-wrap:wrap;gap:8px}.job-form{display:grid;gap:16px}.field{display:grid;gap:6px}.job-modal{width:min(560px,calc(100vw - 32px))}</style>
