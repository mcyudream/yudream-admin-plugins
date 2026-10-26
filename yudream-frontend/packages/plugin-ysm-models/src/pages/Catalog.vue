<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdTablePageLayout, YdResponsiveEnhancedTable, YdPagination, YdButton, YdAlert, type ActionMenuItem } from '@yudream/components'
import type { ModelRow } from '../types'
import { catalogApi } from '../api/catalog'
import { usePaged } from '../composables/usePaged'
import OutfitDialog from '../components/OutfitDialog.vue'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = catalogApi(props.sdk), model = usePaged(api.list)
const row = ref<ModelRow>(), preview = ref(false)
const columns = [{ accessorKey: 'name', header: '名称', fixed: 'left' as const }, { id: 'hash', header: '资源版本' }]
const actions: ActionMenuItem<ModelRow>[] = [{ label: '预览 / 装备', onClick: value => { row.value = value; preview.value = true } }]
onMounted(model.load)
watch(model.page, model.load)
</script>
<template>
  <YdTablePageLayout title="服务器模型" :show-search="false">
    <template #actions><YdButton variant="outline" @click="model.load">刷新</YdButton></template>
    <template #table>
      <YdAlert v-if="model.error.value" type="error">{{ model.error.value }}</YdAlert>
      <YdResponsiveEnhancedTable :data="model.rows.value" :columns="columns" :actions="actions" :loading="model.loading.value" row-key="id" empty-text="服务器还没有共享模型">
        <template #cell-hash="{ row: item }"><code>{{ item.original.hash.slice(0, 12) }}</code></template>
      </YdResponsiveEnhancedTable>
    </template>
    <template #footer><YdPagination v-model:page="model.page.value" :size="model.size" :sizes="[model.size]" :total="model.total.value" /></template>
  </YdTablePageLayout>
  <OutfitDialog v-model="preview" :sdk="sdk" :row="row" :load="api.preview" />
</template>
