<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { HomeCard } from '../types'
import {
  FaButton,
  FaCard,
  FaIcon,
  FaPageHeader,
  FaPageMain,
  FaSwitch,
  FaTag,
  useFaModal,
} from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import JsonViewDrawer from '../components/JsonViewDrawer.vue'
import { useYmclAdapter } from '../composables/useYmclAdapter'
import { formatTime } from '../composables/ymcl-protocol'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useYmclAdapter(props.sdk)
const modal = useFaModal()

const jsonDrawer = ref(false)

const CARD_TYPE_ICONS: Record<string, string> = {
  summary: 'i-ri:dashboard-2-line',
  servers: 'i-ri:server-line',
  packs: 'i-ri:box-3-line',
  news: 'i-ri:news-line',
}

function cardTitle(card: HomeCard) {
  return String(card.title || card.id || '（未命名卡片）')
}

function cardType(card: HomeCard) {
  return String(card.type || 'custom')
}

function cardIcon(card: HomeCard) {
  return CARD_TYPE_ICONS[cardType(card)] || 'i-ri:puzzle-line'
}

function cardSummaryLine(card: HomeCard) {
  const keys = Object.keys(card).filter(key => key !== 'title' && key !== 'type')
  return keys.length ? `${keys.length} 个配置字段` : '默认配置'
}

const previewCards = computed(() => model.homeForm.cards)

function confirmClear() {
  modal.confirm({
    title: '确认信息',
    content: '确认清除首页托管吗？清除后启动器不再下发卡片布局，成员使用启动器默认首页。当前模板会被移除，如需保留请先在启动器设计器中备份。',
    onConfirm: async () => {
      await model.clearHome()
    },
  })
}

function toggleLocked(value: boolean | undefined) {
  model.homeForm.locked = Boolean(value)
  model.homeDirty = true
  void model.saveHome()
}

onMounted(() => {
  void model.loadHome()
})
</script>

<template>
  <div class="ymcl-page">
    <FaPageHeader title="首页布局" description="首页卡片模板由管理员在 YMCL 启动器设计器中编排并发布，此处负责托管管理：查看模板、锁定布局、清除托管。">
      <FaButton variant="outline" @click="jsonDrawer = true">
        <FaIcon name="i-ri:code-s-slash-line" />
        查看 JSON
      </FaButton>
      <FaButton
        variant="destructive"
        :disabled="!model.canDesign || !model.homeForm.cards.length"
        @click="confirmClear"
      >
        <FaIcon name="i-ri:delete-bin-line" />
        清除托管
      </FaButton>
    </FaPageHeader>

    <FaPageMain>
      <div class="ymcl-home-grid">
        <FaCard content-class="ymcl-card-content">
          <template #header>
            <div class="ymcl-card-header-row">
              <div class="ymcl-home-card__main">
                <strong>当前模板</strong>
                <span class="ymcl-muted">管理员在启动器设计器中编排，经 manifest 下发给成员</span>
              </div>
              <FaTag :variant="model.homeForm.cards.length ? 'default' : 'outline'">
                {{ model.homeForm.cards.length ? '已托管' : '未托管' }}
              </FaTag>
            </div>
          </template>

          <div v-loading="model.loading" class="ymcl-home-list">
            <p v-if="!model.homeForm.cards.length" class="ymcl-muted">
              当前为空布局：启动器不托管首页，成员看到启动器默认首页。
            </p>
            <div
              v-for="(card, index) in model.homeForm.cards"
              :key="index"
              class="ymcl-home-card"
            >
              <span class="ymcl-home-card__badge">
                <FaIcon :name="cardIcon(card)" />
              </span>
              <div class="ymcl-home-card__main">
                <strong class="truncate">{{ cardTitle(card) }}</strong>
                <span class="ymcl-muted">类型 {{ cardType(card) }} · {{ cardSummaryLine(card) }}</span>
              </div>
              <FaTag variant="secondary" class="shrink-0">{{ index + 1 }}</FaTag>
            </div>
          </div>
          <p class="ymcl-muted">
            模板内容为只读展示；如需调整卡片，请在 YMCL 启动器「设置 → 域 → 首页设计」中编辑并重新发布。
            最后更新：{{ formatTime(model.homeForm.updatedAt) }}
          </p>
        </FaCard>

        <div class="ymcl-home-side">
          <FaCard title="效果预览" :description="model.homeForm.locked ? '锁定布局：成员看到固定首页' : '未锁定：成员可在启动器内自行调整首页'" content-class="ymcl-card-content">
            <div v-if="previewCards.length" class="ymcl-preview-grid" :class="{ 'ymcl-preview-grid--locked': model.homeForm.locked }">
              <div v-for="(card, index) in previewCards" :key="index" class="ymcl-preview-card">
                <FaIcon :name="cardIcon(card)" class="ymcl-preview-card__icon" />
                <strong class="truncate">{{ cardTitle(card) }}</strong>
                <span class="ymcl-muted">{{ cardType(card) }}</span>
              </div>
            </div>
            <div v-else class="ymcl-preview-empty">
              <FaIcon name="i-ri:layout-masonry-line" class="ymcl-preview-empty__icon" />
              <span>当前为空布局</span>
              <span class="ymcl-muted">启动器不托管首页，成员使用启动器默认首页</span>
            </div>
            <p class="ymcl-muted">
              预览按卡片顺序模拟启动器首页网格，仅供布局参考。
            </p>
          </FaCard>

          <FaCard title="托管设置" content-class="ymcl-card-content">
            <div class="ymcl-nav-row">
              <FaSwitch
                :model-value="model.homeForm.locked"
                :disabled="!model.canDesign"
                @update:model-value="toggleLocked"
              />
              <div class="ymcl-home-card__main">
                <strong>锁定布局</strong>
                <span class="ymcl-muted">开启后成员无法自行调整启动器首页</span>
              </div>
            </div>
            <dl class="ymcl-info-list">
              <div>
                <dt>卡片数量</dt>
                <dd>{{ model.homeForm.cards.length }}</dd>
              </div>
              <div>
                <dt>布局状态</dt>
                <dd>{{ model.homeForm.cards.length ? '已托管' : '未托管' }}</dd>
              </div>
              <div>
                <dt>最后更新</dt>
                <dd>{{ formatTime(model.homeForm.updatedAt) }}</dd>
              </div>
            </dl>
          </FaCard>
        </div>
      </div>
    </FaPageMain>

    <JsonViewDrawer v-model="jsonDrawer" title="首页布局 JSON" :payload="{ schemaVersion: 1, locked: model.homeForm.locked, cards: model.homeForm.cards }" />
  </div>
</template>
