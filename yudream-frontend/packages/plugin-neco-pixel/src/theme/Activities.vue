<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { pluginAsset, themeAsset, useThemeContext, useThemeSeo } from './use-theme-context'

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: { meta?: { plugin?: { component?: string } } } }>()

const { context, loaded } = useThemeContext(props.sdk, { blocks: ['activity-square'], limit: 20, cmsLatest: 20 })

useThemeSeo(props.sdk, {
  title: '活动',
  description: '服务器活动预告、进行中与回顾。',
  canonicalPath: '/activities',
})

const activities = computed(() => {
  const list = context.value?.blocks?.['activity-square']?.activities
  if (Array.isArray(list) && list.length) {
    return list
  }
  return (context.value?.cmsPagesLatest ?? []).map((item: any) => ({
    title: item.title,
    summary: item.excerpt || item.summary,
    cover: item.coverImageUrl,
    url: item.url,
    startAt: item.publishedAt,
    statusKey: 'ended',
    statusText: '已结束',
  }))
})

const page = ref(1)
const pageSize = 8
const maxPage = computed(() => Math.max(1, Math.ceil(activities.value.length / pageSize)))
const paged = computed(() => activities.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const pageInput = ref('1')
const headerBg = computed(() => pluginAsset(props.sdk, 'background/header-bg.jpg'))
const pageBg = computed(() => pluginAsset(props.sdk, 'background/bg.jpg'))

function isActive(item: Record<string, any>) {
  return item.statusKey === 'ongoing' || item.statusKey === 'upcoming'
}

function dateRange(item: Record<string, any>) {
  if (item.meta) return item.meta
  const start = item.startAt || item.date || ''
  const end = item.endAt || item.endDate || '长期'
  return start ? `${start} ~ ${end}` : end
}

function cover(item: Record<string, any>) {
  return themeAsset(props.sdk, item.cover || item.image) || pluginAsset(props.sdk, 'background/beidalou.webp')
}

function isInternal(url?: string) {
  return !!url && !/^(?:https?:|mailto:|tel:|#)/i.test(url)
}

function movePage(dir: 'prev' | 'next') {
  if (dir === 'prev' && page.value > 1) page.value -= 1
  if (dir === 'next' && page.value < maxPage.value) page.value += 1
  pageInput.value = String(page.value)
}

function setPage() {
  const number = Number(pageInput.value)
  if (number > 0 && number <= maxPage.value) {
    page.value = number
  }
}
</script>

<template>
  <div
    class="activity-area"
    :style="{
      backgroundImage: `url('${headerBg}'), url('${pageBg}')`,
    }"
  >
    <p class="mcfont activity-title">活动</p>
    <div v-if="!loaded" class="activity-list-loading-container">
      <img class="activity-list-loading" :src="pluginAsset(props.sdk, 'loading.gif')" alt="">
    </div>
    <div v-else class="activity-list">
      <component
        :is="isInternal(item.url) ? RouterLink : (item.url ? 'a' : 'div')"
        v-for="(item, index) in paged"
        :key="index"
        class="activity-item"
        :to="isInternal(item.url) ? item.url : undefined"
        :href="item.url && !isInternal(item.url) ? item.url : undefined"
        :style="{
          '--delay': `${index * 0.1}s`,
          backgroundColor: isActive(item) ? 'rgb(45, 72, 31)' : 'rgb(88, 46, 46)',
        }"
      >
        <img :src="cover(item)" :alt="item.title">
        <div class="activity-info">
          <div class="activity-title-row">
            {{ item.title }}
            <div class="activity-status" :class="{ 'is-active': isActive(item) }">
              {{ item.statusText || (isActive(item) ? '进行中' : '已结束') }}
            </div>
          </div>
          <div class="activity-date">{{ dateRange(item) }}</div>
          <div class="activity-brief">{{ item.summary }}</div>
        </div>
      </component>
      <p v-if="!activities.length" class="activity-empty">暂时没有活动，可以先去服务器里转转。</p>
    </div>
    <div v-if="loaded && activities.length" class="activity-pagination">
      <div class="activity-pagination-item">
        <button type="button" class="minecraft-button" @click="movePage('prev')">&lt;</button>
        <span>第</span>
        <span class="page">{{ page }}</span>
        <span>/</span>
        <span class="total">{{ maxPage }}</span>
        <span>页</span>
        <button type="button" class="minecraft-button" @click="movePage('next')">&gt;</button>
      </div>
      <div class="activity-pagination-item">
        <span>前往</span>
        <input v-model="pageInput" class="minecraft-input" @keyup.enter="setPage">
        <span>页</span>
        <button type="button" class="minecraft-button" @click="setPage">→</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.activity-area {
  min-height: 100vh;
  padding-top: 5rem;
  display: flex;
  flex-direction: column;
  background-color: #303030;
  background-repeat: repeat-x, repeat;
  background-position: top left, top left;
  background-size: auto 234px, 468px;
  color: rgba(255, 255, 255, 0.8);
}

