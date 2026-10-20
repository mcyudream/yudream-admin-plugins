<script setup lang="ts">
import type { ActionMenuItem, TableColumn, YdTablePageAction } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DomainRecord } from '../types'
import { YdAlert, YdButton, YdForm, YdFormItem, YdIcon, YdInput, YdLoadingOverlay, YdModal, YdResponsiveEnhancedTable, YdSwitch, YdTablePageLayout, useYdModal, useYdToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createEduVerifyApi } from '../api/edu-verify-api'
import { errorMessage, formatTime } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createEduVerifyApi(props.sdk)
const toast = useYdToast()
const confirm = useYdModal()

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const rows = ref<DomainRecord[]>([])
const open = ref(false)
const domain = ref('')
const chineseName = ref('')
const englishName = ref('')
const enabled = ref(true)
const source = ref('CUSTOM')

const columns: TableColumn<DomainRecord>[] = [
  { accessorKey: 'domain', header: '域名', minWidth: 220 },
  { accessorKey: 'chineseName', header: '中文校名', minWidth: 160 },
  { accessorKey: 'englishName', header: '英文校名', minWidth: 240, defaultHidden: true },
  { id: 'enabled', header: '启用', width: 90 },
  { accessorKey: 'source', header: '来源', width: 120 },
  { accessorKey: 'createdAt', header: '创建时间', width: 180, defaultHidden: true },
]

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<DomainRecord>[] = [
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: row => remove(row) },
]

const pageActions: YdTablePageAction[] = [
  { label: '新增域名', icon: 'morph:plus', onClick: openCreate },
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await api.domains()
  }
  catch (cause) {
    error.value = errorMessage(cause, '加载域名失败')
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  domain.value = ''
  chineseName.value = ''
  englishName.value = ''
  enabled.value = true
  source.value = 'CUSTOM'
  open.value = true
}

async function handleSaveClose(action: 'confirm' | 'cancel' | 'close', done: () => void) {
  if (action !== 'confirm') {
    if (!saving.value) {
      done()
    }
    return
  }
  const value = domain.value.trim().toLowerCase()
  if (!value) {
    toast.error('请填写域名')
    return
  }
  if (saving.value) {
    return
  }
  saving.value = true
  try {
    await api.saveDomain(value, chineseName.value.trim(), englishName.value.trim(), enabled.value, source.value.trim() || 'CUSTOM')
    toast.success('域名已保存')
    done()
    await load()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '保存失败'))
  }
  finally {
    saving.value = false
  }
}

async function toggle(row: DomainRecord, value: boolean) {
  try {
    await api.saveDomain(row.domain, row.chineseName || '', row.englishName || '', value, row.source)
    toast.success(value ? '已启用' : '已停用')
    await load()
  }
  catch (cause) {
    toast.error(errorMessage(cause, '更新失败'))
  }
}

function remove(row: DomainRecord) {
  confirm.confirm({
    title: '删除域名',
    content: `确认删除白名单域名「${row.domain}」吗？删除后该域邮箱将无法通过教育邮箱认证。`,
    onConfirm: async () => {
      try {
        await api.deleteDomain(row.domain)
        toast.success('已删除')
        await load()
      }
      catch (cause) {
        toast.error(errorMessage(cause, '删除失败'))
      }
    },
  })
}

onMounted(load)
</script>

<template>
  <YdTablePageLayout title="邮箱域名" :show-search="false" :actions="pageActions">
      <template #table>
        <YdAlert v-if="error" class="mb-3" variant="destructive" title="加载失败" :description="error" />
        <YdLoadingOverlay :loading="loading">
          <YdResponsiveEnhancedTable
            :columns="columns"
            :data="rows"
            row-key="domain"
            table-root-class="rounded-lg overflow-hidden"
            table-class="min-w-[720px]"
            border
            stripe
            column-visibility
            :actions="rowActions"
            empty-text="还没有域名白名单"
          >
            <template #cell-enabled="{ row }">
              <YdSwitch :model-value="row.original.enabled" @update:model-value="value => toggle(row.original, Boolean(value))" />
            </template>
            <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
          </YdResponsiveEnhancedTable>
        </YdLoadingOverlay>
      </template>
    </YdTablePageLayout>

    <YdModal v-model="open" title="新增域名" :confirm-button-loading="saving" :before-close="handleSaveClose">
      <YdForm :model="{ domain, chineseName, englishName, source }" layout="vertical">
        <div class="flex flex-col gap-4">
          <YdFormItem label="域名" required>
            <YdInput v-model="domain" maxlength="80" placeholder="如 edu.cn 或 stu.pku.edu.cn" />
          </YdFormItem>
          <YdFormItem label="中文校名">
            <YdInput v-model="chineseName" maxlength="80" placeholder="如：西南科技大学" />
          </YdFormItem>
          <YdFormItem label="英文校名">
            <YdInput v-model="englishName" maxlength="160" placeholder="如：Southwest University of Science and Technology" />
          </YdFormItem>
          <YdFormItem label="来源">
            <YdInput v-model="source" maxlength="40" placeholder="CUSTOM" />
          </YdFormItem>
          <YdFormItem label="启用状态">
            <div class="flex items-center gap-3">
              <YdSwitch v-model="enabled" />
              <div class="text-sm">
                <strong>{{ enabled ? '立即启用' : '先保存为停用' }}</strong>
                <p class="text-muted-foreground">停用后该域名邮箱无法通过教育邮箱认证。</p>
              </div>
            </div>
          </YdFormItem>
        </div>
      </YdForm>
    </YdModal>
</template>
