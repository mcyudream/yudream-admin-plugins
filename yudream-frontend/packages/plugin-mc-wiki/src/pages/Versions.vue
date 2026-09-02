<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { McWikiPluginModel } from '../composables/useMcWikiPlugin'
import type { WikiJob, WikiVersionRow } from '../types'
import { FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSearchBar, FaSelect, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { onMounted, ref, watch } from 'vue'
import JobLogModal from '../components/JobLogModal.vue'

const props = defineProps<{ model: McWikiPluginModel }>()
const confirm = useFaModal()
const toast = useFaToast()

const typeOptions = [
  { label: '全部类型', value: '' },
  { label: '正式版', value: 'release' },
  { label: '快照', value: 'snapshot' },
  { label: '旧测试版', value: 'old_beta' },
  { label: '旧阿尔法版', value: 'old_alpha' },
]

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '已导入', value: 'imported' },
  { label: '未导入', value: 'unimported' },
  { label: '已发布', value: 'published' },
]

const columns: TableColumn<WikiVersionRow>[] = [
  { accessorKey: 'id', header: '版本', minWidth: 130, fixed: 'left' },
  { id: 'type', header: '类型', width: 90 },
  { id: 'imported', header: '导入状态', width: 100 },
  { id: 'counts', header: '物品 / 配方', width: 120 },
  { id: 'published', header: '发布状态', width: 100 },
  { accessorKey: 'releaseTime', header: '发布时间', width: 170 },
  { id: 'operation', header: '操作', width: 300 },
]

const selectedJob = ref<WikiJob | null>(null)
const logOpen = ref(false)

function typeLabel(type: string) {
  return typeOptions.find(option => option.value === type)?.label ?? type
}

function formatTime(value?: string | null) {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function formatTimestamp(value?: number | string) {
  const time = Number(value)
  if (!value || !Number.isFinite(time) || time <= 0) {
    return '-'
  }
  return new Date(time).toLocaleString('zh-CN', { hour12: false })
}

async function openJob(started: { jobId: string, streamId: string }) {
  const job = await props.model.api.job(started.jobId)
  selectedJob.value = { ...job, streamId: started.streamId }
  logOpen.value = true
}

async function importVersion(version: string) {
  try {
    await openJob(await props.model.api.importVersion(version))
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '导入任务创建失败')
  }
}

async function updateRenders() {
  try {
    await openJob(await props.model.api.updateRenders())
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '渲染资产更新任务创建失败')
  }
}

function confirmDeleteData(row: WikiVersionRow) {
  confirm.confirm({
    title: '删除版本数据',
    content: `确认删除 ${row.id} 已导入的 ${row.items} 个物品、${row.recipes} 个配方与遗留贴图吗？${row.published ? '该版本当前已发布，删除后会同时取消发布。' : ''}`,
    onConfirm: () => props.model.deleteVersionData(row.id),
  })
}

function confirmUnpublish(row: WikiVersionRow) {
  confirm.confirm({
    title: '取消发布',
    content: `确认将 ${row.id} 从公开百科取消发布吗？公开页将无法再查询该版本。`,
    onConfirm: () => props.model.unpublishVersion(row.id),
  })
}

watch(logOpen, (open) => {
  if (!open) {
    void props.model.loadVersions()
    void props.model.loadRenders()
  }
})

onMounted(() => {
  void props.model.loadVersions()
  void props.model.loadMeta()
  void props.model.loadRenders()
})
</script>

