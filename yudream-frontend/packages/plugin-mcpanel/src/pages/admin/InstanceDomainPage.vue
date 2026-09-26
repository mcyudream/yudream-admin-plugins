<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaAlert, FaButton, FaCard, FaDescriptions, FaIcon, FaPageHeader, FaPageMain, FaSwitch, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelExtra } from '../../api/api-extra.ts'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions.ts'
import { errorMessage } from '../../composables/utils.ts'

/**
 * 实例域名（自动解析）：面板按面板设置里的云解析驱动（Cloudflare / 阿里云 / 腾讯云）
 * 为实例写入 <slug>.后缀 的 A 记录，并按需写 _minecraft._tcp 的 SRV 让玩家免端口直连。
 *
 * 分配是幂等的：换节点、换端口后点「重新同步」即可覆盖旧值；
 * 「校验解析」会回读云商记录并与期望值比对，用于排查解析未生效/被手工改动的情况。
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

interface DomainView {
  driver?: string
  driverLabel?: string
  available?: boolean
  reason?: string
  suffix?: string
  zone?: string
  zoneLabel?: string
  ttlSeconds?: number
  assigned?: boolean
  slug?: string
  suggestedSlug?: string
  fqdn?: string
  srvName?: string
  port?: number
  connectAddress?: string
  address?: string
  accessMode?: string
  accessLabel?: string
  nodeAccessMode?: string
  nodeAccessLabel?: string
  recordType?: string
  recordValue?: string
  blockReason?: string
  entryApiReady?: boolean
  entryHint?: string
}

interface ProxyProtocolView {
  supported?: boolean
  enabled?: boolean
  fileExists?: boolean
  path?: string
  key?: string
  reason?: string
  restartHint?: string
}

interface VerifyResult {
  fqdn?: string
  recordType?: string
  accessLabel?: string
  srvName?: string
  expectedAddress?: string
  expectedSrv?: string
  aValues?: string[]
  srvValues?: string[]
  aOk?: boolean
  srvOk?: boolean
  ok?: boolean
}

const loading = ref(false)
const busy = ref('')
const loadError = ref('')
const instanceName = ref('')
const view = ref<DomainView | null>(null)
const verified = ref<VerifyResult | null>(null)
const proxyProtocol = ref<ProxyProtocolView | null>(null)
const proxyBusy = ref(false)
/** 是否同时写 SRV（Java 版免端口直连；基岩版等 UDP 实例无意义）。 */
const withSrv = ref(true)

const basicItems = computed(() => {
  const data = view.value ?? {}
  return [
    { label: '驱动', value: data.available ? `${data.driverLabel || data.driver || '-'}` : '未启用' },
    { label: '区域', value: data.zone ? `${data.zone}（${data.zoneLabel || '-'}）` : '-' },
    { label: '游戏域后缀', value: data.suffix || '-' },
    { label: '生效 TTL', value: data.ttlSeconds ? `${data.ttlSeconds} 秒` : '-' },
    { label: '分配状态', value: data.assigned ? `已分配（${data.slug}）` : '未分配' },
    { label: '玩家接入方式', value: data.accessLabel || data.nodeAccessLabel || '节点直连' },
    { label: `${data.recordType || 'A'} 记录值`, value: data.recordValue || data.address || '-' },
    { label: 'SRV 目标端口', value: data.port ? String(data.port) : '-' },
  ]
})

async function load() {
  const id = instanceId.value
  if (!id) {
    loadError.value = '缺少实例 ID'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    view.value = await extra.instanceDomain(id) as DomainView
  }
  catch (error) {
    loadError.value = errorMessage(error, '域名状态加载失败')
  }
  finally {
    loading.value = false
  }
}

/** PROXY protocol 状态：入口模式配套（实例侧需允许接收入口转发的真实玩家 IP）。 */
async function loadProxyProtocol() {
  try {
    proxyProtocol.value = await extra.instanceProxyProtocol(instanceId.value) as ProxyProtocolView
  }
  catch {
    proxyProtocol.value = null
  }
}

async function toggleProxyProtocol(enabled: boolean) {
  if (proxyBusy.value) {
    return
  }
  proxyBusy.value = true
  try {
    const result = await extra.setInstanceProxyProtocol(instanceId.value, enabled) as ProxyProtocolView
    proxyProtocol.value = result
    toast.success(enabled
      ? '已开启 PROXY protocol：重启实例后生效（入口转发的玩家将显示真实 IP）'
      : '已关闭 PROXY protocol：重启实例后生效（恢复可直连）')
  }
  catch (error) {
    toast.error(errorMessage(error, '切换 PROXY protocol 失败'))
  }
  finally {
    proxyBusy.value = false
  }
}

async function loadInstanceName() {
  try {
    const detail = await extra.instanceDetail(instanceId.value) as { name?: string, kind?: string }
    instanceName.value = String(detail?.name ?? '')
    // 基岩版走 UDP，没有 SRV 语义：默认不勾选，避免校验时误报不一致。
    if (String(detail?.kind ?? '') === 'bedrock') {
      withSrv.value = false
    }
  }
  catch {
    instanceName.value = ''
  }
}

