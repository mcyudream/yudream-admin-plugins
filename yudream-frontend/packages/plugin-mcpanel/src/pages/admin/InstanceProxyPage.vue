<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelExtra } from '../../api/api-extra.ts'
import ProxyGroupPanel from '../../components/ProxyGroupPanel.vue'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions.ts'
import { errorMessage, formatDateTime } from '../../composables/utils.ts'

/**
 * 代理与子服（独立路由页，从实例详情页拆出）：
 * - 代理实例：识别/纳管 velocity.toml · config.yml，把子服绑定到面板实例；
 * - 子服实例：展示所属代理与子服名，跳转代理侧调整绑定；
 * - 子服同步：软链接模式的同步范围、立即同步与解除。
 * 代理关系管理不属于实例主控制台的日常控制流，故不挤在详情页内。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const extra = createMcPanelExtra(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const route = useRoute()
const router = useRouter()

const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const instanceId = computed(() => String(route.params.id ?? ''))
const listPath = '/platform/plugins/mcpanel/admin/instances'
const detailPath = computed(() => `${listPath}/${instanceId.value}`)
const filesPath = computed(() => `${detailPath.value}/files`)

const instanceName = ref('')
const instanceKind = ref('')
const proxyInfo = ref<{
  proxyInstanceId?: string
  proxyName?: string
  serverName?: string
} | null>(null)
// 初始即 loading：首帧不渲染纳管面板，避免实例类型未就绪时先闪一次「非代理」提示。
const loading = ref(true)
const loadError = ref('')

/** 代理实例的「代理与子服」页路径（绑定/解绑都在代理侧完成）。 */
const proxyPagePath = computed(() => {
  const proxyId = proxyInfo.value?.proxyInstanceId
  return proxyId ? `${listPath}/${proxyId}/proxy` : ''
})

async function loadInstance() {
  const id = instanceId.value
  if (!id) {
    loadError.value = '缺少实例 ID'
    loading.value = false
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const detail = await extra.instanceDetail(id) as {
      name?: string
      kind?: string
      proxy?: { proxyInstanceId?: string, proxyName?: string, serverName?: string }
    }
    instanceName.value = String(detail?.name ?? '')
    instanceKind.value = String(detail?.kind ?? '')
    proxyInfo.value = detail?.proxy ?? null
  }
  catch (error) {
    loadError.value = errorMessage(error, '实例信息加载失败')
  }
  finally {
    loading.value = false
  }
}

// ---------- 子服同步（软链接 ← 源实例） ----------

const syncLink = ref<{
  sourceInstanceId?: string
  sourceName?: string
  paths?: string[]
  overrides?: string[]
  lastSyncAt?: number | string
} | null>(null)
const syncBusy = ref(false)

async function loadSyncLink() {
  try {
    const result = await extra.syncLink(instanceId.value) as { exists?: boolean } & NonNullable<typeof syncLink.value>
    syncLink.value = result && result.exists !== false ? result : null
  }
  catch {
    syncLink.value = null
  }
}

async function runSync() {
  if (syncBusy.value) {
    return
  }
  syncBusy.value = true
  try {
    const result = await extra.runSyncLink(instanceId.value) as { bytes?: number }
    toast.success(`同步完成（${((Number(result?.bytes ?? 0)) / 1024 / 1024).toFixed(1)} MB）；运行中的实例需重启加载新文件`)
    void loadSyncLink()
  }
  catch (error) {
    toast.error(errorMessage(error, '同步失败'))
  }
  finally {
    syncBusy.value = false
  }
}

function unlinkSync() {
  modal.confirm({
    title: '解除软链接',
    content: '解除后本实例文件保持现状，不再随源实例同步。',
    confirmButtonText: '解除',
    onConfirm: async () => {
      syncBusy.value = true
      try {
        await extra.deleteSyncLink(instanceId.value)
        toast.success('已解除软链接（文件保留）')
        syncLink.value = null
      }
      catch (error) {
        toast.error(errorMessage(error, '解除失败'))
      }
      finally {
        syncBusy.value = false
      }
    },
  })
}

async function bootstrap() {
  syncLink.value = null
  proxyInfo.value = null
  await loadInstance()
  void loadSyncLink()
}

onMounted(() => {
  void bootstrap()
})

watch(instanceId, (now, was) => {
  if (!was || now === was) {
    return
  }
  void bootstrap()
})
</script>

<template>
  <FaPageHeader
    :title="`代理与子服 · ${instanceName || instanceId}`"
    description="Velocity / Bungee 代理纳管、子服绑定与子服数据同步"
  >
    <FaButton variant="outline" @click="router.push(detailPath)">
      返回控制台
    </FaButton>
    <FaButton variant="outline" @click="router.push(filesPath)">
      文件管理
    </FaButton>
  </FaPageHeader>

  <FaPageMain v-loading="loading" class="flex flex-col gap-4">
    <div v-if="loadError" class="text-sm text-destructive">
      {{ loadError }}
    </div>

    <!-- 子服视角：本实例已被某个代理纳管 -->
    <FaCard
      v-if="proxyInfo?.proxyInstanceId"
      title="所属代理"
      description="本实例作为子服挂在代理下；改绑/解绑在代理实例的「代理与子服」页完成"
    >
      <div class="mcp-toolbar-row">
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-2 text-sm font-medium">
            <FaIcon name="i-ri:git-branch-line" class="text-primary" />
            {{ proxyInfo?.proxyName || proxyInfo?.proxyInstanceId }}
          </div>
          <p class="mt-1 text-xs text-muted-foreground">
            代理配置中的子服名：
            <code class="rounded bg-muted px-1 font-mono">{{ proxyInfo?.serverName || '-' }}</code>
            （即 servers 段的键名）
          </p>
        </div>
        <FaButton size="sm" variant="outline" @click="router.push(proxyPagePath)">
          打开代理侧绑定
        </FaButton>
      </div>
    </FaCard>

    <ProxyGroupPanel
      v-if="!loading"
      :key="instanceId"
      :sdk="sdk"
      :instance-id="instanceId"
      :instance-kind="instanceKind"
      :can-save="canManage"
    />

    <!-- 子服同步（软链接 ← 源实例） -->
    <div v-if="syncLink" class="rounded-xl border bg-card p-4">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-2 text-sm font-medium">
            <FaIcon name="i-ri:links-line" class="text-primary" />
            软链接 ← {{ syncLink.sourceName || syncLink.sourceInstanceId }}
            <FaTag variant="secondary">
              {{ (syncLink.paths ?? []).length }} 个路径{{ (syncLink.overrides ?? []).length ? ` · 跳过 ${(syncLink.overrides ?? []).length}` : '' }}
            </FaTag>
            <FaTag v-if="syncLink.lastSyncAt" variant="outline">
              上次同步 {{ formatDateTime(Number(syncLink.lastSyncAt)) }}
            </FaTag>
          </div>
          <p class="mt-1 truncate text-xs text-muted-foreground">
            同步范围：{{ (syncLink.paths ?? []).join('、') || '-' }}（世界数据不同步；同步后运行中的实例需重启加载）
          </p>
        </div>
        <div class="flex items-center gap-2">
          <FaButton v-if="canManage" size="sm" :loading="syncBusy" @click="runSync">
            <FaIcon name="i-ri:refresh-line" />
            立即同步
          </FaButton>
          <FaButton v-if="canManage" size="sm" variant="destructive" :disabled="syncBusy" @click="unlinkSync">
            解除
          </FaButton>
        </div>
      </div>
    </div>
  </FaPageMain>
</template>
