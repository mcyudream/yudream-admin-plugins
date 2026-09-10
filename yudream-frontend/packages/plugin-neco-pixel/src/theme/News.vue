<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useThemeContext, useThemeSeo } from './use-theme-context'

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: { meta?: { plugin?: { component?: string } } } }>()

// 新闻列表：CMS 最新文章，详情仍走 /site/:slug 由主题 style.css 美化
const { context, loaded } = useThemeContext(props.sdk, { cmsLatest: 12 })

useThemeSeo(props.sdk, {
  title: '新闻',
  description: '社团新闻与最新公告。',
  canonicalPath: '/news',
})

const news = computed(() => context.value?.cmsPagesLatest ?? [])
</script>

<template>
  <div class="neco-news">
    <header class="neco-news__head neco-anim-down">
      <h1 class="neco-news__title">
        新闻
      </h1>
    </header>

    <div v-if="news.length" class="neco-news__list">
      <article v-for="item in news" :key="item.id || item.url" class="neco-panel neco-news__card">
        <h2 class="neco-news__card-title">
          <RouterLink :to="item.url">
            {{ item.title }}
          </RouterLink>
        </h2>
        <p v-if="item.excerpt || item.summary" class="neco-news__card-excerpt">
          {{ item.excerpt || item.summary }}
        </p>
        <span class="neco-news__card-date">{{ item.publishedAt }}</span>
      </article>
    </div>

    <p v-else-if="loaded" class="neco-news__empty">
      内容建设中，敬请期待。
    </p>
  </div>
</template>

<style scoped>
.neco-news {
  width: min(100%, 64rem);
  margin: 0 auto;
  padding: 4rem clamp(1rem, 4vw, 3rem) 3rem;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.neco-news__head {
  text-align: center;
}

.neco-news__title {
  margin: 0;
  font-family: 'Press Start 2P', 'Cubic 11', 'Microsoft YaHei', 'PingFang SC', sans-serif;
  font-size: clamp(1.4rem, 3vw, 2rem);
  text-shadow: 3px 3px 0 rgba(0, 0, 0, 0.25);
}

.neco-news__list {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.neco-news__card {
  padding: 1rem 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.neco-news__card-title {
  margin: 0;
  font-size: 1.2rem;
}

.neco-news__card-title a {
  color: inherit;
  text-decoration: none;
}

.neco-news__card-title a:hover {
  color: var(--neco-accent-light);
}

.neco-news__card-excerpt {
  margin: 0;
  line-height: 1.8;
}

.neco-news__card-date {
  font-size: 0.85em;
  opacity: 0.65;
}

.neco-news__empty {
  text-align: center;
  opacity: 0.7;
}
</style>
