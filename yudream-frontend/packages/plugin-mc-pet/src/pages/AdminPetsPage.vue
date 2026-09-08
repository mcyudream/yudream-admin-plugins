<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { PetAdminItem } from '../types'
import { FaButton, FaCard, FaDrawer, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaTag } from '@yudream/components'
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
  { id: 'size', header: '尺寸', width: 90, align: 'center' },
  { id: 'hidden', header: '状态', width: 90, align: 'center' },
  { id: 'updatedAt', header: '更新时间', width: 170 },
  { id: 'operation', header: '操作', width: 180, align: 'center', fixed: 'right' },
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
    <FaPageHeader title="用户宠物">
      <FaButton variant="outline" :loading="loading" @click="model.load">
        刷新
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <FaResponsiveTable
        v-loading="loading"
        row-key="userId"
        table-root-class="mc-pet-table-scroll"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="records"
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
          <FaTag v-if="row.original.effective.hidden">已隐藏</FaTag>
          <span v-else class="mc-pet-muted">显示中</span>
        </template>
        <template #cell-updatedAt="{ row }">
          {{ formatTime(row.original.preference.updatedAt) }}
        </template>
        <template #cell-operation="{ row }">
          <div class="mc-pet-row-actions">
            <FaButton size="sm" variant="outline" :loading="acting" @click="model.openDetail(row.original)">
              详情
            </FaButton>
            <FaButton size="sm" variant="destructive" :loading="acting" @click="model.reset(row.original)">
              重置
            </FaButton>
          </div>
        </template>
        <template #card="{ row }">
          <FaCard class="w-full">
            <div class="mc-pet-card">
              <strong>{{ row.userId }}</strong>
              <div class="mc-pet-muted">
                {{ modeLabels[row.preference.mode] || row.preference.mode }} ·
                {{ row.effective.source === 'texture' ? '皮肤材质' : '内置 Steve' }} ·
                {{ row.effective.size }}px
                <template v-if="row.effective.hidden"> · 已隐藏</template>
              </div>
              <div class="mc-pet-row-actions">
                <FaButton size="sm" variant="outline" :loading="acting" @click="model.openDetail(row)">
                  详情
                </FaButton>
                <FaButton size="sm" variant="destructive" :loading="acting" @click="model.reset(row)">
                  重置
                </FaButton>
              </div>
            </div>
          </FaCard>
        </template>
      </FaResponsiveTable>
      <FaPagination
        v-model:page="pager.page"
        v-model:size="pager.size"
        :total="pager.total"
        class="mt-3"
        @page-change="model.load"
        @size-change="model.load"
      />
      <FaDrawer
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
            <FaButton variant="destructive" :loading="acting" @click="model.reset(detail)">
              重置该用户偏好
            </FaButton>
          </div>
        </div>
      </FaDrawer>
    </FaPageMain>
  </section>
</template>
