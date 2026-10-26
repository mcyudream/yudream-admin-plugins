<script setup lang="ts">
import { ref, watch } from 'vue'
import { YdModal, YdSelect, YdButton, YdAlert, YdLoadingOverlay, useYdToast } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Bundle, ModelRow, Player } from '../types'
import { userApi } from '../api/user'
import ModelPreview from './ModelPreview.vue'
const props = defineProps<{ sdk: YuDreamPluginSdk; row?: ModelRow; load: (id: string) => Promise<Bundle> }>()
const open = defineModel<boolean>({ default: false })
const bundle = ref<Bundle>(), players = ref<Player[]>([]), player = ref(''), texture = ref('')
const loading = ref(false), saving = ref(false), error = ref(''), configured = ref(true)
const toast = useYdToast()
let generation = 0
watch([open, () => props.row], async ([visible, row]) => {
  const current = ++generation
  if (!visible || !row) { bundle.value = undefined; return }
  loading.value = true; error.value = ''; bundle.value = undefined
  try {
    const [asset, own] = await Promise.all([props.load(row.id), userApi(props.sdk).players()])
    if (generation !== current) return
    bundle.value = asset; players.value = own.records; configured.value = own.bridgeConfigured
    player.value = own.records[0]?.uuid ?? ''
  }
  catch (e) { if (generation === current) error.value = e instanceof Error ? e.message : '模型加载失败' }
  finally { if (generation === current) loading.value = false }
})
async function equip(clear: boolean) {
  if (!player.value || !props.row || saving.value) return
  saving.value = true
  try { await userApi(props.sdk).equip(player.value, clear ? '' : props.row.id, clear ? '' : texture.value); toast.success(clear ? '已恢复皮肤外观' : '角色模型已保存') }
  catch (e) { toast.error(e instanceof Error ? e.message : '外观保存失败') }
  finally { saving.value = false }
}
</script>
<template>
  <YdModal v-model="open" :title="row?.name ?? '模型预览'" :show-confirm-button="false" class="sm:max-w-3xl">
    <YdAlert v-if="error" type="error">{{ error }}</YdAlert>
    <YdLoadingOverlay :loading="loading">
      <ModelPreview v-if="bundle" :bundle="bundle" v-model:texture="texture" />
    </YdLoadingOverlay>
    <div v-if="bundle" class="ysm-form">
      <YdAlert v-if="!configured" type="warning">网站尚未配置游戏服务器连接，已保存的外观将在配置完成后同步。</YdAlert>
      <p v-if="!players.length">请先到皮肤插件创建自己的游戏角色。</p>
      <template v-else>
        <YdSelect v-model="player" aria-label="装备角色" :options="players.map(p => ({ label: p.name, value: p.uuid }))" />
        <div class="ysm-actions">
          <YdButton :disabled="saving || !row?.enabled" @click="equip(false)">装备到角色</YdButton>
          <YdButton variant="outline" :disabled="saving" @click="equip(true)">恢复皮肤外观</YdButton>
        </div>
        <p>原版玩家可以使用已加载的模型；资源有更新时，运行 /model refresh 手动刷新。安装配套 Mod 的玩家会自动更新。</p>
      </template>
    </div>
  </YdModal>
</template>
