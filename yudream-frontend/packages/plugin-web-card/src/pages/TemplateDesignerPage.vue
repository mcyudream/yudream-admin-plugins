<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  FaAlert,
  FaButton,
  FaButtonGroup,
  FaIcon,
  FaInput,
  FaLabel,
  FaModal,
  FaPageHeader,
  FaPageMain,
  FaPagination,
  FaSelect,
  FaTable,
  FaTag,
  useFaModal,
  useFaToast,
  type TableColumn,
} from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createWebCardApi } from '../api/web-card-api'
import TemplateCodeEditor from '../components/TemplateCodeEditor.vue'
import { DEFAULT_STRUCTURED_LAYOUT as DEFAULT_LAYOUT, normalizeStructuredLayout, validateTemplateCode } from '../composables/template-editor'
import type { CardTemplate, ParseRules, Site, TemplateVersion } from '../types'
import { dateTime, errorText, uid } from '../ui'

const DEFAULT_HTML = `<article id="web-card" class="web-card">
  <img class="cover" src="{{image}}" alt="">
  <div class="content">
    <div class="source">{{source}}</div>
    <h1>{{title}}</h1>
    <p>{{summary}}</p>
    <div class="meta">{{author}}</div>
  </div>
</article>`
const DEFAULT_CSS = `#web-card {
  width: 760px;
  overflow: hidden;
  border: 1px solid #dce2e8;
  border-radius: 8px;
  background: #fff;
  color: #17212b;
  font-family: Inter, Arial, "Microsoft YaHei", sans-serif;
}
.cover { display: block; width: 100%; max-height: 360px; object-fit: cover; }
.content { padding: 28px 32px 32px; }
.source { color: #39725d; font-size: 13px; font-weight: 700; }
h1 { margin: 12px 0 0; font-size: 30px; line-height: 1.3; }
p { margin: 14px 0 0; color: #586674; font-size: 16px; line-height: 1.65; }
.meta { margin-top: 20px; color: #74808b; font-size: 13px; }`

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createWebCardApi(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()

const sites = ref<Site[]>([])
const templates = ref<CardTemplate[]>([])
const versions = ref<TemplateVersion[]>([])
const selected = ref('')
const selectedSite = ref('')
const currentRules = ref<ParseRules>()
const templatePage = ref(1)
const templateSize = ref(8)
const templateTotal = ref(0)
const versionPage = ref(1)
const versionSize = ref(10)
const versionTotal = ref(0)
const showTemplateList = ref(false)
const versionOpen = ref(false)

const mode = ref<'STRUCTURED' | 'ADVANCED'>('STRUCTURED')
const editorTab = ref<'layout' | 'html' | 'css'>('layout')
const name = ref('')
const layoutText = ref(DEFAULT_LAYOUT)
const html = ref(DEFAULT_HTML)
const css = ref(DEFAULT_CSS)
const previewUrl = ref('')
const preview = ref('')
const previewFields = ref<Record<string, unknown>>({})
const previewFinalUrl = ref('')
const lastPreviewSignature = ref('')

const loading = ref(false)
const saving = ref(false)
const previewing = ref(false)
const publishing = ref(false)
const error = ref('')
const previewError = ref('')
let versionsRequest = 0
let draftPreviewRequest = 0

const current = computed(() => templates.value.find(value => value.id === selected.value))
const previewFieldText = computed(() => JSON.stringify(previewFields.value, null, 2))
const editorLanguage = computed(() => editorTab.value === 'html' ? 'html' : editorTab.value === 'css' ? 'css' : 'json')
const editorValue = computed({
  get: () => editorTab.value === 'html' ? html.value : editorTab.value === 'css' ? css.value : layoutText.value,
  set: value => {
    if (editorTab.value === 'html') html.value = value
    else if (editorTab.value === 'css') css.value = value
    else layoutText.value = value
  },
})
const draftSignature = computed(() => JSON.stringify({
  url: previewUrl.value.trim(),
  templateId: selected.value,
  siteId: selectedSite.value,
  mode: mode.value,
  structuredLayout: layoutText.value,
  html: html.value,
  css: css.value,
}))
const previewIsCurrent = computed(() => Boolean(preview.value) && lastPreviewSignature.value === draftSignature.value)

const templateColumns: TableColumn<CardTemplate>[] = [
  { accessorKey: 'name', header: '模板名称', minWidth: 220 },
  { accessorKey: 'siteId', header: '站点', minWidth: 180 },
  { id: 'updatedAt', header: '更新时间', width: 180 },
  { id: 'operation', header: '操作', width: 180, fixed: 'right' },
]
const versionColumns: TableColumn<TemplateVersion>[] = [
  { accessorKey: 'version', header: '版本', width: 80 },
  { accessorKey: 'origin', header: '来源', width: 100 },
  { accessorKey: 'summary', header: '变更说明', minWidth: 300 },
  { id: 'status', header: '状态', width: 120 },
  { id: 'createdAt', header: '创建时间', width: 180 },
  { id: 'operation', header: '操作', width: 110, fixed: 'right' },
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [sitePage, templateResult] = await Promise.all([
      api.sites(1, 200),
      api.templates(templatePage.value, templateSize.value),
    ])
    sites.value = sitePage.records
    templates.value = templateResult.records
    templateTotal.value = templateResult.total
    const next = templates.value.some(value => value.id === selected.value) ? selected.value : templates.value[0]?.id || ''
    if (next === selected.value) await loadVersions()
    else selected.value = next
    if (!next) showTemplateList.value = true
  }
  catch (cause) {
    error.value = errorText(cause, '加载模板失败')
  }
  finally {
    loading.value = false
  }
}

