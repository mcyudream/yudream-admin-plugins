<script setup lang="ts">
import type { SkinPluginModel } from '../composables/useSkinPlugin'
import { YdLabel, YdButton, YdIcon, YdNumberField, YdPageHeader, YdSettingsLayout, YdSettingsSection, YdSwitch } from '@yudream/components'
import { ref } from 'vue'

defineProps<{ model: SkinPluginModel }>()

/** 设置页分区：单分区页保持统一骨架 */
const activeSection = ref('main')
const sectionList: { icon?: string, label: string, value: string }[] = [
  { label: '常规设置', value: 'main' },
]
</script>

<template>
  <div class="settings-page-shell">
    <YdPageHeader title="皮肤插件设置" class="mb-0">
      <YdButton :loading="model.saving === 'settings'" @click="model.saveSettings">
        <YdIcon name="i-ri:save-3-line" />
        保存设置
      </YdButton>
    </YdPageHeader>

    <YdSettingsLayout
      v-model="activeSection"
      :list="sectionList"
      surface
      scroll
      width-class="max-w-[880px]"
    >
      <template #main>
        <YdSettingsSection
          title="常规设置"
          description="皮肤站容量与公开上传策略。"
        >
          <div class="grid max-w-3xl gap-4">
            <YdLabel class="grid gap-2">
              <span>每个用户最大角色数</span>
              <YdNumberField v-model="model.settingsForm.maxPlayersPerUser" :min="0" :step="1" class="w-full" />
              <span class="text-sm text-muted-foreground">0 表示不限制。</span>
            </YdLabel>
            <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
              <div class="grid gap-1">
                <strong>公开上传材质</strong>
                <span class="text-sm text-muted-foreground">{{ model.settingsForm.allowPublicUpload ? '允许普通用户公开上传' : '仅管理员可公开材质' }}</span>
              </div>
              <YdSwitch v-model="model.settingsForm.allowPublicUpload" />
            </div>
          </div>
        </YdSettingsSection>
      </template>
    </YdSettingsLayout>
  </div>
</template>
