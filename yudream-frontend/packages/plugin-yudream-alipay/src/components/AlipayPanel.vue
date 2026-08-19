<template>
  <FaCard :title="title" :description="eyebrow">
    <slot />
  </FaCard>
</template>

<script setup lang="ts">
import { FaCard } from '@yudream/components'

defineProps<{
  title: string
  eyebrow: string
}>()
</script>
