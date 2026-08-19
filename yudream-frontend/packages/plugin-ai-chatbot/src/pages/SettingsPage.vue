<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { AiAgent, AiProviderOption, GroupPolicy, Option } from '../types'
import { computed, onMounted, reactive, ref } from 'vue'
import { FaButton, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTextarea } from '@yudream/components'
import { createAiChatbotApi } from '../api/ai-chatbot-api'
import { resolveGroupAgentCode, toGroupAgentOptions } from '../config/group-agent-options'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createAiChatbotApi(props.sdk)
type PolicyForm = Omit<GroupPolicy, 'quietHoursStart' | 'quietHoursEnd'> & { quietHoursStart: string; quietHoursEnd: string }
const saving = ref(false)
const loading = ref(false)
const message = ref('')
const policies = ref<GroupPolicy[]>([])
const connections = ref<Option[]>([]), groups = ref<Option[]>([])
const agents = ref<AiAgent[]>([])
const aiProviders = ref<AiProviderOption[]>([])
const connectionIds = ref<string[]>([]), channelIds = ref<string[]>([])
const connectionOptions = computed(() => connections.value.map(item => ({ label: item.name, value: item.id })))
const groupOptions = computed(() => groups.value.map(item => ({ label: `${item.name}（${item.id}）`, value: item.id })))
const agentOptions = computed(() => toGroupAgentOptions(agents.value))
const profileProviderOptions = computed(() => [{ label: '跟随对话模型/宿主默认', value: '' }, ...aiProviders.value.map(provider => ({ label: provider.name || provider.code, value: provider.code }))])
const profileModelOptions = computed(() => {
  const provider = aiProviders.value.find(item => item.code === form.profileProviderCode)
  return provider ? provider.models.map(model => ({ label: model.name || model.code, value: model.code })) : []
})
const form = reactive<PolicyForm>({ connectionId: '', channelId: '', enabled: true, randomProbability: 0.03, groupContextLimit: 12, personalContextLimit: 16, contextExpansionLimit: 12, cooldownSeconds: 30, hourlyReplyLimit: 30, quietHoursStart: '', quietHoursEnd: '', systemPrompt: '你是 YuDream 群聊助手，回答简短、友好、准确。', persona: '', randomToolCallingEnabled: false, longTermMemoryEnabled: false, semanticMemoryTopK: 5, agentCode: 'builtin-group-chatbot', providerCode: '', modelCode: '', profileProviderCode: '', profileModelCode: '', profileAnalysisMessageCount: 20, profileAutoAnalysisIntervalMinutes: 0, groupToolOpenAccess: true, mentionReplyInjection: '' })

function apply(policy: GroupPolicy) { Object.assign(form, policy, { quietHoursStart: policy.quietHoursStart ?? '', quietHoursEnd: policy.quietHoursEnd ?? '' }) }
async function loadPolicies() { loading.value = true; try { policies.value = await api.policies() } catch (error) { message.value = error instanceof Error ? error.message : '加载配置失败' } finally { loading.value = false } }
async function loadPolicy() { if (connectionIds.value.length !== 1 || channelIds.value.length !== 1) { message.value = '读取已有配置时请选择一个连接和一个群聊'; return } loading.value = true; try { apply(await api.policy(connectionIds.value[0], channelIds.value[0])); message.value = '' } catch (error) { message.value = error instanceof Error ? error.message : '读取群配置失败' } finally { loading.value = false } }
async function loadGroups() { channelIds.value = []; groups.value = connectionIds.value.length === 1 ? await api.groups(connectionIds.value[0]) : [] }
async function loadOptions() { const [connectionValues, agentValues, providerValues] = await Promise.all([api.connections(), api.agents(), api.aiProviders()]); connections.value = connectionValues; agents.value = agentValues; aiProviders.value = providerValues; form.agentCode = resolveGroupAgentCode(agentOptions.value, form.agentCode) }
async function save() { saving.value = true; message.value = ''; try { const policy = { ...form, quietHoursStart: form.quietHoursStart || null, quietHoursEnd: form.quietHoursEnd || null }; if (connectionIds.value.length && channelIds.value.length) await api.saveBatch(connectionIds.value, channelIds.value, policy); else apply(await api.save(policy)); await loadPolicies(); message.value = '已保存，下一条群消息立即按新策略处理。' } catch (error) { message.value = error instanceof Error ? error.message : '保存失败' } finally { saving.value = false } }
onMounted(async () => { await Promise.all([loadPolicies(), loadOptions()]) })
</script>

