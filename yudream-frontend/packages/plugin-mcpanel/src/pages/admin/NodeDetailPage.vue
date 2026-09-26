<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { DescriptionItem } from '@yudream/components'
import type { McpEnrollCredential, McpNode, McpNodeContainerStat, McpNodeStateEvent } from '../../types'
import { FaAlert, FaButton, FaCard, FaDescriptions, FaIcon, FaPageHeader, FaPageMain, FaProgress, FaTable, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelApi } from '../../api/mcpanel-api'
import EnrollCredentialModal from '../../components/EnrollCredentialModal.vue'
import NodeFormModal from '../../components/NodeFormModal.vue'
import NodeStatusTag from '../../components/NodeStatusTag.vue'
import { useNodeEventsStream } from '../../composables/useNodeEventsStream'
import { errorMessage, formatDateTime, formatGib, formatMib, formatPercent, relativeSeenText, toNumberOr } from '../../composables/utils'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'

/**
 * 节点详情（列表路径 + /:id，plugin:mcpanel:view 进入）。SSE /events 的
 * envelope 按 payload.nodeId 严格绑定当前节点；「签发注册凭据」仅在
 * hasSecret=false（未注册）时可用——secret rotate 未实现，不提供轮换入口。
 */
const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: { params?: Record<string, string | string[]> }
}>()

const api = createMcPanelApi(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const router = useRouter()
const route = useRoute()

const canUse = computed(() => accountHasPermission(props.sdk.account, 'plugin:mcpanel:use'))
const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const canDelete = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.delete))

function firstParam(value: string | string[] | undefined): string {
  return Array.isArray(value) ? (value[0] ?? '') : (value ?? '')
}

const nodeId = computed(() => firstParam(route.params.id as string | string[] | undefined) || firstParam(props.route?.params?.id))
const listPath = computed(() => route.path.replace(/\/+$/, '').replace(/\/[^/]*$/, '') || '/')

const loading = ref(false)
const loadError = ref('')
const node = ref<McpNode | null>(null)

const liveStatus = ref('')
const liveReason = ref('')
const { state: streamState, latestStats, authFailed, start, stop } = useNodeEventsStream({
  onState: (event: McpNodeStateEvent) => {
    // 后端统一为 status 前曾用 state 字段，二者兼容读取
    const nextStatus = event.status ?? (event as { state?: string }).state
    if (nextStatus) {
      liveStatus.value = String(nextStatus)
    }
    liveReason.value = event.reason ?? ''
  },
})

const stats = computed(() => latestStats.value ?? node.value?.stats ?? null)
const displayStatus = computed(() => liveStatus.value || String(node.value?.status ?? ''))

const cpuPercent = ref(0)
const memPercent = ref(0)
const diskPercent = ref(0)

function clampPercent(value: number) {
  if (!Number.isFinite(value)) {
    return 0
  }
  return Math.min(100, Math.max(0, Math.round(value)))
}

watch(stats, (value) => {
  cpuPercent.value = clampPercent(toNumberOr(value?.cpuPercent))
  memPercent.value = value?.memTotalMb ? clampPercent((toNumberOr(value.memUsedMb) / toNumberOr(value.memTotalMb)) * 100) : 0
  diskPercent.value = value?.diskTotalGb ? clampPercent((toNumberOr(value.diskUsedGb) / toNumberOr(value.diskTotalGb)) * 100) : 0
}, { immediate: true })

const STREAM_STATE_TEXT: Record<string, string> = {
  connecting: '实时流连接中…',
  open: '实时推送中',
  error: '实时流中断，正在自动重连',
  closed: '实时流未连接',
}

const streamText = computed(() => {
  if (streamState.value === 'error' && authFailed.value) {
    return '实时流鉴权失败，请重新登录后刷新'
  }
  return STREAM_STATE_TEXT[streamState.value] || STREAM_STATE_TEXT.closed
})

const containerColumns: TableColumn<McpNodeContainerStat>[] = [
  { accessorKey: 'instanceId', header: '实例 ID', minWidth: 220 },
  { accessorKey: 'state', header: '容器状态', width: 130 },
  { accessorKey: 'cpuPercent', header: 'CPU', width: 100, align: 'center' },
  { accessorKey: 'memUsedMb', header: '内存', width: 130, align: 'center' },
]

const containers = computed(() => stats.value?.containers ?? [])

