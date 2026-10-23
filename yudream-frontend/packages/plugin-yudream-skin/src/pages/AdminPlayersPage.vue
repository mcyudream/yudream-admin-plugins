<script setup lang="ts">
import type { ActionMenuItem, EntityPeekFact, SearchConfig, SearchFieldConfig, SearchOptionsSource, TableColumn, TableSortingState } from '@yudream/components'
import type { SkinPlayer } from '../types'
import type { SkinPluginModel } from '../composables/useSkinPlugin'
import { computed, reactive, ref, watch } from 'vue'
import { YdLabel, YdButton, YdDynamicSearchForm, YdEntityPeek, YdIcon, YdInput, YdModal, YdPagination, YdSelect, YdTag, useYdModal, YdResponsiveEnhancedTable, YdTablePageLayout } from '@yudream/components'

const props = defineProps<{
  model: SkinPluginModel
}>()

const modal = useYdModal()
const search = reactive({
  keyword: '',
  ownerId: '',
  status: 'all',
})
const pagination = reactive({ page: 1, size: 10 })
const formVisible = ref(false)
const editing = ref<SkinPlayer | null>(null)
const form = reactive({
  name: '',
  ownerId: '',
  skinHash: '',
  capeHash: '',
})

const statusOptions = [
  { label: '全部状态', value: 'all' },
  { label: '已配置外观', value: 'configured' },
  { label: '待换装', value: 'empty' },
]

/** ControlType 是 components 内部的字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({ keyword: '', ownerId: '', status: 'all' })

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'keyword', label: '关键词', placeholder: '角色名 / UUID / 材质 / 用户', controlType: 'TEXT', order: 1, showWhenFolded: true, width: '220px' }),
    searchField({ fieldName: 'ownerId', label: '系统用户', placeholder: '用户 ID / 昵称 / 用户名', controlType: 'TEXT', order: 2, showWhenFolded: true, width: '220px' }),
    searchField({ fieldName: 'status', label: '状态', controlType: 'SELECT', optionsSource: 'statusOptions', order: 3, showWhenFolded: true, width: '180px' }),
  ],
}

const optionsSources: SearchOptionsSource[] = [
  { id: 'statusOptions', loader: () => statusOptions },
]

function applySearch() {
  search.keyword = String(searchQuery.value.keyword ?? '')
  search.ownerId = String(searchQuery.value.ownerId ?? '')
  search.status = String(searchQuery.value.status ?? 'all')
  pagination.page = 1
}

/** 本地数据的手动排序：先排序再切片，保证排序作用于全部筛选结果 */
const sorting = ref<TableSortingState>([{ id: 'lastModified', desc: true }])

function changeSorting(next: TableSortingState) {
  sorting.value = next
  pagination.page = 1
}

function compareValues(left: unknown, right: unknown) {
  if (left == null && right == null) return 0
  if (left == null) return -1
  if (right == null) return 1
  const leftNumber = Number(left)
  const rightNumber = Number(right)
  if (String(left).trim() !== '' && String(right).trim() !== '' && Number.isFinite(leftNumber) && Number.isFinite(rightNumber)) {
    return leftNumber - rightNumber
  }
  return String(left).localeCompare(String(right), 'zh-CN')
}

function sortRows(rows: SkinPlayer[]) {
  const first = sorting.value[0]
  if (!first) return rows
  const sorted = [...rows].sort((a, b) => compareValues((a as unknown as Record<string, unknown>)[first.id], (b as unknown as Record<string, unknown>)[first.id]))
  return first.desc ? sorted.reverse() : sorted
}

const skinOptions = computed(() => props.model.textures
  .filter(texture => texture.type !== 'cape')
  .map(texture => ({ label: `${texture.name} (${shortHash(texture.hash)})`, value: texture.hash })))

const capeOptions = computed(() => props.model.textures
  .filter(texture => texture.type === 'cape')
  .map(texture => ({ label: `${texture.name} (${shortHash(texture.hash)})`, value: texture.hash })))

