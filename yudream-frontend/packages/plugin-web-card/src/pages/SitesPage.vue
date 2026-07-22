<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { FaAlert, FaButton, FaIcon, FaInput, FaLabel, FaModal, FaPageHeader, FaPageMain, FaPagination, FaSelect, FaSwitch, FaTable, FaTextarea, useFaModal, useFaToast, type TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createWebCardApi } from '../api/web-card-api'
import type { ParseRules, Site } from '../types'
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
const rules = ref<ParseRules>(emptyRules(''))
const fieldTypeOptions = [
  { label: '单值文本', value: 'TEXT' },
  { label: '链接 / 图片地址', value: 'URL' },
  { label: '文本列表', value: 'TEXT_LIST' },
  { label: '键值列表', value: 'KEY_VALUE_LIST' },
  { label: '链接列表', value: 'LINK_LIST' },
  { label: '二维表格', value: 'TABLE' },
]
const columns: TableColumn<Site>[] = [
  { accessorKey: 'name', header: '站点', minWidth: 180 },
  { id: 'hosts', header: '允许域名', minWidth: 220 },
  { id: 'pattern', header: '匹配子链接', minWidth: 220 },
  { accessorKey: 'responseType', header: '类型', width: 90 },
  { id: 'enabled', header: '状态', width: 90 },
  { id: 'operation', header: '操作', width: 210, fixed: 'right' },
]

function emptySite(): Site { return { id: uid(), name: '', enabled: true, hosts: [], accessMode: 'PUBLIC_HTTP', headerNames: [], responseType: 'HTML', redirectHosts: [], createdAt: 0, updatedAt: 0 } }
function emptyRules(siteId: string): ParseRules { return { siteId, detailType: 'HTML', detailUrlPattern: '', fields: [{ name: 'title', expression: 'h1', attribute: 'text', type: 'TEXT', required: true }, { name: 'summary', expression: 'meta[name=description]', attribute: 'content', type: 'TEXT', required: false }, { name: 'image', expression: 'meta[property="og:image"]', attribute: 'content', type: 'URL', required: false }], listExpression: 'a', listLinkAttribute: 'href', jsonItemsPath: '$[*].url', canonicalField: 'url', contentKeyField: 'url' } }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await api.sites(page.value, size.value)
    rows.value = result.records
    total.value = result.total
    await loadPatterns()
  }
  catch (cause) { error.value = errorText(cause, '加载站点失败') }
  finally { loading.value = false }
}

function create() { form.value = emptySite(); rules.value = emptyRules(form.value.id); headers.value = ''; testResult.value = ''; testUrl.value = ''; open.value = true }
async function edit(row: Site) {
  try {
    const detail = await api.site(row.id)
    form.value = { ...detail.site, hosts: [...detail.site.hosts], redirectHosts: [...detail.site.redirectHosts] }
    rules.value = await api.rules(row.id) ?? emptyRules(row.id)
    headers.value = Object.keys(detail.headers).map(key => `${key}: `).join('\n')
    testResult.value = ''
    open.value = true
  }
  catch (cause) { toast.error(errorText(cause, '加载站点详情失败')) }
}
function parseHeaders() { const result: Record<string, string> = {}; headers.value.split(/\r?\n/).map(value => value.trim()).filter(Boolean).forEach(line => { const index = line.indexOf(':'); if (index < 1) throw new Error(`Header 格式无效：${line}`); result[line.slice(0, index).trim()] = line.slice(index + 1).trim() }); return result }
async function save() {
  saving.value = true
  try {
    form.value.hosts = form.value.hosts.map(value => value.trim()).filter(Boolean)
    rules.value.siteId = form.value.id
    rules.value.detailType = form.value.responseType
    await api.saveSite(form.value, form.value.accessMode === 'CUSTOM_HEADERS' ? parseHeaders() : {})
    await api.saveRules(form.value.id, rules.value)
    toast.success('站点与解析规则已保存')
    open.value = false
    await load()
  }
  catch (cause) { toast.error(errorText(cause, '保存站点失败')) }
  finally { saving.value = false }
}
async function test(kind: 'fetch' | 'parse') { try { const result = kind === 'fetch' ? await api.testFetch(form.value.id, testUrl.value) : await api.testParse(form.value.id, testUrl.value); testResult.value = JSON.stringify(result, null, 2) } catch (cause) { testResult.value = errorText(cause, '测试失败') } }
function addField() { rules.value.fields.push({ name: '', expression: '', attribute: 'text', type: 'TEXT', required: false }) }
function removeField(index: number) { rules.value.fields.splice(index, 1) }
function remove(row: Site) { confirm.confirm({ title: '删除站点', content: `确认删除“${row.name}”？将级联删除解析规则、模板版本、内容记录、投递记录、定时任务、推送目标和访问凭据。`, onConfirm: async () => { await api.deleteSite(row.id); toast.success('站点及关联数据已删除'); if (rows.value.length === 1 && page.value > 1) page.value--; await load() } }) }
async function toggle(row: Site) { toggling.value = row.id; try { await api.saveSite({ ...row, enabled: !row.enabled }); toast.success(row.enabled ? '站点规则已停用' : '站点规则已启用'); await load() } catch (cause) { toast.error(errorText(cause, '更新站点状态失败')) } finally { toggling.value = '' } }
async function patternFor(row: Site) { return (await api.rules(row.id))?.detailUrlPattern || '全部路径' }
const patterns = ref<Record<string, string>>({})
async function loadPatterns() { const values = await Promise.all(rows.value.map(async row => [row.id, await patternFor(row)] as const)); patterns.value = Object.fromEntries(values) }
onMounted(load)
</script>

