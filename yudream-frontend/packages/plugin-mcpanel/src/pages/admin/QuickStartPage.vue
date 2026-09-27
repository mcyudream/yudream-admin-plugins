<script setup lang="ts">
import type { TableColumn, YdTablePickerQuery, YdTablePickerResult } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McpNode } from '../../types'
import { FaAlert, FaButton, FaCard, FaInput, FaModal, FaPageHeader, FaPageMain, FaResponsiveTable, FaSelect, YdTablePicker, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createMcPanelApi } from '../../api/mcpanel-api'
import { createMcPanelExtra } from '../../api/api-extra'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'
import { errorMessage } from '../../composables/utils'

/**
 * 快速开始向导包（对标 MCSM QuickStart / AppPackages）：
 * 选包 → 选节点 → 改名称/资源 → 创建（核心自动下载 + Java 镜像）。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const extra = createMcPanelExtra(props.sdk)
const api = createMcPanelApi(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const router = useRouter()

const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const canDelete = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.delete))

const loading = ref(false)
const installing = ref(false)
const error = ref('')
const packages = ref<Array<Record<string, unknown>>>([])
const nodes = ref<McpNode[]>([])
const step = ref(0)
const selectedPkg = ref<Record<string, unknown> | null>(null)
const form = reactive({
  name: '',
  nodeId: '',
  nodeKeys: [] as string[],
  memoryMb: 2048,
  cpuMillis: 1000,
  diskMb: 10240,
})

watch(() => form.nodeKeys, (keys) => {
  form.nodeId = keys[0] ?? ''
}, { deep: true })
const catalogLoading = ref(false)
const catalogRows = ref<Record<string, unknown>[]>([])
const catalogPager = reactive({ page: 1, size: 10, total: 0 })
const formOpen = ref(false)
const catalogSubmitting = ref(false)
const catalogError = ref('')
const catalogForm = reactive({
  id: '',
  name: '',
  kind: 'paper',
  mcVersion: '1.21.4',
  javaImageId: 'java21',
  memoryMb: 2048,
  cpuMillis: 1000,
  note: '',
  autoDownloadCore: true,
})

const KIND_OPTIONS = [
  { label: 'Paper', value: 'paper' },
  { label: 'Purpur', value: 'purpur' },
  { label: 'Fabric', value: 'fabric' },
  { label: 'NeoForge', value: 'neoforge' },
  { label: '原版', value: 'vanilla' },
  { label: 'Velocity', value: 'velocity' },
  { label: 'Bungee', value: 'bungee' },
  { label: '通用', value: 'generic' },
]

const JAVA_OPTIONS = [
  { label: 'Java 8', value: 'java8' },
  { label: 'Java 11', value: 'java11' },
  { label: 'Java 17', value: 'java17' },
  { label: 'Java 21', value: 'java21' },
  { label: 'Java 25', value: 'java25' },
]

const nodeColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '节点', minWidth: 120 },
  { accessorKey: 'status', header: '状态', width: 90 },
]

const catalogColumns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '包名称', minWidth: 160 },
  { accessorKey: 'kind', header: '核心', width: 90 },
  { accessorKey: 'mcVersion', header: '版本', width: 100 },
  { accessorKey: 'javaImageId', header: 'Java', width: 90 },
  { accessorKey: 'memoryMb', header: '内存', width: 90 },
  { id: 'operation', header: '操作', width: 140 },
]

async function loadPackages() {
  loading.value = true
  error.value = ''
  try {
    const page = await extra.pageQuickPackages(1, 50) as { records?: Record<string, unknown>[] }
    packages.value = page.records ?? []
  }
  catch (e) {
    error.value = errorMessage(e, '加载快速开始包失败')
  }
  finally {
    loading.value = false
  }
}

async function loadNodes() {
  try {
    const page = await api.pageNodes(1, 100) as { records?: McpNode[] }
    nodes.value = (page.records ?? []).filter(n => n.status !== 'offline')
  }
  catch {
    nodes.value = []
  }
}

async function loadCatalog() {
  catalogLoading.value = true
  try {
    const page = await extra.pageQuickPackages(catalogPager.page, catalogPager.size) as {
      records?: Record<string, unknown>[], total?: number
    }
    catalogRows.value = page.records ?? []
    catalogPager.total = Number(page.total ?? 0)
  }
  finally {
    catalogLoading.value = false
  }
}

function pickPackage(pkg: Record<string, unknown>) {
  selectedPkg.value = pkg
  form.name = String(pkg.name ?? '实例')
  form.memoryMb = Number(pkg.memoryMb ?? 2048)
  form.cpuMillis = Number(pkg.cpuMillis ?? 1000)
  form.diskMb = Number(pkg.diskMb ?? 10240)
  step.value = 1
}

async function nodeFetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<Record<string, unknown>>> {
  const page = await api.pageNodes(query.page, query.size, query.keyword || undefined) as { records?: McpNode[], total?: number }
  nodes.value = page.records ?? []
  return {
    list: nodes.value.map(n => ({ id: n.id, name: n.name, status: n.status })),
    total: Number(page.total ?? nodes.value.length),
  }
}

async function install() {
  const pkg = selectedPkg.value
  if (!pkg || !form.nodeId) {
    toast.warning('请选择节点')
    return
  }
  installing.value = true
  try {
    const javaId = String(pkg.javaImageId || 'java21')
    const docker = await extra.dockerImageOptions() as {
      records?: Array<{ id: string, primaryImage?: string, tags?: string[] }>
    }
    const imageItem = (docker.records ?? []).find(item => item.id === javaId)
      || (docker.records ?? []).find(item => String(item.id).includes(javaId.replace('java', '')))
    const image = imageItem?.primaryImage || String((imageItem?.tags || [])[0] || '')
    const kind = String(pkg.kind || 'paper')
    const cores = await extra.listCores().catch(() => ({ records: [] })) as {
      records?: Array<{ id: string }>
    }
    const coreKind = (cores.records ?? []).some(c => c.id === kind) ? kind : 'paper'
    let resolve: { url?: string, fileName?: string } = {}
    if (pkg.autoDownloadCore !== false) {
      resolve = await extra.resolveCoreDownload(coreKind, String(pkg.mcVersion || '')) as {
        url?: string, fileName?: string
      }
    }
    const created = await extra.createInstance({
      id: `inst-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
      nodeId: form.nodeId,
      name: form.name.trim() || String(pkg.name),
      kind: coreKind,
      mcVersion: pkg.mcVersion || null,
      image,
      command: coreKind === 'velocity'
        ? ['java', '-Xms512M', '-Xmx1G', '-jar', 'velocity.jar']
        : ['java', `-Xms${Math.floor(form.memoryMb / 2)}M`, `-Xmx${form.memoryMb}M`, '-jar', 'server.jar', 'nogui'],
      env: {},
      memoryMb: Number(form.memoryMb),
      cpuMillis: Number(form.cpuMillis),
      diskMb: Number(form.diskMb),
      config: resolve.url
        ? {
            installType: 'download',
            installUrl: resolve.url,
            // 与启动命令 -jar 参数一致
            installFileName: coreKind === 'velocity' ? 'velocity.jar' : 'server.jar',
            autoDownloadCore: 'true',
            quickStartPackage: String(pkg.id || ''),
          }
        : { quickStartPackage: String(pkg.id || '') },
    }) as { id?: string }
    toast.success('快速开始：实例已创建')
    void router.push(`/platform/plugins/mcpanel/admin/instances/${encodeURIComponent(String(created?.id ?? ''))}`)
  }
  catch (e) {
    toast.error(errorMessage(e, '快速开始安装失败'))
  }
  finally {
    installing.value = false
  }
}

function openCatalogCreate() {
  catalogForm.id = ''
  catalogForm.name = ''
  catalogForm.kind = 'paper'
  catalogForm.mcVersion = '1.21.4'
  catalogForm.javaImageId = 'java21'
  catalogForm.memoryMb = 2048
  catalogForm.cpuMillis = 1000
  catalogForm.note = ''
  catalogForm.autoDownloadCore = true
  catalogError.value = ''
  formOpen.value = true
}

async function saveCatalog() {
  if (!catalogForm.name.trim()) {
    catalogError.value = '请填写包名称'
    return
  }
  catalogSubmitting.value = true
  try {
    await extra.saveQuickPackage({ ...catalogForm, memoryMb: Number(catalogForm.memoryMb), cpuMillis: Number(catalogForm.cpuMillis) })
    formOpen.value = false
    toast.success('快速开始包已保存')
    void loadPackages()
    void loadCatalog()
  }
  catch (e) {
    catalogError.value = errorMessage(e, '保存失败')
  }
  finally {
    catalogSubmitting.value = false
  }
}

function confirmCatalogDelete(row: Record<string, unknown>) {
  modal.confirm({
    title: '删除快速开始包',
    content: `确认删除「${row.name}」？`,
    confirmButtonText: '删除',
    onConfirm: async () => {
      try {
        await extra.deleteQuickPackage(String(row.id))
        toast.success('已删除')
        void loadPackages()
        void loadCatalog()
      }
      catch (e) {
        toast.error(errorMessage(e, '删除失败'))
      }
    },
  })
}

onMounted(() => {
  void loadPackages()
  void loadNodes()
})
</script>

<template>
  <FaPageHeader title="快速开始" description="向导包一键安装（对标 MCSM QuickStart / AppPackages）">
    <FaButton variant="outline" @click="router.push('/platform/plugins/mcpanel/admin/market')">
      应用市场
    </FaButton>
    <FaButton @click="router.push('/platform/plugins/mcpanel/admin/instances/create')">
      完整向导
    </FaButton>
  </FaPageHeader>
  <FaPageMain v-loading="loading">
    <FaAlert v-if="error" variant="destructive" class="mb-4">
  <template #description>
      {{ error }}
  </template>
    </FaAlert>

    <FaCard :title="step === 0 ? '① 选择安装包' : `① 已选：${String(selectedPkg?.name || '')}`">
      <div v-if="!packages.length" class="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
        暂无向导包，请在下方维护目录
      </div>
      <div v-else class="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        <button
          v-for="pkg in packages"
          :key="String(pkg.id)"
          type="button"
          class="rounded-lg border bg-background p-4 text-left transition-colors hover:border-primary"
          :class="selectedPkg?.id === pkg.id ? 'border-primary ring-1 ring-primary' : ''"
          @click="pickPackage(pkg)"
        >
          <div class="truncate text-sm font-medium">
            {{ pkg.name }}
          </div>
          <div class="mt-1 text-xs text-muted-foreground">
            {{ pkg.kind }} · {{ pkg.mcVersion || '-' }} · {{ pkg.javaImageId }}
          </div>
          <div class="mt-1 text-xs text-muted-foreground">
            {{ pkg.note || `${pkg.memoryMb}MB / ${pkg.cpuMillis}mCPU` }}
          </div>
        </button>
      </div>
    </FaCard>

    <FaCard v-if="selectedPkg" title="② 节点与资源" class="mt-4">
      <div class="grid gap-4">
        <div class="grid gap-2">
          <span class="text-sm font-medium">目标节点</span>
          <YdTablePicker
            v-model="form.nodeKeys"
            :columns="nodeColumns"
            :fetcher="nodeFetcher"
            row-key="id"
            label-key="name"
            :multiple="false"
            title="选择节点"
            placeholder="点击选择节点"
          />
        </div>
        <label class="grid gap-2">
          <span class="text-sm font-medium">实例名称</span>
          <FaInput v-model="form.name" class="w-full" />
        </label>
        <div class="grid gap-4 sm:grid-cols-3">
          <label class="grid gap-2">
            <span class="text-sm font-medium">内存 MB</span>
            <FaInput v-model="form.memoryMb" type="number" class="w-full" />
          </label>
          <label class="grid gap-2">
            <span class="text-sm font-medium">CPU mCPU</span>
            <FaInput v-model="form.cpuMillis" type="number" class="w-full" />
          </label>
          <label class="grid gap-2">
            <span class="text-sm font-medium">磁盘 MB</span>
            <FaInput v-model="form.diskMb" type="number" class="w-full" />
          </label>
        </div>
        <div class="flex items-center gap-2">
          <FaButton v-if="canManage" :loading="installing" :disabled="!form.nodeId" @click="install()">
            ③ 开始安装
          </FaButton>
          <FaButton variant="outline" @click="step = 0; selectedPkg = null">
            重新选包
          </FaButton>
        </div>
      </div>
    </FaCard>

    <FaCard title="快速开始包目录" description="管理员维护向导包；内置常见核心预设" class="mt-4">
      <div class="mb-3 flex items-center gap-2">
        <FaButton v-if="canManage" size="sm" @click="openCatalogCreate">
          新增包
        </FaButton>
        <FaButton size="sm" variant="outline" @click="loadCatalog">
          刷新目录
        </FaButton>
      </div>
      <FaResponsiveTable
        v-loading="catalogLoading"
        :columns="catalogColumns"
        :data="catalogRows"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[800px]"
        border
        stripe
        empty-text="暂无包，点击新增"
      >
        <template #cell-operation="{ row }">
          <FaButton v-if="canDelete" size="sm" variant="destructive" @click="confirmCatalogDelete(row.original)">
            删除
          </FaButton>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.name }}</span>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">核心</span>
                  <span class="break-all">{{ row.kind || '-' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">版本</span>
                  <span class="break-all">{{ row.mcVersion || '-' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">Java</span>
                  <span class="break-all">{{ row.javaImageId || '-' }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">内存</span>
                  <span class="break-all">{{ row.memoryMb ? `${row.memoryMb} MB` : '-' }}</span>
                </div>
              </div>
              <div v-if="canDelete" class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="destructive" @click="confirmCatalogDelete(row)">
                  删除
                </FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
    </FaCard>

    <FaModal
      v-model="formOpen"
      title="新增快速开始包"
      :show-cancel-button="true"
      confirm-button-text="保存"
      :confirm-button-loading="catalogSubmitting"
      class="max-w-[min(32rem,calc(100vw-2rem))]"
      :before-close="(action: 'confirm' | 'cancel' | 'close', done: () => void) => {
        if (action === 'confirm') {
          void saveCatalog().then(() => done())
        }
        else {
          done()
        }
      }"
    >
      <div class="grid gap-4">
        <label class="grid gap-2"><span class="text-sm font-medium">名称</span><FaInput v-model="catalogForm.name" class="w-full" /></label>
        <div class="grid gap-4 sm:grid-cols-2">
          <label class="grid gap-2"><span class="text-sm font-medium">核心</span><FaSelect v-model="catalogForm.kind" :options="KIND_OPTIONS" class="w-full" /></label>
          <label class="grid gap-2"><span class="text-sm font-medium">MC 版本</span><FaInput v-model="catalogForm.mcVersion" class="w-full" /></label>
          <label class="grid gap-2"><span class="text-sm font-medium">Java 镜像目录 ID</span><FaSelect v-model="catalogForm.javaImageId" :options="JAVA_OPTIONS" class="w-full" /></label>
          <label class="grid gap-2"><span class="text-sm font-medium">默认内存 MB</span><FaInput v-model="catalogForm.memoryMb" type="number" class="w-full" /></label>
        </div>
        <label class="grid gap-2"><span class="text-sm font-medium">说明</span><FaInput v-model="catalogForm.note" class="w-full" /></label>
        <FaAlert v-if="catalogError" variant="destructive">
  <template #description>
          {{ catalogError }}
  </template>
        </FaAlert>
      </div>
    </FaModal>
  </FaPageMain>
</template>
