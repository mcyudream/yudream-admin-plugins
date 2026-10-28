<script setup lang="ts">
import { YdButton } from '@yudream/components'
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps<{ id: string, current: 'copy' | 'deployments' | 'resources', canManage: boolean }>()
const router = useRouter()
const links = computed(() => [
  { path: '', label: '返回实例' },
  { path: 'copy', label: '复制测试服' },
  { path: 'deployments', label: '覆盖与回退' },
  { path: 'resources', label: '安装 CE 资源' },
].filter(item => item.path !== props.current && (item.path !== 'copy' || props.canManage)))
</script>

<template>
  <YdButton v-for="item in links" :key="item.path" variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/instances/${encodeURIComponent(id)}${item.path ? `/${item.path}` : ''}`)">
    {{ item.label }}
  </YdButton>
</template>
