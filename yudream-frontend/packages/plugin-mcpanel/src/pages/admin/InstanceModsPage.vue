<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaAlert, FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSelect, FaTable, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelExtra } from '../../api/api-extra.ts'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions.ts'
import { errorMessage, formatDateTime } from '../../composables/utils.ts'

/**
 * 模组/插件管理（对标 MCSM ModManager）：
 * Modrinth 搜索 → 解析版本 → install.run 下载到 plugins/ 或 mods/。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const extra = createMcPanelExtra(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const route = useRoute()
const router = useRouter()

const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const instanceId = computed(() => String(route.params.id ?? ''))
const instanceName = ref('')

const loading = ref(false)
const installing = ref('')
const error = ref('')
const keyword = ref('paper')
const projectType = ref<'mod' | 'plugin'>('plugin')
const gameVersion = ref('')
const loader = ref('paper')
const rows = ref<Array<Record<string, unknown>>>([])
const localRows = ref<Array<Record<string, unknown>>>([])
const pager = reactive({ page: 1, size: 12, total: 0 })

const columns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'title', header: '名称', minWidth: 220, fixed: 'left' },
  { accessorKey: 'description', header: '简介', minWidth: 260 },
  { accessorKey: 'author', header: '作者', width: 130 },
  { accessorKey: 'downloads', header: '下载', width: 100, align: 'center' },
  { id: 'operation', header: '操作', width: 110, align: 'center', fixed: 'right' },
]

async function search() {
  loading.value = true
  error.value = ''
  try {
    const result = await extra.searchModrinth(keyword.value.trim(), projectType.value, pager.page, pager.size) as {
      records?: Array<Record<string, unknown>>, total?: number
    }
    rows.value = result?.records ?? []
    pager.total = Number(result?.total ?? rows.value.length)
  }
  catch (e) {
    error.value = errorMessage(e, 'Modrinth 搜索失败')
    rows.value = []
  }
  finally {
    loading.value = false
  }
}

async function install(row: Record<string, unknown>) {
  if (!canManage.value || !instanceId.value || installing.value) {
    return
  }
  installing.value = String(row.id)
  try {
    const plan = await extra.resolveModrinth({
      projectId: row.id,
      gameVersion: gameVersion.value.trim() || undefined,
      loader: loader.value.trim() || undefined,
      projectType: projectType.value,
      targetDir: projectType.value === 'plugin' ? 'plugins' : 'mods',
    }) as { files?: Array<{ url: string, path: string }>, path?: string }
    if (!plan?.files?.length) {
      throw new Error('未解析到下载文件')
    }
    await extra.installInstanceFiles(instanceId.value, plan.files)
    toast.success(`已提交安装：${plan.path || plan.files[0]?.path}`)
  }
  catch (e) {
    toast.error(errorMessage(e, '安装失败'))
  }
  finally {
    installing.value = ''
  }
}

async function loadLocal() {
  try {
    const dir = projectType.value === 'plugin' ? 'plugins' : 'mods'
    const result = await extra.listFiles(instanceId.value, dir) as {
      entries?: Array<{ name: string, size?: number, modTime?: number }>
    }
    localRows.value = (result?.entries ?? [])
      .filter(item => String(item.name).toLowerCase().endsWith('.jar'))
      .map(item => ({
        name: item.name,
        size: item.size,
        modTime: item.modTime,
        enabled: true,
      }))
  }
  catch {
    localRows.value = []
  }
}

const localColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '本地文件', minWidth: 220, fixed: 'left' },
  { accessorKey: 'size', header: '大小', width: 110, align: 'center' },
  { accessorKey: 'modTime', header: '修改时间', width: 170 },
  { id: 'operation', header: '操作', width: 90, align: 'center', fixed: 'right' },
]

function confirmDeleteLocal(row: Record<string, unknown>) {
  modal.confirm({
    title: '删除本地文件',
    content: `确认删除「${row.name}」？删除后不可恢复。`,
    confirmButtonText: '删除',
    onConfirm: async () => {
      try {
        await extra.deleteFile(instanceId.value, projectType.value === 'plugin' ? `plugins/${row.name}` : `mods/${row.name}`)
        toast.success('已删除')
        void loadLocal()
      }
      catch (e) {
        toast.error(errorMessage(e, '删除失败'))
      }
    },
  })
}

async function loadInstanceName() {
  try {
    const detail = await extra.instanceDetail(instanceId.value) as { name?: string }
    instanceName.value = String(detail?.name ?? '')
  }
  catch {
    instanceName.value = ''
  }
}

onMounted(() => {
  void loadInstanceName()
  void search()
  void loadLocal()
})
</script>

<template>
  <FaPageHeader :title="`模组/插件 · ${instanceName || instanceId}`" description="Modrinth 检索并安装到实例 plugins/ 或 mods/">
    <FaButton variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/instances/${instanceId}`)">
      返回终端
    </FaButton>
    <FaButton variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/instances/${instanceId}/files`)">
      文件管理
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaAlert v-if="error" variant="destructive" title="提示" class="mb-4">
  <template #description>
      {{ error }}
  </template>
    </FaAlert>
    <FaCard title="搜索 Modrinth" description="可选类型/游戏版本/加载器；安装走节点文件通道">
      <div class="grid w-full gap-2 mb-3 sm:grid-cols-2 xl:grid-cols-[minmax(0,1fr)_170px_170px_170px_auto]">
        <FaInput v-model="keyword" class="w-full" placeholder="关键词" @keydown.enter="search" />
        <FaSelect
          v-model="projectType"
          :options="[{ label: '插件 plugin', value: 'plugin' }, { label: '模组 mod', value: 'mod' }]"
          class="w-full"
          @change="loadLocal"
        />
        <FaInput v-model="gameVersion" class="w-full" placeholder="MC 版本，如 1.21.4" />
        <FaInput v-model="loader" class="w-full" placeholder="加载器 paper/fabric" />
        <div class="flex items-center justify-end">
          <FaButton class="w-full xl:w-auto" @click="search">
            <FaIcon name="i-ri:search-line" />
            搜索
          </FaButton>
        </div>
      </div>

      <FaTable
        v-loading="loading"
        :columns="columns"
        :data="rows"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[900px]"
        border
        stripe
        empty-text="无结果"
      >
        <template #cell-title="{ row }">
          <div class="mcp-col" style="gap:2px;">
            <strong>{{ row.original.title }}</strong>
            <span class="mcp-muted" style="font-size:12px;">{{ row.original.slug }}</span>
          </div>
        </template>
        <template #cell-downloads="{ row }">
          {{ Number(row.original.downloads ?? 0).toLocaleString() }}
        </template>
        <template #cell-operation="{ row }">
          <FaButton
            size="sm"
            variant="outline"
            :disabled="!canManage || !instanceId"
            :loading="installing === String(row.original.id)"
            @click="install(row.original)"
          >
            安装
          </FaButton>
        </template>
      </FaTable>
      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="() => search()"
        @size-change="() => { pager.page = 1; search() }"
      />
    </FaCard>

    <FaCard :title="projectType === 'plugin' ? '本地插件目录 plugins/' : '本地模组目录 mods/'" class="mcp-page-gap">
      <FaTable
        :columns="localColumns"
        :data="localRows"
        row-key="name"
        table-root-class="rounded-lg overflow-hidden"
        border
        stripe
        empty-text="目录为空或不存在"
      >
        <template #cell-size="{ row }">
          {{ Number(row.original.size ?? 0) > 1024 * 1024
            ? `${(Number(row.original.size) / 1024 / 1024).toFixed(1)} MB`
            : `${Math.round(Number(row.original.size ?? 0) / 1024)} KB` }}
        </template>
        <template #cell-modTime="{ row }">
          {{ row.original.modTime ? formatDateTime(row.original.modTime as number) : '-' }}
        </template>
        <template #cell-operation="{ row }">
          <FaButton
            size="icon-sm"
            variant="destructive"
            :disabled="!canManage"
            title="删除"
            @click="confirmDeleteLocal(row.original)"
          >
            <FaIcon name="i-ri:delete-bin-line" />
          </FaButton>
        </template>
      </FaTable>
    </FaCard>
  </FaPageMain>
</template>