<template>
  <section>
    <FaPageHeader title="站点与解析" description="限定可触发即时卡片的域名和子链接格式，并配置图文资源解析字段。"><FaButton @click="create"><FaIcon name="i-ri:add-line"/>新增规则</FaButton></FaPageHeader>
    <FaPageMain class="space-y-4">
      <FaAlert v-if="error" variant="destructive" title="加载失败" :description="error"/>
      <FaTable v-loading="loading" row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[980px]" border stripe :columns="columns" :data="rows">
        <template #cell-hosts="{row}">{{ row.original.hosts.join(', ') }}</template><template #cell-pattern="{row}"><code>{{ patterns[row.original.id] || '全部路径' }}</code></template><template #cell-enabled="{row}"><div class="status-toggle"><FaSwitch :model-value="row.original.enabled" :disabled="toggling===row.original.id" @update:model-value="toggle(row.original)"/><small>{{ row.original.enabled ? '已启用' : '已停用' }}</small></div></template><template #cell-operation="{row}"><div class="row-actions"><FaButton size="sm" variant="outline" @click="edit(row.original)">编辑</FaButton><FaButton size="sm" variant="destructive" @click="remove(row.original)">删除</FaButton></div></template>
      </FaTable>
      <FaPagination v-model:page="page" v-model:size="size" :total="total" @page-change="load" @size-change="load"/>
    </FaPageMain>
    <FaModal v-model="open" title="站点即时渲染规则" description="设置链接匹配、访问方式和页面字段；保存后，群内匹配链接会直接按已发布模板渲染。" class="w-[calc(100vw_-_2rem)] max-w-[1400px]" content-class="overflow-hidden p-0" :maximizable="true" :show-cancel-button="true" :confirm-button-loading="saving" @confirm="save">
      <div class="rule-modal">
        <section class="rule-column scope-column"><div class="column-heading"><h3>匹配与访问</h3><small>控制哪些链接触发，以及服务端如何访问页面资源。</small></div><div class="field"><FaLabel>站点名称</FaLabel><FaInput v-model="form.name"/></div><div class="field"><FaLabel>允许域名</FaLabel><FaTextarea :model-value="form.hosts.join('\n')" :rows="3" placeholder="www.mcmod.cn" @update:model-value="value => form.hosts=String(value).split(/\r?\n/)"/></div><div class="field"><FaLabel>图片与跳转资源域名</FaLabel><FaTextarea :model-value="form.redirectHosts.join('\n')" :rows="3" placeholder="i.mcmod.cn&#10;link.mcmod.cn" @update:model-value="value => form.redirectHosts=String(value).split(/\r?\n/).map(item=>item.trim()).filter(Boolean)"/><small>允许服务端抓取封面、头像等资源；每行一个精确域名。</small></div><div class="field"><FaLabel>子链接格式</FaLabel><FaInput v-model="rules.detailUrlPattern" placeholder="/class/{id}.html"/><small>花括号表示单个路径参数；只有匹配链接才会触发即时渲染。</small></div><div class="grid grid-cols-1 gap-3 sm:grid-cols-2"><div class="field"><FaLabel>页面类型</FaLabel><FaSelect v-model="form.responseType" :options="['HTML','JSON'].map(value => ({label:value,value}))"/></div><div class="field"><FaLabel>访问方式</FaLabel><FaSelect v-model="form.accessMode" :options="[{label:'公开访问',value:'PUBLIC_HTTP'},{label:'自定义 Headers',value:'CUSTOM_HEADERS'}]"/></div></div><div v-if="form.accessMode==='CUSTOM_HEADERS'" class="field"><FaLabel>自定义 Headers</FaLabel><FaTextarea v-model="headers" :rows="4" placeholder="Authorization: Bearer ...&#10;Cookie: ..."/></div><div class="switch-row"><FaSwitch v-model="form.enabled"/><div><strong>{{ form.enabled ? '规则已启用' : '规则已停用' }}</strong><small>{{ form.enabled ? '匹配链接会参与即时渲染' : '保留配置，但不会响应群消息' }}</small></div></div></section>
        <section class="rule-column fields-column"><div class="group-title"><div class="column-heading"><h3>页面字段解析</h3><small>复杂列表和表格会保留为结构化值，供模板动态分区展示。</small></div><FaButton size="sm" variant="outline" @click="addField"><FaIcon name="i-ri:add-line"/>新增字段</FaButton></div><div class="parser-head"><span>字段名</span><span>CSS Selector / JSONPath</span><span>读取方式</span><span>属性</span><span></span></div><div class="parser-list"><div v-for="(field,index) in rules.fields" :key="index" class="parser-row"><FaInput v-model="field.name" placeholder="如：支持版本"/><FaInput v-model="field.expression" placeholder="如：li.mcver a"/><FaSelect v-model="field.type" :options="fieldTypeOptions"/><FaInput v-model="field.attribute" :disabled="!['TEXT','URL','TEXT_LIST'].includes(field.type)" placeholder="text / href / src"/><FaButton size="sm" variant="destructive" title="删除字段" @click="removeField(index)"><FaIcon name="i-ri:delete-bin-line"/></FaButton></div></div><div class="test-panel"><div class="field"><FaLabel>真实链接测试</FaLabel><FaInput v-model="testUrl" placeholder="https://www.mcmod.cn/class/17142.html"/></div><div class="test-actions"><FaButton variant="outline" @click="test('fetch')">测试抓取</FaButton><FaButton variant="outline" @click="test('parse')">测试解析</FaButton></div><pre v-if="testResult">{{ testResult }}</pre></div></section>
      </div>
    </FaModal>
  </section>
