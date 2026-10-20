<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { LauncherPack, LauncherPackVersion } from '../types'
import { YdButton, YdCard, YdIcon, YdInput, YdLoadingOverlay, YdModal, YdPagination, YdResponsiveEnhancedTable, YdResponsiveTable, YdTablePageLayout, YdTag, useYdModal } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { useLauncherPlugin } from '../composables/useLauncherPlugin'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useLauncherPlugin(props.sdk)
const confirm = useYdModal()
const createOpen = ref(false)
const detailOpen = ref(false)

const columns: TableColumn<LauncherPack>[] = [
  { accessorKey: 'id', header: 'ID', width: 160, fixed: 'left' },
  { accessorKey: 'name', header: '名称', minWidth: 180 },
  { accessorKey: 'recommendedVersionId', header: '推荐版本', width: 140 },
  { accessorKey: 'retainedVersionIds', header: '保留版本', width: 110 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 180 },
]

/** 行操作统一收进操作菜单：查看该整合包的版本历史 */
const rowActions: ActionMenuItem<LauncherPack>[] = [
  { label: '版本', icon: 'i-ri:history-line', onClick: row => void openDetail(row) },
]

const versionColumns: TableColumn<LauncherPackVersion>[] = [
  { accessorKey: 'versionId', header: '版本', width: 140, fixed: 'left' },
  { accessorKey: 'status', header: '状态', width: 110 },
  { accessorKey: 'indexHash', header: 'Index Hash', minWidth: 180 },
  { accessorKey: 'publishedAt', header: '发布时间', width: 180 },
  { id: 'operation', header: '操作', width: 120, align: 'center', fixed: 'right' },
]

const recommendedId = computed(() => model.packDetail?.pack.recommendedVersionId || '')

function applyFilters() {
  model.applyPackFilters()
}

function openCreate() {
  model.resetPackForm()
  createOpen.value = true
}

async function savePack() {
  if (await model.createPack()) {
    createOpen.value = false
  }
}

async function openDetail(row: LauncherPack) {
  detailOpen.value = true
  await model.loadPackDetail(row.id)
}

function confirmRollback(version: LauncherPackVersion) {
  const packId = model.packDetail?.pack.id
  if (!packId) {
    return
  }
  confirm.confirm({
    title: '回滚推荐版本',
    content: `确认将「${packId}」的推荐版本切到 ${version.versionId} 吗？启动器下次拉 manifest 会使用该版本。`,
    onConfirm: () => model.rollback(packId, version.versionId),
  })
}

onMounted(() => {
  void model.loadPacks()
})
</script>