async function loadRules(siteId: string) { return siteId ? await api.rules(siteId) ?? undefined : undefined }

async function loadVersions() {
  draftPreviewRequest++
  const template = current.value
  const request = ++versionsRequest
  preview.value = ''
  previewFields.value = {}
  previewFinalUrl.value = ''
  previewError.value = ''
  lastPreviewSignature.value = ''
  if (!template) {
    versions.value = []
    name.value = ''
    selectedSite.value = ''
    currentRules.value = undefined
    versionTotal.value = 0
    return
  }
  name.value = template.name
  selectedSite.value = template.siteId
  const [result, rules, draftVersion] = await Promise.all([
    api.versions(template.id, versionPage.value, versionSize.value),
    loadRules(template.siteId),
    template.draftVersionId ? api.version(template.draftVersionId) : Promise.resolve(undefined),
  ])
  if (request !== versionsRequest || selected.value !== template.id) return
  currentRules.value = rules
  versions.value = result.records.sort((left, right) => right.version - left.version)
  versionTotal.value = result.total
  const draft = draftVersion ?? versions.value[0]
  mode.value = draft?.mode ?? template.mode ?? 'STRUCTURED'
  layoutText.value = normalizedLayout(draft?.structuredLayout)
  html.value = draft?.html || DEFAULT_HTML
  css.value = draft?.css || DEFAULT_CSS
  previewFields.value = draft?.fixture ?? {}
  editorTab.value = mode.value === 'ADVANCED' ? 'html' : 'layout'
}

async function loadVersionHistory() {
  const template = current.value
  if (!template) return
  const request = ++versionsRequest
  try {
    const result = await api.versions(template.id, versionPage.value, versionSize.value)
    if (request !== versionsRequest || selected.value !== template.id) return
    versions.value = result.records.sort((left, right) => right.version - left.version)
    versionTotal.value = result.total
  }
  catch (cause) {
    toast.error(errorText(cause, '加载版本历史失败'))
  }
}

function changeTemplateSize() {
  templatePage.value = 1
  void load()
}

function changeVersionSize() {
  versionPage.value = 1
  void loadVersionHistory()
}

function normalizedLayout(value?: string) {
  return normalizeStructuredLayout(value)
}

function validateDraft() {
  const template = current.value
  if (!template) throw new Error('请先选择或新建模板')
  if (!selectedSite.value) throw new Error('请选择所属站点')
  if (!currentRules.value || currentRules.value.siteId !== selectedSite.value) throw new Error('当前站点尚未配置解析规则')
  const codeError = validateTemplateCode(mode.value, layoutText.value, html.value)
  if (codeError) throw new Error(codeError)
  return template
}

function transientVersion(previewPassed = false): TemplateVersion {
  const template = validateDraft()
  return {
    templateId: template.id,
    version: 0,
    parseRules: currentRules.value!,
    mode: mode.value,
    structuredLayout: layoutText.value,
    html: html.value,
    css: css.value,
    fixture: previewFields.value,
    origin: 'MANUAL',
    summary: '手工编辑',
    previewPassed,
    createdAt: 0,
  }
}