const filteredRows = computed(() => {
  const keyword = search.keyword.trim().toLowerCase()
  const ownerKeyword = search.ownerId.trim().toLowerCase()
  return props.model.players.filter((player) => {
    if (ownerKeyword && !ownerSearchText(player).toLowerCase().includes(ownerKeyword)) {
      return false
    }
    if (search.status === 'configured' && !player.skinHash && !player.capeHash) {
      return false
    }
    if (search.status === 'empty' && (player.skinHash || player.capeHash)) {
      return false
    }
    if (!keyword) {
      return true
    }
    const text = [
      player.name,
      player.uuid,
      player.ownerId,
      ownerSearchText(player),
      props.model.textureName(player.skinHash),
      props.model.textureName(player.capeHash),
    ].join(' ').toLowerCase()
    return text.includes(keyword)
  })
})

const pagedRows = computed(() => {
  const rows = sortRows(filteredRows.value)
  const start = (pagination.page - 1) * pagination.size
  return rows.slice(start, start + pagination.size)
})

const totalPages = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / pagination.size)))

const tableColumns = computed<TableColumn<SkinPlayer>[]>(() => [
  { accessorKey: 'name', header: '角色名', width: 150, fixed: 'left' },
  { accessorKey: 'ownerId', header: '系统用户', width: 220 },
  { id: 'skin', header: '皮肤', width: 180, enableSorting: false },
  { id: 'cape', header: '披风', width: 180, enableSorting: false },
  { id: 'status', header: '状态', width: 96, align: 'center', enableSorting: false },
  { accessorKey: 'lastModified', header: '更新时间', width: 150 },
  { id: 'uuid', header: 'UUID', width: 180, enableSorting: false },
  ])

watch(totalPages, (pages) => {
  if (pagination.page > pages) {
    pagination.page = pages
  }
})

function ownerText(player: SkinPlayer) {
  return player.ownerName || player.ownerUsername || player.ownerEmail || props.model.userName(player.ownerId)
}

/** 抽屉只用本页已持有的本地展示数据（用户名 / 邮箱），不拉取宿主实体 */
function ownerFacts(player: SkinPlayer): EntityPeekFact[] {
  const facts: EntityPeekFact[] = []
  if (player.ownerUsername) {
    facts.push({ label: '用户名', value: `@${player.ownerUsername}` })
  }
  if (player.ownerEmail) {
    facts.push({ label: '邮箱', value: player.ownerEmail })
  }
  return facts
}

function ownerSearchText(player: SkinPlayer) {
  return [
    player.ownerId,
    player.ownerName,
    player.ownerUsername,
    player.ownerEmail,
    props.model.userName(player.ownerId),
  ].filter(Boolean).join(' ')
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    name: '',
    ownerId: props.model.currentUserId || '',
    skinHash: '',
    capeHash: '',
  })
  formVisible.value = true
}

function openEdit(row: SkinPlayer) {
  editing.value = row
  Object.assign(form, {
    name: row.name,
    ownerId: row.ownerId,
    skinHash: row.skinHash || '',
    capeHash: row.capeHash || '',
  })
  formVisible.value = true
}

async function saveForm() {
  const name = form.name.trim()
  const ownerId = form.ownerId.trim()
  if (!name || !ownerId) {
    return
  }
  let targetName = name
  if (editing.value) {
    targetName = editing.value.name
    if (name !== editing.value.name) {
      const renamed = await props.model.renameAdminPlayer(editing.value.name, { name })
      targetName = renamed.name
    }
    if (form.skinHash !== (editing.value.skinHash || '') || form.capeHash !== (editing.value.capeHash || '')) {
      await props.model.assignAdminTextures(targetName, { skinHash: form.skinHash, capeHash: form.capeHash })
    }
  }
  else {
    const created = await props.model.createAdminPlayer({ name, ownerId })
    if (form.skinHash || form.capeHash) {
      await props.model.assignAdminTextures(created.name, { skinHash: form.skinHash, capeHash: form.capeHash })
    }
  }
  formVisible.value = false
}

