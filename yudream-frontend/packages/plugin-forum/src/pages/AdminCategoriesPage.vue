<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdButton, YdIcon, YdInput, YdModal, YdResponsiveEnhancedTable, YdTag, useYdModal, useYdToast, type ActionMenuItem, YdTablePageLayout } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createForumApi } from '../api/forum-api'
import type { Category } from '../api/forum-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createForumApi(props.sdk)
const confirm = useYdModal()
const toast = useYdToast()
const rows = ref<Category[]>([])
const editOpen = ref(false)
const editTarget = ref<Category | null>(null)
const editName = ref('')
const editSlug = ref('')
const editDescription = ref('')
const saving = ref(false)

const columns = [
  { id: 'name', header: '名称', accessorKey: 'name' },
  { id: 'slug', header: 'Slug', accessorKey: 'slug' },
  { id: 'moderation', header: '审核策略', accessorKey: 'moderation' },
  { id: 'viewPermission', header: '查看权限', accessorKey: 'viewPermission' },
  { id: 'postPermission', header: '发帖权限', accessorKey: 'postPermission' },
]

async function load() {
  try {
    rows.value = (await api.adminCategories()).records
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '分类加载失败')
  }
}

function openCreate() {
  editTarget.value = null
  editName.value = ''
  editSlug.value = ''
  editDescription.value = ''
  editOpen.value = true
}

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<Category>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => remove(row) },
]

function openEdit(category: Category) {
  editTarget.value = category
  editName.value = category.name
  editSlug.value = category.slug
  editDescription.value = category.description
  editOpen.value = true
}

async function save() {
  if (!editName.value.trim()) {
    toast.warning('请填写分类名称')
    return
  }
  if (saving.value) return
  saving.value = true
  try {
    if (editTarget.value) {
      await api.updateCategory(editTarget.value.id, {
        name: editName.value.trim(),
        slug: editSlug.value.trim(),
        description: editDescription.value.trim(),
      })
    }
    else {
      await api.createCategory({
        name: editName.value.trim(),
        slug: editSlug.value.trim(),
        description: editDescription.value.trim(),
        enabled: true,
        moderation: 'inherit',
      })
    }
    editOpen.value = false
    await load()
    toast.success('分类已保存')
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '分类保存失败')
  }
  finally {
    saving.value = false
  }
}

function remove(category: Category) {
  confirm.confirm({
    title: '删除分类',
    content: `确认删除分类「${category.name}」吗？分类下存在未归档帖子时，后端会拒绝删除。`,
    confirmButtonText: '删除',
    onConfirm: async () => {
      try {
        await api.deleteCategory(category.id)
        await load()
        toast.success('分类已删除')
      }
      catch (error) {
        toast.error(error instanceof Error ? error.message : '分类删除失败')
      }
    },
  })
}

onMounted(load)
</script>

<template>
  <div class="h-full min-h-0">
    <YdTablePageLayout title="分类管理" :show-search="false">
    <template #actions>
      <YdButton @click="openCreate"><YdIcon name="i-ri:add-line" />新增分类</YdButton>
    </template>
    <template #table>
<YdResponsiveEnhancedTable row-key="id" :columns="columns" :data="rows" border stripe column-visibility :actions="rowActions" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[920px]" empty-text="暂无分类">
        <template #cell-moderation="{ row }"><YdTag variant="outline">{{ row.original.moderation }}</YdTag></template>
      </YdResponsiveEnhancedTable>
    </template>
  </YdTablePageLayout>

      <YdModal v-model="editOpen" :title="editTarget ? '编辑分类' : '新增分类'" :confirm-button-loading="saving" @confirm="save">
        <div class="forum-editor">
          <label>分类名称<YdInput v-model="editName" placeholder="例如：服务器技术" maxlength="40" /></label>
          <label>Slug<YdInput v-model="editSlug" placeholder="可选，留空由后端生成" maxlength="80" /></label>
          <label>分类说明<YdInput v-model="editDescription" placeholder="简要说明该板块的主题" maxlength="160" /></label>
          <p class="forum-form-hint">保存后会自动生成并登记该分类的查看权限和发帖权限。权限码不会因分类改名而改变。</p>
        </div>
      </YdModal>
  </div>
</template>