async function createTemplate() {
  const siteId = selectedSite.value || sites.value[0]?.id
  if (!siteId) return toast.error('请先创建站点')
  try {
    const template = await api.saveTemplate({ id: uid(), siteId, name: '新卡片模板', mode: 'STRUCTURED', createdAt: 0, updatedAt: 0 })
    templatePage.value = 1
    selected.value = template.id
    showTemplateList.value = false
    await load()
  }
  catch (cause) {
    toast.error(errorText(cause, '新建模板失败'))
  }
}

function editTemplate(template: CardTemplate) {
  versionPage.value = 1
  selected.value = template.id
  showTemplateList.value = false
}

function removeTemplate(template: CardTemplate) {
  confirm.confirm({
    title: '删除卡片模板',
    content: `确认删除“${template.name}”？全部版本、关联内容和投递记录也会被删除。`,
    onConfirm: async () => {
      try {
        await api.deleteTemplate(template.id)
        toast.success('模板及关联数据已删除')
        if (selected.value === template.id) selected.value = ''
        if (templates.value.length === 1 && templatePage.value > 1) templatePage.value--
        await load()
      }
      catch (cause) {
        toast.error(errorText(cause, '删除模板失败'))
      }
    },
  })
}

async function saveDraft(silent = false) {
  saving.value = true
  try {
    const template = validateDraft()
    const snapshot = {
      template: { ...template },
      siteId: selectedSite.value,
      name: name.value.trim() || template.name,
      mode: mode.value,
      structuredLayout: layoutText.value,
      html: html.value,
      css: css.value,
      rules: JSON.parse(JSON.stringify(currentRules.value)) as ParseRules,
      fixture: JSON.parse(JSON.stringify(previewFields.value)) as Record<string, unknown>,
    }
    const savedTemplate = await api.saveTemplate({
      ...snapshot.template,
      siteId: snapshot.siteId,
      name: snapshot.name,
      mode: snapshot.mode,
    })
    const version = await api.saveVersion({
      templateId: savedTemplate.id,
      version: 0,
      parseRules: snapshot.rules,
      mode: snapshot.mode,
      structuredLayout: snapshot.structuredLayout,
      html: snapshot.html,
      css: snapshot.css,
      fixture: snapshot.fixture,
      origin: 'MANUAL',
      summary: '手工编辑',
      previewPassed: false,
      createdAt: 0,
    })
    if (!silent) toast.success('草稿已保存')
    await load()
    return version
  }
  catch (cause) {
    if (!silent) toast.error(errorText(cause, '保存模板草稿失败'))
    if (silent) throw cause
    return undefined
  }
  finally {
    saving.value = false
  }
}

async function makePreview() {
  if (!previewUrl.value.trim()) return toast.error('请输入用于预览的详情页链接')
  const request = ++draftPreviewRequest
  previewing.value = true
  previewError.value = ''
  const signature = draftSignature.value
  try {
    const version = transientVersion(false)
    const result = await api.previewDraft({ siteId: selectedSite.value, url: previewUrl.value.trim(), version })
    if (request !== draftPreviewRequest) return
    preview.value = `data:image/png;base64,${result.base64}`
    previewFields.value = result.fields
    previewFinalUrl.value = result.finalUrl
    lastPreviewSignature.value = signature
    toast.success('已使用真实网页内容生成预览')
  }
  catch (cause) {
    if (request !== draftPreviewRequest) return
    previewError.value = errorText(cause, '生成链接预览失败')
  }
  finally {
    if (request === draftPreviewRequest) previewing.value = false
  }
}

async function publish() {
  if (!previewIsCurrent.value) return toast.error('当前代码尚未通过链接预览，请先重新预览')
  publishing.value = true
  try {
    const version = await saveDraft(true)
    if (!version?.id) throw new Error('草稿版本保存失败')
    const verified = await api.preview(version.id, version.fixture)
    await api.publish(version.templateId, String(verified.version.id))
    toast.success('当前预览版本已发布')
    await load()
  }
  catch (cause) {
    toast.error(errorText(cause, '发布模板失败'))
  }
  finally {
    publishing.value = false
  }
}

function rollback(version: TemplateVersion) {
  const template = current.value
  if (!template || !version.id) return
  confirm.confirm({
    title: '回滚为新草稿',
    content: `基于版本 ${version.version} 创建一个新的可编辑草稿？`,
    onConfirm: async () => {
      try {
        await api.rollback(template.id, version.id!)
        versionOpen.value = false
        toast.success('回滚草稿已创建')
        await load()
      }
      catch (cause) {
        toast.error(errorText(cause, '回滚失败'))
      }
    },
  })
}

