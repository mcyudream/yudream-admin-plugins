<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTextarea, YdTablePicker } from '@yudream/components'
import { computed, onMounted } from 'vue'
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
const qqConnectionOptions = computed(() => [
  { label: '请选择消息连接', value: '' },
  ...qqConnections.value.map(item => ({ label: `${item.name || item.id}（${item.platform || '未知平台'}）`, value: item.id })),
])
const qqGroupOptions = computed(() => qqGroups.value.map(item => ({ label: item.name || item.id, value: item.id })))

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
              </label>
            </div>
            <label class="grid gap-2">
              <span>通知消息模板</span>
              <FaTextarea v-model="settingsForm.qqMessageTemplate" :rows="4" placeholder="【新活动】{title}&#10;{summary}&#10;报名时间：{signupTime}&#10;活动时间：{activityTime}" />
              <span class="text-xs text-muted-foreground">可用占位符：{title} 活动标题、{summary} 简介、{signupTime} 报名时间、{activityTime} 活动时间；留空使用默认文案。</span>
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
