<script setup lang="ts">
import type { TimelinePluginModel } from '../composables/useTimelinePlugin'
import type { TimelineEventSummary } from '../types'
import { FaIcon } from '@yudream/components'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import CoverThumb from '../components/CoverThumb.vue'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { eventTypeChip, eventTypeClass, eventTypeMeta, eventYear, formatEventDate, resolveImageUrl, resolveThumbUrl } from '../composables/utils'

const props = defineProps<{ model: TimelinePluginModel }>()
const model = props.model

interface YearGroup {
  year: string
  events: TimelineEventSummary[]
}

// 后端已按 eventDate desc → sort desc → createdAt desc 排好，按年分组只需顺序切分
const groups = computed<YearGroup[]>(() => {
  const list: YearGroup[] = []
  for (const event of model.publicEvents) {
    const year = eventYear(event.eventDate)
    const last = list[list.length - 1]
    if (last && last.year === year) {
      last.events.push(event)
    }
    else {
      list.push({ year, events: [event] })
    }
  }
  return list
})

const earliestYear = computed(() => {
  const events = model.publicEvents
  return events.length ? eventYear(events[events.length - 1].eventDate) : ''
})

function coverUrl(event: TimelineEventSummary) {
  return resolveImageUrl(model.sdk, event.coverImage)
}

function coverThumb(event: TimelineEventSummary) {
  return resolveThumbUrl(model.sdk, event.coverImage)
}

// 滚动显现：进入视口的卡片播放上浮动画
const root = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

function setupReveal() {
  if (typeof window === 'undefined' || !('IntersectionObserver' in window)) {
    return
  }
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        entry.target.classList.add('tl-revealed')
        observer?.unobserve(entry.target)
      }
    }
  }, { threshold: 0.12, rootMargin: '0px 0px -8% 0px' })
}

function observeReveals() {
  root.value?.querySelectorAll('.tl-reveal:not(.tl-revealed)').forEach(el => observer?.observe(el))
}

watch(() => model.publicEvents.length, async () => {
  await nextTick()
  observeReveals()
})

// 详情浮层
const detailOpen = ref(false)
const detail = computed(() => model.publicDetail)
const detailCover = computed(() => (detail.value ? resolveImageUrl(model.sdk, detail.value.coverImage) : ''))
const detailImages = computed(() => (detail.value?.images ?? []).map(url => resolveImageUrl(model.sdk, url)))
const showRoster = computed(() => {
  const event = detail.value
  if (!event || (event.eventType || '').toUpperCase() !== 'ELECTION') {
    return false
  }
  return (event.outgoingMembers?.length || 0) + (event.incomingMembers?.length || 0) > 0
})

async function openEvent(event: TimelineEventSummary) {
  detailOpen.value = true
  await model.loadPublicDetail(event.id)
}

function closeDetail() {
  lightboxIndex.value = -1
  detailOpen.value = false
  model.closePublicDetail()
}

// 图片灯箱
const lightboxIndex = ref(-1)
const lightboxCurrent = computed(() => (lightboxIndex.value >= 0 ? detailImages.value[lightboxIndex.value] : ''))

function openLightbox(index: number) {
  lightboxIndex.value = index
}

function stepLightbox(delta: number) {
  const total = detailImages.value.length
  if (!total) {
    return
  }
  lightboxIndex.value = (lightboxIndex.value + delta + total) % total
}

// 浮层/灯箱打开时锁定背景滚动
watch([detailOpen, lightboxIndex], () => {
  if (typeof document !== 'undefined') {
    document.body.style.overflow = detailOpen.value || lightboxIndex.value >= 0 ? 'hidden' : ''
  }
})

function onKeydown(event: KeyboardEvent) {
  if (lightboxIndex.value >= 0) {
    if (event.key === 'Escape') {
      lightboxIndex.value = -1
    }
    else if (event.key === 'ArrowLeft') {
      stepLightbox(-1)
    }
    else if (event.key === 'ArrowRight') {
      stepLightbox(1)
    }
  }
  else if (detailOpen.value && event.key === 'Escape') {
    closeDetail()
  }
}