async function changeSite(value: unknown) {
  const siteId = typeof value === 'string' ? value : ''
  selectedSite.value = siteId
  try {
    const rules = await loadRules(siteId)
    if (selectedSite.value === siteId) currentRules.value = rules
  }
  catch (cause) { toast.error(errorText(cause, '加载站点解析规则失败')) }
}

watch(selected, () => void loadVersions())
watch(mode, value => { editorTab.value = value === 'ADVANCED' ? 'html' : 'layout' })
onMounted(load)
</script>

<template>
  <section class="template-page">
    <FaPageHeader title="卡片模板" description="用真实网页内容调试模板，确认效果后再保存或发布。">
      <div class="header-actions">
        <FaButton variant="outline" @click="showTemplateList = !showTemplateList">
          <FaIcon name="i-ri:list-check-2" />
          {{ showTemplateList ? '收起列表' : '模板列表' }}
        </FaButton>
        <FaButton variant="outline" @click="createTemplate">
          <FaIcon name="i-ri:add-line" />
          新建模板
        </FaButton>
        <FaButton variant="outline" :disabled="!current" @click="versionOpen = true">
          <FaIcon name="i-ri:history-line" />
          版本历史
        </FaButton>
      </div>
    </FaPageHeader>

    <FaPageMain class="template-main">
      <FaAlert v-if="error" variant="destructive" title="加载失败" :description="error" />

      <section v-if="showTemplateList" class="template-list-section">
        <div class="section-heading">
          <div>
            <h2>模板管理</h2>
            <p>选择一个模板进入编辑，或维护现有模板记录。</p>
          </div>
          <FaTag>{{ templateTotal }} 个模板</FaTag>
        </div>
        <FaTable
          v-loading="loading"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[760px]"
          border
          stripe
          :columns="templateColumns"
          :data="templates"
        >
          <template #cell-siteId="{ row }">{{ sites.find(site => site.id === row.original.siteId)?.name || row.original.siteId }}</template>
          <template #cell-updatedAt="{ row }">{{ dateTime(row.original.updatedAt) }}</template>
          <template #cell-operation="{ row }">
            <div class="row-actions">
              <FaButton size="sm" variant="outline" @click="editTemplate(row.original)">编辑</FaButton>
              <FaButton size="sm" variant="destructive" title="删除模板" @click="removeTemplate(row.original)">
                <FaIcon name="i-ri:delete-bin-line" />
              </FaButton>
            </div>
          </template>
        </FaTable>
        <FaPagination
          v-model:page="templatePage"
          v-model:size="templateSize"
          :total="templateTotal"
          class="mt-3"
          @page-change="load"
          @size-change="changeTemplateSize"
        />
      </section>

      <section v-if="current" class="editor-section">
        <div class="editor-context">
          <div class="context-fields">
            <div class="field-group name-field">
              <FaLabel>模板名称</FaLabel>
              <FaInput v-model="name" placeholder="模板名称" />
            </div>
            <div class="field-group site-field">
              <FaLabel>所属站点</FaLabel>
              <FaSelect
                :model-value="selectedSite"
                :options="sites.map(value => ({ label: value.name, value: value.id }))"
                placeholder="选择站点"
                disabled
                title="模板所属站点创建后不可直接迁移"
                @update:model-value="changeSite"
              />
            </div>
            <div class="field-group mode-field">
              <FaLabel>模板模式</FaLabel>
              <FaButtonGroup class="mode-switch">
                <FaButton :variant="mode === 'STRUCTURED' ? 'default' : 'outline'" @click="mode = 'STRUCTURED'">结构化</FaButton>
                <FaButton :variant="mode === 'ADVANCED' ? 'default' : 'outline'" @click="mode = 'ADVANCED'">HTML / CSS</FaButton>
              </FaButtonGroup>
            </div>
          </div>
          <div class="context-actions">
            <FaButton variant="outline" :loading="saving" :disabled="previewing || publishing" @click="saveDraft(false)">
              <FaIcon name="i-ri:save-line" />
              保存草稿
            </FaButton>
            <FaButton :loading="publishing" :disabled="saving || previewing || !previewIsCurrent" @click="publish">
              <FaIcon name="i-ri:send-plane-line" />
              发布当前版本
            </FaButton>
          </div>
        </div>

        <div class="workspace-shell">
          <section class="code-pane">
            <header class="pane-header">
              <div>
                <h2>模板代码</h2>
                <p>{{ mode === 'ADVANCED' ? 'Agent 生成的 HTML 与 CSS 可在此直接修改。' : '调整结构化布局配置，内容字段来自站点解析结果。' }}</p>
              </div>
              <FaTag v-if="previewIsCurrent">已预览</FaTag>
              <FaTag v-else variant="secondary">待预览</FaTag>
            </header>
            <div class="editor-tabs" role="tablist" aria-label="模板代码类型">
              <button v-if="mode === 'STRUCTURED'" class="active" type="button">布局 JSON</button>
              <template v-else>
                <button type="button" :class="{ active: editorTab === 'html' }" @click="editorTab = 'html'">HTML</button>
                <button type="button" :class="{ active: editorTab === 'css' }" @click="editorTab = 'css'">CSS</button>
              </template>
            </div>
            <TemplateCodeEditor v-model="editorValue" :language="editorLanguage" :min-height="540" />
          </section>

          <section class="preview-pane">
            <header class="pane-header preview-header">
              <div>
                <h2>链接预览</h2>
                <p>按当前站点规则抓取图文，并使用尚未保存的代码渲染。</p>
              </div>
              <FaTag v-if="previewFinalUrl">真实数据</FaTag>
            </header>
            <div class="preview-toolbar">
              <FaInput
                v-model="previewUrl"
                type="url"
                placeholder="https://www.mcmod.cn/class/3376.html"
                @keyup.enter="makePreview"
              />
              <FaButton :loading="previewing" :disabled="saving || publishing" @click="makePreview">
                <FaIcon name="i-ri:play-circle-line" />
                生成预览
              </FaButton>
            </div>
            <FaAlert v-if="previewError" variant="destructive" title="预览失败" :description="previewError" />
            <div class="preview-canvas" :class="{ loading: previewing }">
              <img v-if="preview" :src="preview" alt="卡片渲染预览">
              <div v-else class="preview-empty">
                <FaIcon name="i-ri:image-line" />
                <strong>输入一个匹配当前站点规则的链接</strong>
                <span>解析到的标题、图片、摘要和其他字段会直接用于渲染。</span>
              </div>
            </div>
            <details v-if="Object.keys(previewFields).length" class="parsed-fields">
              <summary>查看本次解析字段</summary>
              <pre>{{ previewFieldText }}</pre>
            </details>
          </section>
        </div>
      </section>

      <section v-else class="no-template">
        <FaIcon name="i-ri:layout-2-line" />
        <h2>还没有可编辑的模板</h2>
        <p>新建模板后即可使用真实链接调试卡片效果。</p>
        <FaButton @click="createTemplate"><FaIcon name="i-ri:add-line" />新建模板</FaButton>
      </section>
    </FaPageMain>

    <FaModal
      v-model="versionOpen"
      title="模板版本历史"
      description="已发布版本保持不可变，回滚会创建新的可编辑草稿。"
      class="version-modal"
      :footer="false"
    >
      <FaTable
        v-loading="loading"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[860px]"
        border
        stripe
        :columns="versionColumns"
        :data="versions"
      >
        <template #cell-status="{ row }">
          <FaTag v-if="row.original.id === current?.publishedVersionId">已发布</FaTag>
          <FaTag v-else-if="row.original.id === current?.draftVersionId">当前草稿</FaTag>
          <span v-else>历史版本</span>
        </template>
        <template #cell-createdAt="{ row }">{{ dateTime(row.original.createdAt) }}</template>
        <template #cell-operation="{ row }">
          <FaButton size="sm" variant="outline" @click="rollback(row.original)">
            <FaIcon name="i-ri:arrow-go-back-line" />
            回滚
          </FaButton>
        </template>
      </FaTable>
      <FaPagination
        v-model:page="versionPage"
        v-model:size="versionSize"
        :total="versionTotal"
        class="mt-3"
        @page-change="loadVersionHistory"
        @size-change="changeVersionSize"
      />
    </FaModal>
  </section>
