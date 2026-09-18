<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaIcon, useFaToast } from '@yudream/components'
import { computed } from 'vue'
import { openYmcl as openYmclProtocol, siteOrigin } from '../composables/ymcl-protocol'

interface DashboardCardLike {
  title?: string
  description?: string
  actionPath?: string
  dragPayloadTemplate?: string
}

const props = defineProps<{
  sdk: YuDreamPluginSdk
  card?: DashboardCardLike
  onOpen?: (card?: DashboardCardLike) => void
}>()

const toast = useFaToast()
void props.sdk
const origin = computed(() => siteOrigin())

async function copyOrigin() {
  await navigator.clipboard.writeText(origin.value)
  toast.success('域站基址已复制')
}

function openYmcl() {
  const ok = openYmclProtocol(origin.value)
  if (ok) {
    toast.info('已尝试唤起 YMCL；若拉起的是旧启动器，请重新构建并安装 YMCL-Axolotl（登记 ymcl://）')
  }
}

function openPage() {
  props.onOpen?.(props.card)
}
</script>

<template>
  <div class="dashboard-card__content ymcl-connect-card">
    <div class="ymcl-connect-card__body">
      <p class="ymcl-connect-card__desc">
        {{ card?.description || '一键唤起 YMCL-Axolotl 启动器并弹出添加域确认；也可以复制基址到启动器的添加域输入框。' }}
      </p>
      <button
        type="button"
        class="ymcl-connect-card__endpoint"
        title="复制域站基址"
        @click="copyOrigin"
      >
        <code>{{ origin }}</code>
        <span class="ymcl-connect-card__copy">
          <FaIcon name="i-ri:file-copy-line" />
        </span>
      </button>
      <p class="ymcl-connect-card__hint">
        <FaIcon name="i-ri:information-line" />
        <span>点击地址复制基址；「打开 YMCL」走 ymcl:// 深链，需已安装 YMCL-Axolotl 并登记该协议。</span>
      </p>
    </div>
    <div class="ymcl-connect-card__actions">
      <FaButton size="sm" @click="openYmcl">
        <FaIcon name="i-ri:external-link-line" />
        打开 YMCL
      </FaButton>
      <FaButton v-if="card?.actionPath" size="sm" variant="outline" @click="openPage">
        打开域概览
      </FaButton>
    </div>
  </div>
</template>
