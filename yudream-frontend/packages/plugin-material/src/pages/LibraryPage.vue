<script setup lang="ts">
import type { MaterialPluginModel } from '../composables/useMaterialPlugin'
import type { MaterialSummary } from '../types'
import { FaButton, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaSelect, FaTag, useFaModal } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import UploadMaterialModal from '../components/UploadMaterialModal.vue'
import ShareModal from '../components/ShareModal.vue'
import { formatSize, MATERIAL_TYPES, TYPE_ICONS, VISIBILITY_OPTIONS, visibilityLabel } from '../types'

const props = defineProps<{ model: MaterialPluginModel }>()
const model = props.model
const router = useRouter()
const confirm = useFaModal()

const uploadOpen = ref(false)
const saving = ref(false)

// 编辑基本信息（名称/分类/标签/可见范围）
const editOpen = ref(false)
const editTarget = ref<MaterialSummary | null>(null)
const editName = ref('')
const editCategoryId = ref('')
const editTags = ref('')
const editVisibility = ref('PRIVATE')

// 分享外链
const shareOpen = ref(false)
const shareTarget = ref<MaterialSummary | null>(null)

function openShare(row: MaterialSummary) {
  shareTarget.value = row
  shareOpen.value = true
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

const editCategoryOptions = computed(() => [
  { label: '未分类', value: '' },
  ...model.categories.map(category => ({ label: category.name, value: category.id })),
])

function typeIcon(row: MaterialSummary) {
  return TYPE_ICONS[row.type] || TYPE_ICONS.OTHER
}

function goDetail(row: MaterialSummary) {
  void router.push({ path: '/platform/plugins/material/detail', query: { id: row.id } })
}

async function submitUpload(payload: { fileId: string, filename: string, name: string, categoryId: string, tags: string[], visibility: string }) {
  saving.value = true
  try {
    await model.uploadMaterial(payload.fileId, payload.filename, payload.name, payload.categoryId, payload.tags, payload.visibility)
    uploadOpen.value = false
  }
  finally {
    saving.value = false
  }
}

function openEdit(row: MaterialSummary) {
  editTarget.value = row
  editName.value = row.name
  editCategoryId.value = row.categoryId || ''
  editTags.value = (row.tags || []).join(', ')
  editVisibility.value = row.visibility || 'PRIVATE'
  editOpen.value = true
}

async function submitEdit() {
  if (!editTarget.value) {
    return
  }
  saving.value = true
  try {
    const tags = editTags.value.split(/[,，]/).map(tag => tag.trim()).filter(Boolean)
    await model.editMaterial(editTarget.value.id, editName.value.trim(), editCategoryId.value, tags, editVisibility.value)
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
      <FaButton @click="uploadOpen = true"><FaIcon name="i-ri:upload-cloud-2-line" />上传物料</FaButton>
    </div>
  </FaPageHeader>
  <FaPageMain>
    <div class="material-library-layout">
      <aside class="material-sidebar">
        <div class="material-sidebar-section">
          <div class="material-sidebar-title">分类</div>
          <button
            class="material-nav-item"
            :class="{ 'is-active': model.libraryFilters.categoryId === '' }"
            @click="model.setCategory('')"
          >
            <FaIcon name="i-ri:apps-line" />
            <span>全部分类</span>
          </button>
          <button
            v-for="category in model.categories"
            :key="category.id"
            class="material-nav-item"
            :class="{ 'is-active': model.libraryFilters.categoryId === category.id }"
            @click="model.setCategory(category.id)"
          >
            <FaIcon name="i-ri:folder-line" />
            <span class="truncate">{{ category.name }}</span>
            <span class="material-nav-count">{{ category.materials }}</span>
          </button>
        </div>
        <div class="material-sidebar-divider" />
        <div class="material-sidebar-section">
          <div class="material-sidebar-title">标签云</div>
          <div v-if="model.tags.length" class="material-tag-cloud">
            <button
              v-for="tag in model.tags"
              :key="tag.name"
              class="material-tag-chip"
              :class="{ 'is-active': model.libraryFilters.tag === tag.name }"
              :title="`${tag.name}（${tag.count}）`"
              @click="model.toggleTag(tag.name)"
            >
              {{ tag.name }}
            </button>
          </div>
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
                <img v-if="model.covers[row.id]" :src="model.covers[row.id]" :alt="row.name" loading="lazy">
                <div v-else class="material-thumb-icon">
                  <FaIcon :name="typeIcon(row)" />
                  <span class="material-thumb-ext">.{{ row.ext || '?' }}</span>
                </div>
                <FaTag v-if="row.status === 'ARCHIVED'" variant="secondary" class="material-thumb-archived">已归档</FaTag>
                <div class="material-overlay">
                  <button class="material-overlay-btn" title="预览" @click.stop="goDetail(row)"><FaIcon name="i-ri:eye-line" /></button>
                  <button class="material-overlay-btn" title="下载" @click.stop="model.downloadMine(row)"><FaIcon name="i-ri:download-line" /></button>
                  <button v-if="model.isOwner(row)" class="material-overlay-btn" title="编辑" @click.stop="openEdit(row)"><FaIcon name="i-ri:edit-line" /></button>
                  <button v-if="model.isOwner(row)" class="material-overlay-btn" title="分享" @click.stop="openShare(row)"><FaIcon name="i-ri:share-forward-line" /></button>
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
          class="mt-3"
          @page-change="model.loadLibrary"
          @size-change="model.applyLibraryFilters"
        />
      </div>
    </div>
    <UploadMaterialModal v-model="uploadOpen" :sdk="model.sdk" :categories="model.categories" :saving="saving" @submit="submitUpload" />
    <ShareModal v-model="shareOpen" :material="shareTarget" :model="model" />
    <FaModal v-model="editOpen" title="编辑物料信息" :confirm-button-loading="saving" @confirm="submitEdit">
      <div class="flex flex-col gap-3">
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">名称</span>
          <FaInput v-model="editName" maxlength="120" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">分类</span>
          <FaSelect v-model="editCategoryId" :options="editCategoryOptions" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">标签（逗号分隔，最多 8 个）</span>
          <FaInput v-model="editTags" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="text-secondary-foreground/80">可见范围</span>
          <FaSelect v-model="editVisibility" :options="VISIBILITY_OPTIONS" />
          <span v-if="editVisibility === 'DEPT'" class="text-xs text-secondary-foreground/70">仅与您同属一个部门的成员可见（按您当前所在部门生效）</span>
        </label>
      </div>
    </FaModal>
  </FaPageMain>
</template>
