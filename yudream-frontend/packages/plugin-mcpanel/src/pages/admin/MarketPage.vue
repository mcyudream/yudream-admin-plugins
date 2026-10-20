<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdAction, YdAlert, YdButton, YdCard, YdIcon, YdInput, YdPageHeader, YdPageMain, YdPagination, YdTag } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createMcPanelExtra } from '../../api/api-extra'
import { errorMessage } from '../../composables/utils'

/**
 * 应用市场（对标 MCSM /market）：模板卡片浏览 + 一键进入创建向导。
 * 模板来自管理员目录；核心包来自官方/镜像版本目录。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const extra = createMcPanelExtra(props.sdk)
const router = useRouter()

const loading = ref(false)
const error = ref('')
const keyword = ref('')
const templates = ref<Record<string, unknown>[]>([])
const cores = ref<Array<{ id: string, label: string, kind?: string }>>([])
const pager = reactive({ page: 1, size: 12, total: 0 })

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) {
    return templates.value
  }
  return templates.value.filter(item =>
    String(item.name ?? '').toLowerCase().includes(kw)
    || String(item.key ?? '').toLowerCase().includes(kw)
    || String(item.kind ?? '').toLowerCase().includes(kw))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [tpl, coreRes] = await Promise.all([
      extra.pageTemplates(pager.page, 50),
      extra.listCores().catch(() => ({ records: [] })),
    ]) as [
      { records?: Record<string, unknown>[], total?: number },
      { records?: Array<{ id: string, label: string, kind?: string }> },
    ]
    templates.value = tpl.records ?? []
    pager.total = Number(tpl.total ?? templates.value.length)
    cores.value = (coreRes.records ?? []).map(item => ({
      id: item.id || String(item.kind || ''),
      label: item.label || String(item.kind || ''),
      kind: item.kind,
    }))
  }
  catch (e) {
    error.value = errorMessage(e, '加载市场数据失败')
  }
  finally {
    loading.value = false
  }
}

function installTemplate(item: Record<string, unknown>) {
  void router.push({
    path: '/platform/plugins/mcpanel/admin/instances/create',
    query: {
      mode: 'template',
      templateKey: String(item.key ?? ''),
      kind: String(item.kind && item.kind !== 'server' ? item.kind : ''),
      mcVersion: String(item.mcVersion ?? ''),
    },
  })
}

function installCore(core: { id: string, label: string }) {
  void router.push({
    path: '/platform/plugins/mcpanel/admin/instances/create',
    query: {
      mode: 'core',
      coreKind: core.id,
    },
  })
}

function zipImport() {
  void router.push({
    path: '/platform/plugins/mcpanel/admin/instances/create',
    query: { mode: 'zip' },
  })
}

function quickStart() {
  void router.push('/platform/plugins/mcpanel/admin/quickstart')
}

onMounted(() => void load())
</script>

<template>
  <YdPageHeader title="应用市场" description="服务端模板与核心包一键创建（对标 MCSM 应用市场）">
    <YdButton variant="outline" @click="load">
      刷新
    </YdButton>
    <YdButton @click="zipImport">
      <YdIcon name="i-ri:file-zip-line" />
      ZIP 导入创建
    </YdButton>
    <YdButton variant="outline" @click="quickStart">
      快速开始包
    </YdButton>
    <YdButton variant="outline" @click="router.push('/platform/plugins/mcpanel/admin/instances/create')">
      手动向导
    </YdButton>
  </YdPageHeader>
  <YdLoadingOverlay :loading="loading"><YdPageMain >
    <YdAlert v-if="error" variant="destructive" title="无法加载" class="mb-4">
  <template #description>
      {{ error }}
  </template>
    </YdAlert>

    <YdCard title="核心包（自动下载）" description="选类型后进入创建向导，版本用选择器挑选">
      <div v-if="!cores.length" class="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
        暂无可自动下载的核心包
      </div>
      <div v-else class="grid gap-3 sm:grid-cols-[repeat(2,minmax(0,1fr))] xl:grid-cols-3">
        <YdAction
          v-for="core in cores"
          :key="core.id"
          type="button"
          class="group rounded-lg border bg-background p-4 text-left transition-colors hover:border-primary"
          @click="installCore(core)"
        >
          <div class="flex items-center justify-between gap-2">
            <span class="truncate text-sm font-medium">{{ core.label }}</span>
            <YdIcon name="i-ri:arrow-right-line" class="shrink-0 text-muted-foreground transition-colors group-hover:text-primary" />
          </div>
          <div class="mt-1 text-xs text-muted-foreground">
            FastMirror / 官方源自动安装
          </div>
        </YdAction>
      </div>
    </YdCard>

    <YdCard title="服务端模板" description="管理员维护的安装源与启动形态" class="mt-4">
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <YdInput v-model="keyword" clearable placeholder="搜索模板名称 / Key / 类型" class="w-[100%] sm:w-72" />
        <span class="ml-auto text-xs text-muted-foreground">共 {{ filtered.length }} 项</span>
      </div>
      <div v-if="!filtered.length" class="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
        暂无模板，可在「服务端模板」页维护
      </div>
      <div v-else class="grid gap-3 sm:grid-cols-[repeat(2,minmax(0,1fr))] xl:grid-cols-3">
        <article v-for="item in filtered" :key="String(item.key)" class="flex flex-col rounded-lg border bg-background p-4">
          <div class="truncate text-sm font-medium">
            {{ item.name || item.key }}
          </div>
          <div class="mt-1 text-xs text-muted-foreground">
            {{ item.mcVersion || '版本待选' }} · {{ item.kind === 'image' ? '镜像' : String(item.kind || '服务端') }}
          </div>
          <div class="mt-1 truncate font-mono text-xs text-muted-foreground" :title="String(item.image || '')">
            {{ item.image || '-' }}
          </div>
          <div class="mt-3 flex items-center gap-2">
            <YdTag v-if="item.mcVersion" variant="secondary">
              {{ item.mcVersion }}
            </YdTag>
            <YdTag variant="outline">
              {{ item.kind || '-' }}
            </YdTag>
            <YdButton size="sm" class="ml-auto" @click="installTemplate(item)">
              安装
            </YdButton>
          </div>
        </article>
      </div>
      <YdPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="() => load()"
        @size-change="() => { pager.page = 1; load() }"
      />
    </YdCard>
  </YdPageMain></YdLoadingOverlay>
</template>
