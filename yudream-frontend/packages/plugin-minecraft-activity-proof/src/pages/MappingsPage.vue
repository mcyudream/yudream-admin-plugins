<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { ActivityProofMapping } from '../types'
import { FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSelect } from '@yudream/components'
import { computed, onMounted } from 'vue'
import { useMappings } from '../composables/useMappings'
import { formatTime } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
}>()

const model = useMappings(props.sdk)
const { loading, saving, mappings, servers, minecraftReady, pager, selectedServerId, createForm } = model

const serverOptions = computed(() => servers.value.map(server => ({ label: server.name, value: server.id })))

const mappingColumns: TableColumn<ActivityProofMapping>[] = [
  { id: 'player', header: '玩家', width: 220, fixed: 'left' },
  { accessorKey: 'studentNo', header: '学号', width: 160 },
  { id: 'createdAt', header: '创建时间', width: 180 },
  { id: 'operation', header: '操作', width: 120, align: 'center', fixed: 'right' },
]

onMounted(model.load)
</script>

<template>
  <FaPageHeader title="玩家学号映射" class="mb-0">
    <FaButton variant="outline" :loading="loading" @click="model.load">
      <FaIcon name="i-ri:refresh-line" />刷新
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div v-if="!loading && !minecraftReady" class="grid gap-2 rounded-lg border border-dashed p-8 text-center text-muted-foreground">
      <FaIcon name="i-ri:server-line" class="mx-auto text-3xl" />
      <p>需启用 Minecraft 服务器插件后才能维护玩家与学号的映射。</p>
    </div>
    <div v-else v-loading="loading" class="grid gap-4">
      <div class="grid max-w-md gap-2">
        <span>服务器</span>
        <FaSelect v-model="selectedServerId" :options="serverOptions" placeholder="选择服务器" @update:model-value="model.changeServer" />
      </div>
      <form class="grid gap-3 rounded-lg border p-4 md:grid-cols-[1fr_1fr_1fr_auto] md:items-end" @submit.prevent="model.create">
        <label class="grid gap-2">
          <span>玩家 ID <em class="required-mark">*</em></span>
          <FaInput v-model="createForm.playerId" placeholder="玩家 UUID" />
        </label>
        <label class="grid gap-2">
          <span>玩家名称</span>
          <FaInput v-model="createForm.playerName" placeholder="游戏内昵称" />
        </label>
        <label class="grid gap-2">
          <span>学号 <em class="required-mark">*</em></span>
          <FaInput v-model="createForm.studentNo" placeholder="对应学生学号" />
        </label>
        <FaButton type="submit" :loading="saving">
          <FaIcon name="i-ri:add-line" />保存映射
        </FaButton>
      </form>
      <div class="proof-min-w-0">
      <FaResponsiveTable
        v-loading="loading"
        row-key="id"
        table-root-class="proof-table-scroll"
        table-class="proof-table-w860"
        border
        stripe
        column-visibility
        :columns="mappingColumns"
        :data="mappings"
      >
        <template #cell-player="{ row }">
          <strong>{{ row.original.playerName || '-' }}</strong>
          <div>{{ row.original.playerId }}</div>
        </template>
        <template #cell-createdAt="{ row }">{{ formatTime(row.original.createdAt) }}</template>
        <template #cell-operation="{ row }">
          <div class="flex-center gap-2">
            <FaButton size="sm" variant="destructive" :loading="saving" @click="model.remove(row.original)">删除</FaButton>
          </div>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.playerName || '-' }}</span>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">学号</span>
                  <span class="break-all">{{ row.studentNo }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">玩家 ID</span>
                  <span class="break-all">{{ row.playerId }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">创建时间</span>
                  <span>{{ formatTime(row.createdAt) }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <FaButton size="sm" variant="destructive" :loading="saving" @click="model.remove(row)">删除</FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
      </div>
      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="model.loadMappings"
        @size-change="model.loadMappings"
      />
    </div>
  </FaPageMain>
</template>