function confirmDelete(row: SkinPlayer) {
  modal.confirm({
    title: '删除角色',
    content: `确认删除角色「${row.name}」吗？`,
    onConfirm: () => props.model.deleteAdminPlayer(row.name),
  })
}

function onPageChange(page: number) {
  pagination.page = page
}

function onSizeChange(size: number) {
  pagination.size = size
  pagination.page = 1
}

function shortHash(hash?: string) {
  if (!hash) {
    return '-'
  }
  return hash.length > 18 ? `${hash.slice(0, 10)}...${hash.slice(-6)}` : hash
}

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<SkinPlayer>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row) },
]

</script>

<template>
  <YdTablePageLayout title="角色管理">
      <template #search="{ fold }">
        <YdDynamicSearchForm
          v-model="searchQuery"
          :config="searchConfig"
          :fold="fold"
          :options-sources="optionsSources"
          @search="applySearch"
          @reset="applySearch"
        />
      </template>
      <template #actions>
        <YdButton @click="openCreate"><YdIcon name="i-ri:add-line" />新增角色</YdButton>
      </template>
      <template #table>
        <YdResponsiveEnhancedTable
          :actions="rowActions"
          row-key="uuid"
          table-root-class="skin-admin-player-table-root rounded-lg overflow-hidden"
          table-class="skin-admin-player-table"
          column-visibility
          border
          stripe
          sortable
          manual-sorting
          :sorting="sorting"
          @sorting-change="changeSorting"
          :columns="tableColumns"
          :data="pagedRows"
        >
          <template #cell-name="{ row }">
            <span class="skin-admin-texture-name" :title="row.original.name">{{ row.original.name }}</span>
          </template>
          <template #cell-ownerId="{ row }">
            <YdEntityPeek
              :title="ownerText(row.original)"
              :subtitle="`ID ${row.original.ownerId}`"
              :facts="ownerFacts(row.original)"
            />
          </template>
          <template #cell-skin="{ row }">
            <span class="skin-admin-texture-name" :title="model.textureName(row.original.skinHash)">{{ model.textureName(row.original.skinHash) }}</span>
          </template>
          <template #cell-cape="{ row }">
            <span class="skin-admin-texture-name" :title="model.textureName(row.original.capeHash)">{{ model.textureName(row.original.capeHash) }}</span>
          </template>
          <template #cell-status="{ row }">
            <YdTag :variant="row.original.skinHash || row.original.capeHash ? 'default' : 'secondary'">
              {{ row.original.skinHash || row.original.capeHash ? '已配置' : '待换装' }}
            </YdTag>
          </template>
          <template #cell-lastModified="{ row }">
            {{ model.dateText(row.original.lastModified) }}
          </template>
          <template #cell-uuid="{ row }">
            <code class="skin-admin-code" :title="row.original.uuid">{{ row.original.uuid }}</code>
          </template>
        </YdResponsiveEnhancedTable>
      </template>
    <template #footer>
      <YdPagination
        v-model:page="pagination.page"
        v-model:size="pagination.size"
        :total="filteredRows.length"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </template>
    </YdTablePageLayout>

    <YdModal v-model="formVisible" :title="editing ? '编辑角色' : '新增角色'" show-cancel-button class="sm:max-w-2xl" @confirm="saveForm">
      <div class="skin-admin-form">
        <YdLabel>
          <span>角色名</span>
          <YdInput v-model="form.name" class="w-full" />
        </YdLabel>
        <YdLabel>
          <span>系统用户 ID</span>
          <YdInput v-model="form.ownerId" :disabled="!!editing" class="w-full" />
        </YdLabel>
        <YdLabel>
          <span>皮肤</span>
          <YdSelect v-model="form.skinHash" clearable :options="skinOptions" class="w-full" />
        </YdLabel>
        <YdLabel>
          <span>披风</span>
          <YdSelect v-model="form.capeHash" clearable :options="capeOptions" class="w-full" />
        </YdLabel>
      </div>
    </YdModal>
</template>