.activity-title {
  user-select: none;
  color: #fff;
  font-size: 1.5rem;
  font-weight: bold;
  text-align: center;
  margin: 0;
}

.activity-list {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 1rem;
  margin: 1.5rem;
  gap: 1.5rem;
}

.activity-item {
  margin: 0 auto;
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  width: 100%;
  max-width: 1024px;
  padding: 1rem 2rem;
  color: inherit;
  text-decoration: none;
  position: relative;
  cursor: pointer;
  user-select: none;
  background-color: #313233;
  border: 2px solid rgba(0, 0, 0, 0.4);
  box-shadow: 4px 4px rgba(0, 0, 0, 0.7);
  transition: transform 0.1s ease-in-out, margin-bottom 0.1s ease-in-out;
  opacity: 0;
  animation: fade-in 0.5s ease-in-out forwards;
  animation-delay: var(--delay);
}

.activity-item::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  box-shadow:
    0 -12px 0 0 rgb(104, 104, 104) inset,
    2px 2px 0 0 rgba(178, 178, 178, 0.5) inset,
    -2px -16px 0 0 rgba(153, 153, 153, 0.5) inset;
  mix-blend-mode: hard-light;
}

.activity-item:hover::after {
  background-color: #ffffff08;
}

.activity-item:active {
  transform: translateY(8px);
  margin-bottom: 8px;
}

.activity-item:active::after {
  box-shadow:
    2px 2px 0 0 rgba(178, 178, 178, 0.5) inset,
    -2px -2px 0 0 rgba(153, 153, 153, 0.5) inset;
}

.activity-item img {
  height: 9rem;
  width: 16rem;
  object-fit: cover;
  user-select: none;
  position: relative;
  z-index: 2;
}

.activity-info {
  display: flex;
  flex-direction: column;
  padding-left: 2rem;
  text-align: left;
  position: relative;
  z-index: 2;
}

.activity-title-row {
  display: flex;
  align-items: center;
  color: #fff;
  font-size: 1.5rem;
}

.activity-status {
  color: #f56c6c;
  margin-left: 0.5rem;
  padding: 3px 8px;
  background-color: rgb(88, 46, 46);
  font-size: 0.9rem;
}

.activity-status.is-active {
  color: #67c23a;
  background-color: rgb(45, 72, 31);
}

.activity-date {
  margin: 0.5rem 0;
  font-size: 0.8rem;
}

.activity-brief {
  font-size: 1rem;
  color: #eee;
}

.activity-empty {
  text-align: center;
}

.activity-list-loading-container {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 40rem;
}

.activity-list-loading {
  width: 16rem;
  height: 16rem;
}

.activity-pagination {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  width: 100%;
  margin-bottom: 2rem;
}

.activity-pagination-item {
  display: inline-flex;
  align-items: center;
  height: 2rem;
  margin-top: 1rem;
  gap: 0.5rem;
  font-size: 1.5rem;
}

.page,
.total {
  min-width: 2rem;
  height: 2rem;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1rem;
  border: 1px solid #fff;
}

.page {
  border-color: #a0e081;
}

.minecraft-button {
  height: 2rem;
  width: 2rem;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #000;
  background-color: #c6c6c6;
  border: 2px solid #333;
  cursor: pointer;
  font: inherit;
}

.minecraft-button:hover {
  color: #fff;
  background-color: #43a01c;
}

.minecraft-input {
  height: 2rem;
  width: 3rem;
  text-align: center;
  background: #616161;
  color: #fff;
  border: 2px solid #000;
  font: inherit;
}

@media screen and (max-width: 524px) {
  .activity-item {
    flex-direction: column;
  }

  .activity-item img {
    width: 100%;
    height: 12rem;
    margin-bottom: 1rem;
  }

  .activity-info {
    padding: 0;
  }
}

@keyframes fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
</style>
