<script setup lang="ts">
import type { DeptOption } from '../types'
import { FaSelect } from '@yudream/components'
import { computed } from 'vue'
import { VISIBILITY_OPTIONS } from '../types'

/**
 * 可见范围 + 仅部门可见时的部门选择，上传/导入/编辑弹窗共用。
 * 选项来源由调用方决定：用户端传自己加入的部门（/me/departments），
 * 管理端传全量部门树拍平选项（/admin/departments，label 带父级路径）。
 */
const props = withDefaults(defineProps<{
  deptOptions: DeptOption[]
  disabled?: boolean
}>(), { disabled: false })

const visibility = defineModel<string>('visibility', { default: 'PRIVATE' })
const deptIds = defineModel<string[]>('deptIds', { default: () => [] })

const deptSelectOptions = computed(() => props.deptOptions.map(dept => ({ label: dept.label, value: dept.id })))
</script>

<template>
  <div class="flex flex-col gap-1 text-sm">
    <span class="text-secondary-foreground/80">可见范围</span>
    <FaSelect v-model="visibility" :options="VISIBILITY_OPTIONS" :disabled="disabled" />
    <template v-if="visibility === 'DEPT'">
      <FaSelect
        v-model="deptIds"
        :options="deptSelectOptions"
        multiple
        :disabled="disabled"
        placeholder="选择可见部门（可多选）"
      />
      <span v-if="deptOptions.length" class="text-xs text-secondary-foreground/70">
        仅所选部门的成员可见；未选择部门将无法保存
      </span>
      <span v-else class="text-xs text-secondary-foreground/70">
        暂无可选部门，请先加入部门或联系管理员
      </span>
    </template>
    <span v-else-if="visibility === 'PUBLIC'" class="text-xs text-secondary-foreground/70">全站成员均可在物料库中查看、预览与下载</span>
    <span v-else class="text-xs text-secondary-foreground/70">仅您本人可见，后续可随时调整</span>
  </div>
</template>
