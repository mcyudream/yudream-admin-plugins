<script setup lang="ts">
import { YdLoadingOverlay, type ActionMenuItem, YdTablePageLayout } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { YdAlert, YdButton, YdIcon, YdInput, YdLabel, YdModal, YdNumberField, YdPagination, YdSelect, YdSwitch, useYdModal, useYdToast, type TableColumn, YdResponsiveEnhancedTable } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createWebCardApi } from '../api/web-card-api'
import type { CrawlJob, Site } from '../types'
import { dateTime, errorText, uid } from '../ui'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createWebCardApi(props.sdk)
const toast = useYdToast()
const modal = useYdModal()
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
const columns: TableColumn<CrawlJob>[] = [{ accessorKey: 'sourceUrl', header: '采集入口', minWidth: 300 }, { accessorKey: 'sourceType', header: '类型', width: 100 }, { accessorKey: 'intervalMinutes', header: '周期/分钟', width: 120 }, { accessorKey: 'initialItemCount', header: '首次条数', width: 100 }, { id: 'nextRunAt', header: '下次运行', width: 180 }, { id: 'enabled', header: '状态', width: 110 }]
function empty(): CrawlJob { return { id: uid(), siteId: '', sourceUrl: '', sourceType: 'RSS', enabled: true, intervalMinutes: 30, initialItemCount: 3, nextRunAt: Date.now(), initialized: false, createdAt: 0, updatedAt: 0 } }
async function load() { loading.value = true; error.value = ''; try { const [result, sitePage] = await Promise.all([api.jobs(page.value, size.value), api.sites(1, 200)]); rows.value = result.records; total.value = result.total; sites.value = sitePage.records } catch (cause) { error.value = errorText(cause, '加载定时任务失败') } finally { loading.value = false } }
function create() { form.value = empty(); open.value = true }
function edit(value: CrawlJob) { form.value = { ...value }; open.value = true }
async function save() { saving.value = true; try { await api.saveJob(form.value); toast.success('定时任务已保存'); open.value = false; await load() } catch (cause) { toast.error(errorText(cause, '保存定时任务失败')) } finally { saving.value = false } }
async function toggle(value: CrawlJob) { toggling.value = value.id; try { await api.saveJob({ ...value, enabled: !value.enabled }); toast.success(value.enabled ? '定时任务已停用' : '定时任务已启用'); await load() } catch (cause) { toast.error(errorText(cause, '更新定时任务状态失败')) } finally { toggling.value = '' } }
function remove(value: CrawlJob) { modal.confirm({ title: '删除定时任务', content: '历史内容和投递记录将保留。', onConfirm: async () => { try { await api.deleteJob(value.id); toast.success('定时任务已删除'); if (rows.value.length === 1 && page.value > 1) page.value--; await load() } catch (cause) { toast.error(errorText(cause, '删除定时任务失败')) } } }) }
onMounted(load)

/** 行操作统一收进操作菜单 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => edit(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => remove(row as never) },
]

</script>

<template>
  <YdTablePageLayout title="定时任务" :show-search="false">
            <template #actions>
      <YdButton @click="create"><YdIcon name="i-ri:add-line"/>新增任务</YdButton>
            </template>
            <template #table>
      <YdAlert v-if="error" variant="destructive" title="加载失败" :description="error"/>
<YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
        :actions="rowActions"
  row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[1080px]" border stripe :columns="columns" :data="rows"><template #cell-siteId="{row}">{{ sites.find(value => value.id === row.original.siteId)?.name || row.original.siteId }}</template><template #cell-nextRunAt="{row}">{{ dateTime(row.original.nextRunAt) }}</template><template #cell-enabled="{row}"><div class="status-toggle"><YdSwitch :model-value="row.original.enabled" :disabled="toggling===row.original.id" @update:model-value="toggle(row.original)"/><small>{{ row.original.enabled ? '已启用' : '已停用' }}</small></div></template></YdResponsiveEnhancedTable></YdLoadingOverlay>
            </template>
            <template #footer>
      <YdPagination v-model:page="page" v-model:size="size" :total="total" @page-change="load" @size-change="load"/>
            </template>
          </YdTablePageLayout>
<YdModal v-model="open" title="定时采集任务" class="job-modal" :show-cancel-button="true" :confirm-button-loading="saving" @confirm="save"><div class="job-form"><div class="field"><YdLabel>站点</YdLabel><YdSelect v-model="form.siteId" :options="sites.map(value => ({ label: value.name, value: value.id }))" placeholder="选择站点"/></div><div class="field"><YdLabel>采集入口</YdLabel><YdInput v-model="form.sourceUrl" type="url" placeholder="RSS、Sitemap 或列表页 URL"/></div><div class="field"><YdLabel>入口类型</YdLabel><YdSelect v-model="form.sourceType" :options="['RSS','SITEMAP','HTML','JSON'].map(value => ({ label: value, value }))"/></div><div class="grid grid-cols-1 gap-3 sm:grid-cols-2"><div class="field"><YdLabel>周期（分钟）</YdLabel><YdNumberField v-model="form.intervalMinutes" :min="1" class="w-full"/></div><div class="field"><YdLabel>首次推送条数</YdLabel><YdNumberField v-model="form.initialItemCount" :min="1" :max="50" class="w-full"/></div></div><YdSwitch v-model="form.enabled">启用定时采集</YdSwitch></div></YdModal>
</template>

<style scoped>.status-toggle{display:flex;align-items:center;gap:8px}.status-toggle small{color:var(--color-text-3)}.row-actions{display:flex;flex-wrap:wrap;gap:8px}.job-form{display:grid;gap:16px}.field{display:grid;gap:6px}.job-modal{width:min(560px,calc(100vw - 32px))}</style>
