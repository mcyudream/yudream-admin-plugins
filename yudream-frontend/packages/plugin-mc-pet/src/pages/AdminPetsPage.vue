<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PetAdminItem } from '../types'
import { YdButton, YdDrawer, YdLoadingOverlay, YdPagination, YdResponsiveEnhancedTable, YdTablePageLayout } from '@yudream/components'
import { usePetAdmin } from '../composables/usePetAdmin'

const props = defineProps<{
  sdk: YuDreamPluginSdk
}>()

const model = usePetAdmin(props.sdk)
const { loading, acting, records, pager, detail, detailVisible } = model

const columns: TableColumn<PetAdminItem>[] = [
  { accessorKey: 'userId', header: '用户', width: 200 },
  { id: 'mode', header: '偏好模式', width: 110 },
  { id: 'source', header: '皮肤来源', width: 160 },
  { id: 'size', header: '尺寸', width: 90 },
  { id: 'hidden', header: '状态', width: 100 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
]

/** 行操作统一收进操作菜单，重置保持确认流程 */
const rowActions: ActionMenuItem<PetAdminItem>[] = [
  { label: '详情', icon: 'morph:eye', disabled: () => acting.value, onClick: row => model.openDetail(row) },
  { label: '重置偏好', icon: 'morph:rotate-ccw', danger: true, disabled: () => acting.value, onClick: row => model.reset(row) },
]

const modeLabels: Record<string, string> = {
  default: '跟随默认',
  player: '指定角色',
  closet: '衣柜皮肤',
}

const cornerLabels: Record<string, string> = {
  'bottom-right': '右下角',
  'bottom-left': '左下角',
  'top-right': '右上角',
  'top-left': '左上角',
}

const clickActionLabels: Record<string, string> = {
  menu: '弹出菜单',
  greet: '打招呼动画',
  none: '无响应',
}

function formatTime(value?: string) {
  if (!value) {
    return '—'
  }
  const time = Number(value)
  if (!Number.isFinite(time) || time <= 0) {
    return '—'
  }
  return new Date(time).toLocaleString()
}
</script>

<template>
  <section class="mc-pet-page">
    <YdTablePageLayout title="用户宠物" :show-search="false">
      <template #table>
        <YdLoadingOverlay :loading="loading">
          <YdResponsiveEnhancedTable
            row-key="userId"
            table-root-class="rounded-lg overflow-hidden"
            table-class="min-w-[860px]"
            border
            stripe
            column-visibility
            :actions="rowActions"
            :columns="columns"
            :data="records"
            empty-text="暂无用户宠物"
          >
            <template #cell-mode="{ row }">
              {{ modeLabels[row.original.preference.mode] || row.original.preference.mode }}
            </template>
            <template #cell-source="{ row }">
              <span v-if="row.original.effective.source === 'texture'">皮肤材质</span>
              <span v-else>内置 Steve</span>
              <span v-if="!row.original.effective.skinAvailable" class="mc-pet-muted">（皮肤插件不可用）</span>
            </template>
            <template #cell-size="{ row }">
              {{ row.original.effective.size }}px
            </template>
            <template #cell-hidden="{ row }">
              <span class="yd-status-tone" :data-tone="row.original.effective.hidden ? 'neutral' : 'success'">
                {{ row.original.effective.hidden ? '已隐藏' : '显示中' }}
              </span>
            </template>
            <template #cell-updatedAt="{ row }">
              {{ formatTime(row.original.preference.updatedAt) }}
            </template>
          </YdResponsiveEnhancedTable>
        </YdLoadingOverlay>
      </template>

      <template #footer>
        <YdPagination
          v-model:page="pager.page"
          v-model:size="pager.size"
          :total="pager.total"
          @page-change="model.load"
          @size-change="model.load"
        />
      </template>
    </YdTablePageLayout>

    <YdDrawer
      v-model="detailVisible"
      title="用户宠物详情"
      side="right"
      :show-confirm-button="false"
      :footer="false"
    >
      <div v-if="detail" class="mc-pet-detail">
        <section class="mc-pet-detail__section">
          <h3>用户偏好</h3>
          <dl>
            <div><dt>用户</dt><dd>{{ detail.userId }}</dd></div>
            <div><dt>模式</dt><dd>{{ modeLabels[detail.preference.mode] || detail.preference.mode }}</dd></div>
            <div v-if="detail.preference.playerName"><dt>角色名</dt><dd>{{ detail.preference.playerName }}</dd></div>
            <div v-if="detail.preference.closetItemId"><dt>衣柜条目</dt><dd>{{ detail.preference.closetItemId }}</dd></div>
            <div><dt>隐藏</dt><dd>{{ detail.preference.hidden ? '是' : '否' }}</dd></div>
            <div v-if="detail.preference.positionX != null">
              <dt>拖拽位置</dt>
              <dd>{{ (detail.preference.positionX * 100).toFixed(1) }}%, {{ ((detail.preference.positionY ?? 0) * 100).toFixed(1) }}%</dd>
            </div>
            <div><dt>更新时间</dt><dd>{{ formatTime(detail.preference.updatedAt) }}</dd></div>
          </dl>
        </section>
        <section class="mc-pet-detail__section">
          <h3>生效配置</h3>
          <dl>
            <div><dt>皮肤来源</dt><dd>{{ detail.effective.source === 'texture' ? '皮肤材质' : '内置 Steve' }}</dd></div>
            <div><dt>模型</dt><dd>{{ detail.effective.model === 'slim' ? '纤细（slim）' : '经典（classic）' }}</dd></div>
            <div><dt>尺寸</dt><dd>{{ detail.effective.size }}px</dd></div>
            <div><dt>停靠角</dt><dd>{{ cornerLabels[detail.effective.corner] || detail.effective.corner }}</dd></div>
            <div><dt>待机动画</dt><dd>{{ detail.effective.animation ? '开启' : '关闭' }}</dd></div>
            <div><dt>点击行为</dt><dd>{{ clickActionLabels[detail.effective.clickAction] || detail.effective.clickAction }}</dd></div>
            <div><dt>皮肤插件</dt><dd>{{ detail.effective.skinAvailable ? '可用' : '不可用（已降级）' }}</dd></div>
          </dl>
        </section>
        <div class="mc-pet-actions">
          <YdButton variant="destructive" :loading="acting" @click="model.reset(detail)">
            重置该用户偏好
          </YdButton>
        </div>
      </div>
    </YdDrawer>
  </section>
</template>
