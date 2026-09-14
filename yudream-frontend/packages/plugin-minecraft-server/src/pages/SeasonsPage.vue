<script setup lang="ts">
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
import type { ModpackBinding } from '../types'
import { FaButton, FaInput, FaPageHeader, FaPageMain, FaSelect, FaTextarea } from '@yudream/components'
import InheritanceRulesEditor from '../components/InheritanceRulesEditor.vue'

const props = defineProps<{ model: MinecraftServerPluginModel }>()

const typeOptions = [
  { label: '无绑定', value: 'NONE' },
  { label: '原版版本', value: 'VANILLA' },
  { label: '站点整合包', value: 'MRPACK' },
]
const loaderOptions = [
  { label: 'Vanilla', value: 'vanilla' },
  { label: 'Fabric', value: 'fabric' },
  { label: 'Forge', value: 'forge' },
  { label: 'NeoForge', value: 'neoforge' },
]

function bindingOf(season: { modpackBinding?: ModpackBinding | null }) {
  return season.modpackBinding || { type: 'NONE' }
}

function setBindingType(season: { modpackBinding?: ModpackBinding | null }, type: string) {
  season.modpackBinding = {
    type,
    gameVersion: type === 'VANILLA' ? season.modpackBinding?.gameVersion || '' : undefined,
    loader: type === 'VANILLA' ? season.modpackBinding?.loader || 'vanilla' : undefined,
    packId: type === 'MRPACK' ? season.modpackBinding?.packId || '' : undefined,
    versionId: type === 'MRPACK' ? season.modpackBinding?.versionId || '' : undefined,
  }
}
</script>
<template>
  <FaPageHeader title="周目管理" class="mb-0" />
  <FaPageMain>
    <form class="grid gap-4" @submit.prevent="model.previewSeason">
      <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
        <label class="grid gap-2"><span>周目名称</span><FaInput v-model="model.seasonForm.name" /></label>
        <label class="grid gap-2"><span>开始时间</span><FaInput v-model="model.seasonForm.startedAtText" type="datetime-local" /></label>
        <label class="grid gap-2"><span>备注</span><FaInput v-model="model.seasonForm.remark" /></label>
      </div>
      <label class="grid gap-2"><span>周目说明</span><FaTextarea v-model="model.seasonForm.description" /></label>
      <div class="mt-4 grid gap-3">
        <h3 class="text-base font-semibold">继承规则</h3>
        <InheritanceRulesEditor :model="model" />
      </div>
      <div class="mt-4 flex flex-wrap justify-end gap-2">
        <FaButton type="submit" variant="outline" :loading="model.saving">预览继承</FaButton>
        <FaButton type="button" :disabled="!model.previewOperation" :loading="model.saving" @click="model.openSeason">确认开启</FaButton>
      </div>
    </form>
    <section v-if="model.serverForm.seasons.length" class="mt-8 grid gap-4">
      <h3 class="text-base font-semibold">已有周目的启动器绑定</h3>
      <p class="text-sm text-muted-foreground">绑定会写入当前周目，YMCL 按原版版本匹配本地实例，或按站点 packId 安装整合包。</p>
      <div v-for="season in model.serverForm.seasons" :key="season.id || season.name" class="grid gap-3 rounded-lg border p-4">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <strong>{{ season.name }}</strong>
          <span v-if="season.current" class="text-sm text-muted-foreground">当前周目</span>
        </div>
        <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
          <label class="grid gap-2">
            <span>绑定类型</span>
            <FaSelect :model-value="bindingOf(season).type || 'NONE'" :options="typeOptions" @update:model-value="setBindingType(season, String($event))" />
          </label>
          <template v-if="bindingOf(season).type === 'VANILLA'">
            <label class="grid gap-2"><span>游戏版本</span><FaInput v-model="bindingOf(season).gameVersion" placeholder="1.21.4" /></label>
            <label class="grid gap-2"><span>加载器</span><FaSelect v-model="bindingOf(season).loader" :options="loaderOptions" /></label>
          </template>
          <template v-else-if="bindingOf(season).type === 'MRPACK'">
            <label class="grid gap-2"><span>整合包 ID</span><FaInput v-model="bindingOf(season).packId" placeholder="pack-id" /></label>
            <label class="grid gap-2"><span>版本（可选）</span><FaInput v-model="bindingOf(season).versionId" placeholder="推荐版本可留空" /></label>
          </template>
        </div>
        <div class="flex justify-end">
          <FaButton size="sm" type="button" :loading="model.saving" :disabled="!season.id" @click="model.bindSeasonModpack(String(season.id), bindingOf(season))">保存绑定</FaButton>
        </div>
      </div>
    </section>
  </FaPageMain>
</template>
