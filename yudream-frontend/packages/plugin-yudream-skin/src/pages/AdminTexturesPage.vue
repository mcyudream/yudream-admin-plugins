<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions, SearchConfig, SearchFieldConfig, SearchOptionsSource, TableColumn, TableSortingState, ActionMenuItem } from '@yudream/components'
import type { SkinTexture } from '../types'
import type { SkinPluginModel } from '../composables/useSkinPlugin'
import { computed, reactive, ref, watch } from 'vue'
import { useYdModal, YdButton, YdDynamicSearchForm, YdFileUpload, YdIcon, YdInput, YdLabel, YdModal, YdPagination, YdRelatedEntity, YdSelect, YdSwitch, YdTag, YdResponsiveEnhancedTable, YdTablePageLayout } from '@yudream/components'
import SkinTexturePreview from '../components/SkinTexturePreview.vue'

const props = defineProps<{
  model: SkinPluginModel
}>()

const modal = useYdModal()
const uploadVisible = ref(false)
const editVisible = ref(false)
const textureFiles = ref<FileItem[]>([])
const editingTexture = ref<SkinTexture | null>(null)
const editForm = reactive({ name: '', publicAccess: true })
const search = reactive({
  keyword: '',
  type: 'all',
  visibility: 'all',
})
const pagination = reactive({ page: 1, size: 10 })

const typeOptions = [
  { label: '全部类型', value: 'all' },
  { label: 'Steve 皮肤', value: 'default' },
  { label: 'Alex 皮肤', value: 'slim' },
  { label: '披风', value: 'cape' },
]
const visibilityOptions = [
  { label: '全部权限', value: 'all' },
  { label: '公开材质', value: 'public' },
  { label: '私有材质', value: 'private' },
]
const uploadTypeOptions = [
  { label: 'Steve 皮肤', value: 'steve' },
  { label: 'Alex 皮肤', value: 'alex' },
  { label: '披风', value: 'cape' },
]

/** ControlType 是 components 内部的字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({ keyword: '', type: 'all', visibility: 'all' })

const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'keyword', label: '关键词', placeholder: '名称 / Hash / 上传人', controlType: 'TEXT', order: 1, showWhenFolded: true, width: '220px' }),
    searchField({ fieldName: 'type', label: '类型', controlType: 'SELECT', optionsSource: 'typeOptions', order: 2, showWhenFolded: true, width: '180px' }),
    searchField({ fieldName: 'visibility', label: '权限', controlType: 'SELECT', optionsSource: 'visibilityOptions', order: 3, showWhenFolded: true, width: '180px' }),
  ],
}

const optionsSources: SearchOptionsSource[] = [
  { id: 'typeOptions', loader: () => typeOptions },
  { id: 'visibilityOptions', loader: () => visibilityOptions },
]

function applySearch() {
  search.keyword = String(searchQuery.value.keyword ?? '')
  search.type = String(searchQuery.value.type ?? 'all')
  search.visibility = String(searchQuery.value.visibility ?? 'all')
  pagination.page = 1
}

/** 本地数据的手动排序：先排序再切片，保证排序作用于全部筛选结果 */
const sorting = ref<TableSortingState>([{ id: 'uploadedAt', desc: true }])

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

function sortRows(rows: SkinTexture[]) {
  const first = sorting.value[0]
  if (!first) return rows
  const sorted = [...rows].sort((a, b) => compareValues((a as unknown as Record<string, unknown>)[first.id], (b as unknown as Record<string, unknown>)[first.id]))
  return first.desc ? sorted.reverse() : sorted
}

