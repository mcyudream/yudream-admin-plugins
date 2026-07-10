<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { ActivityProofModel } from '../composables/useActivityProof'
import type { ActivityProofParticipant } from '../types'
import { FaButton, FaCheckbox, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaPagination, FaSelect, FaTable } from '@yudream/components'
import { computed } from 'vue'
import ProofPanel from '../components/ProofPanel.vue'

const props = defineProps<{
  model: ActivityProofModel
}>()
const participantColumns: TableColumn<ActivityProofParticipant>[] = [
  { id: 'selected', header: '选择', width: 70, align: 'center' },
  { id: 'player', header: '玩家', width: 220, fixed: 'left' },
  { accessorKey: 'studentName', header: '姓名', width: 120 },
  { accessorKey: 'className', header: '班级', width: 140 },
  { accessorKey: 'studentNo', header: '学号', width: 140 },
  { id: 'online', header: '有效在线', width: 120 },
]
const serverOptions = computed(() => props.model.servers.map(server => ({ label: `${server.name}${server.enabled ? '' : '（停用）'}`, value: server.id })))
async function participantPageChanged() { await props.model.reloadServerData() }
</script>

<template>
  <section class="proof-page">
    <FaPageHeader title="活动证明导出" />
    <FaPageMain>
    <section class="proof-toolbar">
      <div>
        <span>Word Export</span>
        <h2>活动证明导出</h2>
      </div>
      <div class="proof-state">
        <span :class="{ ok: model.status?.dependencies.minecraftReady }">MC</span>
        <span :class="{ ok: model.status?.dependencies.studentInfoReady }">学生</span>
        <span :class="{ ok: model.status?.dependencies.wordTemplateReady }">模板能力</span>
        <span :class="{ ok: model.settings?.templateReady }">模板</span>
      </div>
    </section>

    <section v-if="model.status && !model.ready" class="proof-warning">
      <span class="i-ri:error-warning-line" />
      <span v-if="!model.status.dependencies.minecraftReady">请先启用 Minecraft 服务器插件。</span>
      <span v-else-if="!model.status.dependencies.studentInfoReady">请先启用学生信息插件。</span>
      <span v-else-if="!model.status.dependencies.wordTemplateReady">请先在能力管理中启用 Word 模板能力。</span>
      <span v-else-if="!model.settings?.templateReady">请选择 Word 模板。</span>
    </section>

    <section class="proof-grid single">
      <ProofPanel title="导出参数" eyebrow="Export">
        <form class="proof-form" @submit.prevent="model.exportWord">
          <label>
            <span>服务器</span>
            <FaSelect v-model="model.selectedServerId" :options="serverOptions" @update:model-value="model.reloadServerData" />
          </label>
          <label>
            <span>活动名称</span>
            <FaInput v-model="model.exportForm.activityName" />
          </label>
          <label>
            <span>活动日期</span>
            <FaInput v-model="model.exportForm.activityDate" placeholder="2026年7月8日" />
          </label>
          <label>
            <span>证明编号</span>
            <FaInput v-model="model.exportForm.proofNo" placeholder="留空自动生成" />
          </label>
          <label>
            <span>学院</span>
            <FaInput v-model="model.exportForm.college" />
          </label>
          <label>
            <span>落款</span>
            <FaInput v-model="model.exportForm.issuer" />
          </label>
          <label>
            <span>出具日期</span>
            <FaInput v-model="model.exportForm.issueDate" />
          </label>
          <div class="proof-inline">
            <label>
              <span>最低在线分钟</span>
              <FaNumberField v-model="model.exportForm.minOnlineMinutes" :min="0" @update:model-value="model.reloadServerData" />
            </label>
            <label class="proof-check">
              <FaCheckbox v-model="model.exportForm.includeAfk" @update:model-value="model.reloadServerData" />
              <span>计入 AFK</span>
            </label>
          </div>
          <FaButton type="submit" :disabled="model.exporting || !model.ready" :loading="model.exporting">
            <span class="i-ri:file-word-2-line" />
            生成 Word
          </FaButton>
        </form>
      </ProofPanel>

    </section>

    <ProofPanel title="玩家与学生信息" eyebrow="Participants">
      <template #action>
        <div class="proof-actions">
          <FaButton size="sm" variant="outline" type="button" @click="model.selectAll">全选</FaButton>
          <FaButton size="sm" variant="outline" type="button" @click="model.clearSelection">清空</FaButton>
          <FaButton size="sm" variant="outline" type="button" @click="model.reloadServerData">刷新</FaButton>
        </div>
      </template>
      <div class="proof-summary">
        <span>{{ model.participants.length }} 条记录</span>
        <span>{{ model.selectedCount }} 人待导出</span>
        <span>{{ model.unmatchedCount }} 人未匹配学生信息</span>
      </div>
      <FaTable row-key="playerId" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[1000px]" border stripe column-visibility :columns="participantColumns" :data="model.participants">
        <template #cell-selected="{ row }"><FaCheckbox :model-value="model.selectedPlayerIds.includes(row.original.playerId)" @update:model-value="model.togglePlayer(row.original)" /></template>
        <template #cell-player="{ row }"><strong>{{ row.original.playerName }}</strong><div>{{ row.original.playerId }}</div></template>
        <template #cell-online="{ row }">{{ model.minutes(row.original.effectiveOnlineMillis) }}</template>
      </FaTable>
      <FaPagination v-model:page="model.participantPager.page" v-model:size="model.participantPager.size" :total="model.participantPager.total" class="mt-3" @page-change="participantPageChanged" @size-change="participantPageChanged" />
    </ProofPanel>

    </FaPageMain>
  </section>
</template>
