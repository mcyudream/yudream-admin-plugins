<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DomainRecord } from '../types'
import { FaAlert, FaButton, FaIcon, FaInput, FaLabel, FaModal, FaPageHeader, FaPageMain, FaResponsiveTable, FaSwitch, useFaModal, useFaToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createEduVerifyApi } from '../api/edu-verify-api'
import { errorMessage, formatTime } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createEduVerifyApi(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()

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
  { accessorKey: 'domain', header: '域名', minWidth: 220, fixed: 'left' },
  { accessorKey: 'chineseName', header: '中文校名', minWidth: 160 },
  { accessorKey: 'englishName', header: '英文校名', minWidth: 240 },
  { id: 'enabled', header: '启用', width: 90 },
  { accessorKey: 'source', header: '来源', width: 120 },
  { accessorKey: 'createdAt', header: '创建时间', width: 180 },
  { id: 'operation', header: '操作', width: 180 },
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
  <section class="ev-page">
    <FaPageHeader title="邮箱域名" description="教育邮箱认证仅接受白名单中的高校域名。默认包含 edu.cn。">
      <FaButton @click="openCreate">
        <FaIcon name="i-ri:add-line" />
        新增域名
      </FaButton>
    </FaPageHeader>

    <FaPageMain>
      <FaAlert v-if="error" variant="destructive" title="加载失败" :description="error" />
      <FaResponsiveTable
        v-loading="loading"
        :columns="columns"
        :data="rows"
        row-key="domain"
        table-root-class="ev-table-scroll rounded-lg overflow-hidden"
        table-class="ev-table-w720"
        border
        stripe
        empty-text="还没有域名白名单"
      >
        <template #cell-enabled="{ row }">
          <FaSwitch :model-value="row.original.enabled" @update:model-value="value => toggle(row.original, Boolean(value))" />
        </template>
        <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
        <template #cell-operation="{ row }">
          <div class="ev-row-actions">
            <FaButton size="sm" variant="destructive" @click="remove(row.original)">删除</FaButton>
          </div>
        </template>
        <template #card="{ row }">
          <div class="ev-record-card">
            <div class="ev-record-head">
              <strong>{{ row.domain }}</strong>
              <FaSwitch :model-value="row.enabled" @update:model-value="value => toggle(row, Boolean(value))" />
            </div>
            <div class="ev-muted">{{ row.chineseName || '未设置中文校名' }}<span v-if="row.englishName"> · {{ row.englishName }}</span></div>
            <div class="ev-muted">{{ row.source || 'CUSTOM' }} · {{ formatTime(row.createdAt) }}</div>
            <div class="ev-actions ev-actions-end">
              <FaButton size="sm" variant="destructive" @click="remove(row)">删除</FaButton>
            </div>
          </div>
        </template>
      </FaResponsiveTable>
    </FaPageMain>

    <FaModal v-model="open" title="新增域名" :confirm-button-loading="saving" :before-close="handleSaveClose">
      <div class="ev-form">
        <FaLabel label="域名" class="ev-field">
          <FaInput v-model="domain" class="w-full" maxlength="80" placeholder="如 edu.cn 或 stu.pku.edu.cn" />
        </FaLabel>
        <FaLabel label="中文校名" class="ev-field">
          <FaInput v-model="chineseName" class="w-full" maxlength="80" placeholder="如：西南科技大学" />
        </FaLabel>
        <FaLabel label="英文校名" class="ev-field">
          <FaInput v-model="englishName" class="w-full" maxlength="160" placeholder="如：Southwest University of Science and Technology" />
        </FaLabel>
        <FaLabel label="来源" class="ev-field">
          <FaInput v-model="source" class="w-full" maxlength="40" placeholder="CUSTOM" />
        </FaLabel>
        <div class="ev-switch-row">
          <FaSwitch v-model="enabled" />
          <div class="ev-switch-copy">
            <strong>{{ enabled ? '立即启用' : '先保存为停用' }}</strong>
            <small>停用后该域名邮箱无法通过教育邮箱认证。</small>
          </div>
        </div>
      </div>
    </FaModal>
  </section>
</template>
