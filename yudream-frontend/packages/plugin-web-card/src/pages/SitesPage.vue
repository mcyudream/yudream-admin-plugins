<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { FaAlert, FaButton, FaIcon, FaInput, FaLabel, FaModal, FaPageHeader, FaPageMain, FaPagination, FaSelect, FaSwitch, FaTable, FaTextarea, useFaModal, useFaToast, type TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createWebCardApi } from '../api/web-card-api'
import type { CardTemplate, ParseRules, Site, SiteRouteRule } from '../types'
import { errorText, uid } from '../ui'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createWebCardApi(props.sdk)
const toast = useFaToast()
const confirm = useFaModal()
const rows = ref<Site[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const open = ref(false)
const saving = ref(false)
const toggling = ref('')
const testUrl = ref('')
const testResult = ref('')
const form = ref<Site>(emptySite())
const headers = ref('')
const routeRules = ref<SiteRouteRule[]>([])
const deletedRuleIds = ref<string[]>([])
const activeRuleId = ref('')
const allTemplates = ref<CardTemplate[]>([])
const patterns = ref<Record<string, string>>({})

const fieldTypeOptions = [
  { label: '单值文本', value: 'TEXT' }, { label: '链接 / 图片地址', value: 'URL' },
  { label: '文本列表', value: 'TEXT_LIST' }, { label: '键值列表', value: 'KEY_VALUE_LIST' },
  { label: '链接列表', value: 'LINK_LIST' }, { label: '二维表格', value: 'TABLE' },
]
const columns: TableColumn<Site>[] = [
  { accessorKey: 'name', header: '站点', minWidth: 180 }, { id: 'hosts', header: '允许域名', minWidth: 220 },
  { id: 'pattern', header: '子链接规则', minWidth: 280 }, { accessorKey: 'responseType', header: '类型', width: 90 },
  { id: 'enabled', header: '状态', width: 100 }, { id: 'operation', header: '操作', width: 210, fixed: 'right' },
]

const activeRule = computed(() => routeRules.value.find(value => value.id === activeRuleId.value))
const templateOptions = computed(() => [
  { label: '使用站点默认模板', value: '' },
  ...allTemplates.value.filter(value => value.siteId === form.value.id).map(value => ({ label: value.name, value: value.id })),
])

function emptySite(): Site { return { id: uid(), name: '', enabled: true, hosts: [], accessMode: 'PUBLIC_HTTP', headerNames: [], responseType: 'HTML', redirectHosts: [], createdAt: 0, updatedAt: 0 } }
function emptyRules(siteId: string): ParseRules { return { siteId, detailType: 'HTML', detailUrlPattern: '', fields: [{ name: 'title', expression: 'h1', attribute: 'text', type: 'TEXT', required: true }, { name: 'summary', expression: 'meta[name=description]', attribute: 'content', type: 'TEXT', required: false }, { name: 'image', expression: 'meta[property="og:image"]', attribute: 'content', type: 'URL', required: false }], listExpression: '', listLinkAttribute: 'href', jsonItemsPath: '', canonicalField: 'url', contentKeyField: 'url' } }
function emptyRouteRule(siteId: string, index = 1): SiteRouteRule { return { id: uid(), siteId, name: `子链接规则 ${index}`, enabled: true, templateId: '', rules: emptyRules(siteId), createdAt: 0, updatedAt: 0 } }

async function load() {
  loading.value = true; error.value = ''
  try {
    const [sites, templates] = await Promise.all([api.sites(page.value, size.value), api.templates(1, 200)])
    rows.value = sites.records; total.value = sites.total; allTemplates.value = templates.records
    await loadPatterns()
  } catch (cause) { error.value = errorText(cause, '加载站点失败') } finally { loading.value = false }
}
function create() {
  form.value = emptySite(); headers.value = ''; testResult.value = ''; testUrl.value = ''; deletedRuleIds.value = []
  const rule = emptyRouteRule(form.value.id); routeRules.value = [rule]; activeRuleId.value = rule.id; open.value = true
}
async function edit(row: Site) {
  try {
    const [detail, persistedRoutes, legacy] = await Promise.all([api.site(row.id), api.routeRules(row.id), api.rules(row.id)])
    form.value = { ...detail.site, hosts: [...detail.site.hosts], redirectHosts: [...detail.site.redirectHosts] }
    routeRules.value = persistedRoutes.length ? persistedRoutes.map(value => ({ ...value, rules: { ...value.rules, fields: value.rules.fields.map(field => ({ ...field })) } })) : legacy ? [{ ...emptyRouteRule(row.id), name: '原默认规则', rules: { ...legacy, fields: legacy.fields.map(field => ({ ...field })) } }] : [emptyRouteRule(row.id)]
    activeRuleId.value = routeRules.value[0].id; deletedRuleIds.value = []
    headers.value = Object.keys(detail.headers).map(key => `${key}: `).join('\n'); testResult.value = ''; testUrl.value = ''; open.value = true
  } catch (cause) { toast.error(errorText(cause, '加载站点详情失败')) }
}
function parseHeaders() { const result: Record<string, string> = {}; headers.value.split(/\r?\n/).map(value => value.trim()).filter(Boolean).forEach(line => { const index = line.indexOf(':'); if (index < 1) throw new Error(`Header 格式无效：${line}`); result[line.slice(0, index).trim()] = line.slice(index + 1).trim() }); return result }
async function save() {
  saving.value = true
  try {
    form.value.hosts = form.value.hosts.map(value => value.trim()).filter(Boolean)
    await api.saveSite(form.value, form.value.accessMode === 'CUSTOM_HEADERS' ? parseHeaders() : {})
    for (const rule of routeRules.value) {
      rule.siteId = form.value.id; rule.rules.siteId = form.value.id; rule.rules.detailType = form.value.responseType
      await api.saveRouteRule(rule)
    }
    if (routeRules.value[0]) await api.saveRules(form.value.id, routeRules.value[0].rules)
    await Promise.all(deletedRuleIds.value.map(id => api.deleteRouteRule(id)))
    toast.success(`站点及 ${routeRules.value.length} 条子链接规则已保存`); open.value = false; await load()
  } catch (cause) { toast.error(errorText(cause, '保存站点失败')) } finally { saving.value = false }
}
function addRouteRule() { const rule = emptyRouteRule(form.value.id, routeRules.value.length + 1); routeRules.value.push(rule); activeRuleId.value = rule.id; testResult.value = '' }
function removeRouteRule(rule: SiteRouteRule) {
  if (rule.createdAt > 0) deletedRuleIds.value.push(rule.id)
  routeRules.value = routeRules.value.filter(value => value.id !== rule.id)
  if (!routeRules.value.length) { const replacement = emptyRouteRule(form.value.id); routeRules.value = [replacement] }
  activeRuleId.value = routeRules.value[0].id; testResult.value = ''
}
function addField() { activeRule.value?.rules.fields.push({ name: '', expression: '', attribute: 'text', type: 'TEXT', required: false }) }
function removeField(index: number) { activeRule.value?.rules.fields.splice(index, 1) }
async function test(kind: 'fetch' | 'parse') {
  try {
    if (form.value.createdAt === 0) throw new Error('请先保存新站点，再进行真实链接测试')
    const result = kind === 'fetch' ? await api.testFetch(form.value.id, testUrl.value) : activeRule.value ? await api.testRouteRule(form.value.id, testUrl.value, activeRule.value) : {}
    testResult.value = JSON.stringify(result, null, 2)
  } catch (cause) { testResult.value = errorText(cause, '测试失败') }
}
function remove(row: Site) { confirm.confirm({ title: '删除站点', content: `确认删除“${row.name}”？将级联删除全部子链接规则、模板版本、内容记录、投递记录、定时任务、推送目标和访问凭据。`, onConfirm: async () => { await api.deleteSite(row.id); toast.success('站点及关联数据已删除'); if (rows.value.length === 1 && page.value > 1) page.value--; await load() } }) }
async function toggle(row: Site) { toggling.value = row.id; try { await api.saveSite({ ...row, enabled: !row.enabled }); toast.success(row.enabled ? '站点规则已停用' : '站点规则已启用'); await load() } catch (cause) { toast.error(errorText(cause, '更新站点状态失败')) } finally { toggling.value = '' } }
async function loadPatterns() { const values = await Promise.all(rows.value.map(async row => { const routes = await api.routeRules(row.id); if (routes.length) return [row.id, routes.map(value => value.rules.detailUrlPattern).join('、')] as const; const legacy = await api.rules(row.id); return [row.id, legacy?.detailUrlPattern || '未配置'] as const })); patterns.value = Object.fromEntries(values) }
onMounted(load)
</script>

<template>
  <section>
    <FaPageHeader title="站点与子链接规则" description="一个域名可维护多套子链接匹配、字段解析和卡片模板。"><FaButton @click="create"><FaIcon name="i-ri:add-line"/>新增站点</FaButton></FaPageHeader>
    <FaPageMain class="space-y-4">
      <FaAlert v-if="error" variant="destructive" title="加载失败" :description="error"/>
      <FaTable v-loading="loading" row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[1040px]" border stripe :columns="columns" :data="rows">
        <template #cell-hosts="{row}">{{ row.original.hosts.join(', ') }}</template>
        <template #cell-pattern="{row}"><span class="pattern-cell">{{ patterns[row.original.id] || '加载中' }}</span></template>
        <template #cell-enabled="{row}"><div class="status-toggle"><FaSwitch :model-value="row.original.enabled" :disabled="toggling===row.original.id" @update:model-value="toggle(row.original)"/><small>{{ row.original.enabled ? '已启用' : '已停用' }}</small></div></template>
        <template #cell-operation="{row}"><div class="row-actions"><FaButton size="sm" variant="outline" @click="edit(row.original)">管理规则</FaButton><FaButton size="sm" variant="destructive" @click="remove(row.original)">删除</FaButton></div></template>
      </FaTable>
      <FaPagination v-model:page="page" v-model:size="size" :total="total" class="mt-3" @page-change="load" @size-change="load"/>
    </FaPageMain>

    <FaModal v-model="open" title="站点与子链接规则" description="同一域名下的不同页面格式可使用独立解析字段和卡片模板。" :maximize="true" :maximizable="false" content-class="overflow-hidden p-0" :show-cancel-button="true" :confirm-button-loading="saving" @confirm="save">
      <div class="rule-workspace">
        <section class="workspace-column site-column">
          <div class="column-heading"><h3>站点访问</h3><small>域名、资源白名单和访问凭据只配置一次。</small></div>
          <div class="field"><FaLabel>站点名称</FaLabel><FaInput v-model="form.name"/></div>
          <div class="field"><FaLabel>允许域名</FaLabel><FaTextarea :model-value="form.hosts.join('\n')" :rows="3" placeholder="www.mcmod.cn" @update:model-value="value => form.hosts=String(value).split(/\r?\n/)"/></div>
          <div class="field"><FaLabel>图片与跳转资源域名</FaLabel><FaTextarea :model-value="form.redirectHosts.join('\n')" :rows="4" placeholder="i.mcmod.cn&#10;link.mcmod.cn" @update:model-value="value => form.redirectHosts=String(value).split(/\r?\n/).map(item=>item.trim()).filter(Boolean)"/><small>每行一个精确域名。</small></div>
          <div class="field"><FaLabel>页面类型</FaLabel><FaSelect v-model="form.responseType" :options="['HTML','JSON'].map(value => ({label:value,value}))"/></div>
          <div class="field"><FaLabel>访问方式</FaLabel><FaSelect v-model="form.accessMode" :options="[{label:'公开访问',value:'PUBLIC_HTTP'},{label:'自定义 Headers',value:'CUSTOM_HEADERS'}]"/></div>
          <div v-if="form.accessMode==='CUSTOM_HEADERS'" class="field"><FaLabel>自定义 Headers</FaLabel><FaTextarea v-model="headers" :rows="4" placeholder="Authorization: Bearer ...&#10;Cookie: ..."/></div>
          <div class="switch-row"><FaSwitch v-model="form.enabled"/><div><strong>{{ form.enabled ? '站点已启用' : '站点已停用' }}</strong><small>停用后该域名下所有子规则都不会响应。</small></div></div>
        </section>

        <aside class="route-sidebar">
          <div class="route-sidebar-head"><div class="column-heading"><h3>子链接规则</h3><small>{{ routeRules.length }} 条</small></div><FaButton size="sm" variant="outline" title="新增子链接规则" @click="addRouteRule"><FaIcon name="i-ri:add-line"/></FaButton></div>
          <div class="route-list">
            <button v-for="rule in routeRules" :key="rule.id" type="button" class="route-item" :class="{active:activeRuleId===rule.id}" @click="activeRuleId=rule.id;testResult=''">
              <span><strong>{{ rule.name }}</strong><small>{{ rule.rules.detailUrlPattern || '尚未填写匹配格式' }}</small></span><i :class="rule.enabled ? 'enabled' : ''"></i>
            </button>
          </div>
        </aside>

        <section v-if="activeRule" class="workspace-column editor-column">
          <div class="editor-header"><div class="column-heading"><h3>规则配置</h3><small>越具体的路径规则优先级越高。</small></div><FaButton size="sm" variant="destructive" @click="removeRouteRule(activeRule)"><FaIcon name="i-ri:delete-bin-line"/>删除规则</FaButton></div>
          <div class="rule-basics">
            <div class="field"><FaLabel>规则名称</FaLabel><FaInput v-model="activeRule.name" placeholder="例如：模组详情"/></div>
            <div class="field"><FaLabel>子链接格式</FaLabel><FaInput v-model="activeRule.rules.detailUrlPattern" placeholder="/class/{id}.html"/></div>
            <div class="field"><FaLabel>卡片模板</FaLabel><FaSelect v-model="activeRule.templateId" :options="templateOptions"/></div>
            <div class="switch-row compact"><FaSwitch v-model="activeRule.enabled"/><strong>{{ activeRule.enabled ? '规则启用' : '规则停用' }}</strong></div>
          </div>
          <div class="field-section-head"><div class="column-heading"><h3>字段解析</h3><small>列表、键值和表格会以结构化数据交给模板。</small></div><FaButton size="sm" variant="outline" @click="addField"><FaIcon name="i-ri:add-line"/>新增字段</FaButton></div>
          <div class="parser-head"><span>字段名</span><span>CSS Selector / JSONPath</span><span>读取方式</span><span>属性</span><span></span></div>
          <div class="parser-list"><div v-for="(field,index) in activeRule.rules.fields" :key="index" class="parser-row"><FaInput v-model="field.name" placeholder="如：支持版本"/><FaInput v-model="field.expression" placeholder="如：li.mcver a"/><FaSelect v-model="field.type" :options="fieldTypeOptions"/><FaInput v-model="field.attribute" :disabled="!['TEXT','URL','TEXT_LIST'].includes(field.type)" placeholder="text / href / src"/><FaButton size="sm" variant="destructive" title="删除字段" @click="removeField(index)"><FaIcon name="i-ri:delete-bin-line"/></FaButton></div></div>
          <div class="test-panel"><div class="field test-url"><FaLabel>真实链接测试</FaLabel><FaInput v-model="testUrl" placeholder="https://www.mcmod.cn/class/17142.html"/></div><div class="test-actions"><FaButton variant="outline" @click="test('fetch')">测试抓取</FaButton><FaButton variant="outline" @click="test('parse')">测试当前规则</FaButton></div><pre v-if="testResult">{{ testResult }}</pre></div>
        </section>
      </div>
    </FaModal>
  </section>
</template>

<style scoped>
.status-toggle,.row-actions,.test-actions{display:flex;align-items:center;flex-wrap:wrap;gap:8px}.status-toggle small,.column-heading small,.field small,.switch-row small,.route-item small{color:#667281}.pattern-cell{display:block;max-width:420px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.rule-workspace{display:grid;grid-template-columns:340px 260px minmax(620px,1fr);height:100%;min-height:0;background:#f4f6f8}.workspace-column,.route-sidebar{min-width:0;overflow-y:auto;padding:24px}.site-column{border-right:1px solid #dfe4e8;background:#fff}.route-sidebar{display:flex;flex-direction:column;gap:16px;border-right:1px solid #dfe4e8;background:#f8fafb}.editor-column{display:flex;flex-direction:column;gap:18px;background:#f7f9fa}.column-heading{display:grid;gap:5px}.column-heading h3{margin:0;font-size:16px}.column-heading small,.field small,.switch-row small{line-height:1.5}.field{display:grid;gap:7px;margin-top:18px}.route-sidebar-head,.editor-header,.field-section-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.route-list{display:grid;gap:8px}.route-item{display:flex;width:100%;align-items:center;justify-content:space-between;gap:12px;border:1px solid #dfe4e8;border-radius:6px;background:#fff;padding:12px;text-align:left}.route-item:hover,.route-item.active{border-color:#39725d;background:#f1f7f4}.route-item span{display:grid;min-width:0;gap:4px}.route-item strong,.route-item small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.route-item i{width:8px;height:8px;flex:none;border-radius:50%;background:#b8c0c7}.route-item i.enabled{background:#2f8f5b}.switch-row{display:flex;align-items:center;gap:12px;margin-top:18px;padding:12px;border:1px solid #dfe4e8;background:#f8fafb}.switch-row div{display:grid;gap:2px}.switch-row.compact{margin:0;background:#fff}.rule-basics{display:grid;grid-template-columns:minmax(160px,.8fr) minmax(240px,1.3fr) minmax(180px,1fr) 150px;gap:12px;align-items:end}.rule-basics .field{margin:0}.parser-head,.parser-row{display:grid;grid-template-columns:minmax(110px,.75fr) minmax(230px,1.6fr) minmax(140px,.85fr) minmax(110px,.65fr) 36px;gap:10px;align-items:center}.parser-head{padding:0 12px;color:#667281;font-size:12px}.parser-list{display:grid;gap:10px}.parser-row{padding:12px;border:1px solid #dfe4e8;border-radius:6px;background:#fff}.test-panel{display:grid;grid-template-columns:minmax(280px,1fr) auto;gap:12px;align-items:end;margin-top:auto;padding-top:18px;border-top:1px solid #dfe4e8}.test-url{margin:0}.test-panel pre{grid-column:1/-1;max-height:260px;overflow:auto;border:1px solid #dfe4e8;border-radius:6px;background:#fff;padding:12px;font-size:12px;line-height:1.55;white-space:pre-wrap}@media(max-width:1180px){.rule-workspace{grid-template-columns:300px 220px minmax(560px,1fr)}.rule-basics{grid-template-columns:1fr 1fr}.parser-head{display:none}.parser-row{grid-template-columns:1fr 1fr}.parser-row>*:nth-child(2){grid-column:1/-1}.parser-row>*:last-child{justify-self:end}}@media(max-width:820px){.rule-workspace{display:block;overflow-y:auto}.workspace-column,.route-sidebar{overflow:visible;padding:18px}.route-sidebar{border-right:0;border-bottom:1px solid #dfe4e8}.route-list{grid-template-columns:repeat(2,minmax(0,1fr))}.editor-column{min-height:720px}.rule-basics,.parser-row,.test-panel{grid-template-columns:1fr}.parser-row>*:nth-child(2),.test-panel pre{grid-column:auto}}
</style>