/** 分配/重新同步：幂等写入 A（+SRV），换节点或换端口后重复调用即可。 */
async function assign() {
  if (!canManage.value || busy.value) {
    return
  }
  busy.value = 'assign'
  try {
    const result = await extra.assignInstanceDomain(instanceId.value, withSrv.value) as {
      fqdn?: string, srv?: boolean, address?: string, recordType?: string, entryNote?: string, published?: boolean
    }
    toast.success(`已写入解析：${result?.fqdn} ${result?.recordType || ''} → ${result?.address}${result?.srv ? '（含 SRV）' : ''}；DNS 生效通常需数十秒`)
    if (result?.entryNote) {
      toast.info(String(result.entryNote))
    }
    verified.value = null
    await load()
  }
  catch (error) {
    toast.error(errorMessage(error, '域名分配失败'))
  }
  finally {
    busy.value = ''
  }
}

/** 实时校验：回读云商记录与期望值比对（会外呼云商）。 */
async function verify() {
  if (busy.value) {
    return
  }
  busy.value = 'verify'
  try {
    verified.value = await extra.verifyInstanceDomain(instanceId.value, withSrv.value) as VerifyResult
    if (verified.value?.ok) {
      toast.success('解析已生效')
    }
    else {
      toast.error('解析与期望值不一致，详见下方明细')
    }
  }
  catch (error) {
    toast.error(errorMessage(error, '校验失败'))
  }
  finally {
    busy.value = ''
  }
}

function release() {
  modal.confirm({
    title: '解除实例域名',
    content: `确认删除「${view.value?.fqdn || '该实例'}」的 A/SRV 记录？删除后玩家需改用 IP:端口 连接；面板不会再自动重建，除非重新分配。`,
    confirmButtonText: '解除',
    onConfirm: async () => {
      busy.value = 'release'
      try {
        await extra.releaseInstanceDomain(instanceId.value)
        toast.success('已删除解析记录')
        verified.value = null
        await load()
      }
      catch (error) {
        toast.error(errorMessage(error, '解除失败'))
      }
      finally {
        busy.value = ''
      }
    },
  })
}

async function copyAddress() {
  const text = view.value?.connectAddress || view.value?.fqdn || ''
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    toast.success(`已复制：${text}`)
  }
  catch {
    toast.error('复制失败，请手动选择地址复制')
  }
}