<template>
  <YdTablePageLayout title="整合包管理">
      <template #search>
        <div class="grid grid-cols-1 gap-3 md:grid-cols-[minmax(220px,1fr)_auto]">
          <div class="min-w-0">
            <YdInput
              v-model="model.packKeyword"
              placeholder="搜索 ID、名称或描述"
              clearable
              class="w-full"
              @keydown.enter="applyFilters"
              @clear="applyFilters"
            />
          </div>
          <div class="flex items-center gap-2 md:justify-end">
            <YdButton size="sm" variant="outline" @click="applyFilters">
              <YdIcon name="i-ri:search-line" />
              查询
            </YdButton>
          </div>
        </div>
      </template>
      <template #actions>
        <YdButton @click="openCreate">
          <YdIcon name="i-ri:add-line" />
          新建整合包
        </YdButton>
      </template>
      <template #table>
        <YdLoadingOverlay :loading="model.loading">
          <YdResponsiveEnhancedTable
            :actions="rowActions"
            :columns="columns"
            :data="model.packs"
            row-key="id"
            table-root-class="rounded-lg overflow-hidden"
            table-class="min-w-[980px]"
            border
            stripe
            column-visibility
            empty-text="暂无整合包，点击右上角新建"
          >
            <template #cell-recommendedVersionId="{ row }">
              <YdTag v-if="row.original.recommendedVersionId" variant="default">
                {{ row.original.recommendedVersionId }}
              </YdTag>
              <span v-else>-</span>
            </template>
            <template #cell-retainedVersionIds="{ row }">
              {{ row.original.retainedVersionIds?.length || 0 }}
            </template>
            <template #cell-updatedAt="{ row }">
              {{ model.formatTime(row.original.updatedAt) }}
            </template>
            <template #card="{ row }">
              <YdCard class="w-full">
                <div class="flex flex-col gap-3">
                  <div class="flex items-center justify-between gap-2">
                    <span class="min-w-0 break-words text-base font-semibold">{{ row.name || row.id }}</span>
                    <YdTag v-if="row.recommendedVersionId" variant="default">
                      {{ row.recommendedVersionId }}
                    </YdTag>
                  </div>
                  <div class="flex flex-col gap-1 text-sm">
                    <div class="flex gap-2">
                      <span class="shrink-0 text-secondary-foreground/60">ID</span>
                      <span class="break-all">{{ row.id }}</span>
                    </div>
                    <div class="flex gap-2">
                      <span class="shrink-0 text-secondary-foreground/60">保留版本</span>
                      <span>{{ row.retainedVersionIds?.length || 0 }}</span>
                    </div>
                    <div class="flex gap-2">
                      <span class="shrink-0 text-secondary-foreground/60">更新时间</span>
                      <span class="break-all">{{ model.formatTime(row.updatedAt) }}</span>
                    </div>
                  </div>
                  <div class="flex flex-wrap gap-2 border-t pt-3">
                    <YdButton size="sm" variant="outline" @click="openDetail(row)">
                      版本
                    </YdButton>
                  </div>
                </div>
              </YdCard>
            </template>
          </YdResponsiveEnhancedTable>
        </YdLoadingOverlay>
      </template>
      <template #footer>
        <YdPagination
          v-model:page="model.packPager.page"
          v-model:size="model.packPager.size"
          :total="model.packPager.total"
          @page-change="model.loadPacks"
          @size-change="model.applyPackFilters"
        />
      </template>
  </YdTablePageLayout>

  <YdModal v-model="createOpen" title="新建整合包" class="sm:max-w-xl" :show-confirm-button="false" show-cancel-button>
      <form class="launcher-form" @submit.prevent>
        <label>
          <span>整合包 ID</span>
          <YdInput v-model="model.packForm.packId" placeholder="稳定标识，例如 survival" />
        </label>
        <label>
          <span>名称</span>
          <YdInput v-model="model.packForm.name" placeholder="展示给启动器的名称" />
        </label>
        <label>
          <span>描述</span>
          <YdInput v-model="model.packForm.description" placeholder="可选" />
        </label>
        <label>
          <span>图标 URL</span>
          <YdInput v-model="model.packForm.icon" placeholder="可选" />
        </label>
      </form>
      <template #footer>
        <YdButton variant="outline" @click="createOpen = false">
          取消
        </YdButton>
        <YdButton :loading="model.saving" @click="savePack">
          创建
        </YdButton>
      </template>
    </YdModal>

    <YdModal v-model="detailOpen" title="版本历史" class="sm:max-w-4xl" :show-confirm-button="false" show-cancel-button>
      <YdLoadingOverlay :loading="model.loading" v-if="model.packDetail"><YdResponsiveTable
        
        
        :columns="versionColumns"
        :data="model.packDetail.versions"
        row-key="versionId"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[760px]"
        border
        stripe
        empty-text="该整合包还没有发布版本"
      >
        <template #cell-status="{ row }">
          <YdTag :variant="row.original.versionId === recommendedId ? 'default' : 'secondary'">
            {{ row.original.versionId === recommendedId ? '推荐' : (row.original.status || '-') }}
          </YdTag>
        </template>
        <template #cell-indexHash="{ row }">
          <code>{{ row.original.indexHash || '-' }}</code>
        </template>
        <template #cell-publishedAt="{ row }">
          {{ model.formatTime(row.original.publishedAt) }}
        </template>
        <template #cell-operation="{ row }">
          <YdButton
            size="sm"
            variant="outline"
            :disabled="row.original.versionId === recommendedId"
            @click="confirmRollback(row.original)"
          >
            设为推荐
          </YdButton>
        </template>
        <template #card="{ row }">
          <YdCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.versionId }}</span>
                <YdTag :variant="row.versionId === recommendedId ? 'default' : 'secondary'">
                  {{ row.versionId === recommendedId ? '推荐' : (row.status || '-') }}
                </YdTag>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">Index Hash</span>
                  <code class="break-all">{{ row.indexHash || '-' }}</code>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">发布时间</span>
                  <span class="break-all">{{ model.formatTime(row.publishedAt) }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <YdButton
                  size="sm"
                  variant="outline"
                  :disabled="row.versionId === recommendedId"
                  @click="confirmRollback(row)"
                >
                  设为推荐
                </YdButton>
              </div>
            </div>
          </YdCard>
        </template>
      </YdResponsiveTable></YdLoadingOverlay>
      <YdCard v-else class="w-full">
        正在读取版本…
      </YdCard>
  </YdModal>
</template>
