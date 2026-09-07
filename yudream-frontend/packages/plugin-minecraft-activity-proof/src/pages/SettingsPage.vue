<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTextarea, YdTablePicker } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { useProofSettings } from '../composables/useProofSettings'
import { userPickerColumns } from '../composables/user-picker'

const props = defineProps<{
  sdk: YuDreamPluginSdk
}>()

const model = useProofSettings(props.sdk)
const { loading, saving, status, settings, settingsForm, selectedTemplate, qqConnections, qqGroups, loadingQqGroups, savingMembers, memberIds, memberLabels } = model

const wordTemplateReady = computed(() => status.value?.dependencies.wordTemplateReady ?? false)
const templateOptions = computed(() => [
  { label: '请选择模板', value: '' },
  ...model.templates.value.map(template => ({ label: `${template.name} / ${template.code}`, value: template.id })),
])
function protocolLabel(item: { protocol?: string | null, platform?: string | null }) {
  if (item.protocol === 'official') {
    return '官方 QQ'
  }
  if (item.protocol === 'milky') {
    return 'Milky'
  }
  return item.platform || '未知平台'
}

const qqConnectionOptions = computed(() => [
  { label: '请选择消息连接', value: '' },
  ...qqConnections.value.map(item => ({ label: `${item.name || item.id}（${protocolLabel(item)}）`, value: item.id })),
])
const extraGroupId = ref('')
const qqGroupOptions = computed(() => {
  const known = qqGroups.value.map(item => ({ label: item.name || item.id, value: item.id }))
  const knownIds = new Set(known.map(item => item.value))
  const extras = settingsForm.qqGroupIds
    .filter(id => id && !knownIds.has(id))
    .map(id => ({ label: id, value: id }))
  return [...known, ...extras]
})

function addExtraGroup() {
  const id = extraGroupId.value.trim()
  if (!id) {
    return
  }
  if (!settingsForm.qqGroupIds.includes(id)) {
    settingsForm.qqGroupIds = [...settingsForm.qqGroupIds, id]
  }
  extraGroupId.value = ''
}

onMounted(model.load)
</script>

