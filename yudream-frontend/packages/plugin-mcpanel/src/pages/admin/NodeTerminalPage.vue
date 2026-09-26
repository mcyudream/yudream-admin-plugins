<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaAlert, FaButton, FaIcon, FaPageHeader, FaPageMain, FaTag } from '@yudream/components'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelApi } from '../../api/mcpanel-api.ts'
import { createNodeOpsApi } from '../../api/node-ops-api.ts'
import FileBrowserPanel from '../../components/FileBrowserPanel.vue'
import FileEditorModal from '../../components/FileEditorModal.vue'
import TerminalConsole from '../../components/TerminalConsole.vue'
import { useTerminalConsole } from '../../composables/useTerminalConsole.ts'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions.ts'
import { errorMessage } from '../../composables/utils.ts'
import { unwrapStreamEvent } from '../../composables/sse-frames.ts'
import { createSseTransport } from '../../composables/sseTransport.ts'
import { withTimeout } from '../../utils/fileContent.ts'
import { createTerminalDecoder } from '../../utils/terminalDecode.ts'
import { createTextFileAccess } from '../../utils/textFileAccess.ts'
import { SHELL_CONSOLE_COMMANDS } from '../../utils/commandComplete.ts'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createNodeOpsApi(props.sdk)
const panel = createMcPanelApi(props.sdk)
const route = useRoute()
const router = useRouter()
const canUse = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.use))
const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const nodeId = computed(() => String(route.params.id ?? ''))
const nodeName = ref('')
const nodeStatus = ref('')
const terminalId = ref('')
const opening = ref(false)
const closing = ref(false)
const connected = ref(false)
const error = ref('')
const nodeRoot = ref('')
const editorOpen = ref(false)
const editorPath = ref('')
const editorDirectory = ref('')
const editorAccess = computed(() => createTextFileAccess({
  readChunk: (path, offset, length) => api.nodeFileReadChunk(nodeId.value, nodeRoot.value, path, offset, length),
  write: (path, content, charset) => api.nodeFileWrite(nodeId.value, nodeRoot.value, path, content, charset),
}))
let generation = 0
let opens = 0
let sessionNode = ''
let disposed = false
const termDecoder = createTerminalDecoder(() => term.encoding.value)
const term = useTerminalConsole({ mode: 'shell', builtins: () => SHELL_CONSOLE_COMMANDS, historyKey: () => `mcp-shell-${nodeId.value}`, onSubmit: send,
  systemCommands: word => fetchSystemCommands(word),
  onEncodingChanged(encoding) {
    termDecoder.reset()
    // 节点终端没有历史接口：编码切换只对后续输出诚实生效。
    term.appendDivider(`输出解码已切换为 ${encoding.toUpperCase()}，仅对后续输出生效`)
  },
})
/** 真实系统命令补全：节点 0.4.0+ 扫 PATH 可执行文件；旧节点/离线一律静默降级为空。 */
async function fetchSystemCommands(word: string): Promise<string[]> {
  if (!terminalId.value || !nodeId.value) return []
  try {
    const result = await withTimeout(api.terminalComplete(nodeId.value, word), 6_000, '补全查询超时') as { completions?: string[] }
    return Array.isArray(result.completions) ? result.completions.slice(0, 10) : []
  }
  catch { return [] }
}
const common = ['ls', 'pwd', 'df -h', 'free -h', 'docker ps', 'uptime']
const transport = createSseTransport({
  onOpen() {
    termDecoder.reset()
    connected.value = true
    error.value = ''
    term.appendSystem(opens++ ? '输出已重新连接；断线期间的输出无法补回。' : '节点终端已连接')
  },
  onFrame(frame) {
    const event = unwrapStreamEvent(frame, { events: ['node.terminal.output'], idKey: 'terminalId', expectedId: terminalId.value, nodeId: sessionNode })
    if (!event) return
    if (event.gap) { term.flushPending(); term.appendSystem(`输出不连续：缓冲区丢弃了 ${event.payload.dropped ?? '部分'} 个输出事件`); return }
    const outChunk = termDecoder.decodeFrame(event.payload.data, event.payload.text)
    if (outChunk) term.appendLines(outChunk)
    if (event.payload.closed === true) {
      term.flushPending(); term.appendSystem('节点已结束该 shell 会话'); transport.stop(); terminalId.value = ''; connected.value = false
    }
  },
  onError(reason) {
    connected.value = false
    term.flushPending()
    error.value = reason === 'auth' ? '没有输出流访问权限或登录已过期，请重新登录后重连' : '输出连接中断，正在重连；中断期间的输出可能缺失'
  },
  onClosed() { connected.value = false },
}, { getToken: () => localStorage.getItem('token') })

