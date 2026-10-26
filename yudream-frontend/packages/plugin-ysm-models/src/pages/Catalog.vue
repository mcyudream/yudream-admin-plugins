<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdPageHeader, YdPageMain, YdPagination, YdButton, YdAlert } from '@yudream/components'
import type { ModelRow } from '../types'
import { catalogApi } from '../api/catalog'
import { usePaged } from '../composables/usePaged'
import { useModelCovers } from '../composables/useModelCovers'
import ModelGallery from '../components/ModelGallery.vue'
import OutfitDialog from '../components/OutfitDialog.vue'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = catalogApi(props.sdk), model = usePaged(api.list)
const cover = useModelCovers(api.preview)
const row = ref<ModelRow>(), preview = ref(false)
function view(value: ModelRow) { row.value = value; preview.value = true }
onMounted(model.load)
watch(model.page, model.load)
</script>
<template>
  <section class="ysm-library">
    <YdPageHeader title="服务器模型" description="发现服务器里的不同模样，选一个喜欢的模型，装备到自己的角色。">
      <YdButton variant="outline" :loading="model.loading.value" @click="model.load">刷新</YdButton>
    </YdPageHeader>
    <YdPageMain>
      <div class="ysm-library-heading"><h2>共享模型库</h2><span>{{ model.total.value }} 个模型可用</span></div>
      <YdAlert v-if="model.error.value" type="error">{{ model.error.value }}</YdAlert>
      <ModelGallery :rows="model.rows.value" :loading="model.loading.value" :failed="!!model.error.value" :cover="cover" empty-title="共享模型库还空着" empty-description="管理员将模型设为服务器共享后，就会出现在这里。" @preview="view" />
      <YdPagination v-model:page="model.page.value" :size="model.size" :sizes="[model.size]" :total="model.total.value" class="ysm-pagination" />
    </YdPageMain>
  <OutfitDialog v-model="preview" :sdk="sdk" :row="row" :load="api.preview" />
  </section>
</template>
