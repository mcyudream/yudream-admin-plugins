<template>
  <section class="space-y-4">
    <FaPageHeader title="群自动化策略" />
    <FaPageMain>
      <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
        <label>连接<select v-model="form.connectionId" @change="loadGroups"><option value="">选择连接</option><option v-for="item in connections" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label>群聊<select v-model="form.channelId"><option value="">选择群聊</option><option v-for="item in groups" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label>媒体解析服务<input v-model.trim="form.mediaProviderEndpoint" placeholder="https://provider.example/parse" /></label>
        <label>通过答案，逗号分隔<input v-model="approvedText" /></label>
        <label>拒绝答案，逗号分隔<input v-model="rejectedText" /></label>
      </div>
      <div class="mt-4 flex flex-wrap gap-4">
        <label><input v-model="form.enabled" type="checkbox" /> 启用策略</label>
        <label><input v-model="form.mediaEnabled" type="checkbox" /> 解析抖音/Bilibili 链接</label>
        <label><input v-model="form.joinVerificationEnabled" type="checkbox" /> 自动审核入群申请</label>
        <label><input v-model="form.aiFallbackEnabled" type="checkbox" /> 规则未命中时使用 AI 判定</label>
        <label><input v-model="form.failClosed" type="checkbox" /> 无法判定时拒绝</label>
      </div>
      <div class="mt-4 flex gap-2"><FaButton :disabled="saving" @click="save">保存策略</FaButton><FaButton variant="outline" @click="reset">新建</FaButton></div>
    </FaPageMain>
    <FaPageMain>
      <FaTable row-key="channelId" :columns="columns" :data="policies" border stripe table-root-class="max-w-full overflow-x-auto rounded-lg" />
    </FaPageMain>
  </section>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { FaButton, FaPageHeader, FaPageMain, FaTable, useFaToast, type TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { createQqbotAutomationApi } from '../api/qqbot-automation-api'
import type { AutomationPolicy, Option } from '../types'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createQqbotAutomationApi(props.sdk)
const toast = useFaToast(); const saving = ref(false); const policies = ref<AutomationPolicy[]>([]); const connections = ref<Option[]>([]); const groups = ref<Option[]>([])
const form = reactive<AutomationPolicy>({ connectionId: '', channelId: '', enabled: true, mediaEnabled: false, mediaProviderEndpoint: '', joinVerificationEnabled: false, approvedAnswers: [], rejectedAnswers: [], aiFallbackEnabled: false, failClosed: true, providerCode: '', modelCode: '' })
const approvedText = ref(''); const rejectedText = ref('')
const columns: TableColumn<AutomationPolicy>[] = [{ accessorKey: 'connectionId', header: '连接', width: 140 }, { accessorKey: 'channelId', header: '群聊', width: 160 }, { accessorKey: 'mediaEnabled', header: '媒体解析', width: 120 }, { accessorKey: 'joinVerificationEnabled', header: '入群审核', width: 120 }]
async function refresh() { policies.value = await api.policies() }
async function loadGroups() { groups.value = form.connectionId ? await api.groups(form.connectionId) : [] }
function split(value: string) { return value.split(',').map(item => item.trim()).filter(Boolean) }
async function save() { saving.value = true; try { await api.save({ ...form, approvedAnswers: split(approvedText.value), rejectedAnswers: split(rejectedText.value) }); toast.success('策略已保存'); await refresh() } finally { saving.value = false } }
function reset() { Object.assign(form, { connectionId: '', channelId: '', enabled: true, mediaEnabled: false, mediaProviderEndpoint: '', joinVerificationEnabled: false, approvedAnswers: [], rejectedAnswers: [], aiFallbackEnabled: false, failClosed: true, providerCode: '', modelCode: '' }); approvedText.value = ''; rejectedText.value = ''; groups.value = [] }
onMounted(async () => { connections.value = await api.connections(); await refresh() })
</script>
