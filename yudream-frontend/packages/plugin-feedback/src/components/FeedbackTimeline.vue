<script setup lang="ts">
import type { FeedbackActivity, FeedbackComment, FeedbackTimelineEntry } from '../types'
import { YdAction, YdIcon } from '@yudream/components'
import { computed, ref } from 'vue'
import { buildTimeline } from '../composables/utils'
import FeedbackTimelineItem from './FeedbackTimelineItem.vue'

/**
 * 处理记录时间线。
 *
 * <p>同一次操作产生的系统留痕与评论会合并进同一个气泡：气泡头部是「谁、什么时候」，
 * 中间是这次操作触发的系统信息（状态变更、指派、自动加入处理人等），下面是这次操作的正文。
 * 这样「回复并流转状态」读起来是一件事，而不是两件。
 *
 * <p>连续的系统记录（没有正文的气泡）会被收成一段可折叠的摘要：这类条目单条信息量很低，
 * 连着来几条就会把时间线撑得老长、把真正说话的气泡挤出屏幕。折叠段默认收起，点开才逐条展开。
 */
const props = defineProps<{
  comments: FeedbackComment[]
  activities: FeedbackActivity[]
}>()

/** 时间线上的一个渲染单元：单条记录，或一段被折叠起来的连续系统记录。 */
type TimelineBlock =
  | { kind: 'entry', key: string, entry: FeedbackTimelineEntry }
  | { kind: 'system-run', key: string, entries: FeedbackTimelineEntry[], fromLabel: string, toLabel: string }

const entries = computed(() => buildTimeline(props.comments ?? [], props.activities ?? []))

/** 少于这个条数的系统记录不折叠——只多一行，折叠反而让人多点一次。 */
const SYSTEM_RUN_MIN = 2

const expandedRuns = ref<Record<string, boolean>>({})

/**
 * 把相邻的「纯系统记录」合并成一段。
 *
 * <p>带正文的气泡是天然的分隔：它前后各自成段，不会被跨过合并，否则用户会以为中间什么都没发生。
 */
const blocks = computed<TimelineBlock[]>(() => {
  const source = entries.value
  const result: TimelineBlock[] = []
  let index = 0
  while (index < source.length) {
    if (source[index].content) {
      result.push({ kind: 'entry', key: source[index].key, entry: source[index] })
      index += 1
      continue
    }
    let end = index
    while (end + 1 < source.length && !source[end + 1].content) {
      end += 1
    }
    const run = source.slice(index, end + 1)
    if (run.length < SYSTEM_RUN_MIN) {
      result.push({ kind: 'entry', key: source[index].key, entry: source[index] })
      index += 1
      continue
    }
    result.push({
      kind: 'system-run',
      key: `system-run-${source[index].key}`,
      entries: run,
      fromLabel: run[0].createdAtLabel,
      toLabel: run[run.length - 1].createdAtLabel,
    })
    index = end + 1
  }
  return result
})

function isRunExpanded(key: string): boolean {
  return expandedRuns.value[key] === true
}

function toggleRun(key: string) {
  expandedRuns.value = { ...expandedRuns.value, [key]: !isRunExpanded(key) }
}

/** 折叠段里实际有多少条留痕：条目会被后端按「同一次操作」合并，所以不等于条目数。 */
function runActionCount(entries: FeedbackTimelineEntry[]): number {
  return entries.reduce((total, entry) => total + entry.actions.length, 0)
}
</script>

<template>
  <div class="fb-timeline">
    <div v-if="!blocks.length" class="fb-timeline__empty">
      <YdIcon name="i-ri:inbox-line" />
      <span>暂无处理记录</span>
    </div>

    <template v-for="block in blocks" :key="block.key">
      <FeedbackTimelineItem v-if="block.kind === 'entry'" :entry="block.entry" />

      <div v-else class="fb-timeline__run">
        <YdAction
          type="button"
          class="fb-timeline__run-head"
          :aria-expanded="isRunExpanded(block.key)"
          @click="toggleRun(block.key)"
        >
          <span class="fb-timeline__run-icon">
            <YdIcon name="i-ri:history-line" />
          </span>
          <span class="fb-timeline__run-label">
            系统记录 {{ block.entries.length }} 条（{{ runActionCount(block.entries) }} 次操作）
          </span>
          <span class="fb-timeline__run-time">{{ block.fromLabel }} ～ {{ block.toLabel }}</span>
          <YdIcon
            :name="isRunExpanded(block.key) ? 'i-ri:arrow-up-s-line' : 'i-ri:arrow-down-s-line'"
            class="fb-timeline__run-arrow"
          />
        </YdAction>

        <div v-if="isRunExpanded(block.key)" class="fb-timeline__run-body">
          <FeedbackTimelineItem v-for="entry in block.entries" :key="entry.key" :entry="entry" />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.fb-timeline {
  display: grid;
  gap: var(--fb-sp-4);
}

.fb-timeline__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--fb-sp-2);
  padding: var(--fb-sp-6) 0;
  color: var(--fb-text-4);
  font-size: 12px;
}

/* 折叠段整体：摘要行 + 展开后的条目，缩进用左侧虚线把「这些是被收起来的记录」标出来。 */
.fb-timeline__run {
  display: grid;
  gap: var(--fb-sp-4);
}

.fb-timeline__run-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fb-sp-3);
  width: 100%;
  padding: var(--fb-sp-2) var(--fb-sp-3);
  border: 1px solid transparent;
  border-radius: var(--fb-radius-sm);
  background: var(--fb-surface-muted);
  color: var(--fb-text-3);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease;
}

.fb-timeline__run-head:hover {
  border-color: var(--fb-border);
}

.fb-timeline__run-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: var(--fb-radius-pill);
  background: var(--fb-surface);
  color: var(--fb-text-3);
  font-size: 12px;
}

.fb-timeline__run-label {
  color: var(--fb-text-2);
  font-weight: 600;
}

.fb-timeline__run-time {
  color: var(--fb-text-4);
}

.fb-timeline__run-arrow {
  margin-left: auto;
  color: var(--fb-text-3);
}

.fb-timeline__run-body {
  display: grid;
  gap: var(--fb-sp-4);
  margin-left: 14px;
  padding-left: var(--fb-sp-4);
  border-left: 1px dashed var(--fb-border);
}
</style>