const filteredRows = computed(() => {
  const keyword = search.keyword.trim().toLowerCase()
  return props.model.textures.filter((texture) => {
    if (search.type === 'cape' && texture.type !== 'cape') {
      return false
    }
    if (search.type === 'slim' && !(texture.type !== 'cape' && texture.model === 'slim')) {
      return false
    }
    if (search.type === 'default' && !(texture.type !== 'cape' && texture.model !== 'slim')) {
      return false
    }
    if (search.visibility === 'public' && !texture.publicAccess) {
      return false
    }
    if (search.visibility === 'private' && texture.publicAccess) {
      return false
    }
    if (!keyword) {
      return true
    }
    const text = [
      texture.name,
      texture.hash,
      textureKind(texture),
      texture.uploaderId,
      props.model.userName(texture.uploaderId),
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

const tableColumns = computed<TableColumn<SkinTexture>[]>(() => [
  { id: 'preview', header: '预览', width: 112, fixed: 'left', enableSorting: false },
  { accessorKey: 'name', header: '材质名称', width: 220, fixed: 'left' },
  { id: 'type', header: '类型', width: 116, enableSorting: false },
  { id: 'publicAccess', header: '权限', width: 92, align: 'center', enableSorting: false },
  { accessorKey: 'uploaderId', header: '上传人', width: 132 },
  { accessorKey: 'size', header: '大小', width: 96 },
  { accessorKey: 'uploadedAt', header: '上传时间', width: 152 },
  { id: 'hash', header: 'Hash', width: 190, enableSorting: false },
  ])

watch(totalPages, (pages) => {
  if (pagination.page > pages) {
    pagination.page = pages
  }
})

function openUpload() {
  Object.assign(props.model.textureForm, {
    name: '',
    type: 'steve',
    model: 'default',
    contentType: 'image/png',
    base64: '',
    publicAccess: true,
  })
  textureFiles.value = []
  uploadVisible.value = true
}

async function selectTextureFile(options: FileUploadRequestOptions) {
  if (options.file.type !== 'image/png' && !options.file.name.toLowerCase().endsWith('.png')) {
    throw new Error('请选择 PNG 文件')
  }
  await props.model.handleTextureFile(options.file)
  options.onProgress(100)
  return { selected: true }
}

async function submitUpload() {
  if (!props.model.textureForm.base64) {
    return
  }
  await props.model.uploadTexture()
  uploadVisible.value = false
  textureFiles.value = []
}

function confirmDelete(row: SkinTexture) {
  modal.confirm({
    title: '删除材质',
    content: `确认删除材质「${row.name}」吗？关联衣柜项会同步清理，角色绑定会置空。`,
    onConfirm: () => props.model.deleteAdminTexture(row.hash),
  })
}

function openEdit(row: SkinTexture) {
  editingTexture.value = row
  editForm.name = row.name
  editForm.publicAccess = Boolean(row.publicAccess)
  editVisible.value = true
}

async function saveEdit() {
  if (!editingTexture.value) return
  await props.model.updateAdminTexture(editingTexture.value.hash, { ...editForm })
  editVisible.value = false
  editingTexture.value = null
}

function onPageChange(page: number) {
  pagination.page = page
}

function onSizeChange(size: number) {
  pagination.size = size
  pagination.page = 1
}

function textureKind(texture: SkinTexture) {
  if (texture.type === 'cape') {
    return '披风'
  }
  return texture.model === 'slim' ? 'Alex 皮肤' : 'Steve 皮肤'
}

function shortHash(hash?: string) {
  if (!hash) {
    return '-'
  }
  return hash.length > 18 ? `${hash.slice(0, 12)}...${hash.slice(-8)}` : hash
}

function formatSize(size?: number) {
  if (!size) {
    return '-'
  }
  if (size < 1024) {
    return `${size} B`
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<SkinTexture>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => confirmDelete(row) },
]

</script>

<template>
  <YdTablePageLayout title="材质管理">
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
        <YdButton @click="openUpload">
          <YdIcon name="i-ri:upload-cloud-2-line" />
          上传材质
        </YdButton>
      </template>
      <template #table>
        <YdResponsiveEnhancedTable
          :actions="rowActions"
          row-key="hash"
          table-root-class="skin-admin-texture-table-root rounded-lg overflow-hidden"
          table-class="skin-admin-texture-table"
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
          <template #cell-preview="{ row }">
            <div class="skin-admin-texture-preview" aria-hidden="true">
              <SkinTexturePreview
                :texture-url="model.textureUrl(row.original.hash)"
                :type="row.original.type"
                :model="row.original.model"
              />
            </div>
          </template>
          <template #cell-name="{ row }">
            <span class="skin-admin-texture-name" :title="row.original.name">{{ row.original.name }}</span>
          </template>
          <template #cell-type="{ row }">
            {{ textureKind(row.original) }}
          </template>
          <template #cell-publicAccess="{ row }">
            <YdTag :variant="row.original.publicAccess ? 'default' : 'secondary'">
              {{ row.original.publicAccess ? '公开' : '私有' }}
            </YdTag>
          </template>
          <template #cell-uploaderId="{ row }">
            <YdRelatedEntity
              v-if="row.original.uploaderId"
              entity-type="user"
              :entity-id="row.original.uploaderId"
              :label="model.userName(row.original.uploaderId)"
            />
            <span v-else>-</span>
          </template>
          <template #cell-size="{ row }">
            {{ formatSize(row.original.size) }}
          </template>
          <template #cell-uploadedAt="{ row }">
            {{ model.dateText(row.original.uploadedAt) }}
          </template>
          <template #cell-hash="{ row }">
            <code class="skin-admin-code" :title="row.original.hash">{{ shortHash(row.original.hash) }}</code>
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



    <YdModal
      v-model="editVisible"
      title="编辑材质信息"
      description="名称和公开状态可修改；PNG 内容与类型保持不变，以避免破坏已绑定的角色和衣柜项。"
      show-cancel-button
      class="sm:max-w-xl"
      :confirm-loading="Boolean(editingTexture && model.saving === `admin-texture:${editingTexture.hash}`)"
      @confirm="saveEdit"
    >
      <div class="skin-admin-form">
        <YdLabel><span>名称</span><YdInput v-model="editForm.name" /></YdLabel>
        <YdLabel class="switch-row"><span>公开到皮肤库</span><YdSwitch v-model="editForm.publicAccess" /></YdLabel>
      </div>
    </YdModal>

    <YdModal
      v-model="uploadVisible"
      title="上传材质"
      description="上传 PNG 皮肤或披风，并设置全站可见性。"
      show-cancel-button
      class="sm:max-w-2xl"
      :confirm-loading="model.saving === 'texture'"
      @confirm="submitUpload"
    >
      <div class="skin-admin-form">
        <YdLabel>
          <span>名称</span>
          <YdInput v-model="model.textureForm.name" placeholder="例如：冬季外套 Steve" />
        </YdLabel>
        <YdLabel>
          <span>类型</span>
          <YdSelect v-model="model.textureForm.type" :options="uploadTypeOptions" />
        </YdLabel>
        <YdLabel>
          <span>PNG 文件</span>
          <YdFileUpload
            v-model="textureFiles"
            :max="1"
            :http-request="selectTextureFile"
            description="拖放或点击选择 PNG 材质"
          />
        </YdLabel>
        <YdLabel class="switch-row">
          <span>公开到皮肤库</span>
          <YdSwitch v-model="model.textureForm.publicAccess" />
        </YdLabel>
      </div>
    </YdModal>
</template>