</template>

<style scoped>
.template-main { display: grid; gap: 20px; }
.header-actions, .context-actions, .row-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.template-list-section, .editor-section { min-width: 0; border: 1px solid #e0e4e7; border-radius: 8px; background: #fff; }
.template-list-section { padding: 16px; }
.section-heading, .pane-header, .editor-context { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.section-heading { margin-bottom: 16px; }
.section-heading h2, .pane-header h2, .no-template h2 { margin: 0; color: #17212b; font-size: 16px; font-weight: 700; }
.section-heading p, .pane-header p, .no-template p { margin: 5px 0 0; color: #687582; font-size: 13px; line-height: 1.6; }
.editor-context { padding: 18px 20px; border-bottom: 1px solid #e4e8eb; }
.context-fields { display: grid; flex: 1; grid-template-columns: minmax(220px, 1.2fr) minmax(200px, .9fr) auto; gap: 16px; }
.field-group { display: grid; min-width: 0; gap: 7px; }
.mode-switch { align-self: end; }
.context-actions { align-self: end; justify-content: flex-end; }
.workspace-shell { display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(420px, .95fr); min-width: 0; }
.code-pane, .preview-pane { min-width: 0; padding: 20px; }
.code-pane { border-right: 1px solid #e4e8eb; }
.pane-header { min-height: 54px; margin-bottom: 16px; }
.editor-tabs { display: flex; gap: 4px; margin-bottom: 10px; padding: 4px; border: 1px solid #e0e4e7; border-radius: 6px; background: #f5f7f8; }
.editor-tabs button { min-width: 88px; height: 34px; padding: 0 14px; border: 0; border-radius: 4px; background: transparent; color: #697581; font: inherit; font-size: 13px; font-weight: 650; cursor: pointer; }
.editor-tabs button.active { background: #fff; color: #1d526f; box-shadow: 0 1px 3px rgb(23 33 43 / 12%); }
.preview-toolbar { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; margin-bottom: 14px; }
.preview-canvas { display: flex; min-height: 584px; align-items: flex-start; justify-content: center; overflow: auto; border: 1px solid #dfe4e7; border-radius: 6px; background: #f0f2f3; padding: 24px; }
.preview-canvas.loading { opacity: .7; }
.preview-canvas img { display: block; max-width: 100%; height: auto; box-shadow: 0 8px 24px rgb(28 38 47 / 12%); }
.preview-empty { display: grid; max-width: 360px; min-height: 530px; place-content: center; justify-items: center; gap: 8px; color: #75818c; text-align: center; }
.preview-empty :deep(svg) { font-size: 30px; }
.preview-empty strong { color: #3a4650; font-size: 14px; }
.preview-empty span { font-size: 13px; line-height: 1.6; }
.parsed-fields { margin-top: 12px; border: 1px solid #e0e4e7; border-radius: 6px; background: #fbfcfd; }
.parsed-fields summary { padding: 12px 14px; color: #46525d; font-size: 13px; font-weight: 650; cursor: pointer; }
.parsed-fields pre { max-height: 240px; margin: 0; overflow: auto; border-top: 1px solid #e5e8ea; padding: 14px; color: #26333d; font: 12px/1.65 "JetBrains Mono", Consolas, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.no-template { display: grid; min-height: 420px; place-content: center; justify-items: center; gap: 8px; border: 1px dashed #ccd3d8; border-radius: 8px; background: #fafbfb; text-align: center; }
.no-template :deep(svg) { font-size: 32px; color: #6d7b86; }
.no-template :deep(button) { margin-top: 8px; }
.version-modal { width: min(1120px, calc(100vw - 32px)); }
@media (max-width: 1180px) {
  .workspace-shell { grid-template-columns: 1fr; }
  .code-pane { border-right: 0; border-bottom: 1px solid #e4e8eb; }
  .preview-canvas { min-height: 460px; }
  .preview-empty { min-height: 410px; }
}
@media (max-width: 820px) {
  .template-main { gap: 14px; }
  .template-list-section { padding: 12px; }
  .editor-context { align-items: stretch; padding: 16px; flex-direction: column; }
  .context-fields { grid-template-columns: 1fr; gap: 12px; }
  .context-actions { width: 100%; justify-content: stretch; }
  .context-actions :deep(button) { flex: 1; }
  .code-pane, .preview-pane { padding: 16px; }
  .code-pane :deep(.cm-editor) { height: 360px !important; min-height: 360px !important; }
  .preview-toolbar { grid-template-columns: 1fr; }
  .preview-toolbar :deep(button) { width: 100%; }
  .preview-canvas { min-height: 360px; padding: 12px; }
  .preview-empty { min-height: 330px; }
}
@media (max-width: 520px) {
  .header-actions { width: 100%; }
  .header-actions :deep(button) { flex: 1 1 calc(50% - 4px); }
  .section-heading, .pane-header { align-items: stretch; flex-direction: column; gap: 8px; }
  .mode-switch { display: grid; grid-template-columns: 1fr 1fr; }
  .editor-tabs button { flex: 1; min-width: 0; }
  .preview-canvas { min-height: 300px; }
  .preview-empty { min-height: 275px; }
}
</style>