</template>

<style scoped>.status-toggle{display:flex;align-items:center;gap:8px}.status-toggle small,.column-heading small,.field small,.switch-row small{color:#667281}.row-actions{display:flex;flex-wrap:wrap;gap:8px}.rule-modal{display:grid;grid-template-columns:minmax(320px,420px) minmax(680px,1fr);height:min(72vh,760px);min-height:520px;background:#f4f6f8}.rule-column{display:flex;min-width:0;flex-direction:column;gap:18px;overflow-y:auto;padding:24px}.scope-column{border-right:1px solid #dfe4e8;background:#fff}.fields-column{background:#f7f9fa}.column-heading{display:grid;gap:5px}.column-heading h3{margin:0;font-size:16px}.column-heading small,.field small,.switch-row small{line-height:1.5}.field{display:grid;gap:7px}.group-title{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.switch-row{display:flex;align-items:center;gap:12px;padding:12px;border:1px solid #dfe4e8;background:#f8fafb}.switch-row div{display:grid;gap:2px}.parser-head,.parser-row{display:grid;grid-template-columns:minmax(110px,.75fr) minmax(230px,1.6fr) minmax(140px,.85fr) minmax(110px,.65fr) 36px;gap:10px;align-items:center}.parser-head{padding:0 12px;color:#667281;font-size:12px}.parser-list{display:grid;gap:10px}.parser-row{padding:12px;border:1px solid #dfe4e8;border-radius:6px;background:#fff}.test-panel{display:grid;gap:12px;margin-top:auto;padding-top:18px;border-top:1px solid #dfe4e8}.test-actions{display:flex;flex-wrap:wrap;gap:8px}pre{max-height:240px;overflow:auto;border:1px solid #dfe4e8;border-radius:6px;background:#fff;padding:12px;font-size:12px;line-height:1.55;white-space:pre-wrap}@media(max-width:1050px){.rule-modal{grid-template-columns:1fr;height:76vh;overflow-y:auto}.rule-column{overflow:visible}.scope-column{border-right:0;border-bottom:1px solid #dfe4e8}.parser-head{display:none}.parser-row{grid-template-columns:1fr 1fr}.parser-row>*:nth-child(2){grid-column:1/-1}.parser-row>*:last-child{justify-self:end}}@media(max-width:640px){.rule-modal{height:calc(100vh - 152px);min-height:0}.rule-column{padding:18px}.group-title{align-items:stretch;flex-direction:column}.parser-row{grid-template-columns:1fr}.parser-row>*:nth-child(2){grid-column:auto}.parser-row>*:last-child{justify-self:end}}</style>