const descriptions = computed<DescriptionItem[]>(() => {
  const items: DescriptionItem[] = [
    { label: '控制信道地址', value: node.value?.endpoint || '-' },
    { label: 'TLS 校验', value: node.value?.tlsMode === 'pinned' ? '自签指纹钉住' : '标准证书校验（PKIX）' },
  ]
  if (node.value?.tlsMode === 'pinned') {
    items.push({ label: '预批准指纹', value: node.value?.pinSha256 || '-' })
  }
  if (node.value?.reportedCertSha256) {
    items.push({ label: '上报证书指纹', value: node.value.reportedCertSha256 })
  }
  items.push(
    { label: '本地开发模式', value: node.value?.localDevelopment ? '开启（仅放宽 loopback）' : '关闭' },
    { label: '启用状态', value: node.value?.enabled === false ? '已停用' : '已启用' },
    { label: '控制信道', value: node.value?.connected ? '已连接' : '未连接' },
    { label: '持有密钥', value: node.value?.hasSecret ? '是' : '否' },
    { label: '上报地址', value: node.value?.reportedHost || '-' },
    { label: '会话', value: node.value?.sessionId || '-' },
    { label: '能力位', value: node.value?.caps?.length ? node.value.caps.join('、') : '-' },
    { label: 'Agent 版本', value: node.value?.agentVersion || '-' },
    { label: 'Docker 版本', value: stats.value?.dockerVersion || node.value?.dockerVersion || '-' },
    { label: '创建时间', value: formatDateTime(node.value?.createdAt) },
    { label: '更新时间', value: formatDateTime(node.value?.updatedAt) },
    { label: '最近心跳', value: relativeSeenText(node.value?.lastSeenAt) },
    { label: '备注', value: node.value?.remark || '-' },
  )
  return items
})

const formOpen = ref(false)
const credentialOpen = ref(false)
const credential = ref<McpEnrollCredential | null>(null)
const reconnecting = ref(false)
const issuing = ref(false)
const deleting = ref(false)

// 弹窗关闭即丢弃 token，不在父组件 ref 中残留临时秘密
watch(credentialOpen, (visible) => {
  if (!visible) {
    credential.value = null
  }
})

/** 请求代际：详情切换/重载后，过期的异步响应不得覆盖当前节点。 */
let loadToken = 0

async function load() {
  if (!nodeId.value) {
    loadError.value = '缺少节点 ID'
    stop()
    node.value = null
    return
  }
  const requestToken = ++loadToken
  loading.value = true
  loadError.value = ''
  try {
    const detail = await api.nodeDetail(nodeId.value)
    if (requestToken !== loadToken) {
      return
    }
    node.value = detail
    liveStatus.value = String(detail.status ?? '')
    start(api.nodeEventsUrl(nodeId.value), nodeId.value)
  }
  catch (error) {
    if (requestToken !== loadToken) {
      return
    }
    stop()
    node.value = null
    loadError.value = errorMessage(error, '加载节点详情失败，可能已被删除或无权访问')
  }
  finally {
    if (requestToken === loadToken) {
      loading.value = false
    }
  }
}

function goList() {
  stop()
  void router.push(listPath.value)
}

async function reconnect() {
  if (!node.value || reconnecting.value) {
    return
  }
  reconnecting.value = true
  try {
    const updated = await api.reconnectNode(node.value.id)
    if (updated?.status) {
      liveStatus.value = String(updated.status)
    }
    toast.success('已触发重连，请稍候观察状态变化')
  }
  catch (error) {
    toast.error(errorMessage(error, '触发重连失败'))
  }
  finally {
    reconnecting.value = false
  }
}

async function issueEnrollment() {
  if (!node.value || issuing.value) {
    return
  }
  issuing.value = true
  try {
    const issued = await api.issueEnrollment(node.value.id)
    if (issued?.token) {
      credential.value = issued
      credentialOpen.value = true
    }
    else {
      toast.warning('签发响应未包含 token，请稍后重试')
    }
  }
  catch (error) {
    toast.error(errorMessage(error, '签发注册凭据失败'))
  }
  finally {
    issuing.value = false
  }
}

function confirmDelete() {
  if (!node.value) {
    return
  }
  const target = node.value
  modal.confirm({
    title: '删除节点',
    content: `确认删除节点「${target.name}」吗？节点上的实例与端口分配将不可用，此操作不可恢复。`,
    confirmButtonText: '删除',
    onConfirm: async () => {
      deleting.value = true
      try {
        const result = await api.removeNode(target.id)
        if (result && result.deleted === false) {
          toast.warning('后端未确认删除，请稍后重试')
          return
        }
        toast.success(`节点「${target.name}」已删除`)
        goList()
      }
      catch (error) {
        toast.error(errorMessage(error, '删除节点失败'))
      }
      finally {
        deleting.value = false
      }
    },
  })
}

function onSaved() {
  toast.success('节点已保存')
  void load()
}

watch(nodeId, () => {
  // 换节点：先停旧流并清空实时数据与节点态，再拉新详情
  stop()
  node.value = null
  liveStatus.value = ''
  liveReason.value = ''
  void load()
})

onMounted(() => {
  void load()
})
</script>

