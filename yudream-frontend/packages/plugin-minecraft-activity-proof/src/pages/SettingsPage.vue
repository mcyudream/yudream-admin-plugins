<script setup lang="ts">
import type { ActivityProofModel } from '../composables/useActivityProof'
import { computed } from 'vue'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaSelect } from '@yudream/components'

const props = defineProps<{ model: ActivityProofModel }>()
const templateOptions = computed(() => [{ label: '请选择模板', value: '' }, ...props.model.templates.map(template => ({ label: `${template.name} / ${template.code}`, value: template.id }))])
</script>

<template>
  <FaPageHeader title="活动证明配置" class="mb-0">
    <FaButton variant="outline" @click="model.reloadTemplates"><FaIcon name="i-ri:refresh-line" />刷新模板</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <form class="grid max-w-3xl gap-4" @submit.prevent="model.saveSettings">
      <div class="grid gap-3 rounded-lg border p-4">
        <div class="grid gap-1">
          <strong>Word 模板</strong>
          <span class="text-sm text-muted-foreground">{{ model.selectedTemplate?.originalFilename || model.settings?.templateFilename || '请先在 Word 模板能力中维护模板' }}</span>
        </div>
        <FaSelect v-model="model.settingsForm.templateId" :options="templateOptions" :disabled="model.saving" @update:model-value="model.selectTemplate" />
      </div>
      <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
        <label class="grid gap-2"><span>默认活动</span><FaInput v-model="model.settingsForm.defaultActivityName" /></label>
        <label class="grid gap-2"><span>默认学院</span><FaInput v-model="model.settingsForm.defaultCollege" /></label>
        <label class="grid gap-2 md:col-span-2"><span>默认落款</span><FaInput v-model="model.settingsForm.defaultIssuer" /></label>
      </div>
      <div class="flex justify-end gap-2"><FaButton type="submit" :loading="model.saving"><FaIcon name="i-ri:save-3-line" />保存配置</FaButton></div>
    </form>
  </FaPageMain>
</template>