<template>
  <FaPageHeader title="AI 群聊机器人配置" class="mb-0">
    <FaButton variant="outline" :loading="loading" @click="loadPolicies">刷新</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_280px]">
      <form class="grid max-w-4xl gap-4" @submit.prevent="save">
        <p v-if="message" class="rounded border px-3 py-2 text-sm">{{ message }}</p>
        <section class="grid gap-3 rounded border p-4">
          <h3>目标群聊</h3>
          <div class="grid grid-cols-1 gap-3 md:grid-cols-2"><label class="grid gap-2">机器人连接（可多选）<FaSelect v-model="connectionIds" multiple :options="connectionOptions" placeholder="选择连接" class="w-full" @change="loadGroups" /></label><label class="grid gap-2">群聊（可多选）<FaSelect v-model="channelIds" multiple :options="groupOptions" placeholder="选择群聊" class="w-full" :disabled="connectionIds.length !== 1" /></label></div>
          <div class="flex flex-wrap gap-2"><FaButton type="button" variant="outline" :loading="loading" @click="loadPolicy">读取此群配置</FaButton><FaSwitch v-model="form.enabled">启用机器人</FaSwitch></div>
        </section>
        <section class="grid gap-3 rounded border p-4">
          <h3>回复策略</h3>
          <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
            <label class="grid gap-2">随机回复概率（0-1）<FaNumberField v-model="form.randomProbability" :min="0" :max="1" :step="0.01" class="w-full" /></label>
            <label class="grid gap-2">冷却秒数（仅随机回复）<FaNumberField v-model="form.cooldownSeconds" :min="1" :max="3600" class="w-full" /></label>
            <label class="grid gap-2">每小时上限<FaNumberField v-model="form.hourlyReplyLimit" :min="1" :max="1000" class="w-full" /></label>
            <label class="grid gap-2">Agent 应用<FaSelect v-model="form.agentCode" :options="agentOptions" placeholder="选择已发布 Agent" class="w-full" /></label>
            <label class="grid min-w-0 gap-2">画像分析供应商<FaSelect :model-value="form.profileProviderCode" :options="profileProviderOptions" placeholder="跟随对话模型/宿主默认" class="w-full" @change="(value: unknown) => { form.profileProviderCode = typeof value === 'string' ? value : ''; form.profileModelCode = '' }" /></label>
            <label class="grid min-w-0 gap-2">画像分析模型<FaSelect v-model="form.profileModelCode" :options="profileModelOptions" placeholder="选择模型" :disabled="!form.profileProviderCode" class="w-full" /></label>
            <label class="grid gap-2">画像分析消息数<FaNumberField v-model="form.profileAnalysisMessageCount" :min="1" :max="200" /><span class="text-xs text-muted-foreground">每次分析从互动记录与近一天消息库各随机抽取不超过该条数；一键分析全部时证据不足该条数的用户跳过</span></label>
            <label class="grid gap-2">画像自动更新间隔（分钟）<FaNumberField v-model="form.profileAutoAnalysisIntervalMinutes" :min="0" :max="10080" /><span class="text-xs text-muted-foreground">0 表示关闭；开启后插件每分钟检查一次，对证据充足且距上次分析超过该间隔的用户自动重新分析画像</span></label>
          </div>
        </section>
        <section class="grid gap-3 rounded border p-4"><h3>上下文与人设</h3><div class="grid grid-cols-1 gap-3 md:grid-cols-3"><label class="grid gap-2">群聊上下文条数<FaInput v-model="form.groupContextLimit" type="number" /></label><label class="grid gap-2">个人上下文条数<FaInput v-model="form.personalContextLimit" type="number" /></label><label class="grid gap-2">扩展上下文条数<FaInput v-model="form.contextExpansionLimit" type="number" /></label><label class="grid gap-2">静默开始（HH:mm）<FaInput v-model="form.quietHoursStart" placeholder="22:30" /></label><label class="grid gap-2">静默结束（HH:mm）<FaInput v-model="form.quietHoursEnd" placeholder="08:00" /></label></div><label class="grid gap-2">系统提示词<FaTextarea v-model="form.systemPrompt" :rows="3" /></label><label class="grid gap-2">人设<FaTextarea v-model="form.persona" :rows="2" placeholder="例如：Minecraft 社群管理员，活泼但不过度打扰。" /></label><label class="grid gap-2">@ 回复注入<FaTextarea v-model="form.mentionReplyInjection" :rows="2" placeholder="仅在用户明确 @ 机器人时追加到提示词，例如：优先结合知识库回答并附上来源。" /></label><span class="text-sm text-muted-foreground">工具调用（含 Wiki 求助检索与配图）由所选 Agent 应用的工作流编排统一调度，无需在此配置。</span></section>
        <div class="flex justify-end"><FaButton type="submit" :loading="saving">保存配置</FaButton></div>
        <section class="grid gap-3 rounded border p-4"><h3>Tools and long-term memory</h3><div class="flex flex-wrap gap-4"><FaSwitch v-model="form.randomToolCallingEnabled">Allow tools in random replies</FaSwitch><FaSwitch v-model="form.longTermMemoryEnabled">Enable long-term vector memory</FaSwitch><FaSwitch v-model="form.groupToolOpenAccess">群内开放只读工具（不要求绑定账号权限）</FaSwitch></div><label class="grid max-w-xs gap-2">Recall result limit<FaNumberField v-model="form.semanticMemoryTopK" :min="1" :max="20" /></label></section>
      </form>
      <aside class="grid content-start gap-2 rounded border p-4"><h3>已配置群聊</h3><button v-for="item in policies" :key="`${item.connectionId}:${item.channelId}`" type="button" class="rounded border px-3 py-2 text-left" @click="apply(item)">{{ item.connectionId }} / {{ item.channelId }}<br><small>{{ item.enabled ? '已启用' : '已停用' }}，随机 {{ item.randomProbability }}</small></button><span v-if="!policies.length" class="text-sm text-muted-foreground">尚未保存任何群配置。</span></aside>
    </div>
  </FaPageMain>
</template>
