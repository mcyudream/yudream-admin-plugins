<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ActivityProofExportRecord } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable } from '@yudream/components'
import { onMounted } from 'vue'
import { useMyProofs } from '../composables/useMyProofs'
import { formatTime } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const model = useMyProofs(props.sdk)
const { loading, downloading, records, pager } = model

onMounted(model.load)

const columns: TableColumn<ActivityProofExportRecord>[] = [
  { accessorKey: 'activityName', header: '活动', width: 240, fixed: 'left' },
  { accessorKey: 'serverName', header: '服务器', width: 180 },
  { id: 'uploadedAt', header: '盖章时间', width: 180 },
  { id: 'operation', header: '操作', width: 140, align: 'center', fixed: 'right' },
]
</script>

<template>
  <section class="proof-page">
    <FaPageHeader title="我的活动证明" class="mb-0">
      <FaButton variant="outline" :loading="loading" @click="model.load">
        <FaIcon name="i-ri:refresh-line" />刷新
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <FaResponsiveTable
        v-loading="loading"
        row-key="id"
        table-root-class="max-w-full overflow-x-auto rounded-lg"
        table-class="min-w-[760px]"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="records"
      >
        <template #cell-uploadedAt="{ row }">{{ formatTime(row.original.stampedPdfUploadedAt) }}</template>
        <template #cell-operation="{ row }">
          <FaButton size="sm" variant="outline" :loading="downloading" @click="model.downloadStamped(row.original)">下载 PDF</FaButton>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.activityName }}</span>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div v-if="row.serverName" class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">服务器</span>
                  <span class="break-all">{{ row.serverName }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">盖章时间</span>
                  <span>{{ formatTime(row.stampedPdfUploadedAt) }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="outline" :loading="downloading" @click="model.downloadStamped(row)">下载 PDF</FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="model.load"
        @size-change="model.load"
      />
    </FaPageMain>
  </section>
</template>
