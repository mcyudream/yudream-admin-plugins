<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import {
  FaButton,
  FaIcon,
  FaInput,
  FaModal,
  FaPageHeader,
  FaPageMain,
  FaPagination,
  FaSearchBar,
  FaSelect,
  FaSwitch,
  FaTable,
  FaTag,
  FaTextarea,
  useFaModal,
  useFaToast,
} from '@yudream/components'
import { onMounted, reactive, ref } from 'vue'
import MarkdownEditor from '../components/MarkdownEditor.vue'
import {
  CONTENT_KIND_OPTIONS,
  createContentApi,
  type ContentKind,
  type ContentRecord,
} from '../api/content-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createContentApi(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()

const loading = ref(false)
const saving = ref(false)
const records = ref<ContentRecord[]>([])
const total = ref(0)
const editorOpen = ref(false)
const editorMode = ref<'create' | 'edit'>('create')

const pagination = reactive({ page: 1, size: 10 })
const filters = reactive<{ kind: '' | ContentKind }>({ kind: '' })

const kindSelectOptions = [{ label: '全部分类', value: '' }, ...CONTENT_KIND_OPTIONS.map(item => ({ ...item }))]

const form = reactive({
  id: '',
  kind: 'updates' as ContentKind,
  title: '',
  summary: '',
  body: '',
  coverUrl: '',
  url: '',
  meta: '',
  sort: 0,
  enabled: true,
})

const columns = ref<TableColumn<ContentRecord>[]>([
  { accessorKey: 'title', header: '标题', minWidth: 200 },
  { accessorKey: 'kind', header: '分类', width: 130 },
  { accessorKey: 'summary', header: '摘要', minWidth: 220 },
  { accessorKey: 'sort', header: '排序', width: 80 },
  { id: 'status', header: '状态', width: 90 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 160 },
  { id: 'operation', header: '操作', width: 210, fixed: 'right' },
])

const kindLabels: Record<string, string> = {
  updates: '更新公告',
  guides: '新手指引',
  online: '在线内容',
}

function formatTime(raw?: string): string {
  if (!raw) {
    return '-'
  }
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) {
    return raw
  }
  const pad = (input: number) => String(input).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

async function reload() {
  loading.value = true
  try {
    const res = await api.list({ kind: filters.kind || undefined, page: pagination.page, size: pagination.size })
    records.value = res.records ?? []
    total.value = res.total ?? 0
  }
  catch (error) {
    toast.error((error as Error).message || '加载失败')
  }
  finally {
    loading.value = false
  }
}

function applyFilters() {
  pagination.page = 1
  void reload()
}

function resetFilters() {
  filters.kind = ''
  applyFilters()
}

function openCreate() {
  editorMode.value = 'create'
  form.id = ''
  form.kind = 'updates'
  form.title = ''
  form.summary = ''
  form.body = ''
  form.coverUrl = ''
  form.url = ''
  form.meta = ''
  form.sort = 0
  form.enabled = true
  editorOpen.value = true
}

function openEdit(row: ContentRecord) {
  editorMode.value = 'edit'
  form.id = row.id
  form.kind = (row.kind as ContentKind) || 'updates'
  form.title = row.title || ''
  form.summary = row.summary || ''
  form.body = row.body || ''
  form.coverUrl = row.coverUrl || ''
  form.url = row.url || ''
  form.meta = row.meta || ''
  form.sort = typeof row.sort === 'number' ? row.sort : 0
  form.enabled = row.enabled !== false
  editorOpen.value = true
}

async function submitRecord() {
  if (!form.title.trim()) {
    toast.warning('请填写标题')
    return
  }
  saving.value = true
  try {
    await api.save({
      id: form.id || undefined,
      kind: form.kind,
      title: form.title.trim(),
      summary: form.summary.trim() || undefined,
      body: form.body || undefined,
      coverUrl: form.coverUrl.trim() || undefined,
      url: form.url.trim() || undefined,
      meta: form.meta.trim() || undefined,
      sort: Number(form.sort) || 0,
      enabled: form.enabled,
    })
    toast.success(editorMode.value === 'create' ? '内容已创建' : '内容已保存')
    editorOpen.value = false
    await reload()
  }
  catch (error) {
    toast.error((error as Error).message || '保存失败')
  }
  finally {
    saving.value = false
  }
}

function confirmDelete(row: ContentRecord) {
  confirm.confirm({
    title: '删除内容',
    content: `确定删除「${row.title}」吗？启动器将不再拉取到该条内容，此操作不可恢复。`,
    onConfirm: async () => {
      try {
        await api.remove(row.id)
        toast.success('已删除')
        const pageCount = Math.max(1, Math.ceil((total.value - 1) / pagination.size))
        if (pagination.page > pageCount) {
          pagination.page = pageCount
        }
        await reload()
      }
      catch (error) {
        toast.error((error as Error).message || '删除失败')
      }
    },
  })
}

async function toggleEnabled(row: ContentRecord) {
  try {
    await api.save({
      id: row.id,
      kind: row.kind,
      title: row.title,
      enabled: !(row.enabled !== false),
    })
    toast.success(row.enabled !== false ? '已停用' : '已启用')
    await reload()
  }
  catch (error) {
    toast.error((error as Error).message || '操作失败')
  }
}

onMounted(() => {
  void reload()
})
</script>

