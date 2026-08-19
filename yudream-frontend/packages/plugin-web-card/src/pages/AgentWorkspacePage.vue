<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { FaAlert, FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaResponsiveTable, FaSelect, FaTextarea, useFaToast, type TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createWebCardApi } from '../api/web-card-api'
import type { AgentProposal, AgentSession, CardTemplate, Option, Site } from '../types'
import { dateTime, errorText } from '../ui'
const props=defineProps<{sdk:YuDreamPluginSdk}>(),api=createWebCardApi(props.sdk),toast=useFaToast()
const sites=ref<Site[]>([]),templates=ref<CardTemplate[]>([]),agents=ref<Option[]>([]),sessions=ref<AgentSession[]>([]),proposals=ref<AgentProposal[]>([]),sessionId=ref(''),siteId=ref(''),templateId=ref(''),agentCode=ref(''),message=ref(''),busy=ref(false),error=ref('')
const active=computed(()=>sessions.value.find(v=>v.id===sessionId.value)),columns:TableColumn<AgentProposal>[]=[{accessorKey:'summary',header:'提案摘要',minWidth:260},{id:'operations',header:'变更',minWidth:260},{accessorKey:'status',header:'状态',width:100},{id:'createdAt',header:'时间',width:180},{id:'operation',header:'操作',width:160}]
async function load(){try{const [s,t,a,se,p]=await Promise.all([api.sites(1,200),api.templates(1,200),api.agents(),api.sessions(1,100),api.proposals(1,100)]);sites.value=s.records;templates.value=t.records;agents.value=a;sessions.value=se.records;proposals.value=p.records;if(!sessionId.value&&sessions.value.length)sessionId.value=String(sessions.value[0].id)}catch(e){error.value=errorText(e,'加载 Agent 工作台失败')}}
async function create(){if(!siteId.value||!templateId.value||!agentCode.value){toast.error('请选择站点、模板和 Agent');return}const r=await api.createSession({siteId:siteId.value,templateId:templateId.value,agentCode:agentCode.value,messages:[],createdAt:0,updatedAt:0});sessionId.value=String(r.id);toast.success('Agent 会话已创建');await load()}
async function send(){if(!sessionId.value||!message.value.trim())return;busy.value=true;try{await api.agentMessage(sessionId.value,message.value.trim());message.value='';toast.success('Agent 已生成待审阅提案');await load()}catch(e){toast.error(errorText(e,'Agent 请求失败'))}finally{busy.value=false}}
async function apply(id:string){try{await api.applyProposal(id);toast.success('提案已应用为新草稿版本');await load()}catch(e){toast.error(errorText(e,'应用提案失败'))}}
async function reject(id:string){await api.rejectProposal(id);toast.success('提案已拒绝');await load()}
onMounted(load)
</script>
<template><section><FaPageHeader title="Agent 工作台" description="通过持续对话调整解析规则与模板；每次变更都需要人工确认。"><FaButton variant="outline" @click="create"><FaIcon name="i-ri:add-line"/>创建会话</FaButton></FaPageHeader><FaPageMain class="space-y-4"><FaAlert v-if="error" variant="destructive" title="加载失败" :description="error"/><div class="grid grid-cols-1 gap-3 md:grid-cols-4"><FaSelect v-model="siteId" :options="sites.map(v=>({label:v.name,value:v.id}))" placeholder="站点"/><FaSelect v-model="templateId" :options="templates.filter(v=>!siteId||v.siteId===siteId).map(v=>({label:v.name,value:v.id}))" placeholder="模板"/><FaSelect v-model="agentCode" :options="agents.map(v=>({label:v.name,value:v.id}))" placeholder="Agent"/><FaSelect v-model="sessionId" :options="sessions.map(v=>({label:`${v.siteId} / ${v.agentCode}`,value:String(v.id)}))" placeholder="已有会话"/></div><div class="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_360px]"><div class="space-y-3"><div class="conversation"><div v-if="!active?.messages.length" class="empty">尚无对话</div><div v-for="(item,i) in active?.messages" :key="i" class="message" :class="item.role"><strong>{{item.role==='user'?'管理员':'Agent'}}</strong><p>{{item.content}}</p></div></div><FaTextarea v-model="message" placeholder="例如：标题改用 article h1，并把作者和发布时间放到摘要下方。"/><FaButton :disabled="busy||!sessionId||!message.trim()" @click="send"><FaIcon name="i-ri:send-plane-line"/>生成变更提案</FaButton></div><FaAlert title="变更边界" description="Agent 只能修改解析规则、结构化布局、HTML 和 CSS；无法修改域名、访问凭据、群绑定或发布状态。"/></div><FaResponsiveTable row-key="id" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[900px]" border stripe :columns="columns" :data="proposals"><template #cell-operations="{row}"><code>{{row.original.operations.map((v: { target: string; operation: string; value: unknown })=>`${v.operation} ${v.target}`).join('；')}}</code></template><template #cell-createdAt="{row}">{{dateTime(row.original.createdAt)}}</template><template #cell-operation="{row}"><div v-if="row.original.status==='PENDING'" class="flex gap-2"><FaButton size="sm" @click="apply(row.original.id)">应用</FaButton><FaButton size="sm" variant="outline" @click="reject(row.original.id)">拒绝</FaButton></div></template>
<template #card="{ row }">
  <FaCard class="w-full">
    <div class="flex flex-col gap-3">
      <div class="flex items-center justify-between gap-2">
        <span class="min-w-0 break-words text-base font-semibold">{{ row.summary }}</span>
      </div>
      <div class="flex flex-col gap-1 text-sm">
        <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">状态</span><span class="break-all">{{ row.status }}</span></div>
        <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">变更</span><span class="break-all">{{ row.operations.map((v: { target: string; operation: string; value: unknown }) => `${v.operation} ${v.target}`).join('；') }}</span></div>
        <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">时间</span><span>{{ dateTime(row.createdAt) }}</span></div>
      </div>
      <div v-if="row.status === 'PENDING'" class="flex flex-wrap gap-2 border-t pt-3">
        <FaButton size="sm" @click="apply(row.id)">应用</FaButton>
        <FaButton size="sm" variant="outline" @click="reject(row.id)">拒绝</FaButton>
      </div>
    </div>
  </FaCard>
</template></FaResponsiveTable></FaPageMain></section></template>
<style scoped>.conversation{min-height:280px;max-height:440px;overflow:auto;border:1px solid var(--color-border-2);padding:16px}.empty{display:grid;min-height:240px;place-items:center;color:var(--color-text-3)}.message{margin-bottom:14px;padding:12px;border-left:3px solid rgb(var(--primary-6));background:var(--color-fill-1)}.message.assistant{border-left-color:rgb(var(--success-6))}.message p{margin:6px 0 0;white-space:pre-wrap}</style>
