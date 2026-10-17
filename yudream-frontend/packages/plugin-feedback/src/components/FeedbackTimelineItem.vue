<script setup lang="ts">
import type { FeedbackTimelineAction, FeedbackTimelineEntry } from '../types'
import { YdIcon } from '@yudream/components'

/**
 * 处理记录里的一个气泡。
 *
 * <p>单独成组件是为了让「折叠掉的连续系统记录」展开后复用同一套渲染——展开的条目与
 * 未折叠的条目必须长得一模一样，否则用户分不清哪些是刚刚被展开出来的。
 *
 * <p>内部备注由后端只下发给管理员，这里按 `internal` 单独标注样式，避免被误认为已答复用户。
 */
defineProps<{ entry: FeedbackTimelineEntry }>()

function actionIcon(action: string): string {
  switch (action) {
    case 'submit':
      return 'i-ri:add-circle-line'
    case 'update':
      return 'i-ri:edit-line'
    case 'status':
      return 'i-ri:progress-2-line'
    case 'assign':
      return 'i-ri:user-received-line'
    case 'unassign':
      return 'i-ri:user-unfollow-line'
    case 'severity':
      return 'i-ri:alarm-warning-line'
    case 'archive':
      return 'i-ri:archive-line'
    case 'note':
      return 'i-ri:lock-line'
    default:
      return 'i-ri:chat-3-line'
  }
}

/** 气泡左侧头像取「本次操作的主图标」：有正文就用回复图标，否则用系统动作图标。 */
function entryIcon(entry: { content: string, actions: FeedbackTimelineAction[] }): string {
  if (entry.content) {
    return 'i-ri:chat-quote-line'
  }
  const primary = entry.actions[entry.actions.length - 1]
  return primary ? actionIcon(primary.action) : 'i-ri:chat-3-line'
}
</script>

<template>
  <div class="fb-timeline__item">
    <span class="fb-timeline__avatar" :class="{ 'is-admin': entry.adminReply, 'is-internal': entry.internal }">
      <YdIcon :name="entryIcon(entry)" />
    </span>
    <div class="fb-timeline__body">
      <div class="fb-timeline__meta">
        <strong>{{ entry.actorName || '未知用户' }}</strong>
        <span v-if="entry.adminReply" class="fb-timeline__badge">官方回复</span>
        <span v-if="entry.internal" class="fb-timeline__badge is-internal">仅管理员可见</span>
        <span class="fb-timeline__time">{{ entry.createdAtLabel }}</span>
      </div>

      <ul v-if="entry.actions.length" class="fb-timeline__actions">
        <li v-for="action in entry.actions" :key="action.key" class="fb-timeline__action">
          <YdIcon :name="actionIcon(action.action)" class="fb-timeline__action-icon" />
          <span class="fb-timeline__action-label">{{ action.actionLabel }}</span>
          <span v-if="action.detail" class="fb-timeline__action-detail">{{ action.detail }}</span>
        </li>
      </ul>

      <p v-if="entry.content" class="fb-timeline__content">{{ entry.content }}</p>
    </div>
  </div>
</template>

<style scoped>
.fb-timeline__item {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: var(--fb-sp-3);
  align-items: start;
}

.fb-timeline__avatar {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: var(--fb-radius-pill);
  background: var(--fb-surface-muted);
  color: var(--fb-text-3);
  font-size: 14px;
}

.fb-timeline__avatar.is-admin {
  background: var(--color-primary-light-1, #e8f3ff);
  color: var(--color-primary-6, #165dff);
}

.fb-timeline__avatar.is-internal {
  background: rgb(var(--warning-1, 255 247 232));
  color: rgb(var(--warning-6));
}

.fb-timeline__body {
  display: grid;
  gap: var(--fb-sp-2);
  padding: var(--fb-sp-3) var(--fb-sp-4);
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius-sm);
  background: var(--fb-surface);
  min-width: 0;
}

.fb-timeline__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fb-sp-2);
  color: var(--fb-text-3);
  font-size: 12px;
}

.fb-timeline__meta strong {
  color: var(--fb-text-2);
  font-weight: 600;
}

.fb-timeline__badge {
  padding: 0 var(--fb-sp-2);
  border-radius: var(--fb-radius-pill);
  background: var(--color-primary-light-1, #e8f3ff);
  color: var(--color-primary-6, #165dff);
  font-size: 11px;
  line-height: 18px;
}

.fb-timeline__badge.is-internal {
  background: rgb(var(--warning-1, 255 247 232));
  color: rgb(var(--warning-6));
}

.fb-timeline__time {
  margin-left: auto;
  color: var(--fb-text-4);
}

/* 系统信息条：与正文同处一个气泡，但用更弱的层级区分「系统记录」与「人说的话」。 */
.fb-timeline__actions {
  display: grid;
  gap: 2px;
  margin: 0;
  padding: var(--fb-sp-2) var(--fb-sp-3);
  border-radius: var(--fb-radius-sm);
  background: var(--fb-surface-muted);
  list-style: none;
}

.fb-timeline__action {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--fb-sp-2);
  color: var(--fb-text-3);
  font-size: 12px;
  line-height: 1.7;
}

.fb-timeline__action-icon {
  color: var(--fb-text-4);
  font-size: 12px;
}

.fb-timeline__action-label {
  color: var(--fb-text-2);
}

.fb-timeline__action-detail {
  color: var(--fb-text-3);
  word-break: break-word;
}

.fb-timeline__content {
  margin: 0;
  color: var(--fb-text-1);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