<template>
  <FaPageHeader title="百科版本" description="刷新官方版本清单、导入资源并将版本发布到公开百科">
    <FaButton variant="outline" :loading="model.loading" @click="model.refreshVersions"><FaIcon name="i-ri:refresh-line" />刷新清单</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaCard style="margin-bottom: 12px">
      <div class="flex flex-wrap items-center gap-3">
        <FaIcon name="i-ri:image-2-line" class="text-2xl text-secondary-foreground/60" />
        <div class="flex min-w-0 flex-1 flex-col gap-1">
          <strong class="text-sm">共享渲染资产</strong>
          <span v-if="model.renderMeta?.updated" class="text-xs text-secondary-foreground/60">
            来源 Owen1212055/mc-assets（{{ model.renderMeta.commit }}，Minecraft {{ model.renderMeta.gameVersion || '未知' }}）·
            物品渲染 {{ model.renderMeta.items }} · 生物渲染 {{ model.renderMeta.entitiesIsometric }} · 更新于 {{ formatTimestamp(model.renderMeta.updatedAt) }}
          </span>
          <span v-else class="text-xs text-secondary-foreground/60">尚未更新渲染资产，公开页图标将显示回退占位。所有版本共用同一份渲染图，更新一次即可。</span>
        </div>
        <FaButton variant="outline" @click="updateRenders"><FaIcon name="i-ri:download-cloud-2-line" />一键更新渲染资产</FaButton>
      </div>
    </FaCard>
    <FaResponsiveTable
      v-loading="model.loading"
      :columns="columns"
      :data="model.versions"
      row-key="id"
      table-root-class="max-w-full overflow-x-auto rounded-lg"
      table-class="min-w-[1080px]"
      border stripe column-visibility
      empty-text="暂无版本数据，请先刷新官方版本清单"
    >
      <template #toolbar>
        <FaSearchBar class="w-full">
          <div class="mc-wiki-filter-bar">
            <FaInput v-model="model.versionFilters.keyword" placeholder="搜索版本号，如 1.12.2" clearable @keydown.enter="model.applyVersionFilters" @clear="model.applyVersionFilters" />
            <FaSelect v-model="model.versionFilters.type" :options="typeOptions" @change="model.applyVersionFilters" />
            <FaSelect v-model="model.versionFilters.status" :options="statusOptions" @change="model.applyVersionFilters" />
            <FaButton variant="outline" @click="model.applyVersionFilters"><FaIcon name="i-ri:search-line" />查询</FaButton>
          </div>
        </FaSearchBar>
      </template>
      <template #cell-id="{ row }"><strong>{{ row.original.id }}</strong><FaTag v-if="row.original.latest" class="ml-2" variant="secondary">最新</FaTag></template>
      <template #cell-type="{ row }">{{ typeLabel(row.original.type) }}</template>
      <template #cell-imported="{ row }"><FaTag :variant="row.original.imported ? 'default' : 'outline'">{{ row.original.imported ? '已导入' : '未导入' }}</FaTag></template>
      <template #cell-counts="{ row }"><span v-if="row.original.imported">{{ row.original.items }} / {{ row.original.recipes }}</span><span v-else>-</span></template>
      <template #cell-published="{ row }"><FaTag v-if="row.original.published" variant="default">已发布</FaTag><span v-else class="text-secondary-foreground/60">-</span></template>
      <template #cell-releaseTime="{ row }">{{ formatTime(row.original.releaseTime) }}</template>
      <template #cell-operation="{ row }">
        <div class="flex-center gap-2">
          <FaButton size="sm" @click="importVersion(row.original.id)">{{ row.original.imported ? '重新导入' : '导入' }}</FaButton>
          <FaButton v-if="!row.original.published" size="sm" variant="outline" :disabled="!row.original.imported" @click="model.publishVersion(row.original.id)">发布</FaButton>
          <FaButton v-else size="sm" variant="outline" @click="confirmUnpublish(row.original)">取消发布</FaButton>
          <FaButton size="sm" variant="destructive" :disabled="!row.original.imported" @click="confirmDeleteData(row.original)">删除数据</FaButton>
        </div>
      </template>
      <template #card="{ row }">
        <div class="flex flex-col gap-3">
          <div class="flex items-center justify-between gap-2">
            <strong class="text-base">{{ row.id }}</strong>
            <FaTag :variant="row.imported ? 'default' : 'outline'">{{ row.imported ? '已导入' : '未导入' }}</FaTag>
          </div>
          <div class="flex flex-col gap-1 text-sm">
            <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">类型</span><span>{{ typeLabel(row.type) }}</span></div>
            <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">物品 / 配方</span><span>{{ row.imported ? `${row.items} / ${row.recipes}` : '-' }}</span></div>
            <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">发布时间</span><span>{{ formatTime(row.releaseTime) }}</span></div>
          </div>
          <div class="flex flex-wrap gap-2">
            <FaButton size="sm" @click="importVersion(row.id)">{{ row.imported ? '重新导入' : '导入' }}</FaButton>
            <FaButton v-if="!row.published" size="sm" variant="outline" :disabled="!row.imported" @click="model.publishVersion(row.id)">发布</FaButton>
            <FaButton v-else size="sm" variant="outline" @click="confirmUnpublish(row)">取消发布</FaButton>
            <FaButton size="sm" variant="destructive" :disabled="!row.imported" @click="confirmDeleteData(row)">删除数据</FaButton>
          </div>
        </div>
      </template>
    </FaResponsiveTable>
    <FaPagination v-model:page="model.versionPager.page" v-model:size="model.versionPager.size" :total="model.versionPager.total" class="mt-3" @page-change="model.loadVersions" @size-change="model.applyVersionFilters" />
    <JobLogModal v-model="logOpen" :job="selectedJob" :api="model.api" />
  </FaPageMain>
</template>
