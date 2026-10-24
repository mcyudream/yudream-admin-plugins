<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { OfficePackage, PrecheckRow } from '../types'
import { YdButton, YdInput, YdLoadingOverlay, YdResponsiveEnhancedTable, YdSelect, YdTablePageLayout, YdTag, useYdToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createOfficeAdminApi, errorMessage, saveBlob } from '../api/office-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const api = createOfficeAdminApi(props.sdk)

const packages = ref<OfficePackage[]>([])
const precheckRows = ref<PrecheckRow[]>([])
const packageForm = ref({ code: '', period: new Date().toISOString().slice(0, 7) })
const packageLoading = ref(false)
const exporting = ref(false)

const packageOptions = () => [
  { label: '选择打包定义', value: '' },
  ...packages.value.map(item => ({ label: item.name, value: item.code })),
]

const columns: TableColumn<PrecheckRow>[] = [
  { id: 'docTypeName', header: '文档类型', width: 180, enableSorting: false },
  { id: 'targetLabel', header: '目标', minWidth: 200, enableSorting: false },
  { id: 'status', header: '状态', width: 110, enableSorting: false },
  { id: 'filename', header: '产物', minWidth: 220, enableSorting: false },
]

const rowActions: ActionMenuItem<PrecheckRow>[] = [
  { label: '去生成', icon: 'morph:magic-staff', visible: row => !row.ready && !row.missing, disabled: () => false, onClick: row => createForRow(row) },
]

onMounted(load)

async function load() {
  packageLoading.value = true
  try {
    packages.value = await api.packages()
    if (packageForm.value.code && precheckRows.value.length) {
      precheckRows.value = await api.packagePrecheck(packageForm.value.code, packageForm.value.period)
    }
  }
  catch (cause) {
    toast.error(errorMessage(cause, '打包定义加载失败'))
  }
  finally {
    packageLoading.value = false
  }
}

async function runPrecheck() {
  if (!packageForm.value.code) {
    toast.warning('请选择打包定义')
    return
  }
  const segment = /^\d{4}-\d{2}$|^\d{4}-\d{2}-\d{2}\/\d{4}-\d{2}-\d{2}$/
  if (!packageForm.value.period.split(',').every(part => segment.test(part.trim()))) {
    toast.warning('周期格式：yyyy-MM 或 yyyy-MM-dd/yyyy-MM-dd，多段用逗号分隔（如 2026-09-01/2026-12-01,2027-03-01/2027-06-30）')
    return
  }
  packageLoading.value = true
  try {
    precheckRows.value = await api.packagePrecheck(packageForm.value.code, packageForm.value.period)
  }
  catch (cause) {
    toast.error(errorMessage(cause, '预检失败'))
  }
  finally {
    packageLoading.value = false
  }
}

/** 预检行一键创建生成任务（沿用打包项的目标与周期）。 */
async function createForRow(row: PrecheckRow) {
  try {
    await api.createJob({
      docTypeCode: row.docTypeCode,
      sourceType: row.sourceType,
      sourceId: row.targetId,
      period: row.period,
    })
    toast.success(`已创建任务：${row.docTypeName} / ${row.targetLabel}，可到「生成任务」页起草渲染`)
    await runPrecheck()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '创建失败'))
  }
}

async function exportZip() {
  if (!packageForm.value.code) {
    toast.warning('请选择打包定义')
    return
  }
  exporting.value = true
  try {
    const blob = await api.exportPackage(packageForm.value.code, packageForm.value.period, true)
    saveBlob(blob.data, `ai-office-${packageForm.value.code}-${packageForm.value.period.replace('/', '～')}.zip`)
    toast.success('已导出：就绪文档已打包；若有缺料，包内附「缺料清单.txt」')
  }
  catch (cause) {
    toast.error(errorMessage(cause, '打包导出失败'))
  }
  finally {
    exporting.value = false
  }
}

function readyText(row: PrecheckRow) {
  if (row.missing) {
    return '类型缺失'
  }
  return row.ready ? '已定稿' : '未定稿'
}
</script>

<template>
  <YdTablePageLayout title="打包导出" :show-search="false" stats-title="怎么用">
    <template #stats>
      <div class="text-sm text-muted-foreground">
        选择打包定义与周期（月度 <code>yyyy-MM</code>、范围 <code>yyyy-MM-dd/yyyy-MM-dd</code>，多段逗号分隔如 <code>2026-09-01/2026-12-01,2027-03-01/2027-06-30</code>）→ 预检 → 未定稿的行可「去生成」→ 导出 zip。导出永远包含已定稿文档；缺料自动附「缺料清单.txt」，补齐后重新导出即可覆盖。
      </div>
    </template>
    <template #actions>
      <div class="flex flex-wrap items-center gap-2">
        <YdSelect v-model="packageForm.code" class="w-52" :options="packageOptions()" />
        <YdInput v-model="packageForm.period" class="w-72" placeholder="yyyy-MM 或 起/止，多段逗号分隔" />
        <YdButton variant="outline" :loading="packageLoading" @click="runPrecheck">预检</YdButton>
        <YdButton :loading="exporting" @click="exportZip">导出 zip</YdButton>
      </div>
    </template>
    <template #table>
      <YdLoadingOverlay :loading="packageLoading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="precheckRows"
          :row-key="row => `${row.docTypeCode}-${row.targetId}`"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[820px]"
          border
          stripe
          :actions="rowActions"
          empty-text="选择打包定义后点「预检」查看各文档状态"
        >
          <template #cell-status="{ row }">
            <YdTag :variant="row.original.ready ? 'default' : 'outline'">{{ readyText(row.original) }}</YdTag>
          </template>
          <template #cell-filename="{ row }">
            <span class="truncate" :title="row.original.filename">{{ row.original.filename || '—' }}</span>
          </template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
  </YdTablePageLayout>
</template>