onMounted(() => {
  setupReveal()
  observeReveals()
  void model.loadPublicEvents()
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  window.removeEventListener('keydown', onKeydown)
  if (typeof document !== 'undefined') {
    document.body.style.overflow = ''
  }
})
</script>

<template>
  <div ref="root" class="tl-page">
    <section class="tl-hero">
      <div class="tl-aurora tl-aurora-a" />
      <div class="tl-aurora tl-aurora-b" />
      <div class="tl-aurora tl-aurora-c" />
      <div class="tl-hero-inner">
        <p class="tl-hero-eyebrow">
          TIMELINE · 时光印记
        </p>
        <h1 class="tl-hero-title">
          大事记
        </h1>
        <p class="tl-hero-sub">
          记录我们一路走来的每个重要时刻
        </p>
        <div v-if="!model.publicLoading && model.publicEvents.length" class="tl-hero-stats">
          <span class="tl-chip">{{ model.publicEvents.length }} 个时刻</span>
          <span v-if="earliestYear" class="tl-chip">始于 {{ earliestYear }}</span>
        </div>
      </div>
    </section>

    <section class="tl-body">
      <div v-if="model.publicLoading" class="tl-skeletons">
        <div v-for="index in 3" :key="index" class="tl-skeleton-card" />
      </div>

      <div v-else-if="model.publicError" class="tl-state">
        <FaIcon name="i-ri:error-warning-line" class="tl-state-icon" />
        <p>{{ model.publicError }}</p>
        <button type="button" class="tl-btn" @click="model.loadPublicEvents">
          重新加载
        </button>
      </div>

      <div v-else-if="!model.publicEvents.length" class="tl-state">
        <FaIcon name="i-ri:time-line" class="tl-state-icon" />
        <p>还没有公开的大事记，敬请期待</p>
      </div>

      <div v-else class="tl-timeline">
        <div class="tl-spine" aria-hidden="true" />
        <template v-for="group in groups" :key="group.year || 'unknown'">
          <div class="tl-year tl-reveal">
            <span class="tl-year-badge">{{ group.year || '往昔' }}</span>
          </div>
          <div
            v-for="(event, index) in group.events"
            :key="event.id"
            class="tl-item tl-reveal"
            :class="{ 'tl-item-right': index % 2 === 1 }"
          >
            <span class="tl-node" aria-hidden="true"><span class="tl-dot" /></span>
            <button type="button" class="tl-card" :class="`tl-card--${eventTypeClass(event.eventType)}`" @click="openEvent(event)">
              <span v-if="event.coverImage" class="tl-card-cover">
                <CoverThumb :src="coverThumb(event)" :fallback-src="coverUrl(event)" :alt="event.title" />
              </span>
              <span class="tl-card-body">
                <span class="tl-type-badge" :class="`tl-type-badge--${eventTypeClass(event.eventType)}`">
                  <FaIcon :name="eventTypeMeta(event.eventType).icon" />
                  {{ eventTypeChip(event) }}
                </span>
                <span class="tl-card-date">{{ formatEventDate(event.eventDate, event.dateLabel) }}</span>
                <span class="tl-card-title">{{ event.title }}</span>
                <span v-if="event.summary" class="tl-card-summary">{{ event.summary }}</span>
                <span class="tl-card-more">
                  阅读详情
                  <FaIcon name="i-ri:arrow-right-line" />
                </span>
              </span>
            </button>
          </div>
        </template>
      </div>
    </section>

    <Teleport to="body">
      <Transition name="tl-fade">
        <div v-if="detailOpen" class="tl-overlay" @click.self="closeDetail">
          <div class="tl-detail" role="dialog" aria-modal="true">
            <button type="button" class="tl-detail-close" aria-label="关闭" @click="closeDetail">
              <FaIcon name="i-ri:close-line" />
            </button>
            <div v-if="model.publicDetailLoading" class="tl-detail-loading">
              <span class="tl-spinner" />
              <p>正在加载…</p>
            </div>
            <template v-else-if="detail">
              <header class="tl-detail-hero" :class="{ 'tl-detail-hero-plain': !detailCover }">
                <img v-if="detailCover" :src="detailCover" :alt="detail.title" class="tl-detail-hero-img">
                <div class="tl-detail-hero-mask" />
                <div class="tl-detail-hero-text">
                  <span class="tl-type-badge" :class="`tl-type-badge--${eventTypeClass(detail.eventType)}`">
                    <FaIcon :name="eventTypeMeta(detail.eventType).icon" />
                    {{ eventTypeChip(detail) }}
                  </span>
                  <span class="tl-detail-date">{{ formatEventDate(detail.eventDate, detail.dateLabel) }}</span>
                  <h2>{{ detail.title }}</h2>
                  <p v-if="detail.summary">
                    {{ detail.summary }}
                  </p>
                </div>
              </header>
              <div class="tl-detail-body">
                <div v-if="showRoster" class="tl-roster">
                  <section v-if="detail.outgoingMembers?.length" class="tl-roster-col">
                    <header class="tl-roster-head">
                      <FaIcon name="i-ri:logout-box-r-line" />
                      <span>卸任</span>
                      <em>{{ detail.outgoingMembers.length }}</em>
                    </header>
                    <ul>
                      <li v-for="member in detail.outgoingMembers" :key="`out-${member}`">
                        {{ member }}
                      </li>
                    </ul>
                  </section>
                  <section v-if="detail.incomingMembers?.length" class="tl-roster-col">
                    <header class="tl-roster-head">
                      <FaIcon name="i-ri:login-box-line" />
                      <span>新任</span>
                      <em>{{ detail.incomingMembers.length }}</em>
                    </header>
                    <ul>
                      <li v-for="member in detail.incomingMembers" :key="`in-${member}`">
                        {{ member }}
                      </li>
                    </ul>
                  </section>
                </div>
                <MarkdownPreview v-if="detail.detail" :sdk="model.sdk" :content="detail.detail" />
                <div v-if="detailImages.length" class="tl-gallery">
                  <button
                    v-for="(image, index) in detailImages"
                    :key="index"
                    type="button"
                    class="tl-gallery-item"
                    @click="openLightbox(index)"
                  >
                    <img :src="image" :alt="`${detail.title} 图片 ${index + 1}`" loading="lazy">
                  </button>
                </div>
              </div>
            </template>
          </div>
        </div>
      </Transition>

      <Transition name="tl-fade">
        <div v-if="lightboxIndex >= 0" class="tl-lightbox" @click.self="lightboxIndex = -1">
          <span class="tl-lightbox-counter">{{ lightboxIndex + 1 }} / {{ detailImages.length }}</span>
          <button type="button" class="tl-lightbox-close" aria-label="关闭" @click="lightboxIndex = -1">
            <FaIcon name="i-ri:close-line" />
          </button>
          <button
            v-if="detailImages.length > 1"
            type="button"
            class="tl-lightbox-nav tl-lightbox-prev"
            aria-label="上一张"
            @click.stop="stepLightbox(-1)"
          >
            <FaIcon name="i-ri:arrow-left-s-line" />
          </button>
          <img :src="lightboxCurrent" :alt="detail?.title || ''" class="tl-lightbox-img" @click.stop>
          <button
            v-if="detailImages.length > 1"
            type="button"
            class="tl-lightbox-nav tl-lightbox-next"
            aria-label="下一张"
            @click.stop="stepLightbox(1)"
          >
            <FaIcon name="i-ri:arrow-right-s-line" />
          </button>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
