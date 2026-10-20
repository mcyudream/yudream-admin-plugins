<script setup lang="ts">
// yudream-self-chrome 页面自管页头与卡片。
// 本页是状态页而非设置表单：插件没有全局可编辑配置，联动按项目在「项目管理」里配置，
// 这里只展示依赖能力的可用性，因此用普通页面骨架（YdPageHeader + YdPageMain），
// 不用 YdSettingsLayout 的分区设置壳。
import type { ProjectProgressModel } from '../composables/useProjectProgress'
import { YdButton, YdPageHeader, YdPageMain } from '@yudream/components'
import ProgressPanel from '../components/ProgressPanel.vue'

defineProps<{
  model: ProjectProgressModel
}>()
</script>

<template>
  <YdPageHeader
    title="插件设置"
    description="本页无可编辑配置项：Minecraft 自动打卡等联动按项目在「项目管理」中配置，这里只展示依赖能力的可用性。"
    class="mb-0"
  />
  <YdPageMain>
    <section class="pp-page">
      <section class="pp-toolbar">
        <div>
          <span>联动状态</span>
          <h2>插件状态</h2>
        </div>
        <YdButton variant="outline" :loading="model.loading" @click="model.load">刷新状态</YdButton>
      </section>

      <ProgressPanel title="依赖能力" subtle="Minecraft 自动打卡已改为按项目配置，这里只显示联动能力是否可用">
        <div class="pp-status-grid">
          <article :class="{ ok: model.status?.mailReady }">
            <span>邮件通知</span>
            <strong>{{ model.status?.mailReady ? '可用' : '不可用' }}</strong>
          </article>
          <article :class="{ ok: model.status?.minecraftReady }">
            <span>Minecraft Server</span>
            <strong>{{ model.status?.minecraftReady ? '已启用' : '未启用' }}</strong>
          </article>
        </div>
      </ProgressPanel>
    </section>
  </YdPageMain>
</template>
