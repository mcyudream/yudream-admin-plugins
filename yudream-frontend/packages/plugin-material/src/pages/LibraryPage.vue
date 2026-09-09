<script setup lang="ts">
import type { MaterialPluginModel } from '../composables/useMaterialPlugin'
import type { MaterialSummary } from '../types'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSelect, FaTag, useFaModal } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import EditMaterialModal from '../components/EditMaterialModal.vue'
import ImportFolderModal from '../components/ImportFolderModal.vue'
import MaterialCover from '../components/MaterialCover.vue'
import UploadMaterialModal from '../components/UploadMaterialModal.vue'
import ShareModal from '../components/ShareModal.vue'
import { formatSize, MATERIAL_TYPES, TYPE_ICONS, visibilityLabel } from '../types'

const props = defineProps<{ model: MaterialPluginModel }>()
const model = props.model
const router = useRouter()
const confirm = useFaModal()

const uploadOpen = ref(false)
const importOpen = ref(false)
const saving = ref(false)

// 编辑基本信息（名称/分类/标签/可见范围）；代编他人物料走管理端接口与全量部门树
const editOpen = ref(false)
const editTarget = ref<MaterialSummary | null>(null)
const editAsAdmin = ref(false)
const editDeptOptions = computed(() => editAsAdmin.value ? model.adminDeptOptions : model.myDeptOptions)

// 分享外链；代分享他人物料走管理端接口
const shareOpen = ref(false)
const shareTarget = ref<MaterialSummary | null>(null)
const shareAsAdmin = computed(() => !!shareTarget.value && !model.isOwner(shareTarget.value))

function openShare(row: MaterialSummary) {
  shareTarget.value = row
  shareOpen.value = true
}

/** 卡片上的编辑/分享入口：属主或有管理权限者可用（删除仍仅属主，他人物料的删除在管理页） */
function canOperate(row: MaterialSummary) {
  return model.isOwner(row) || model.hasManage
}

const statusOptions = [
  { label: '默认（隐藏已归档）', value: '' },
  { label: '正常使用', value: 'ACTIVE' },
  { label: '已归档', value: 'ARCHIVED' },
]

const scopeOptions = [
  { label: '全部可见', value: '' },
  { label: '只看我的', value: 'mine' },
]

// 侧栏分类/标签变多（超过阈值）时提供本地搜索，列表本体分区滚动
const CATEGORY_SEARCH_THRESHOLD = 10
const TAG_SEARCH_THRESHOLD = 15
const categoryKeyword = ref('')
const tagKeyword = ref('')

const filteredCategories = computed(() => {
  const keyword = categoryKeyword.value.trim().toLowerCase()
  return keyword
    ? model.categories.filter(category => category.name.toLowerCase().includes(keyword))
    : model.categories
})

const filteredTags = computed(() => {
  const keyword = tagKeyword.value.trim().toLowerCase()
  return keyword
    ? model.tags.filter(tag => tag.name.toLowerCase().includes(keyword))
    : model.tags
})

function typeIcon(row: MaterialSummary) {
  return TYPE_ICONS[row.type] || TYPE_ICONS.OTHER
}

function goDetail(row: MaterialSummary) {
  void router.push({ path: '/platform/plugins/material/detail', query: { id: row.id } })
}

async function submitUpload(payload: { fileId: string, filename: string, name: string, categoryId: string, tags: string[], visibility: string, deptIds: string[] }) {
  saving.value = true
  try {
    await model.uploadMaterial(payload.fileId, payload.filename, payload.name, payload.categoryId, payload.tags, payload.visibility, payload.deptIds)
    uploadOpen.value = false
  }
  finally {
    saving.value = false
  }
}

function openEdit(row: MaterialSummary) {
  editTarget.value = row
  editAsAdmin.value = !model.isOwner(row)
  // 部门选项按提交路径加载：编自己的物料只能选自己加入的部门，代编他人可选全量部门树
  void (editAsAdmin.value ? model.loadAdminDepartments() : model.loadMyDepartments())
  editOpen.value = true
}

