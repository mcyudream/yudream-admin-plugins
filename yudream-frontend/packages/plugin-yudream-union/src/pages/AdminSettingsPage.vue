<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdAlert, YdButton, YdForm, YdInput, YdLabel, YdNumberField, YdPageHeader, YdSettingsLayout, YdSettingsSection, YdTag } from '@yudream/components'
import { ref } from 'vue'
import { useUnionSettings } from '../composables/useUnionSettings'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useUnionSettings(props.sdk)
const activeSection = ref('identity')
const sectionList = [
  { icon: 'i-ri:links-line', label: '站点身份', value: 'identity' },
]
</script>

<template>
  <div class="settings-page-shell">
    <YdPageHeader title="站点身份" class="mb-0">
      <YdButton :loading="model.saving" @click="model.save">
        保存
      </YdButton>
    </YdPageHeader>
    <YdAlert v-if="model.error" class="mt-3" variant="destructive" title="操作失败" :description="model.error" />
    <YdSettingsLayout
      v-model="activeSection"
      :list="sectionList"
      surface
      scroll
      width-class="max-w-[880px]"
    >
      <template #identity>
        <YdSettingsSection title="站点身份" description="siteCode 是跨站断言的受众标识，启用后不可变更；站点名展示给友站与启动器。">
          <div class="grid max-w-3xl gap-4">
            <YdLabel class="grid gap-2">
              <span>siteCode</span>
              <YdInput v-model="model.siteCode" placeholder="如 yda" maxlength="64" />
            </YdLabel>
            <YdLabel class="grid gap-2">
              <span>站点展示名</span>
              <YdInput v-model="model.siteName" placeholder="如 余梦" maxlength="64" />
            </YdLabel>
            <YdLabel class="grid gap-2">
              <span>对外 origin（由 app.web-url / app.base-url 推导，只读）</span>
              <YdInput :model-value="model.settings.origin || '未配置'" disabled />
            </YdLabel>
          </div>
        </YdSettingsSection>

        <YdSettingsSection title="联合签名公钥" description="私钥保存在宿主 SecretStore；公钥经发现文档对外发布，轮换需对各友站执行刷新。">
            <div class="grid max-w-3xl gap-4">
              <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
                <div class="grid gap-1">
                  <strong>密钥指纹</strong>
                  <code class="break-all text-sm">{{ model.keyFingerprint || '生成中…' }}</code>
                </div>
                <YdTag variant="secondary">RSA-4096</YdTag>
              </div>
              <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
                <div class="grid gap-1">
                  <strong>已宣告能力</strong>
                  <div class="flex flex-wrap gap-2">
                    <YdTag v-for="feature in model.features" :key="feature" variant="secondary">{{ feature }}</YdTag>
                    <span v-if="!model.features.length" class="text-sm text-muted-foreground">配置 siteCode 后将宣告 join-assertions 与 ban-ledger。</span>
                  </div>
                </div>
              </div>
            </div>
          </YdSettingsSection>

          <YdSettingsSection title="数据保留" description="自动任务启动后 60 秒首跑，之后每天执行一次；填 0 表示永久保留。">
            <YdForm :model="model.settings" layout="vertical">
              <div class="grid max-w-3xl gap-4 md:grid-cols-2">
                <YdLabel class="grid gap-2">
                  <span>审计记录保留天数</span>
                  <YdNumberField v-model="model.auditRetentionDays" :min="0" :max="36500" :step="1" />
                </YdLabel>
                <YdLabel class="grid gap-2">
                  <span>跨站登录事件保留天数</span>
                  <YdNumberField v-model="model.joinRetentionDays" :min="0" :max="36500" :step="1" />
                </YdLabel>
              </div>
            </YdForm>
          </YdSettingsSection>

      </template>
    </YdSettingsLayout>
  </div>
</template>