<template>
  <FaPageHeader :title="node?.name || '节点详情'" class="mb-0">
    <FaButton variant="outline" @click="goList">
      <FaIcon name="i-ri:arrow-left-line" />
      返回列表
    </FaButton>
    <template v-if="node">
      <FaButton v-if="canUse && node.hasSecret" variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/nodes/${nodeId}/terminal`)">
        <FaIcon name="i-ri:terminal-line" />
        终端
      </FaButton>
      <FaButton v-if="canManage && node.hasSecret" variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/nodes/${nodeId}/files`)">
        <FaIcon name="i-ri:folder-line" />
        文件
      </FaButton>
      <FaButton v-if="canManage" variant="outline" :loading="reconnecting" @click="reconnect">
        <FaIcon name="i-ri:refresh-line" />
        重连
      </FaButton>
      <FaButton v-if="canManage && node.hasSecret === false" variant="outline" :loading="issuing" @click="issueEnrollment">
        <FaIcon name="i-ri:key-2-line" />
        签发注册凭据
      </FaButton>
      <FaButton v-if="canManage" variant="outline" @click="formOpen = true">
        <FaIcon name="i-ri:edit-line" />
        编辑
      </FaButton>
      <FaButton v-if="canDelete" variant="destructive" :loading="deleting" @click="confirmDelete">
        <FaIcon name="i-ri:delete-bin-line" />
        删除
      </FaButton>
    </template>
  </FaPageHeader>

  <FaAlert v-if="loadError" variant="destructive" title="无法加载节点" class="mt-4">
  <template #description>
    {{ loadError }}
  </template>
  </FaAlert>

  <FaPageMain v-else v-loading="loading">
    <div class="mcp-status-row">
      <NodeStatusTag :status="displayStatus" />
      <FaTag v-if="node?.enabled === false" variant="secondary">
        已停用
      </FaTag>
      <FaTag v-else-if="node?.connected" variant="secondary">
        控制信道已连接
      </FaTag>
      <span class="mcp-status-meta">最近心跳：{{ relativeSeenText(node?.lastSeenAt) }}</span>
      <FaTag v-if="liveReason" variant="secondary">{{ liveReason }}</FaTag>
      <span class="mcp-stream-state" :class="`is-${streamState}`">{{ streamText }}</span>
    </div>

    <FaAlert v-if="displayStatus === 'enrolling'" class="mt-4" title="节点尚未完成注册">
  <template #description>
      点击「签发注册凭据」获取一次性 token 并配置到节点；token 仅签发当次展示，遗失可重新签发。
  </template>
    </FaAlert>

    <FaCard title="节点信息" class="mt-4">
      <FaDescriptions :items="descriptions" :column="2" border />
    </FaCard>

    <FaCard title="实时资源（node.stats）" class="mt-4">
      <div v-if="stats" class="grid grid-cols-1 gap-4 md:grid-cols-3">
        <div class="mcp-stat">
          <div class="mcp-stat-head">
            <span>CPU</span>
            <strong>{{ formatPercent(cpuPercent) }}</strong>
          </div>
          <FaProgress v-model="cpuPercent" class="mt-2" />
        </div>
        <div class="mcp-stat">
          <div class="mcp-stat-head">
            <span>内存</span>
            <strong>{{ formatMib(stats.memUsedMb) }} / {{ formatMib(stats.memTotalMb) }}</strong>
          </div>
          <FaProgress v-model="memPercent" class="mt-2" />
        </div>
        <div class="mcp-stat">
          <div class="mcp-stat-head">
            <span>磁盘</span>
            <strong>{{ formatGib(stats.diskUsedGb) }} / {{ formatGib(stats.diskTotalGb) }}</strong>
          </div>
          <FaProgress v-model="diskPercent" class="mt-2" />
        </div>
      </div>
      <div v-else class="mcp-empty-hint">
        暂无 stats 快照。节点在线后每 5 秒推送一次；离线时仅显示最后快照。
      </div>
      <div v-if="stats" class="mcp-status-meta mt-2">
        快照时间：{{ relativeSeenText(stats.reportedAt) }}
      </div>

      <div class="mt-4">
        <div class="mcp-stat-head mb-2">
          <span>实例容器</span>
          <span class="mcp-status-meta">负载：{{ stats?.load ?? '-' }}</span>
        </div>
        <FaTable
          :columns="containerColumns"
          :data="containers"
          row-key="instanceId"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[580px]"
          border
          stripe
          empty-text="节点未上报运行中的实例容器"
        >
          <template #cell-cpuPercent="{ row }">
            {{ formatPercent(toNumberOr(row.original.cpuPercent)) }}
          </template>
          <template #cell-memUsedMb="{ row }">
            {{ formatMib(toNumberOr(row.original.memUsedMb)) }}
          </template>
        </FaTable>
      </div>
    </FaCard>

    <NodeFormModal v-model:open="formOpen" :api="api" :node="node" @saved="onSaved" />
    <EnrollCredentialModal v-model:open="credentialOpen" :credential="credential" />
  </FaPageMain>
</template>