async function openTerminal() {
  if (!canUse.value || opening.value || closing.value || disposed) return
  if (terminalId.value && sessionNode === nodeId.value) {
    transport.stop(); transport.start(api.terminalEventsUrl(sessionNode, terminalId.value)); return
  }
  const epoch = ++generation, id = nodeId.value
  const requested = `t-${crypto.randomUUID()}`
  opening.value = true; error.value = ''
  try {
    const request = api.terminalOpen(id, requested) as Promise<{ terminalId?: string }>
    void request.then(result => {
      if (disposed || epoch !== generation) void api.terminalClose(id, result.terminalId || requested).catch(() => {})
    }, () => {})
    const result = await withTimeout(request, 70_000, '终端创建结果未确认，请检查节点状态。不要连续创建多个会话。')
    if (disposed || epoch !== generation) return
    terminalId.value = result.terminalId || requested
    sessionNode = id; opens = 0
    transport.start(api.terminalEventsUrl(id, terminalId.value))
  }
  catch (cause) { if (epoch === generation) { error.value = errorMessage(cause, '终端创建失败'); generation++ } }
  finally { if (epoch === generation || !terminalId.value) opening.value = false }
}
async function send(command: string) {
  if (!canUse.value || !terminalId.value || opening.value || closing.value) { error.value = '请先打开终端'; return }
  const epoch = generation, id = sessionNode, session = terminalId.value
  const echo = term.appendCommandEcho(command)
  try {
    await withTimeout(api.terminalInput(id, session, `${command}\n`), 40_000, '发送结果未确认，请核对节点输出后再决定是否重试')
    if (epoch === generation) { term.commitCommand(command); error.value = '' }
  }
  catch (cause) { if (epoch === generation) { error.value = errorMessage(cause, '发送失败'); term.markCommandFailed(echo, error.value) } }
}
async function closeTerminal() {
  if (!terminalId.value || closing.value) return
  const epoch = generation, id = sessionNode, session = terminalId.value
  closing.value = true
  try {
    await withTimeout(api.terminalClose(id, session), 40_000, '关闭结果未确认，请核对节点状态')
    if (epoch !== generation) return
    transport.stop(); term.flushPending(); terminalId.value = ''; term.appendSystem('终端会话已关闭')
  }
  catch (cause) { if (epoch === generation) error.value = errorMessage(cause, '关闭失败') }
  finally { if (epoch === generation) closing.value = false }
}
async function browserList(path: string, keyword?: string) {
  const id = nodeId.value
  const result = await withTimeout(api.nodeFileList(id, nodeRoot.value, path, 1, 200, keyword), 12_000, '目录读取超时') as {
    entries?: Array<{ name: string, isDir: boolean, size?: number }>, total?: number | string, root?: string
  }
  if (id === nodeId.value && result.root) nodeRoot.value = result.root
  return { entries: result.entries ?? [], total: Number(result.total ?? result.entries?.length ?? 0) }
}
function editFile(path: string) {
  editorPath.value = path
  editorDirectory.value = path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : ''
  editorOpen.value = true
}
function releaseSession() {
  generation++
  transport.stop()
  if (terminalId.value) void api.terminalClose(sessionNode, terminalId.value).catch(() => {})
  terminalId.value = ''; opening.value = false; closing.value = false
}
watch(nodeId, async id => {
  releaseSession(); term.clear(); term.reloadHistory(); nodeRoot.value = ''; error.value = ''; nodeName.value = ''; nodeStatus.value = ''
  const epoch = generation
  try { const node = await panel.nodeDetail(id); if (epoch === generation) { nodeName.value = node.name; nodeStatus.value = String(node.status ?? '') } }
  catch (cause) { if (epoch === generation) error.value = errorMessage(cause, '节点详情加载失败') }
  if (epoch === generation) void openTerminal()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; releaseSession() })
