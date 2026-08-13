<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  FaAlert,
  FaButton,
  FaCheckbox,
  FaIcon,
  FaInput,
  FaLabel,
  FaPageHeader,
  FaPageMain,
  FaSelect,
  FaSwitch,
  FaTextarea,
  useFaModal,
  useFaToast,
} from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createWebCardApi } from '../api/web-card-api'
import TemplateCodeEditor from '../components/TemplateCodeEditor.vue'
import TokuiAgentOutput from '../components/TokuiAgentOutput.vue'
import { openAgentMessageStream } from '../composables/agent-message-stream'
import { DEFAULT_STRUCTURED_LAYOUT, normalizeStructuredLayout, validateTemplateCode } from '../composables/template-editor'
import type { AgentProposal, AgentSession, Option, Site, TemplateVersion, WorkspacePlan, WorkspacePlanJob } from '../types'
import { dateTime, errorText } from '../ui'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createWebCardApi(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
type MobileView = 'chat' | 'plan' | 'preview'

const sessions = ref<AgentSession[]>([])
const proposals = ref<AgentProposal[]>([])
const connections = ref<Option[]>([])
const groups = ref<Option[]>([])
const activeId = ref('')
const message = ref('')
const sending = ref(false)
const streamText = ref('')
const streamActive = ref(false)
const saving = ref(false)
const applying = ref(false)
const deletingSession = ref(false)
const error = ref('')
const credentialText = ref('')
const credentialSite = ref<Site | null>(null)
const preview = ref('')
const previewing = ref(false)
const previewError = ref('')
const previewFields = ref<Record<string, unknown>>({})
const agentPreviewUrl = ref('')
const lastAgentPreviewSignature = ref('')
const conversationEl = ref<HTMLElement | null>(null)
const mobileView = ref<MobileView>('chat')
const draft = ref<WorkspacePlan | null>(null)
const savedDraft = ref('')
let previewRequest = 0

const active = computed(() => sessions.value.find(value => String(value.id) === activeId.value))
const pending = computed(() => proposals.value.filter(value => value.sessionId === activeId.value && value.status === 'PENDING').sort((left, right) => Number(right.updatedAt) - Number(left.updatedAt))[0])
const fixture = computed(() => draft.value?.template.fixture ?? {})
const requiresSecret = computed(() => draft.value?.site.accessMode === 'CUSTOM_HEADERS' || credentialSite.value?.accessMode === 'CUSTOM_HEADERS')
const crawlEnabled = computed(() => Boolean(draft.value?.job))
const dirty = computed(() => Boolean(draft.value) && JSON.stringify(draft.value) !== savedDraft.value)
const agentDraftSignature = computed(() => JSON.stringify({ url: agentPreviewUrl.value, site: draft.value?.site, rules: draft.value?.rules, template: draft.value?.template }))
const agentPreviewIsCurrent = computed(() => Boolean(preview.value) && lastAgentPreviewSignature.value === agentDraftSignature.value)
const bindingTarget = computed(() => {
  const binding = draft.value?.binding
  if (!binding) return '未配置群投递'
  const connection = connections.value.find(value => value.id === binding.connectionId)?.name || '未选择连接'
  const group = groups.value.find(value => value.id === binding.channelId)?.name || '未选择目标群'
  return `${connection} / ${group}`
})
const artifactDsl = computed(() => {
  if (!draft.value) return ''
  const safe = (value: unknown) => String(value ?? '').replace(/[\[\]]/g, '').replace(/"/g, "'").replace(/\s+/g, ' ').trim()
  const plan = draft.value
  return `[card tt:"方案概览"][h3 ${safe(plan.summary || plan.template.name)}][p 站点：${safe(plan.site.name)} · ${safe(plan.site.hosts[0])}][p 解析字段：${plan.rules.fields.length} 个 · 模板：${safe(plan.template.name)}][p ${plan.job ? `采集：每 ${plan.job.intervalMinutes} 分钟` : '采集：未开启'}][/card]`
})
const artifactFallback = computed(() => draft.value
  ? `${draft.value.summary || draft.value.template.name}。站点 ${draft.value.site.name}，${draft.value.rules.fields.length} 个解析字段，${draft.value.job ? `每 ${draft.value.job.intervalMinutes} 分钟采集` : '未开启采集'}。`
  : '')

function planOf(proposal?: AgentProposal): WorkspacePlan | null {
  const value = proposal?.operations.find(operation => operation.target === 'workspace')?.value
  if (!value || typeof value !== 'object') return null
  return JSON.parse(JSON.stringify(value)) as WorkspacePlan
}

async function loadBindingGroups(connectionId?: string) {
  groups.value = connectionId ? await api.groups(connectionId) : []
}

function syncDraft(proposal?: AgentProposal) {
  const value = planOf(proposal)
  if (value) value.template.structuredLayout = normalizeStructuredLayout(value.template.structuredLayout || DEFAULT_STRUCTURED_LAYOUT)
  draft.value = value
  savedDraft.value = value ? JSON.stringify(value) : ''
  void loadBindingGroups(value?.binding?.connectionId).catch(() => { groups.value = [] })
}

watch(pending, syncDraft, { immediate: true })

async function load() {
  try {
    const [sessionPage, proposalPage, connectionOptions] = await Promise.all([
      api.sessions(1, 100),
      api.proposals(1, 100),
      api.connections(),
    ])
    sessions.value = sessionPage.records
    proposals.value = proposalPage.records
    connections.value = connectionOptions
    if (!activeId.value && sessions.value.length) activeId.value = String(sessions.value[0].id)
    if (!sessions.value.length) await createSession()
  } catch (cause) {
    error.value = errorText(cause, '加载工作台失败')
  }
}

async function createSession() {
  try {
    previewRequest++
    previewing.value = false
    const session = await api.createSession({ siteId: '', templateId: '', agentCode: 'builtin-web-card-studio', messages: [], createdAt: 0, updatedAt: 0 })
    sessions.value = [session, ...sessions.value]
    activeId.value = String(session.id)
    streamText.value = ''
    preview.value = ''
    mobileView.value = 'chat'
  } catch (cause) {
    toast.error(errorText(cause, '创建会话失败'))
  }
}

function removeSession(session: AgentSession) {
  const id = String(session.id || '')
  if (!id || deletingSession.value) return
  modal.confirm({
    title: '删除会话',
    content: '会话消息和该会话生成的待处理方案会一并删除，已应用的站点、模板和任务不受影响。',
    onConfirm: async () => {
      deletingSession.value = true
      try {
        await api.deleteSession(id)
        sessions.value = sessions.value.filter(value => String(value.id) !== id)
        proposals.value = proposals.value.filter(value => value.sessionId !== id)
        if (activeId.value === id) {
          previewRequest++
          previewing.value = false
          activeId.value = sessions.value[0]?.id ? String(sessions.value[0].id) : ''
          streamText.value = ''
          preview.value = ''
          if (!activeId.value) await createSession()
        }
        toast.success('会话已删除')
      }
      catch (cause) {
        toast.error(errorText(cause, '删除会话失败'))
      }
      finally {
        deletingSession.value = false
      }
    },
  })
}

function replaceProposal(proposal: AgentProposal) {
  const index = proposals.value.findIndex(value => value.id === proposal.id)
  if (index < 0) proposals.value = [proposal, ...proposals.value]
  else proposals.value.splice(index, 1, proposal)
}

async function scrollConversation() {
  await nextTick()
  conversationEl.value?.scrollTo({ top: conversationEl.value.scrollHeight, behavior: 'smooth' })
}

async function send() {
  const content = message.value.trim()
  if (!activeId.value || !content || sending.value) return
  const session = active.value
  if (session) session.messages = [...session.messages, { role: 'user', content }]
  message.value = ''
  streamText.value = ''
  streamActive.value = true
  sending.value = true
  const link = content.match(/https?:\/\/[^\s<>]+/)?.[0]
  if (link) {
    agentPreviewUrl.value = link
    void renderLink(link)
  }
  await scrollConversation()
  try {
    const { streamId } = await api.startAgentMessage(activeId.value, content)
    const stream = openAgentMessageStream(api.agentMessageEventsUrl(streamId), {
      onDelta(delta) {
        streamText.value += delta
        void scrollConversation()
      },
      onComplete(finalContent) {
        streamText.value = finalContent || streamText.value
      },
      onProposal(proposal) {
        replaceProposal(proposal)
        void nextTick().then(() => {
          if (agentPreviewUrl.value) return renderLink(agentPreviewUrl.value)
        })
      },
      onWarning(warning) {
        toast.warning(warning || '回复已完成，但没有生成可应用的方案')
      },
    })
    await stream.done
    streamActive.value = false
    await load()
    streamText.value = ''
  } catch (cause) {
    streamActive.value = false
    toast.error(errorText(cause, 'Agent 处理失败'))
    await load()
  } finally {
    sending.value = false
    await scrollConversation()
  }
}

async function renderLink(url: string) {
  const request = ++previewRequest
  const signature = agentDraftSignature.value
  previewing.value = true
  previewError.value = ''
  try {
    const plan = draft.value
    const result = plan ? await api.previewDraft(agentPreviewRequest(plan, url)) : await api.previewUrl(url)
    if (request !== previewRequest) return
    preview.value = `data:image/png;base64,${result.base64}`
    previewFields.value = result.fields
    lastAgentPreviewSignature.value = signature
    mobileView.value = 'preview'
  }
  catch (cause) {
    if (request !== previewRequest) return
    previewError.value = errorText(cause, '链接没有匹配可用的渲染规则')
  }
  finally {
    if (request === previewRequest) previewing.value = false
  }
}

function agentPreviewRequest(plan: WorkspacePlan, url: string) {
  const siteId = plan.site.id || 'agent-preview'
  const rules = { ...plan.rules, siteId }
  const site: Site = {
    id: siteId,
    name: plan.site.name,
    enabled: true,
    hosts: plan.site.hosts,
    accessMode: plan.site.accessMode,
    headerNames: [],
    responseType: plan.site.responseType,
    redirectHosts: plan.site.redirectHosts,
    createdAt: 0,
    updatedAt: 0,
  }
  const version: TemplateVersion = {
    templateId: plan.template.id || 'agent-preview-template',
    version: 0,
    parseRules: rules,
    mode: plan.template.mode,
    structuredLayout: plan.template.structuredLayout || '{}',
    html: plan.template.html || '',
    css: plan.template.css || '',
    fixture: plan.template.fixture || {},
    origin: 'AGENT',
    summary: plan.summary,
    previewPassed: false,
    createdAt: 0,
  }
  return { siteId, url, site, rules, version }
}

function validateDraft(plan: WorkspacePlan) {
  if (!plan.site.name.trim()) return '请填写站点名称'
  if (!plan.site.hosts[0]?.trim()) return '请填写站点域名'
  if (!plan.template.name.trim()) return '请填写模板名称'
  const codeError = validateTemplateCode(plan.template.mode, plan.template.structuredLayout || '{}', plan.template.html || '')
  if (codeError) return codeError
  if (!plan.rules.fields.length) return '至少需要一个解析字段'
  if (plan.rules.fields.some(field => !field.name.trim() || !field.expression.trim())) return '解析字段名称和表达式不能为空'
  if (plan.job && (!plan.job.sourceUrl.trim() || plan.job.intervalMinutes < 1)) return '开启采集后需要填写入口地址和有效间隔'
  return ''
}

async function saveProposal(showSuccess = true) {
  if (!pending.value || !draft.value) return false
  const validation = validateDraft(draft.value)
  if (validation) {
    toast.error(validation)
    return false
  }
  saving.value = true
  try {
    const updated = await api.updateProposal(pending.value.id, draft.value)
    replaceProposal(updated)
    savedDraft.value = JSON.stringify(draft.value)
    if (showSuccess) toast.success('方案修改已保存')
    return true
  } catch (cause) {
    toast.error(errorText(cause, '保存方案失败'))
    return false
  } finally {
    saving.value = false
  }
}

async function apply() {
  if (!pending.value || !draft.value) return
  if (dirty.value && !await saveProposal(false)) return
  const appliedPlan = JSON.parse(JSON.stringify(draft.value)) as WorkspacePlan
  applying.value = true
  try {
    await api.applyProposal(pending.value.id)
    if (agentPreviewUrl.value) await renderLink(agentPreviewUrl.value)
    const sites = await api.sites(1, 100)
    credentialSite.value = sites.records.find(value => value.hosts.includes(appliedPlan.site.hosts[0])) ?? null
    mobileView.value = 'preview'
    toast.success('工作区方案已应用为草稿')
    await load()
  } catch (cause) {
    toast.error(errorText(cause, '应用方案失败'))
  } finally {
    applying.value = false
  }
}

async function reject() {
  if (!pending.value) return
  try {
    await api.rejectProposal(pending.value.id)
    toast.success('方案已放弃')
    await load()
  } catch (cause) {
    toast.error(errorText(cause, '放弃方案失败'))
  }
}

function setHost(value: unknown) {
  if (!draft.value) return
  draft.value.site.hosts = [String(value)]
}

function addField() {
  draft.value?.rules.fields.push({ name: '', expression: '', attribute: 'text', type: 'TEXT', required: false })
}

function removeField(index: number) {
  draft.value?.rules.fields.splice(index, 1)
}

function setCrawl(enabled: boolean) {
  if (!draft.value) return
  if (!enabled) {
    draft.value.job = null
    return
  }
  const job: WorkspacePlanJob = {
    sourceUrl: '',
    sourceType: 'RSS',
    enabled: true,
    intervalMinutes: 30,
    initialItemCount: 3,
  }
  draft.value.job = job
}

function setInterval(value: unknown) {
  if (draft.value?.job) draft.value.job.intervalMinutes = Math.max(1, Number(value) || 1)
}

function parseHeaders() {
  const result: Record<string, string> = {}
  credentialText.value.split(/\r?\n/).map(value => value.trim()).filter(Boolean).forEach(line => {
    const index = line.indexOf(':')
    if (index < 1) throw new Error(`Header 格式无效：${line}`)
    result[line.slice(0, index).trim()] = line.slice(index + 1).trim()
  })
  return result
}

async function saveSecrets() {
  if (!credentialSite.value) return
  try {
    await api.saveSite(credentialSite.value, parseHeaders())
    credentialText.value = ''
    toast.success('访问凭据已安全保存')
  } catch (cause) {
    toast.error(errorText(cause, '保存凭据失败'))
  }
}

function choose(id: string) {
  if (sending.value) return
  previewRequest++
  previewing.value = false
  activeId.value = id
  streamText.value = ''
  preview.value = ''
  lastAgentPreviewSignature.value = ''
  credentialSite.value = null
  mobileView.value = 'chat'
}

onMounted(load)
</script>

<template>
  <section class="studio-shell">
    <FaPageHeader class="studio-header" title="网站卡片工作室" description="告诉 Agent 你想如何理解、展示和推送网站内容。">
      <div class="header-actions">
        <a class="studio-link" href="/platform/plugins/web-card/admin/sites"><FaIcon name="i-ri:global-line"/>站点与解析</a>
        <a class="studio-link" href="/platform/plugins/web-card/admin/templates"><FaIcon name="i-ri:layout-4-line"/>卡片模板</a>
        <a class="studio-link" href="/platform/plugins/web-card/admin/jobs"><FaIcon name="i-ri:timer-line"/>定时任务</a>
        <FaButton v-if="active" variant="outline" :disabled="sending || deletingSession" title="删除当前会话" @click="removeSession(active)"><FaIcon name="i-ri:delete-bin-line"/>删除会话</FaButton>
        <FaButton variant="outline" :disabled="sending" @click="createSession"><FaIcon name="i-ri:add-line"/>新会话</FaButton>
      </div>
    </FaPageHeader>
    <FaPageMain class="studio-main">
      <FaAlert v-if="error" variant="destructive" title="工作台不可用" :description="error"/>
      <nav class="mobile-switcher" aria-label="工作区视图">
        <button type="button" :class="{ active: mobileView === 'chat' }" :aria-pressed="mobileView === 'chat'" @click="mobileView = 'chat'"><FaIcon name="i-ri:chat-3-line"/><span>对话</span></button>
        <button type="button" :class="{ active: mobileView === 'plan' }" :aria-pressed="mobileView === 'plan'" @click="mobileView = 'plan'"><FaIcon name="i-ri:file-list-3-line"/><span>方案</span><i v-if="pending" class="pending-dot" aria-label="有待确认方案"/></button>
        <button type="button" :class="{ active: mobileView === 'preview' }" :aria-pressed="mobileView === 'preview'" @click="mobileView = 'preview'"><FaIcon name="i-ri:image-line"/><span>预览</span></button>
      </nav>

      <div class="studio-grid">
        <aside class="session-rail" aria-label="Agent 会话">
          <div class="rail-title">会话</div>
          <div v-for="session in sessions" :key="session.id" class="session-item" :class="{ active: String(session.id) === activeId }">
            <button class="session-row" @click="choose(String(session.id))"><FaIcon name="i-ri:message-3-line"/><span><strong>网站卡片方案</strong><small>{{ session.updatedAt ? dateTime(session.updatedAt) : '刚刚' }}</small></span></button>
            <button class="session-delete" type="button" title="删除会话" :aria-label="`删除会话 ${dateTime(session.updatedAt)}`" :disabled="sending || deletingSession" @click="removeSession(session)"><FaIcon name="i-ri:delete-bin-line"/></button>
          </div>
        </aside>

        <main class="conversation-pane" :class="{ 'mobile-visible': mobileView === 'chat' }">
          <div ref="conversationEl" class="conversation-stream">
            <div v-if="!active?.messages.length && !streamActive" class="welcome-state">
              <FaIcon name="i-ri:sparkling-2-line"/>
              <h2>从一个网址或一句需求开始</h2>
              <p>例如：识别这个博客的新文章，生成简洁卡片并绑定到技术交流群。只有明确要求时才会配置定时采集。</p>
            </div>
            <div v-for="(item, index) in active?.messages" :key="index" class="turn" :class="item.role">
              <div class="turn-author">{{ item.role === 'user' ? '你' : 'Web Card Agent' }}</div>
              <div v-if="item.role === 'user'" class="turn-body">{{ item.content }}</div>
              <div v-else class="turn-body agent-turn"><TokuiAgentOutput :content="item.content"/></div>
            </div>
            <div v-if="streamActive || streamText" class="turn assistant streaming-turn">
              <div class="turn-author"><span>Web Card Agent</span><small v-if="streamActive">生成中</small></div>
              <div class="turn-body agent-turn"><TokuiAgentOutput :content="streamText" :streaming="streamActive"/></div>
            </div>
          </div>
          <div class="composer">
            <FaTextarea v-model="message" :rows="4" :disabled="sending" placeholder="粘贴网址，然后描述卡片样式、目标群和推送方式…" @keydown.ctrl.enter.prevent="send"/>
            <div class="composer-footer"><span>Ctrl + Enter 发送。方案应用前不会改变现有配置。</span><FaButton :disabled="sending || !message.trim()" @click="send"><FaIcon :name="sending ? 'i-ri:loader-4-line' : 'i-ri:send-plane-2-line'"/>{{ sending ? '生成中' : '发送' }}</FaButton></div>
          </div>
        </main>

        <aside class="inspector-pane" :class="[`mobile-${mobileView}`, { 'mobile-visible': mobileView !== 'chat' }]">
          <section class="inspector-section plan-section">
            <div class="section-heading"><span>待执行方案</span><small v-if="pending">{{ dirty ? '有未保存修改' : '等待确认' }}</small></div>
            <template v-if="draft">
              <TokuiAgentOutput class="proposal-artifact" :content="artifactDsl" :fallback="artifactFallback" label="方案概览"/>
              <div class="proposal-editor">
                <section class="editor-group">
                  <h3><FaIcon name="i-ri:global-line"/>站点</h3>
                  <div class="field"><FaLabel for="plan-site-name">站点名称</FaLabel><FaInput id="plan-site-name" v-model="draft.site.name"/></div>
                  <div class="field"><FaLabel for="plan-site-host">域名</FaLabel><FaInput id="plan-site-host" :model-value="draft.site.hosts[0] || ''" placeholder="example.com" @update:model-value="setHost"/></div>
                  <div class="field"><FaLabel>访问方式</FaLabel><FaSelect v-model="draft.site.accessMode" :options="[{ label: '公开访问', value: 'PUBLIC_HTTP' }, { label: '自定义 Headers', value: 'CUSTOM_HEADERS' }]"/></div>
                </section>

                <section class="editor-group">
                  <div class="group-heading"><h3><FaIcon name="i-ri:code-s-slash-line"/>解析字段</h3><FaButton size="sm" variant="outline" @click="addField"><FaIcon name="i-ri:add-line"/>添加</FaButton></div>
                  <div v-for="(field, index) in draft.rules.fields" :key="index" class="parser-field">
                    <div class="parser-field-heading"><strong>字段 {{ index + 1 }}</strong><FaButton size="sm" variant="outline" title="删除字段" :aria-label="`删除字段 ${index + 1}`" :disabled="draft.rules.fields.length === 1" @click="removeField(index)"><FaIcon name="i-ri:delete-bin-line"/></FaButton></div>
                    <div class="field"><FaLabel :for="`field-name-${index}`">名称</FaLabel><FaInput :id="`field-name-${index}`" v-model="field.name" placeholder="title"/></div>
                    <div class="field"><FaLabel :for="`field-expression-${index}`">表达式</FaLabel><FaInput :id="`field-expression-${index}`" v-model="field.expression" placeholder="article h1 / $.title"/></div>
                    <div class="field"><FaLabel :for="`field-attribute-${index}`">属性</FaLabel><FaInput :id="`field-attribute-${index}`" v-model="field.attribute" placeholder="text"/></div>
                    <FaCheckbox v-model="field.required">必填字段</FaCheckbox>
                  </div>
                </section>

                <section class="editor-group">
                  <h3><FaIcon name="i-ri:layout-4-line"/>卡片模板</h3>
                  <div class="field"><FaLabel for="plan-template-name">模板名称</FaLabel><FaInput id="plan-template-name" v-model="draft.template.name"/></div>
                  <div class="field"><FaLabel>模板模式</FaLabel><FaSelect v-model="draft.template.mode" :options="[{ label: '结构化模板', value: 'STRUCTURED' }, { label: '高级 HTML', value: 'ADVANCED' }]"/></div>
                  <div v-if="draft.template.mode === 'STRUCTURED'" class="field proposal-code-field">
                    <FaLabel>布局 JSON</FaLabel>
                    <TemplateCodeEditor v-model="draft.template.structuredLayout" language="json" :min-height="220"/>
                  </div>
                  <template v-if="draft.template.mode === 'ADVANCED'">
                    <div class="field proposal-code-field"><FaLabel>HTML</FaLabel><TemplateCodeEditor v-model="draft.template.html" language="html" :min-height="240"/></div>
                    <div class="field proposal-code-field"><FaLabel>CSS</FaLabel><TemplateCodeEditor v-model="draft.template.css" language="css" :min-height="220"/></div>
                  </template>
                </section>

                <section class="editor-group">
                  <h3><FaIcon name="i-ri:group-line"/>定时推送目标</h3>
                  <div class="setting-row"><span><strong>随定时任务主动推送</strong><small>{{ bindingTarget }}</small></span><FaSwitch v-if="draft.binding" v-model="draft.binding.enabled"/><small v-else>未配置</small></div>
                </section>

                <section class="editor-group crawl-group">
                  <div class="setting-row"><span><strong>定时采集</strong><small>默认关闭，仅在需要持续监控时开启</small></span><FaSwitch :model-value="crawlEnabled" @update:model-value="setCrawl(Boolean($event))"/></div>
                  <template v-if="draft.job">
                    <div class="field"><FaLabel for="crawl-source">采集入口</FaLabel><FaInput id="crawl-source" v-model="draft.job.sourceUrl" placeholder="RSS、Sitemap 或列表页地址"/></div>
                    <div class="field"><FaLabel for="crawl-interval">间隔（分钟）</FaLabel><FaInput id="crawl-interval" type="number" min="1" :model-value="String(draft.job.intervalMinutes)" @update:model-value="setInterval"/></div>
                  </template>
                </section>
              </div>
            </template>
            <div v-else class="inspector-empty">Agent 生成方案后，可在这里逐项检查和修改。</div>
            <div v-if="pending" class="action-row"><FaButton variant="outline" @click="reject">放弃</FaButton><FaButton variant="outline" :disabled="saving || !dirty" @click="saveProposal()"><FaIcon name="i-ri:save-3-line"/>保存修改</FaButton><FaButton :disabled="applying || saving" @click="apply"><FaIcon name="i-ri:check-line"/>应用方案</FaButton></div>
          </section>

          <section class="inspector-section preview-section">
            <div class="section-heading"><span>即时渲染结果</span><small v-if="previewing">正在抓取并渲染</small><small v-else-if="preview && !agentPreviewIsCurrent">方案已修改，请重新预览</small><small v-else-if="agentPreviewIsCurrent">当前方案</small></div>
            <div class="agent-preview-toolbar">
              <FaInput v-model="agentPreviewUrl" type="url" placeholder="输入匹配站点规则的详情页链接" @keyup.enter="renderLink(agentPreviewUrl)"/>
              <FaButton :disabled="previewing || !agentPreviewUrl.trim()" @click="renderLink(agentPreviewUrl)"><FaIcon name="i-ri:play-circle-line"/>预览方案</FaButton>
            </div>
            <FaAlert v-if="previewError" variant="destructive" title="无法渲染此链接" :description="previewError"/>
            <div class="card-preview"><img v-if="preview" :src="preview" alt="按站点规则生成的卡片"><div v-else-if="!previewError"><strong>{{ fixture.title || '发送匹配链接后自动渲染' }}</strong><p>{{ fixture.summary || '插件会直接使用管理员配置的子链接规则、解析字段和卡片模板。' }}</p></div></div>
            <div v-if="Object.keys(previewFields).length" class="preview-fields"><span v-for="(value,key) in previewFields" :key="key"><small>{{ key }}</small><strong>{{ String(value) }}</strong></span></div>
          </section>

          <section v-if="requiresSecret" class="inspector-section secret-section">
            <div class="section-heading"><span>安全凭据</span><small>不会发送给 Agent</small></div>
            <FaTextarea v-model="credentialText" :rows="4" placeholder="Authorization: Bearer …&#10;Cookie: …"/>
            <FaButton variant="outline" :disabled="!credentialSite || !credentialText.trim()" @click="saveSecrets"><FaIcon name="i-ri:lock-2-line"/>保存到 Secret Store</FaButton>
          </section>
        </aside>
      </div>
    </FaPageMain>
  </section>
</template>

<style scoped>
.studio-shell{--studio-border:#dfe4ea;--studio-muted:#667281;--studio-soft:#f5f7f9;--studio-accent:#316fa8;background:#fff;min-height:calc(100vh - 56px)}
.studio-main{padding-top:20px}.studio-grid{display:grid;grid-template-columns:220px minmax(420px,1fr) 400px;min-height:680px;border:1px solid var(--studio-border);background:#fff}
.header-actions{display:flex;flex-wrap:wrap;gap:8px}.studio-link{display:inline-flex;min-height:34px;align-items:center;gap:6px;border:1px solid var(--studio-border);background:#fff;padding:0 11px;color:#405466;text-decoration:none;font-size:13px}.studio-link:hover{border-color:#8ca9c2;background:#f3f7fa;color:#245f91}.session-rail{border-right:1px solid var(--studio-border);padding:20px 12px;background:#fafbfc}.rail-title{padding:0 10px 12px;color:var(--studio-muted);font-size:12px;font-weight:700;text-transform:uppercase}.session-item{position:relative;display:flex;align-items:stretch}.session-item:hover,.session-item.active{background:#edf3f8}.session-row{display:flex;min-width:0;flex:1;gap:10px;align-items:flex-start;border:0;background:transparent;padding:12px 36px 12px 10px;text-align:left;color:#283443;cursor:pointer}.session-row>svg{margin-top:2px;color:#5c6b7a}.session-row span{display:grid;gap:4px;min-width:0}.session-row strong{font-size:14px}.session-row small{color:var(--studio-muted);font-size:11px}.session-delete{position:absolute;top:50%;right:7px;display:grid;width:28px;height:28px;transform:translateY(-50%);place-items:center;border:0;background:transparent;color:#788491;cursor:pointer;opacity:0}.session-item:hover .session-delete,.session-item.active .session-delete,.session-delete:focus-visible{opacity:1}.session-delete:hover{color:#b42318}.session-delete:disabled{cursor:not-allowed;opacity:.4}
.conversation-pane{display:grid;grid-template-rows:minmax(0,1fr) auto;min-width:0}.conversation-stream{max-height:620px;overflow:auto;padding:28px 32px}.welcome-state{display:grid;min-height:360px;place-items:center;align-content:center;text-align:center;color:var(--studio-muted);gap:10px}.welcome-state>svg{font-size:34px;color:var(--studio-accent)}.welcome-state h2{margin:4px 0 0;color:#1f2b37;font-size:22px}.welcome-state p{max-width:520px;margin:0;line-height:1.7}.turn{max-width:760px;margin-bottom:24px}.turn-author{display:flex;align-items:center;gap:8px;margin-bottom:7px;color:var(--studio-muted);font-size:12px;font-weight:700}.turn-author small{color:#387c60}.turn-body{border-left:3px solid #8ca9c2;padding:12px 16px;background:var(--studio-soft);line-height:1.7;white-space:pre-wrap}.turn.assistant .turn-body{border-left-color:#4b9275;background:#f2f8f5}.agent-turn{white-space:normal}.streaming-turn .turn-author small::before{display:inline-block;width:6px;height:6px;margin-right:5px;border-radius:50%;background:#3b8f6c;content:'';animation:pulse 1.2s infinite}.composer{border-top:1px solid var(--studio-border);padding:20px 24px}.composer-footer{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-top:12px}.composer-footer span{color:var(--studio-muted);font-size:12px}
.inspector-pane{border-left:1px solid var(--studio-border);background:#fafbfc;overflow:auto}.inspector-section{padding:22px 20px;border-bottom:1px solid var(--studio-border)}.section-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;color:#25313e;font-size:14px;font-weight:700}.section-heading small{color:#387c60;font-size:11px}.proposal-artifact{margin-bottom:18px}.proposal-editor{display:grid;gap:22px}.editor-group{display:grid;gap:12px;padding-top:18px;border-top:1px solid #e6eaee}.editor-group:first-child{padding-top:0;border-top:0}.editor-group h3{display:flex;align-items:center;gap:8px;margin:0;color:#293746;font-size:13px}.editor-group h3 svg{color:#57738d}.group-heading,.setting-row,.parser-field-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.field{display:grid;gap:6px}.field label{font-size:12px;color:#536170}.parser-field{display:grid;gap:10px;padding:13px;border:1px solid #e1e6eb;background:#fff}.parser-field-heading strong{font-size:12px}.setting-row>span{display:grid;gap:3px;min-width:0}.setting-row strong{font-size:13px}.setting-row small,.inspector-empty{color:var(--studio-muted);font-size:12px;line-height:1.5}.crawl-group{padding-bottom:4px}.inspector-empty{padding:20px 0}.action-row{position:sticky;bottom:0;z-index:2;display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px;margin:22px -20px -22px;padding:14px 20px;border-top:1px solid var(--studio-border);background:rgba(250,251,252,.96);backdrop-filter:blur(12px)}
.proposal-code-field{min-width:0}.agent-preview-toolbar{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px;margin-bottom:12px}.card-preview{display:grid;min-height:210px;place-items:center;border:1px solid var(--studio-border);background:#eef1f4;padding:14px}.card-preview img{display:block;max-width:100%;height:auto}.card-preview>div{width:100%;background:#fff;border:1px solid #d9dee5;padding:20px}.card-preview strong{font-size:17px}.card-preview p{margin:9px 0 0;color:var(--studio-muted);font-size:13px;line-height:1.6}.preview-fields{display:grid;gap:8px;margin-top:12px}.preview-fields>span{display:grid;grid-template-columns:80px minmax(0,1fr);gap:10px;padding-bottom:8px;border-bottom:1px solid var(--studio-border)}.preview-fields small{color:var(--studio-muted)}.preview-fields strong{overflow:hidden;text-overflow:ellipsis;font-size:12px;font-weight:500;white-space:nowrap}.secret-section{display:grid;gap:12px}.secret-section .section-heading{margin-bottom:4px}.mobile-switcher{display:none}

/* Keep the workspace card within the viewport; chat and proposal own their scroll. */
.studio-grid{grid-template-columns:220px minmax(520px,1fr) 480px;height:min(780px,calc(100dvh - 150px));min-height:580px;overflow:hidden}
.conversation-pane{min-height:0}
.conversation-stream{min-height:0;max-height:none;overflow-y:auto;overscroll-behavior:contain}
.inspector-pane{min-height:0;overflow-y:auto;overscroll-behavior:contain}
@keyframes pulse{50%{opacity:.35}}
@media(max-width:1320px){.studio-grid{grid-template-columns:180px minmax(440px,1fr) 420px}.conversation-stream{padding:24px}}
@media(max-width:900px){.studio-shell{min-height:calc(100dvh - 56px);background:#f6f8fa}.studio-header{background:#fff}.studio-main{padding-top:12px}.mobile-switcher{position:sticky;top:8px;z-index:5;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:4px;margin-bottom:12px;padding:4px;border:1px solid var(--studio-border);background:rgba(255,255,255,.96);box-shadow:0 5px 18px rgba(30,42,54,.08);backdrop-filter:blur(12px)}.mobile-switcher button{position:relative;display:flex;min-width:0;height:42px;align-items:center;justify-content:center;gap:7px;border:0;background:transparent;color:#637080;font:inherit;font-size:13px;font-weight:650;cursor:pointer}.mobile-switcher button.active{background:#eaf1f7;color:#245f91}.mobile-switcher button:focus-visible{outline:2px solid #5d8db8;outline-offset:-2px}.mobile-switcher svg{font-size:17px}.mobile-switcher .pending-dot{position:absolute;top:8px;right:calc(50% - 30px);width:6px;height:6px;border-radius:50%;background:#d04b4b}.studio-grid{display:block;min-height:0;border:0;background:transparent}.session-rail{display:none}.conversation-pane,.inspector-pane{display:none}.conversation-pane.mobile-visible{display:grid;min-height:clamp(500px,calc(100dvh - 205px),720px);border:1px solid var(--studio-border);background:#fff}.conversation-stream{min-height:0;max-height:none;padding:20px 18px;overscroll-behavior:contain}.welcome-state{min-height:240px;padding:16px 4px;gap:8px}.welcome-state>svg{font-size:28px}.welcome-state h2{font-size:18px;line-height:1.4}.welcome-state p{font-size:13px;line-height:1.6}.turn{margin-bottom:18px}.turn-body{padding:10px 12px;font-size:14px;line-height:1.65}.composer{padding:12px;border-top-color:#e3e7eb;background:#fbfcfd}.composer-footer{justify-content:flex-end;margin-top:10px}.composer-footer span{display:none}.inspector-pane.mobile-visible{display:block;min-height:clamp(500px,calc(100dvh - 205px),720px);border:1px solid var(--studio-border);background:#fff;overflow:visible}.inspector-pane.mobile-plan .preview-section{display:none}.inspector-pane.mobile-preview .plan-section,.inspector-pane.mobile-preview .secret-section{display:none}.inspector-section{padding:20px 18px}.inspector-pane.mobile-visible .inspector-section:last-child{border-bottom:0}.section-heading{margin-bottom:18px;font-size:15px}.action-row{margin-right:-18px;margin-bottom:-20px;margin-left:-18px;padding:12px 18px;flex-wrap:wrap}.preview-section{padding:18px}.card-preview{min-height:320px;padding:12px}}
@media(max-width:900px){.studio-grid{height:auto;overflow:visible}}
@media(max-width:480px){.studio-main{padding-right:12px;padding-left:12px}.conversation-pane.mobile-visible,.inspector-pane.mobile-visible{min-height:calc(100dvh - 190px)}.action-row>*{flex:1 1 auto}.agent-preview-toolbar{grid-template-columns:1fr}.agent-preview-toolbar :deep(button){width:100%}}
</style>
