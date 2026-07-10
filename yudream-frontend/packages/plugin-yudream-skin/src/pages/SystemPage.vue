<script setup lang="ts">
import type { SkinPluginModel } from '../composables/useSkinPlugin'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaSwitch } from '@yudream/components'

defineProps<{ model: SkinPluginModel }>()
</script>

<template>
  <FaPageHeader title="皮肤插件设置" class="mb-0" />
  <FaPageMain>
    <form class="grid max-w-3xl gap-4" @submit.prevent="model.saveSettings">
      <label class="grid gap-2">
        <span>每个用户最大角色数</span>
        <FaInput v-model.number="model.settingsForm.maxPlayersPerUser" type="number" placeholder="0 表示不限制" />
      </label>
      <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
        <div class="grid gap-1">
          <strong>公开上传材质</strong>
          <span class="text-sm text-muted-foreground">{{ model.settingsForm.allowPublicUpload ? '允许普通用户公开上传' : '仅管理员可公开材质' }}</span>
        </div>
        <FaSwitch v-model="model.settingsForm.allowPublicUpload" />
      </div>
      <div class="flex justify-end gap-2">
        <FaButton type="submit" :loading="model.saving === 'settings'"><FaIcon name="i-ri:save-3-line" />保存设置</FaButton>
      </div>
    </form>
  </FaPageMain>
</template>