async function submitEdit(payload: { materialId: string, name: string, categoryId: string, tags: string[], visibility: string, deptIds: string[] }) {
  saving.value = true
  try {
    if (editAsAdmin.value) {
      await model.adminEditMaterial(payload.materialId, {
        name: payload.name,
        categoryId: payload.categoryId || null,
        tags: payload.tags,
        visibility: payload.visibility || undefined,
        deptIds: payload.deptIds,
      })
      await model.loadLibrary()
      void model.loadTags()
    }
    else {
      await model.editMaterial(payload.materialId, payload.name, payload.categoryId, payload.tags, payload.visibility, payload.deptIds)
    }
    editOpen.value = false
  }
  finally {
    saving.value = false
  }
}

function confirmDelete(row: MaterialSummary) {
  confirm.confirm({
    title: '删除物料',
    content: `确认删除「${row.name}」吗？全部 ${row.currentVersion} 个版本与文件都会删除，不可恢复。`,
    onConfirm: () => model.removeMaterial(row),
  })
}

onMounted(() => {
  void model.loadCategories()
  void model.loadTags()
  void model.loadLibrary()
  // 上传/导入弹窗的部门选择器数据源（仅自己加入的部门）
  void model.loadMyDepartments()
})
</script>

<template>
  <FaPageHeader title="物料库" description="统一管理图片、文档、设计稿、音视频等电子物料，支持在线预览与版本回溯">
    <div class="material-header-actions">
      <FaInput
        v-model="model.libraryFilters.keyword"
        placeholder="搜索名称或标签"
        clearable
        class="material-header-search"
        @keydown.enter="model.applyLibraryFilters"
        @clear="model.applyLibraryFilters"
      />
      <FaSelect v-model="model.libraryFilters.scope" :options="scopeOptions" class="material-header-type" @change="model.applyLibraryFilters" />
      <FaSelect v-model="model.libraryFilters.type" :options="MATERIAL_TYPES" class="material-header-type" @change="model.applyLibraryFilters" />
      <FaButton variant="outline" @click="importOpen = true"><FaIcon name="i-ri:folder-upload-line" />导入文件夹</FaButton>
      <FaButton @click="uploadOpen = true"><FaIcon name="i-ri:upload-cloud-2-line" />上传物料</FaButton>
    </div>
  </FaPageHeader>
  <FaPageMain>
    <div class="material-library-layout">
      <aside class="material-sidebar">
        <div class="material-sidebar-section">
          <div class="material-sidebar-title">分类</div>
          <FaInput
            v-if="model.categories.length > CATEGORY_SEARCH_THRESHOLD"
            v-model="categoryKeyword"
            placeholder="搜索分类"
            clearable
            class="material-sidebar-search"
          />
          <button
            class="material-nav-item"
            :class="{ 'is-active': model.libraryFilters.categoryId === '' }"
            @click="model.setCategory('')"
          >
            <FaIcon name="i-ri:apps-line" />
            <span>全部分类</span>
          </button>
          <div class="material-sidebar-list">
            <button
              v-for="category in filteredCategories"
              :key="category.id"
              class="material-nav-item"
              :class="{ 'is-active': model.libraryFilters.categoryId === category.id }"
              @click="model.setCategory(category.id)"
            >
              <FaIcon name="i-ri:folder-line" />
              <span class="truncate">{{ category.name }}</span>
              <span class="material-nav-count">{{ category.materials }}</span>
            </button>
            <div v-if="!filteredCategories.length" class="material-sidebar-empty">无匹配分类</div>
          </div>
        </div>
        <div class="material-sidebar-divider" />
        <div class="material-sidebar-section">
          <div class="material-sidebar-title">标签云</div>
          <FaInput
            v-if="model.tags.length > TAG_SEARCH_THRESHOLD"
            v-model="tagKeyword"
            placeholder="搜索标签"
            clearable
            class="material-sidebar-search"
          />
          <div v-if="filteredTags.length" class="material-tag-cloud">
            <button
              v-for="tag in filteredTags"
              :key="tag.name"
              class="material-tag-chip"
              :class="{ 'is-active': model.libraryFilters.tag === tag.name }"
              :title="`${tag.name}（${tag.count}）`"
              @click="model.toggleTag(tag.name)"
            >
              {{ tag.name }}
            </button>
          </div>
          <div v-else-if="model.tags.length" class="material-sidebar-empty">无匹配标签</div>
          <div v-else class="material-sidebar-empty">暂无标签</div>
        </div>
        <div class="material-sidebar-divider" />
        <div class="material-sidebar-section">
          <div class="material-sidebar-title">状态</div>
          <FaSelect v-model="model.libraryFilters.status" :options="statusOptions" @change="model.applyLibraryFilters" />
        </div>
      </aside>
      <div class="material-library-main">
        <div v-if="model.libraryFilters.tag" class="material-active-filter">
          <span>标签筛选：</span>
          <FaTag variant="secondary">
            {{ model.libraryFilters.tag }}
            <button class="material-tag-clear" title="清除标签筛选" @click="model.toggleTag(model.libraryFilters.tag)">
              <FaIcon name="i-ri:close-line" />
            </button>
          </FaTag>
        </div>
        <div v-loading="model.loading" class="material-grid-wrap">
          <div v-if="model.library.length" class="material-grid">
            <div v-for="row in model.library" :key="row.id" class="material-card">
              <div class="material-thumb" @click="goDetail(row)">
                <MaterialCover v-if="model.covers[row.id]" :src="model.covers[row.id]" :alt="row.name" />
                <div v-else class="material-thumb-icon">
                  <FaIcon :name="typeIcon(row)" />
                  <span class="material-thumb-ext">.{{ row.ext || '?' }}</span>
                </div>
                <FaTag v-if="row.status === 'ARCHIVED'" variant="secondary" class="material-thumb-archived">已归档</FaTag>
                <div class="material-overlay">
                  <button class="material-overlay-btn" title="预览" @click.stop="goDetail(row)"><FaIcon name="i-ri:eye-line" /></button>
                  <button class="material-overlay-btn" title="下载" @click.stop="model.downloadMine(row)"><FaIcon name="i-ri:download-line" /></button>
                  <button v-if="canOperate(row)" class="material-overlay-btn" title="编辑" @click.stop="openEdit(row)"><FaIcon name="i-ri:edit-line" /></button>
                  <button v-if="canOperate(row)" class="material-overlay-btn" title="分享" @click.stop="openShare(row)"><FaIcon name="i-ri:share-forward-line" /></button>
                  <button v-if="model.isOwner(row)" class="material-overlay-btn is-danger" title="删除" @click.stop="confirmDelete(row)"><FaIcon name="i-ri:delete-bin-line" /></button>
                </div>
              </div>
              <div class="material-card-body">
                <button class="material-link material-card-name" :title="row.name" @click="goDetail(row)">{{ row.name }}</button>
                <div class="material-card-meta">
                  <span>{{ row.typeLabel }}</span>
                  <span>{{ formatSize(row.size) }}</span>
                  <span>v{{ row.currentVersion }}</span>
                </div>
                <div class="material-card-meta">
                  <span v-if="!model.isOwner(row)" class="truncate">{{ row.ownerName || '其他成员' }}</span>
                  <FaTag variant="secondary" :title="row.visibility === 'DEPT' && (row.deptNames || []).length ? `可见部门：${(row.deptNames || []).join('、')}` : undefined">
                    {{ visibilityLabel(row.visibility) }}
                  </FaTag>
                </div>
              </div>
            </div>
          </div>
          <div v-else-if="!model.loading" class="material-empty">
            <FaIcon name="i-ri:folder-open-line" class="material-empty-icon" />
            <p>没有符合条件的物料</p>
            <FaButton variant="outline" @click="uploadOpen = true"><FaIcon name="i-ri:upload-cloud-2-line" />上传物料</FaButton>
          </div>
        </div>
        <FaPagination
          v-model:page="model.libraryPager.page"
          v-model:size="model.libraryPager.size"
          :total="model.libraryPager.total"
          :sizes="[12, 24, 48, 96]"
          class="mt-3"
          @page-change="model.loadLibrary"
          @size-change="model.applyLibraryFilters"
        />
      </div>
    </div>
    <UploadMaterialModal v-model="uploadOpen" :sdk="model.sdk" :categories="model.categories" :tags="model.tags" :dept-options="model.myDeptOptions" :saving="saving" @submit="submitUpload" />
    <ImportFolderModal v-model="importOpen" :sdk="model.sdk" :tags="model.tags" :dept-options="model.myDeptOptions" :submit="model.importFolder" />
    <ShareModal v-model="shareOpen" :material="shareTarget" :model="model" :admin="shareAsAdmin" />
    <EditMaterialModal
      v-model="editOpen"
      v-model:target="editTarget"
      :categories="model.categories"
      :tags="model.tags"
      :dept-options="editDeptOptions"
      :saving="saving"
      @submit="submitEdit"
    />
  </FaPageMain>
</template>
