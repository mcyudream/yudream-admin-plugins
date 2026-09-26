<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaIcon } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createMcPanelExtra } from '../api/api-extra'
import { errorMessage } from '../composables/utils'

interface DashboardCardLike {
  actionPath?: string
}

/**
 * 首页「数据监控 / 应用实例」卡片体：单次拉取 admin/overview，
 * 展示在线节点与运行实例概览；宿主已渲染卡片标题栏。
 */
const props = defineProps<{
  sdk: YuDreamPluginSdk
  card?: DashboardCardLike
  onOpen?: (card?: DashboardCardLike) => void
}>()

const extra = createMcPanelExtra(props.sdk)
const loading = ref(false)
const error = ref('')
const nodes = ref<Record<string, number>>({})
const instances = ref<Record<string, number>>({})

const nodeText = computed(() => `${nodes.value.online ?? 0} / ${nodes.value.total ?? 0}`)
const instanceText = computed(() => `${instances.value.running ?? 0} / ${instances.value.total ?? 0}`)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await extra.overview() as Record<string, unknown>
    nodes.value = (data.nodes as Record<string, number>) ?? {}
    instances.value = (data.instances as Record<string, number>) ?? {}
  }
  catch (cause) {
    error.value = errorMessage(cause, '监控数据加载失败')
  }
  finally {
    loading.value = false
  }
}

function openCard() {
  props.onOpen?.(props.card)
}
</script>

<template>
  <div class="mcp-dash-card">
    <div v-if="loading" class="mcp-dash-card__state">
      <FaIcon name="i-ri:loader-4-line" class="animate-spin" />
      正在读取监控数据
    </div>
    <div v-else-if="error" class="mcp-dash-card__state is-error">
      <FaIcon name="i-ri:error-warning-line" />
      <span>{{ error }}</span>
      <FaButton size="sm" variant="outline" @click="load">
        重试
      </FaButton>
    </div>
    <template v-else>
      <div class="mcp-dash-card__stats">
        <div class="mcp-dash-card__stat">
          <span><FaIcon name="i-ri:server-line" />在线节点</span>
          <strong>{{ nodeText }}</strong>
        </div>
        <div class="mcp-dash-card__stat">
          <span><FaIcon name="i-ri:gamepad-line" />运行实例</span>
          <strong>{{ instanceText }}</strong>
        </div>
      </div>
      <div v-if="card?.actionPath" class="mcp-dash-card__actions">
        <FaButton size="sm" @click="openCard">
          <FaIcon name="i-ri:arrow-right-line" />
          查看详情
        </FaButton>
      </div>
    </template>
  </div>
</template>
