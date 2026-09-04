<script setup lang="ts">
import type { CategoryView, DeptOption, MaterialSummary, TagView } from '../types'
import { FaInput, FaModal } from '@yudream/components'
import { ref, watch } from 'vue'
import CategoryPicker from './CategoryPicker.vue'
import TagPicker from './TagPicker.vue'
import VisibilityDeptField from './VisibilityDeptField.vue'

/**
 * 编辑物料元数据（名称/分类/标签/可见范围），LibraryPage 与 AdminPage 共用。
 * 提交走 /me 还是 /admin 由调用方按归属决定；部门选项数据源同样由调用方提供
 * （编自己的物料传我的部门，代编他人/管理页传全量部门树）。
 */
const props = defineProps<{
  categories: CategoryView[]
  tags: TagView[]
  deptOptions: DeptOption[]
  saving?: boolean
}>()
const open = defineModel<boolean>({ required: true })
const target = defineModel<MaterialSummary | null>('target', { default: null })
const emit = defineEmits<{
  submit: [{ materialId: string, name: string, categoryId: string, tags: string[], visibility: string, deptIds: string[] }]
}>()

const name = ref('')
const categoryId = ref('')
const tags = ref<string[]>([])
const visibility = ref('PRIVATE')
const deptIds = ref<string[]>([])

watch(open, (value) => {
  if (value && target.value) {
    name.value = target.value.name
    categoryId.value = target.value.categoryId || ''
    tags.value = [...(target.value.tags || [])]
    visibility.value = target.value.visibility || 'PRIVATE'
    deptIds.value = [...(target.value.deptIds || [])]
  }
})

function onConfirm() {
  if (!target.value) {
    return
  }
  emit('submit', {
    materialId: target.value.id,
    name: name.value.trim(),
    categoryId: categoryId.value,
    tags: tags.value,
    visibility: visibility.value,
    // 非 DEPT 时后端忽略部门选择，这里直接清空避免携带陈旧快照
    deptIds: visibility.value === 'DEPT' ? deptIds.value : [],
  })
}
</script>

<template>
  <FaModal v-model="open" title="编辑物料信息" :confirm-button-loading="saving" @confirm="onConfirm">
    <div class="flex flex-col gap-3">
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">名称</span>
        <FaInput v-model="name" maxlength="120" />
      </label>
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">分类</span>
        <CategoryPicker v-model="categoryId" :categories="props.categories" />
      </label>
      <label class="flex flex-col gap-1 text-sm">
        <span class="text-secondary-foreground/80">标签（最多 8 个）</span>
        <TagPicker v-model="tags" :tags="props.tags" />
      </label>
      <VisibilityDeptField v-model:visibility="visibility" v-model:dept-ids="deptIds" :dept-options="props.deptOptions" />
    </div>
  </FaModal>
</template>
