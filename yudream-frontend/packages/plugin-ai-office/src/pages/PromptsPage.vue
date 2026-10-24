<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { OfficePrompt } from '../types'
import { YdButton, YdIcon, YdInput, YdLoadingOverlay, YdModal, YdResponsiveEnhancedTable, YdTablePageLayout, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createOfficeAdminApi, errorMessage } from '../api/office-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const toast = useYdToast()
const confirm = useYdModal()
const api = createOfficeAdminApi(props.sdk)

const loading = ref(false)
const saving = ref(false)
const prompts = ref<OfficePrompt[]>([])

const columns: TableColumn<OfficePrompt>[] = [
  { accessorKey: 'code', header: '编码', width: 220 },
  { accessorKey: 'name', header: '名称', width: 180 },
  { id: 'user', header: '正文摘要', minWidth: 400, enableSorting: false },
]

const rowActions: ActionMenuItem<OfficePrompt>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: row => openEdit(row) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => remove(row) },
]

onMounted(load)

async function load() {
  loading.value = true
  try {
    prompts.value = await api.prompts()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '提示词加载失败'))
  }
  finally {
    loading.value = false
  }
}

// ---------- 编辑 ----------

const modalVisible = ref(false)
const editing = ref<OfficePrompt | null>(null)
const form = ref({ id: '', code: '', name: '', system: '', user: '' })

function openCreate() {
  editing.value = null
  form.value = { id: '', code: '', name: '', system: '', user: '' }
  modalVisible.value = true
}

function openEdit(prompt: OfficePrompt) {
  editing.value = prompt
  form.value = { id: prompt.id, code: prompt.code, name: prompt.name, system: prompt.system, user: prompt.user }
  modalVisible.value = true
}

async function save() {
  if (!form.value.code.trim() || !form.value.name.trim() || !form.value.user.trim()) {
    toast.warning('请填写编码、名称与正文')
    return
  }
  saving.value = true
  try {
    const payload = { ...form.value, id: form.value.id || undefined }
    if (editing.value) {
      await api.updatePrompt(editing.value.id, payload)
    }
    else {
      await api.savePrompt(payload)
    }
    toast.success('已保存')
    modalVisible.value = false
    await load()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '保存失败'))
  }
  finally {
    saving.value = false
  }
}

function remove(prompt: OfficePrompt) {
  confirm.confirm({
    title: '删除提示词',
    content: `确认删除「${prompt.name}」吗？引用它的映射将无法起草。`,
    onConfirm: async () => {
      try {
        await api.deletePrompt(prompt.id)
        toast.success('已删除')
        await load()
      }
      catch (cause) {
        toast.error(errorMessage(cause, '删除失败'))
      }
    },
  })
}
</script>

<template>
  <YdTablePageLayout title="AI 提示词" :show-search="false" stats-title="引用方式">
    <template #stats>
      <div class="text-sm text-muted-foreground">
        提示词被文档类型映射以 <code>ai:提示词编码</code> 引用；user 正文用 <code>{点路径}</code> 引用上下文（如 <code>{target.title}</code>、<code>{finance.expenseTotal}</code>）。站点写作规范会在生成时自动并入 system。
      </div>
    </template>
    <template #actions>
      <YdButton @click="openCreate">
        <YdIcon name="i-ri:add-line" />新建提示词
      </YdButton>
    </template>
    <template #table>
      <YdLoadingOverlay :loading="loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="prompts"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[860px]"
          border
          stripe
          :actions="rowActions"
          empty-text="还没有提示词"
        >
          <template #cell-user="{ row }">
            <span class="line-clamp-2 text-xs" :title="row.original.user">{{ row.original.user }}</span>
          </template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="modalVisible"
    :title="editing ? '编辑提示词' : '新建提示词'"
    confirm-button-text="保存"
    :confirm-button-loading="saving"
    :show-cancel-button="!saving"
    cancel-button-text="取消"
    @confirm="save"
  >
    <div class="flex flex-col gap-3">
      <div class="grid grid-cols-[repeat(2,minmax(0,1fr))] gap-3">
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">编码</span>
          <YdInput v-model="form.code" :disabled="!!editing" placeholder="activity-summary-body" />
        </label>
        <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">名称</span>
          <YdInput v-model="form.name" placeholder="活动总结正文起草" />
        </label>
      </div>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">System（可空，站点写作规范会自动追加）</span>
        <YdTextarea v-model="form.system" :rows="3" />
      </label>
      <label class="flex flex-col gap-1 text-sm"><span class="text-secondary-foreground/80">User 正文（用 {target.title} 等点路径引用上下文）</span>
        <YdTextarea v-model="form.user" :rows="6" />
      </label>
    </div>
  </YdModal>
</template>
