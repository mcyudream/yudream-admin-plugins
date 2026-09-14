<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaIcon, useFaToast } from '@yudream/components'
import { computed } from 'vue'
import { applyYmclDragPayload, openYmcl as openYmclProtocol, siteOrigin } from '../composables/ymcl-protocol'

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
  openYmclProtocol(origin.value)
}

function handleDragStart(event: DragEvent) {
  applyYmclDragPayload(event, origin.value)
}

function openPage() {
  props.onOpen?.(props.card)
}
</script>

<template>
  <div class="dashboard-card__content launcher-dashboard-card">
    <div class="launcher-dashboard-card__body">
      <p class="launcher-dashboard-card__desc">
        {{ card?.description || '一键向 YMCL 添加本域授权。点击拉起启动器，或把地址拖到 YMCL 的站点输入框。' }}
      </p>
      <button
        type="button"
        class="launcher-dashboard-card__endpoint"
        title="复制域站基址，也可拖到 YMCL 自动填充"
        draggable="true"
        @click="copyOrigin"
        @dragstart="handleDragStart"
      >
        <code>{{ origin }}</code>
        <span class="launcher-dashboard-card__copy">
          <FaIcon name="i-ri:file-copy-line" />
        </span>
      </button>
      <p class="launcher-dashboard-card__hint">
        <FaIcon name="i-ri:information-line" />
        <span>点击复制前端基址；拖到 YMCL 会写入 ymcl:site: 邀请。打开 YMCL 走 ymcl:// 协议，需启动器已登记该协议。</span>
      </p>
    </div>
    <div class="launcher-dashboard-card__actions">
      <FaButton size="sm" @click="openYmcl">
        <FaIcon name="i-ri:external-link-line" />
        打开 YMCL
      </FaButton>
      <FaButton v-if="card?.actionPath" size="sm" variant="outline" @click="openPage">
        查看说明
      </FaButton>
    </div>
  </div>
</template>