async function bootstrap() {
  view.value = null
  verified.value = null
  await Promise.all([loadInstanceName(), load()])
  void loadProxyProtocol()
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
    :title="`实例域名 · ${instanceName || instanceId}`"
    description="按面板配置的云解析自动写入 A / SRV 记录，玩家免端口直连"
  >
    <FaButton variant="outline" @click="router.push(detailPath)">
      返回控制台
    </FaButton>
    <FaButton variant="outline" @click="router.push(filesPath)">
      文件管理
    </FaButton>
  </FaPageHeader>

  <FaPageMain v-loading="loading" class="flex flex-col gap-4">
    <FaAlert v-if="loadError" variant="destructive" title="无法加载域名状态">
      <template #description>
        {{ loadError }}
      </template>
    </FaAlert>

    <FaAlert v-else-if="view && !view.available" variant="default" title="域名自动解析不可用">
      <template #description>
        {{ view.reason || '面板未配置云解析驱动。' }}
        <span>可由管理员在「面板设置 → 实例域名」选择驱动（Cloudflare / 阿里云 / 腾讯云）并填写凭据。</span>
      </template>
    </FaAlert>

    <FaCard
      v-else-if="view"
      title="自动解析"
      :description="view.assigned
        ? `已分配：${view.fqdn}`
        : `未分配；将使用 ${view.suggestedSlug || '-'}.${view.suffix || '-'}`"
    >
      <FaDescriptions :items="basicItems" :column="2" border />

      <FaAlert v-if="view.blockReason" variant="default" title="当前节点的接入方式无法写公网解析" class="mcp-page-gap">
        <template #description>
          {{ view.blockReason }}
          <span>如需公网域名，可在「节点管理 → 编辑节点」把玩家接入方式改为「自定义解析地址」或「FRP 入口」。</span>
        </template>
      </FaAlert>

      <div
        v-if="view.entryHint"
        class="mcp-page-gap rounded-xl border px-4 py-2 text-xs"
        :class="view.entryApiReady ? 'text-muted-foreground' : 'border-amber-500/40 bg-amber-500/5 text-amber-700 dark:text-amber-400'"
      >
        <FaIcon name="i-ri:git-branch-line" class="mr-1" />
        {{ view.entryHint }}
        <span v-if="view.entryApiReady">
          变更后可在「面板设置 → 单端口入口」点「立即对账」补齐；入口侧需开 PROXY protocol 才能让实例看到真实玩家 IP。
        </span>
      </div>

      <div
        v-if="view.accessMode === 'entry' && proxyProtocol?.supported"
        class="mcp-page-gap rounded-xl border bg-card p-4"
      >
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div class="min-w-0">
            <div class="flex flex-wrap items-center gap-2 text-sm font-medium">
              <FaIcon name="i-ri:shield-user-line" class="text-primary" />
              PROXY protocol（真实玩家 IP）
              <FaTag :variant="proxyProtocol.enabled ? 'default' : 'secondary'">
                {{ proxyProtocol.enabled ? '已开启' : '未开启' }}
              </FaTag>
            </div>
            <p class="mt-1 text-xs text-muted-foreground">
              经入口转发的连接必须让实例接收 PROXY 头，否则玩家 IP 会显示为入口机地址（封禁/日志/白名单都会失真）。
              <template v-if="proxyProtocol.path">
                写入 <code class="rounded bg-muted px-1 font-mono">{{ proxyProtocol.path }}</code> 的
                <code class="rounded bg-muted px-1 font-mono">{{ proxyProtocol.key }}</code>；
              </template>
              切换接入方式时面板会自动同步（入口模式开、其它模式关）。
            </p>
            <p v-if="proxyProtocol.reason" class="mt-1 text-xs text-amber-600 dark:text-amber-400">
              {{ proxyProtocol.reason }}
            </p>
          </div>
          <FaButton
            v-if="canManage"
            :variant="proxyProtocol.enabled ? 'outline' : 'default'"
            :loading="proxyBusy"
            :disabled="proxyBusy || !proxyProtocol.fileExists"
            @click="toggleProxyProtocol(!proxyProtocol.enabled)"
          >
            {{ proxyProtocol.enabled ? '关闭 PROXY protocol' : '一键开启' }}
          </FaButton>
        </div>
      </div>

      <div class="mcp-toolbar-row mcp-page-gap">
        <label class="flex items-center gap-2 text-sm">
          <FaSwitch v-model="withSrv" />
          <span>同时写 SRV（{{ view.srvName || '-' }}）</span>
        </label>
        <div class="mcp-spacer mcp-row">
          <FaButton v-if="canManage" variant="outline" :disabled="!!busy || !!view.blockReason" @click="verify">
            <FaIcon name="i-ri:shield-check-line" />
            校验解析
          </FaButton>
          <FaButton
            v-if="view.assigned"
            variant="outline"
            :disabled="!view.connectAddress"
            @click="copyAddress"
          >
            <FaIcon name="i-ri:file-copy-line" />
            复制连接地址
          </FaButton>
          <FaButton
            v-if="canManage"
            :loading="busy === 'assign'"
            :disabled="!!busy || !!view.blockReason"
            @click="assign"
          >
            <FaIcon name="i-ri:link" />
            {{ view.assigned ? '重新同步' : '分配域名' }}
          </FaButton>
          <FaButton
            v-if="canManage && view.assigned"
            variant="destructive"
            :disabled="!!busy"
            @click="release"
          >
            解除域名
          </FaButton>
        </div>
      </div>

      <p v-if="view.assigned" class="mt-3 text-sm text-muted-foreground">
        玩家连接地址：
        <code class="rounded bg-muted px-1 font-mono">{{ view.connectAddress }}</code>
        <span v-if="withSrv && view.port">（SRV 生效后也可用 {{ view.fqdn }}，无需带端口）</span>
      </p>
      <p v-else class="mt-3 text-sm text-muted-foreground">
        分配后玩家可用 <code class="rounded bg-muted px-1 font-mono">{{ view.fqdn }}</code>
        {{ view.port ? `（SRV 生效后免端口，否则为 ${view.fqdn}:${view.port}）` : '' }} 连接；解析生效通常需数十秒。
      </p>
    </FaCard>

    <FaCard v-if="verified" title="解析校验">
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <FaTag :variant="verified.ok ? 'default' : 'destructive'">
          {{ verified.ok ? '已生效' : '与期望值不一致' }}
        </FaTag>
        <span class="text-xs text-muted-foreground">
          实时回读云商记录（不依赖本地缓存）
        </span>
      </div>
      <FaDescriptions
        :column="2"
        border
        :items="[
          { label: `${verified.recordType || 'A'} 记录名`, value: verified.fqdn || '-' },
          { label: `${verified.recordType || 'A'} 记录期望值`, value: verified.expectedAddress || '-' },
          { label: `${verified.recordType || 'A'} 记录实际值`, value: (verified.aValues ?? []).join('、') || '（无记录）' },
          { label: `${verified.recordType || 'A'} 记录结论`, value: verified.aOk ? '一致' : '不一致' },
          { label: 'SRV 记录名', value: verified.srvName || '-' },
          { label: 'SRV 记录期望值', value: verified.expectedSrv || '-' },
          { label: 'SRV 记录实际值', value: (verified.srvValues ?? []).join('、') || '（无记录）' },
          { label: 'SRV 记录结论', value: verified.srvOk ? '一致' : '不一致或未启用' },
        ]"
      />
    </FaCard>
  </FaPageMain>
</template>
