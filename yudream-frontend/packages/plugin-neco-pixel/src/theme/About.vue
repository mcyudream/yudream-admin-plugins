<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed } from 'vue'
import { configList, configText, themeAsset, useThemeContext, useThemeSeo } from './use-theme-context'

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: { meta?: { plugin?: { component?: string } } } }>()

// 关于我们：介绍项 + 部门 + 友情链接，全部来自主题配置
const { context } = useThemeContext(props.sdk, {})

useThemeSeo(props.sdk, {
  title: '关于我们',
  description: '社团介绍、部门分工与友情链接。',
  canonicalPath: '/about',
})

const config = computed(() => context.value?.themeConfig ?? {})
const aboutTitle = computed(() => configText(config.value, 'aboutTitle', '关于我们'))
const introItems = computed(() => configList(config.value, 'introItems'))
const departments = computed(() => configList(config.value, 'departments'))
const friendLinks = computed(() => configList(config.value, 'friendLinks'))
</script>

<template>
  <div class="neco-about">
    <header class="neco-about__head neco-anim-down">
      <h1 class="neco-about__title">
        {{ aboutTitle }}
      </h1>
    </header>

    <section v-if="introItems.length" class="neco-about__intro-list">
      <div v-for="(item, index) in introItems" :key="index" class="neco-about__intro-card" :class="{ 'neco-about__intro-card--plain': !item.image }">
        <div v-if="item.image" class="neco-about__intro-media" :style="{ backgroundImage: `url('${themeAsset(props.sdk, item.image)}')` }" />
        <div class="neco-about__intro-body">
          <h2>{{ item.title }}</h2>
          <p>{{ item.description }}</p>
        </div>
      </div>
    </section>

    <section v-if="departments.length" class="neco-about__section">
      <h2 class="neco-about__section-title">
        部门分工
      </h2>
      <div class="neco-about__dept-grid">
        <article v-for="(dept, index) in departments" :key="index" class="neco-panel neco-about__dept">
          <h3>{{ dept.name }}</h3>
          <p>{{ dept.description }}</p>
        </article>
      </div>
    </section>

    <section v-if="friendLinks.length" class="neco-about__section">
      <h2 class="neco-about__section-title">
        友情链接
      </h2>
      <div class="neco-about__links">
        <a
          v-for="(link, index) in friendLinks"
          :key="index"
          class="neco-about__link"
          :href="link.url"
          target="_blank"
          rel="noopener noreferrer"
        >
          <span v-if="link.icon" class="neco-about__link-icon" :style="{ backgroundImage: `url('${themeAsset(props.sdk, link.icon)}')` }" />
          {{ link.name }}
        </a>
      </div>
    </section>
  </div>
</template>

<style scoped>
.neco-about {
  width: min(100%, 80rem);
  margin: 0 auto;
  padding: 4rem clamp(1rem, 4vw, 3rem) 3rem;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 3rem;
}

.neco-about__head {
  text-align: center;
}

.neco-about__title {
  margin: 0;
  font-family: 'Press Start 2P', 'Cubic 11', 'Microsoft YaHei', 'PingFang SC', sans-serif;
  font-size: clamp(1.4rem, 3vw, 2rem);
  text-shadow: 3px 3px 0 rgba(0, 0, 0, 0.25);
}

.neco-about__intro-list {
  display: flex;
  flex-direction: column;
  gap: 2.5rem;
}

.neco-about__intro-card {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-around;
  align-items: center;
  gap: 1.5rem;
  animation: neco-fade-in-left 0.6s ease both;
}

.neco-about__intro-list > .neco-about__intro-card:nth-child(even) {
  flex-direction: row-reverse;
  animation-name: neco-fade-in-right;
}

.neco-about__intro-card--plain {
  flex-direction: column;
  text-align: center;
  border-top: 2px dashed var(--yb-site-border);
  padding-top: 2rem;
}

.neco-about__intro-media {
  width: min(100%, 26rem);
  min-height: 11rem;
  background-size: cover;
  background-position: center;
  border: 2px solid #000;
  box-shadow: 4px 4px 0 rgba(0, 0, 0, 0.45);
}

.neco-about__intro-body {
  max-width: 30rem;
}

.neco-about__intro-card--plain .neco-about__intro-body {
  max-width: 45rem;
}

.neco-about__intro-body h2 {
  margin: 0 0 0.5rem;
  font-size: 1.35rem;
}

.neco-about__intro-body p {
  margin: 0;
  line-height: 1.9;
}

.neco-about__section-title {
  margin: 0 0 1.25rem;
  font-size: 1.3rem;
  border-left: 4px solid var(--neco-accent);
  padding-left: 0.75rem;
}

.neco-about__dept-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
  gap: 1.5rem;
}

.neco-about__dept {
  padding: 1rem 1.25rem;
}

.neco-about__dept h3 {
  margin: 0 0 0.5rem;
}

.neco-about__dept p {
  margin: 0;
  line-height: 1.8;
}

.neco-about__links {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
}

.neco-about__link {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
  color: inherit;
  text-decoration: none;
  border: 2px solid #000;
  background: var(--yb-site-surface);
  box-shadow: inset 2px 2px 0 var(--neco-bevel-light), inset -2px -2px 0 var(--neco-bevel-dark);
}

.neco-about__link:hover {
  color: var(--neco-accent-light);
}

.neco-about__link-icon {
  width: 1.5rem;
  height: 1.5rem;
  background-size: cover;
  image-rendering: pixelated;
}

@media (max-width: 640px) {
  .neco-about__intro-card,
  .neco-about__intro-list > .neco-about__intro-card:nth-child(even) {
    flex-direction: column;
  }

  .neco-about__intro-media {
    width: 100%;
  }
}
</style>
