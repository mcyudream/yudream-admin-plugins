<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ActivityProofModel } from '../composables/useActivityProof'
import type { ActivityProofMapping, ActivityProofParticipant } from '../types'
import { computed } from 'vue'
import { FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSelect, FaResponsiveTable } from '@yudream/components'

const props = defineProps<{ model: ActivityProofModel }>()
const serverOptions = computed(() => props.model.servers.map(server => ({ label: server.name, value: server.id })))
const participantColumns: TableColumn<ActivityProofParticipant>[] = [
  { accessorKey: 'playerName', header: '玩家', width: 220, fixed: 'left' },
  { accessorKey: 'studentName', header: '姓名', width: 120 },
  { accessorKey: 'studentNo', header: '当前学号', width: 150 },
  { id: 'operation', header: '映射操作', width: 280, align: 'center', fixed: 'right' },
]
const mappingColumns: TableColumn<ActivityProofMapping>[] = [
  { accessorKey: 'playerName', header: '玩家', width: 220, fixed: 'left' },
  { accessorKey: 'studentNo', header: '学号', width: 160 },
  { id: 'operation', header: '操作', width: 120, align: 'center', fixed: 'right' },
]
async function reload() { await props.model.reloadServerData() }
</script>

<template>
  <FaPageHeader title="玩家学号映射" class="mb-0">
    <FaButton variant="outline" @click="reload"><FaIcon name="i-ri:refresh-line" />刷新</FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div class="grid gap-4">
      <div class="grid max-w-md gap-2"><span>服务器</span><FaSelect v-model="model.selectedServerId" :options="serverOptions" @update:model-value="reload" /></div>
      <FaResponsiveTable row-key="playerId" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[820px]" border stripe column-visibility :columns="participantColumns" :data="model.participants">
        <template #cell-operation="{ row }"><form class="flex items-center justify-end gap-2" @submit.prevent="model.bindStudent(row.original)"><FaInput v-model="model.mappingInputs[row.original.playerId]" placeholder="输入学号" /><FaButton size="sm" type="submit">保存</FaButton></form></template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="flex flex-col gap-3">
              <div class="flex items-center justify-between gap-2">
                <span class="min-w-0 break-words text-base font-semibold">{{ row.playerName }}</span>
              </div>
              <div class="flex flex-col gap-1 text-sm">
                <div v-if="row.studentName" class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">姓名</span>
                  <span class="break-all">{{ row.studentName }}</span>
                </div>
                <div v-if="row.studentNo" class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">当前学号</span>
                  <span class="break-all">{{ row.studentNo }}</span>
                </div>
                <div class="flex gap-2">
                  <span class="shrink-0 text-secondary-foreground/60">玩家 ID</span>
                  <span class="break-all">{{ row.playerId }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-2 border-t pt-3">
                <form class="flex w-full flex-wrap items-center gap-2" @submit.prevent="model.bindStudent(row)">
                  <FaInput v-model="model.mappingInputs[row.playerId]" placeholder="输入学号" class="min-w-0 flex-1" />
                  <FaButton size="sm" type="submit">保存</FaButton>
                </form>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
      <FaPagination v-model:page="model.participantPager.page" v-model:size="model.participantPager.size" :total="model.participantPager.total" class="mt-3" @page-change="reload" @size-change="reload" />
      <div class="mt-4 grid gap-3">
        <h3 class="text-base font-semibold">已保存映射</h3>
        <FaResponsiveTable row-key="id" table-root-class="max-w-full overflow-x-auto rounded-lg" border stripe :columns="mappingColumns" :data="model.mappings">
          <template #cell-operation="{ row }"><FaButton size="sm" variant="destructive" @click="model.deleteMapping(row.original)">删除</FaButton></template>
          <template #card="{ row }">
            <FaCard class="w-full">
              <div class="flex flex-col gap-3">
                <div class="flex items-center justify-between gap-2">
                  <span class="min-w-0 break-words text-base font-semibold">{{ row.playerName }}</span>
                </div>
                <div class="flex flex-col gap-1 text-sm">
                  <div v-if="row.studentNo" class="flex gap-2">
                    <span class="shrink-0 text-secondary-foreground/60">学号</span>
                    <span class="break-all">{{ row.studentNo }}</span>
                  </div>
                  <div class="flex gap-2">
                    <span class="shrink-0 text-secondary-foreground/60">玩家 ID</span>
                    <span class="break-all">{{ row.playerId }}</span>
                  </div>
                </div>
                <div class="flex flex-wrap gap-2 border-t pt-3">
                  <FaButton size="sm" variant="destructive" @click="model.deleteMapping(row)">删除</FaButton>
                </div>
              </div>
            </FaCard>
          </template>
        </FaResponsiveTable>
        <FaPagination v-model:page="model.mappingPager.page" v-model:size="model.mappingPager.size" :total="model.mappingPager.total" class="mt-3" @page-change="reload" @size-change="reload" />
      </div>
    </div>
  </FaPageMain>
</template>
