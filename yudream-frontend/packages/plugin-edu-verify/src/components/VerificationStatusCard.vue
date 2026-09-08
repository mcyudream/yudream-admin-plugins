<script setup lang="ts">
import type { VerificationRecord } from '../types'
import { FaCard, FaTag } from '@yudream/components'
import { channelLabel, formatTime, statusLabel, statusVariant } from '../types'

defineProps<{
  record: VerificationRecord
  title?: string
}>()
</script>

<template>
  <FaCard :title="title || channelLabel(record.channel, record.channelName)" content-class="ev-card-content">
    <div class="ev-record-card">
      <div class="ev-record-head">
        <div class="ev-record-title">
          <FaTag :variant="statusVariant(record.status)">
            {{ statusLabel(record.status, record.statusName) }}
          </FaTag>
          <span>{{ channelLabel(record.channel, record.channelName) }}</span>
        </div>
        <span class="ev-record-meta">提交于 {{ formatTime(record.submittedAt) }}</span>
      </div>
      <dl class="ev-detail">
        <div v-if="record.email"><dt>邮箱</dt><dd>{{ record.email }}</dd></div>
        <div v-if="record.realName"><dt>姓名</dt><dd>{{ record.realName }}</dd></div>
        <div v-if="record.schoolName"><dt>学校</dt><dd>{{ record.schoolName }}</dd></div>
        <div v-if="record.reason"><dt>说明</dt><dd>{{ record.reason }}</dd></div>
        <div v-if="record.expiresAt && record.expiresAt !== '0'"><dt>有效期至</dt><dd>{{ formatTime(record.expiresAt) }}</dd></div>
        <div v-if="record.decidedAt && record.decidedAt !== '0'"><dt>处理时间</dt><dd>{{ formatTime(record.decidedAt) }}</dd></div>
      </dl>
    </div>
  </FaCard>
</template>
