<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { LauncherPack, LauncherPackVersion } from '../types'
import { FaButton, FaCard, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaTable, FaTag, useFaModal } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { useLauncherPlugin } from '../composables/useLauncherPlugin'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useLauncherPlugin(props.sdk)
const confirm = useFaModal()
const createOpen = ref(false)
const detailOpen = ref(false)

const columns: TableColumn<LauncherPack>[] = [
  { accessorKey: 'id', header: 'ID', width: 160, fixed: 'left' },
  { accessorKey: 'name', header: '名称', minWidth: 180 },
  { accessorKey: 'recommendedVersionId', header: '推荐版本', width: 140 },
  { accessorKey: 'retainedVersionIds', header: '保留版本', width: 110 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 180 },
  { id: 'operation', header: '操作', width: 160, align: 'center', fixed: 'right' },
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
  <FaPageHeader title="整合包管理" description="维护面向 YMCL 的 mrpack 整合包：创建条目、查看版本并回滚推荐版本。">
    <FaButton @click="openCreate">
      <FaIcon name="i-ri:add-line" />
      新建整合包
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaSearchBar class="w-full">
      <div class="launcher-filter-bar">
        <FaInput
          v-model="model.packKeyword"
          placeholder="搜索 ID、名称或描述"
          clearable
          @keydown.enter="applyFilters"
          @clear="applyFilters"
        />
        <FaButton variant="outline" @click="applyFilters">
          <FaIcon name="i-ri:search-line" />
          查询
        </FaButton>
      </div>
    </FaSearchBar>

    <FaTable
      v-loading="model.loading"
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
        <FaTag v-if="row.original.recommendedVersionId" variant="default">
          {{ row.original.recommendedVersionId }}
        </FaTag>
        <span v-else>-</span>
      </template>
      <template #cell-retainedVersionIds="{ row }">
        {{ row.original.retainedVersionIds?.length || 0 }}
      </template>
      <template #cell-updatedAt="{ row }">
        {{ model.formatTime(row.original.updatedAt) }}
      </template>
      <template #cell-operation="{ row }">
        <div class="launcher-actions">
          <FaButton size="sm" variant="outline" @click="openDetail(row.original)">
            版本
          </FaButton>
        </div>
      </template>
    </FaTable>
    <FaPagination
      v-model:page="model.packPager.page"
      v-model:size="model.packPager.size"
      :total="model.packPager.total"
      class="mt-3"
      @page-change="model.loadPacks"
      @size-change="model.applyPackFilters"
    />

    <FaModal v-model="createOpen" title="新建整合包" class="sm:max-w-xl" :show-confirm-button="false" show-cancel-button>
      <form class="launcher-form" @submit.prevent>
        <label>
          <span>整合包 ID</span>
          <FaInput v-model="model.packForm.packId" placeholder="稳定标识，例如 survival" />
        </label>
        <label>
          <span>名称</span>
          <FaInput v-model="model.packForm.name" placeholder="展示给启动器的名称" />
        </label>
        <label>
          <span>描述</span>
          <FaInput v-model="model.packForm.description" placeholder="可选" />
        </label>
        <label>
          <span>图标 URL</span>
          <FaInput v-model="model.packForm.icon" placeholder="可选" />
        </label>
      </form>
      <template #footer>
        <FaButton variant="outline" @click="createOpen = false">
          取消
        </FaButton>
        <FaButton :loading="model.saving" @click="savePack">
          创建
        </FaButton>
      </template>
    </FaModal>

    <FaModal v-model="detailOpen" title="版本历史" class="sm:max-w-4xl" :show-confirm-button="false" show-cancel-button>
      <FaTable
        v-if="model.packDetail"
        v-loading="model.loading"
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
          <FaTag :variant="row.original.versionId === recommendedId ? 'default' : 'secondary'">
            {{ row.original.versionId === recommendedId ? '推荐' : (row.original.status || '-') }}
          </FaTag>
        </template>
        <template #cell-indexHash="{ row }">
          <code>{{ row.original.indexHash || '-' }}</code>
        </template>
        <template #cell-publishedAt="{ row }">
          {{ model.formatTime(row.original.publishedAt) }}
        </template>
        <template #cell-operation="{ row }">
          <FaButton
            size="sm"
            variant="outline"
            :disabled="row.original.versionId === recommendedId"
            @click="confirmRollback(row.original)"
          >
            设为推荐
          </FaButton>
        </template>
      </FaTable>
      <FaCard v-else class="w-full">
        正在读取版本…
      </FaCard>
    </FaModal>
  </FaPageMain>
</template>
