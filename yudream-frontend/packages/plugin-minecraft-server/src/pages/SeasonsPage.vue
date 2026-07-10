<script setup lang="ts">
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
import { FaButton, FaInput, FaPageHeader, FaPageMain, FaTextarea } from '@yudream/components'
import InheritanceRulesEditor from '../components/InheritanceRulesEditor.vue'
defineProps<{ model: MinecraftServerPluginModel }>()
</script>
<template>
  <FaPageHeader title="周目管理" class="mb-0" />
  <FaPageMain><form class="grid gap-4" @submit.prevent="model.previewSeason"><div class="grid grid-cols-1 gap-3 md:grid-cols-3"><label class="grid gap-2"><span>周目名称</span><FaInput v-model="model.seasonForm.name" /></label><label class="grid gap-2"><span>开始时间</span><FaInput v-model="model.seasonForm.startedAtText" type="datetime-local" /></label><label class="grid gap-2"><span>备注</span><FaInput v-model="model.seasonForm.remark" /></label></div><label class="grid gap-2"><span>周目说明</span><FaTextarea v-model="model.seasonForm.description" /></label><div class="mt-4 grid gap-3"><h3 class="text-base font-semibold">继承规则</h3><InheritanceRulesEditor :model="model" /></div><div class="mt-4 flex flex-wrap justify-end gap-2"><FaButton type="submit" variant="outline" :loading="model.saving">预览继承</FaButton><FaButton type="button" :disabled="!model.previewOperation" :loading="model.saving" @click="model.openSeason">确认开启</FaButton></div></form></FaPageMain>
</template>