</script>

<template>
  <FaPageHeader :title="`${nodeName || '节点'} · 终端`" description="直接操作节点主机的 Shell，请谨慎执行系统命令" class="mb-0">
    <FaButton variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/nodes/${nodeId}`)"><FaIcon name="i-ri:arrow-left-line" />节点详情</FaButton>
    <FaButton v-if="canUse" variant="outline" :loading="opening" @click="openTerminal">{{ terminalId ? '重新连接输出' : '打开终端' }}</FaButton>
    <FaButton v-if="terminalId && canUse" variant="destructive" :loading="closing" @click="closeTerminal">关闭会话</FaButton>
  </FaPageHeader>
  <FaPageMain class="mcp-mt-4" main-class="mcp-page-stack">
    <FaAlert v-if="!canUse" title="当前账号没有节点操作权限">需要 plugin:mcpanel:use 权限。</FaAlert>
    <FaAlert v-if="error && !terminalId" variant="destructive" title="终端未就绪">{{ error }}<p>节点需要在线并开启 security.terminalEnabled。</p></FaAlert>
    <TerminalConsole :key="nodeId" :term="term" mode="shell" :can-use="canUse && !!terminalId && !closing" :transport="{ opening, connected }" :hint="error" show-reconnect @reconnect="openTerminal">
      <template #tags><FaTag variant="outline">{{ nodeName || nodeId }}</FaTag><FaTag variant="secondary">{{ nodeStatus === 'online' ? '在线' : nodeStatus === 'offline' ? '离线' : nodeStatus || '未知状态' }}</FaTag></template>
      <template v-if="canManage" #left><FileBrowserPanel :key="nodeId" title="节点文件" :list="browserList" :open-file="editFile" /><FaButton size="sm" variant="outline" class="mcp-wt-mt" @click="router.push(`/platform/plugins/mcpanel/admin/nodes/${nodeId}/files`)">完整文件管理<FaIcon name="i-ri:arrow-right-line" /></FaButton></template>
      <template #right><div class="mcp-wt-pane-title">常用命令</div><div class="mcp-wt-list"><FaButton v-for="command in common" :key="command" size="sm" variant="ghost" class="mcp-wt-cmd" @click="term.commandText.value = command">{{ command }}</FaButton></div><div class="mcp-wt-pane-title">最近命令</div><div class="mcp-wt-list"><FaButton v-for="command in [...term.history.value].reverse().slice(0, 15)" :key="command" size="sm" variant="ghost" class="mcp-wt-cmd" @click="term.commandText.value = command">{{ command }}</FaButton><span v-if="!term.history.value.length" class="mcp-muted">还没有命令记录</span></div></template>
      <template #status-start><span>{{ terminalId ? 'Shell 会话已打开' : '尚未打开会话' }}</span></template>
    </TerminalConsole>
    <FileEditorModal
      v-model="editorOpen"
      title="节点文件编辑"
      :load="editorAccess.load"
      :save="editorAccess.save"
      :list-dir="browserList"
      :can-save="canManage"
      :initial-path="editorPath"
      :initial-directory="editorDirectory"
    />
  </FaPageMain>
</template>