<template>
  <FaPageHeader title="活动证明配置" class="mb-0">
    <FaButton variant="outline" :disabled="!wordTemplateReady" @click="model.reloadTemplates">
      <FaIcon name="i-ri:refresh-line" />刷新模板
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div v-loading="loading">
      <div v-if="!loading && !wordTemplateReady" class="mb-4 grid gap-2 rounded-lg border border-dashed p-6 text-center text-muted-foreground">
        <FaIcon name="i-ri:file-word-2-line" class="mx-auto text-3xl" />
        <p>系统未启用 Word 模板能力，暂时无法选择证明模板；默认信息仍可保存。</p>
      </div>
      <form class="grid max-w-3xl gap-4" @submit.prevent="model.save">
        <div class="grid gap-3 rounded-lg border p-4">
          <div class="grid gap-1">
            <strong>Word 模板</strong>
            <span class="text-sm text-muted-foreground">{{ selectedTemplate?.originalFilename || settings?.templateFilename || '请先在 Word 模板能力中维护模板' }}</span>
          </div>
          <FaSelect v-model="settingsForm.templateId" :options="templateOptions" :disabled="saving || !wordTemplateReady" @update:model-value="model.changeTemplate" />
        </div>
        <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
          <label class="grid gap-2"><span>默认活动</span><FaInput v-model="settingsForm.defaultActivityName" /></label>
          <label class="grid gap-2"><span>默认学院</span><FaInput v-model="settingsForm.defaultCollege" /></label>
          <label class="grid gap-2 md:col-span-2"><span>默认落款</span><FaInput v-model="settingsForm.defaultIssuer" /></label>
        </div>
        <div class="grid gap-3 rounded-lg border p-4">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div class="grid gap-1">
              <strong>QQ 群发布通知</strong>
              <span class="text-sm text-muted-foreground">开启后，每次发布活动将自动向所选 QQ 群发送通知；连接或群未配置完整时不会发送。</span>
            </div>
            <FaSwitch v-model="settingsForm.qqNotifyEnabled" :disabled="saving" />
          </div>
          <template v-if="settingsForm.qqNotifyEnabled">
            <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
              <label class="grid gap-2">
                <span>消息连接</span>
                <FaSelect v-model="settingsForm.qqConnectionId" :options="qqConnectionOptions" :disabled="saving" @update:model-value="model.changeQqConnection" />
              </label>
              <label class="grid gap-2">
                <span>通知群（可多选）</span>
                <FaSelect v-model="settingsForm.qqGroupIds" multiple :options="qqGroupOptions" placeholder="请选择 QQ 群" :disabled="saving || !settingsForm.qqConnectionId || loadingQqGroups" />
                <FaInput v-model="extraGroupId" placeholder="官方群 openid，回车添加" :disabled="saving || !settingsForm.qqConnectionId" @keydown.enter.prevent="addExtraGroup" />
                <span class="text-xs text-muted-foreground">官方 QQ 没有历史群列表，选项来自本进程收到过的群消息；已保存的群会保留，也可粘贴群 openid。</span>
              </label>
            </div>
            <label class="grid gap-2">
              <span>通知消息模板</span>
              <FaTextarea v-model="settingsForm.qqMessageTemplate" :rows="4" placeholder="【新活动】{title}&#10;{summary}&#10;报名时间：{signupTime}&#10;活动时间：{activityTime}" />
              <span class="text-xs text-muted-foreground">可用占位符：{title} 活动标题、{summary} 简介、{signupTime} 报名时间、{activityTime} 活动时间；留空使用默认文案。官方 QQ 机器人连接按 markdown 渲染，可在模板中使用 markdown 语法（不支持表格）。</span>
            </label>
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div class="grid gap-1">
                <strong>报名按钮</strong>
                <span class="text-sm text-muted-foreground">仅在官方 QQ 机器人连接生效：活动通知下方附带报名按钮，群成员点击即完成报名；未绑定系统账号的成员会收到绑定提示。</span>
              </div>
              <FaSwitch v-model="settingsForm.qqSignupButtonEnabled" :disabled="saving" />
            </div>
            <label v-if="settingsForm.qqSignupButtonEnabled" class="grid gap-2">
              <span>按钮文案</span>
              <FaInput v-model="settingsForm.qqSignupButtonLabel" placeholder="✅ 我要报名" />
              <span class="text-xs text-muted-foreground">留空使用默认文案「✅ 我要报名」；仅在报名开放期间展示按钮。</span>
            </label>
          </template>
        </div>
        <div v-if="settingsForm.templateId" class="grid gap-3 rounded-lg border p-4">
          <div class="grid gap-1">
            <strong>模板默认人员</strong>
            <span class="text-sm text-muted-foreground">每次导出活动证明时，所选人员的记录将自动并入名单，无需其参与活动。</span>
          </div>
          <YdTablePicker
            :model-value="memberIds"
            :columns="userPickerColumns"
            :fetcher="model.fetchUserOptions"
            row-key="id"
            label-key="label"
            title="选择模板默认人员"
            placeholder="点击搜索并选择默认人员"
            search-placeholder="输入用户名 / 昵称后回车"
            :initial-labels="memberLabels"
            :disabled="savingMembers"
            @update:model-value="model.applyMemberSelection"
          >
            <template #cell-deptNames="{ row }">
              {{ (row.original.deptNames || []).join('、') || '-' }}
            </template>
          </YdTablePicker>
        </div>
        <div class="flex justify-end gap-2">
          <FaButton type="submit" :loading="saving">
            <FaIcon name="i-ri:save-3-line" />保存配置
          </FaButton>
        </div>
      </form>
    </div>
  </FaPageMain>
</template>