<template>
	<FaPageHeader title="内容分发" description="维护启动器拉取的更新公告、新手指引与在线内容，公开端点 /v1/content/{kind} 与 /v1/catalog">
		<FaButton type="primary" @click="openCreate">
			<FaIcon name="i-ri:add-line" />
			新建内容
		</FaButton>
	</FaPageHeader>
	<FaPageMain>
		<FaTable
			v-loading="loading"
			:columns="columns"
			:data="records"
			row-key="id"
			table-root-class="max-w-full overflow-x-auto rounded-lg overflow-hidden"
			table-class="min-w-[1080px]"
			border
			stripe
			column-visibility
		>
			<template #toolbar>
				<FaSearchBar class="w-full">
					<div class="ymcl-form-row ymcl-filter-bar">
						<FaSelect v-model="filters.kind" :options="kindSelectOptions" @update:model-value="applyFilters" />
						<FaButton variant="outline" @click="applyFilters">
							<FaIcon name="i-ri:search-line" />
							查询
						</FaButton>
						<FaButton variant="ghost" @click="resetFilters">
							重置
						</FaButton>
					</div>
				</FaSearchBar>
			</template>
			<template #empty>
				<div class="ymcl-empty">
					<span class="ymcl-muted">暂无内容。新建的更新公告会推送给启动器用户。</span>
					<FaButton type="primary" @click="openCreate">
						<FaIcon name="i-ri:add-line" />
						新建内容
					</FaButton>
				</div>
			</template>
			<template #cell-title="{ row }">
				<span class="ymcl-break">{{ row.original.title }}</span>
			</template>
			<template #cell-kind="{ row }">
				<FaTag :variant="row.original.kind === 'updates' ? 'default' : 'secondary'">
					{{ kindLabels[row.original.kind] || row.original.kind }}
				</FaTag>
			</template>
			<template #cell-summary="{ row }">
				<span class="ymcl-break">{{ row.original.summary || '-' }}</span>
			</template>
			<template #cell-status="{ row }">
				<FaTag :variant="row.original.enabled !== false ? 'default' : 'outline'">
					{{ row.original.enabled !== false ? '已启用' : '已停用' }}
				</FaTag>
			</template>
			<template #cell-updatedAt="{ row }">{{ formatTime(row.original.updatedAt) }}</template>
			<template #cell-operation="{ row }">
				<div class="ymcl-row-actions">
					<FaButton size="sm" variant="outline" @click="openEdit(row.original)">
						编辑
					</FaButton>
					<FaButton size="sm" variant="outline" @click="toggleEnabled(row.original)">
						{{ row.original.enabled !== false ? '停用' : '启用' }}
					</FaButton>
					<FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">
						删除
					</FaButton>
				</div>
			</template>
		</FaTable>
		<FaPagination
			v-model:page="pagination.page"
			v-model:size="pagination.size"
			:total="total"
			:sizes="[10, 20, 50]"
			class="mt-3"
			@page-change="reload"
			@size-change="applyFilters"
		/>
	</FaPageMain>

	<FaModal
		v-model="editorOpen"
		:title="editorMode === 'create' ? '新建内容' : '编辑内容'"
		width="720px"
		:show-confirm-button="false"
		show-cancel-button
	>
		<form class="ymcl-form" @submit.prevent>
			<div class="ymcl-form-row">
				<label class="ymcl-label">分类</label>
				<FaSelect v-model="form.kind" :options="CONTENT_KIND_OPTIONS.map(item => ({ ...item }))" />
			</div>
			<div class="ymcl-form-row">
				<label class="ymcl-label">标题（必填）</label>
				<FaInput v-model="form.title" placeholder="公告或指引标题" clearable />
			</div>
			<div class="ymcl-form-row">
				<label class="ymcl-label">摘要（弹窗通知预览，建议 300 字内）</label>
				<FaTextarea v-model="form.summary" :rows="3" placeholder="一句话说明这次内容的要点" />
			</div>
			<div class="ymcl-form-row">
				<label class="ymcl-label">正文（Markdown，公告弹窗展示）</label>
				<MarkdownEditor v-model="form.body" placeholder="支持标题、列表、表格与链接" />
			</div>
			<div class="ymcl-form-row">
				<label class="ymcl-label">跳转链接（可选，启动器「访问」按钮）</label>
				<FaInput v-model="form.url" placeholder="https://..." clearable />
			</div>
			<div class="ymcl-form-row">
				<label class="ymcl-label">封面图（可选）</label>
				<FaInput v-model="form.coverUrl" placeholder="https://..." clearable />
			</div>
			<div class="ymcl-form-row">
				<label class="ymcl-label">备注（可选，仅管理端可见）</label>
				<FaInput v-model="form.meta" clearable />
			</div>
			<div class="ymcl-form-row">
				<label class="ymcl-label">排序（数值越小越靠前）</label>
				<FaInput v-model="form.sort" type="number" />
			</div>
			<div class="ymcl-form-row">
				<label class="ymcl-label">启用（停用后启动器不再拉取）</label>
				<FaSwitch v-model="form.enabled" />
			</div>
			<div class="ymcl-form-row">
				<FaButton type="primary" :loading="saving" @click="submitRecord">
					保存
				</FaButton>
			</div>
		</form>
	</FaModal>
</template>
