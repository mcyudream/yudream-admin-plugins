<script setup lang="ts">
import type { ActionMenuItem, TableColumn, YdTablePageAction } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { UnionPeer } from '../types'
import { YdAlert, YdButton, YdCopyButton, YdForm, YdFormItem, YdInput, YdLoadingOverlay, YdModal, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout, useYdModal, useYdToast } from '@yudream/components'
import { formatDateTime } from '../composables/useUnionApiShell'
import { useUnionPeers } from '../composables/useUnionPeers'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useUnionPeers(props.sdk)
const toast = useYdToast()
const confirm = useYdModal()

const columns: TableColumn<UnionPeer>[] = [
  { id: 'site', header: '站点', minWidth: 180 },
  { id: 'origin', header: 'origin', minWidth: 220 },
  { id: 'fingerprint', header: '公钥指纹', minWidth: 160 },
  { id: 'cursor', header: '账本游标', width: 110, defaultHidden: true },
  { id: 'when', header: '注册时间', width: 170, defaultHidden: true },
  { id: 'status', header: '状态', width: 100 },
]

const rowActions: ActionMenuItem<UnionPeer>[] = [
  { label: '互通自检', icon: 'morph:shield-check', onClick: peer => model.selfTest(peer) },
  { label: '刷新公钥', icon: 'morph:refresh', onClick: peer => model.refreshPeer(peer) },
  { label: '停用/恢复', icon: 'morph:pause', onClick: peer => model.toggle(peer) },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: peer => remove(peer) },
]

const pageActions: YdTablePageAction[] = [
  { label: '签发注册令牌', icon: 'morph:key', onClick: () => model.issueToken() },
  { label: '注册受信站', icon: 'morph:plus', onClick: () => { model.enrollOpen = true } },
]

function remove(peer: UnionPeer) {
  confirm.confirm({
    title: '删除受信站点',
    content: `确认删除受信站点「${peer.id}」吗？删除后其断言不再被本站接受。`,
    onConfirm: async () => {
      try {
        await model.remove(peer)
        toast.success('已删除')
      }
      catch (cause) {
        toast.error(cause instanceof Error ? cause.message : '删除失败')
      }
    },
  })
}
</script>

<template>
  <YdTablePageLayout title="受信站点" :show-search="false" :actions="pageActions" stats-title="受信站说明">
    <template #stats>
      <div class="grid gap-2 text-sm text-muted-foreground">
        <p>受信站是本站在 hasJoined 未命中时会回源查询断言的站点；信任单向，双向互通需各自注册对方。</p>
        <p>注册流程：本页「签发注册令牌」→ 通过可信渠道把令牌交给对端管理员 → 持令牌在此注册；注册即 TOFU 固定对端 TLS 证书指纹与联合签名公钥。</p>
        <p>对端密钥轮换后执行「刷新公钥」；「互通自检」可随时核对发现文档与公钥指纹是否一致。</p>
      </div>
    </template>
    <template #table>
      <div class="flex h-full min-h-0 flex-col gap-3">
        <YdAlert v-if="model.error" class="shrink-0" variant="destructive" title="加载失败" :description="model.error" />
        <div v-if="model.issuedToken" class="flex shrink-0 flex-wrap items-center gap-2 rounded-lg border border-dashed p-3">
          <span class="text-sm text-muted-foreground">一次性注册令牌（5 分钟内有效，仅此一次显示）：</span>
          <code class="min-w-0 flex-1 truncate font-mono text-xs" :title="model.issuedToken">{{ model.issuedToken }}</code>
          <YdCopyButton :text="model.issuedToken" label="复制" size="sm" variant="outline" />
        </div>
        <YdLoadingOverlay class="min-h-0 flex-1" :loading="model.loading">
          <YdResponsiveEnhancedTable
            :columns="columns"
            :data="model.peers"
            row-key="id"
            table-root-class="h-full rounded-lg overflow-hidden"
            table-class="min-w-[720px]"
            border
            stripe
            column-visibility
            :actions="rowActions"
            empty-text="尚未注册任何受信站点"
          >
            <template #cell-site="{ row }">{{ row.original.siteName || row.original.id }}</template>
            <template #cell-origin="{ row }"><code class="break-all">{{ row.original.origin }}</code></template>
            <template #cell-fingerprint="{ row }"><code>{{ row.original.keyFingerprint }}</code></template>
            <template #cell-when="{ row }">{{ formatDateTime(row.original.addedAt) }}</template>
            <template #cell-status="{ row }">
              <YdTag :variant="row.original.status === 'trusted' ? 'secondary' : 'destructive'">
                {{ row.original.status === 'trusted' ? '受信' : '已停用' }}
              </YdTag>
            </template>
          </YdResponsiveEnhancedTable>
        </YdLoadingOverlay>
      </div>
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

  <YdModal v-model="model.enrollOpen" title="注册受信站点">
    <YdForm :model="model.enrollForm" layout="vertical">
      <div class="flex flex-col gap-4">
        <YdFormItem label="对端 origin" required>
          <YdInput v-model="model.enrollForm.origin" placeholder="https://peer.example.com" />
        </YdFormItem>
        <YdFormItem label="一次性注册令牌" required>
          <YdInput v-model="model.enrollForm.enrollToken" placeholder="对端管理员确认的注册令牌" />
        </YdFormItem>
        <YdFormItem label="备注">
          <YdInput v-model="model.enrollForm.note" placeholder="可选" />
        </YdFormItem>
      </div>
    </YdForm>
    <p class="mt-2 text-xs text-muted-foreground">注册即 TOFU 固定对端 TLS 证书指纹与联合签名公钥；请通过可信渠道核对指纹。</p>
    <template #footer>
      <YdButton :disabled="model.loading" @click="model.enroll()">注册</YdButton>
    </template>
  </YdModal>
</template>
