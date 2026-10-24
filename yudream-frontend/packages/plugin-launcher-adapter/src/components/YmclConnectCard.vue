<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdAction, YdButton, YdIcon, useYdToast } from '@yudream/components'
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

const toast = useYdToast()
void props.sdk
const origin = computed(() => siteOrigin())

async function copyOrigin() {
  try {
    await navigator.clipboard.writeText(origin.value)
    toast.success('域站基址已复制')
  }
  catch {
    toast.error('复制失败，请手动复制')
  }
}

function openYmcl() {
  if (!openYmclProtocol(origin.value)) {
    toast.error('无法唤起 YMCL，请检查是否已安装启动器')
  }
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
      <YdAction
        type="button"
        class="launcher-dashboard-card__endpoint"
        title="复制域站基址，也可拖到 YMCL 自动填充"
        draggable="true"
        @click="copyOrigin"
        @dragstart="handleDragStart"
      >
        <code>{{ origin }}</code>
        <span class="launcher-dashboard-card__copy">
          <YdIcon name="i-ri:file-copy-line" />
        </span>
      </YdAction>
      <p class="launcher-dashboard-card__hint">
        <YdIcon name="i-ri:information-line" />
        <span>点击复制前端基址；拖到 YMCL 会写入 ymcl:site: 邀请。打开 YMCL 走 ymcl:// 协议，需启动器已登记该协议。</span>
      </p>
    </div>
    <div class="launcher-dashboard-card__actions">
      <YdButton size="sm" @click="openYmcl">
        <YdIcon name="i-ri:external-link-line" />
        打开 YMCL
      </YdButton>
      <YdButton v-if="card?.actionPath" size="sm" variant="outline" @click="openPage">
        查看说明
      </YdButton>
    </div>
  </div>
</template>
