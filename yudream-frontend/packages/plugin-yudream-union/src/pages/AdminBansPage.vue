<script setup lang="ts">
import type { ActionMenuItem, TableColumn, YdTablePageAction } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { UnionBan } from '../types'
import { YdAlert, YdButton, YdForm, YdFormItem, YdInput, YdLoadingOverlay, YdModal, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout, YdTag, useYdModal, useYdToast } from '@yudream/components'
import { formatDateTime } from '../composables/useUnionApiShell'
import { useUnionBans } from '../composables/useUnionBans'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useUnionBans(props.sdk)
const toast = useYdToast()
const confirm = useYdModal()

const columns: TableColumn<UnionBan>[] = [
  { id: 'name', header: '角色名', minWidth: 160 },
  { id: 'reason', header: '原因', minWidth: 240 },
  { id: 'source', header: '来源', width: 140 },
  { id: 'status', header: '状态', width: 100 },
  { id: 'when', header: '裁决时间', width: 170 },
]

const rowActions: ActionMenuItem<UnionBan>[] = [
  { label: '解除', icon: 'morph:circle-check', danger: true, onClick: ban => lift(ban) },
]

const pageActions: YdTablePageAction[] = [
  { label: '拉取受信站账本', icon: 'morph:download', onClick: () => model.pull() },
  { label: '新增封禁', icon: 'morph:plus', onClick: () => { model.addOpen = true } },
]

function lift(ban: UnionBan) {
  confirm.confirm({
    title: '解除联合封禁',
    content: `确认解除「${ban.name}」的联合封禁吗？本站裁决会以解除事件同步给直连受信站。`,
    onConfirm: async () => {
      try {
        await model.lift(ban)
        toast.success('已解除')
      }
      catch (cause) {
        toast.error(cause instanceof Error ? cause.message : '解除失败')
      }
    },
  })
}
</script>

<template>
  <YdTablePageLayout title="联合封禁" :show-search="false" :actions="pageActions" stats-title="账本规则">
    <template #stats>
      <div class="grid gap-2 text-sm text-muted-foreground">
        <p>封禁键为规范化角色名：trim + 小写 + 去除 § 颜色码，跨受信站按名称全局匹配。</p>
        <p>生效裁决取「各直接受信站的最新账本事件」；站点只导出本站签发的裁决，不转发他站条目，避免点对点信任被隐式传递。</p>
        <p>本站管理员可解除任意条目：本站裁决会以解除事件同步给直连受信站，外来裁决仅写本地覆盖。</p>
      </div>
    </template>
    <template #table>
      <YdAlert v-if="model.error" class="mb-3" variant="destructive" title="加载失败" :description="model.error" />
      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="model.bans"
          row-key="seq"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[680px]"
          border
          stripe
          column-visibility
          :actions="rowActions"
          empty-text="当前没有生效中的联合封禁"
        >
          <template #cell-name="{ row }">{{ row.original.name }}</template>
          <template #cell-reason="{ row }">{{ row.original.reason || '-' }}</template>
          <template #cell-source="{ row }">{{ row.original.sourceSite || '本站' }}</template>
          <template #cell-status="{ row }">
            <YdTag :variant="row.original.status === 'active' ? 'destructive' : 'secondary'">
              {{ row.original.status === 'active' ? '生效中' : '已解除' }}
            </YdTag>
          </template>
          <template #cell-when="{ row }">{{ formatDateTime(row.original.createdAt) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </template>
    <template #footer>
      <YdPagination
        v-model:page="model.page"
        v-model:size="model.size"
        :total="model.total"
        @page-change="page => model.changePage(page)"
        @size-change="size => model.changePage(1, size)"
      />
    </template>
  </YdTablePageLayout>

  <YdModal v-model="model.addOpen" title="新增联合封禁">
    <YdForm :model="model.addForm" layout="vertical">
      <div class="flex flex-col gap-4">
        <YdFormItem label="角色名" required>
          <YdInput v-model="model.addForm.name" placeholder="1-16 位英数或下划线" maxlength="32" />
        </YdFormItem>
        <YdFormItem label="原因" required>
          <YdInput v-model="model.addForm.reason" placeholder="写入账本与审计" maxlength="200" />
        </YdFormItem>
        <YdFormItem label="证据摘要">
          <YdInput v-model="model.addForm.evidence" placeholder="可选" maxlength="500" />
        </YdFormItem>
      </div>
    </YdForm>
    <template #footer>
      <YdButton variant="destructive" :disabled="model.loading" @click="model.add()">封禁</YdButton>
    </template>
  </YdModal>
</template>
