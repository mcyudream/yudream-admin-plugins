<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdPageHeader, YdPageMain, YdSettingsSection, YdSwitch, YdAlert, YdButton, YdLoadingOverlay, useYdToast } from '@yudream/components'
import type { ModelSettings } from '../types'
import { adminApi } from '../api/admin'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = adminApi(props.sdk), toast = useYdToast()
const settings = ref<ModelSettings>(), loading = ref(false), saving = ref(false), error = ref('')
async function load() {
  loading.value = true; error.value = ''
  try { settings.value = await api.settings() }
  catch (e) { error.value = e instanceof Error ? e.message : '设置加载失败' }
  finally { loading.value = false }
}
async function update(key: keyof ModelSettings, value: boolean) {
  if (!settings.value || saving.value) return
  saving.value = true; error.value = ''
  try { settings.value = await api.saveSettings({ ...settings.value, [key]: value }); toast.success('设置已生效') }
  catch (e) { error.value = e instanceof Error ? e.message : '设置保存失败' }
  finally { saving.value = false }
}
onMounted(load)
</script>
<template>
  <section class="ysm-settings">
    <YdPageHeader title="模型设置" description="控制模型上传与审核。开关自动保存，保存成功后立即生效。" />
    <YdPageMain>
      <YdLoadingOverlay :loading="loading">
        <div class="ysm-settings-content">
          <YdAlert v-if="error" type="error">{{ error }}<YdButton v-if="!settings" variant="outline" size="sm" @click="load">重新加载</YdButton></YdAlert>
          <YdSettingsSection v-if="settings" title="上传与审核" description="策略同时适用于原始 .ysm 和明文 ZIP。">
            <div class="ysm-setting-row">
              <div><strong>允许上传模型</strong><p>关闭后，所有用户都无法上传新模型或新版本，已有可用模型仍可装备。</p></div>
              <YdSwitch :model-value="settings.uploadEnabled" :disabled="saving || loading" aria-label="允许上传模型" @update:model-value="update('uploadEnabled', !!$event)" />
            </div>
            <div class="ysm-setting-row">
              <div><strong>上传后需要审核</strong><p>开启后，新模型和新版本须经管理员审核通过，才能装备或加入服务器模型库。</p></div>
              <YdSwitch :model-value="settings.reviewRequired" :disabled="saving || loading" aria-label="上传后需要审核" @update:model-value="update('reviewRequired', !!$event)" />
            </div>
            <YdAlert v-if="!settings.uploadEnabled" type="warning">模型上传已关闭。重新打开上传开关后，用户即可继续提交。</YdAlert>
            <p class="ysm-settings-note">开启审核不会追溯审核已有模型；关闭审核也不会自动通过待审核或已驳回的模型。请在「模型管理」中处理待审核提交。已装备模型上传新版本后，审核期间角色将暂时恢复皮肤外观。</p>
          </YdSettingsSection>
        </div>
      </YdLoadingOverlay>
    </YdPageMain>
  </section>
</template>
