<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { ChannelInfo, PublicMethods, StatusPayload, VerificationChannel, VerificationRecord } from '../types'
import { FaAlert, FaButton, FaCard, FaIcon, FaInput, FaLabel, FaModal, FaTabs, useFaToast } from '@yudream/components'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { createEduVerifyApi } from '../api/edu-verify-api'
import ManualSubmitForm from './ManualSubmitForm.vue'
import MarkdownPreview from './MarkdownPreview.vue'
import VerificationStatusCard from './VerificationStatusCard.vue'
import { errorMessage } from '../types'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  initialEmail?: string
  initialChannel?: string
}>()

const emit = defineEmits<{
  done: [payload: { email: string, status: 'PASSED' | 'PENDING' | 'PENDING_MAIL' }]
  'update:email': [value: string]
}>()

const api = createEduVerifyApi(props.sdk)
const toast = useFaToast()

const loading = ref(false)
const methods = ref<PublicMethods | null>(null)
const tab = ref<VerificationChannel>('EMAIL')
const error = ref('')
const email = ref('')
const chsiVcode = ref('')
const chsiRealName = ref('')
const chsiSchoolName = ref('')
const chsiBusy = ref(false)
const submitting = ref(false)
const status = ref<StatusPayload | null>(null)
const latest = ref<VerificationRecord | null>(null)
const successHint = ref('')
const tutorialOpen = ref(false)
const tutorialChannel = ref<ChannelInfo | null>(null)
let statusTimer: ReturnType<typeof setTimeout> | null = null
let mailPollTimer: ReturnType<typeof setInterval> | null = null
let statusSequence = 0

const enabledChannels = computed(() => (methods.value?.channels || []).filter(item => item.enabled && item.code !== 'CARSI'))
const tabs = computed(() => enabledChannels.value.map(item => ({ value: item.code, label: item.name })))
const currentChannel = computed(() => enabledChannels.value.find(item => item.code === tab.value) || enabledChannels.value[0])
const pending = computed(() => Boolean(status.value?.pending) || (!status.value?.passed && (status.value?.records || []).some(item => item.status === 'PENDING' || item.status === 'PENDING_MAIL')))
const pendingMail = computed(() => Boolean(status.value?.pendingMail) || (!status.value?.passed && (status.value?.records || []).some(item => item.status === 'PENDING_MAIL')))
const mailWaitMinutes = computed(() => methods.value?.settings.chsiMailWaitMinutes || 15)
const mailConfirmationEnabled = computed(() => Boolean(methods.value?.settings.chsiMailConfirmationEnabled))
const eduDomainPassed = computed(() => Boolean(status.value?.eduDomain))

function channelOf(code: VerificationChannel): ChannelInfo | undefined {
  return (methods.value?.channels || []).find(item => item.code === code)
}

function applyPreferredChannel() {
  const preferred = String(props.initialChannel || '').toUpperCase() as VerificationChannel
  const first = enabledChannels.value.find(item => item.code === preferred) || enabledChannels.value[0]
  if (first) {
    tab.value = first.code
  }
}

watch(email, (value) => {
  emit('update:email', value)
  if (statusTimer) {
    clearTimeout(statusTimer)
  }
  statusTimer = setTimeout(() => {
    statusTimer = null
    void refreshStatus()
  }, 400)
})

async function loadMethods() {
  loading.value = true
  error.value = ''
  try {
    methods.value = await api.publicMethods()
    applyPreferredChannel()
  }
  catch (cause) {
    error.value = errorMessage(cause, '加载认证渠道失败')
  }
  finally {
    loading.value = false
  }
}

async function refreshStatus() {
  const value = email.value.trim()
  if (!value || !value.includes('@')) {
    status.value = null
    stopMailPoll()
    return
  }
  const sequence = ++statusSequence
  try {
    const payload = await api.publicStatus(value)
    if (sequence !== statusSequence) {
      return
    }
    status.value = payload
    if (payload.passed || payload.eduDomain) {
      stopMailPoll()
      emit('done', { email: value, status: 'PASSED' })
      return
    }
    if (payload.pendingMail || (payload.records || []).some(item => item.status === 'PENDING_MAIL')) {
      emit('done', { email: value, status: 'PENDING_MAIL' })
      startMailPoll()
      return
    }
    stopMailPoll()
    if (payload.pending || (payload.records || []).some(item => item.status === 'PENDING')) {
      emit('done', { email: value, status: 'PENDING' })
    }
  }
  catch {
    if (sequence === statusSequence) {
      status.value = null
    }
  }
}

function startMailPoll() {
  if (mailPollTimer || !email.value.trim()) {
    return
  }
  mailPollTimer = setInterval(() => {
    void refreshStatus()
  }, 8000)
}

function stopMailPoll() {
  if (mailPollTimer) {
    clearInterval(mailPollTimer)
    mailPollTimer = null
  }
}

async function confirmChsiMail() {
  error.value = ''
  const value = email.value.trim()
  if (!value) {
    error.value = '请填写联系邮箱'
    return
  }
  chsiBusy.value = true
  try {
    const result = await api.chsiConfirm(value)
    if (result.status === 'PASSED' && result.verification) {
      stopMailPoll()
      markPassed(result.verification, '学信网报告页与官方邮件均已核验，可继续注册。', '学信网认证通过')
      await refreshStatus()
      return
    }
    if (result.verification) {
      latest.value = result.verification
    }
    successHint.value = result.message || '尚未收到匹配的学信网报告邮件，请确认已在学信网页面发送报告。'
    await refreshStatus()
  }
  catch (cause) {
    error.value = errorMessage(cause, '查询学信网报告邮件失败')
  }
  finally {
    chsiBusy.value = false
  }
}

function openTutorial(channel: ChannelInfo | undefined) {
  if (!channel?.tutorialMarkdown) {
    return
  }
  tutorialChannel.value = channel
  tutorialOpen.value = true
}

async function recognizeEduEmail() {
  error.value = ''
  const value = email.value.trim()
  if (!value) {
    error.value = '请填写教育邮箱'
    return
  }
  await refreshStatus()
  if (status.value?.passed || status.value?.eduDomain || status.value?.pending) {
    return
  }
  error.value = '该邮箱不在教育邮箱白名单中，请改用学信网或人工审核'
}

function markPassed(record: VerificationRecord, hint: string, toastText: string) {
  latest.value = record
  successHint.value = hint
  emit('done', { email: email.value.trim(), status: 'PASSED' })
  toast.success(toastText)
}

async function verifyChsi() {
  error.value = ''
  if (!email.value.trim()) {
    error.value = '请填写联系邮箱'
    return
  }
  if (!chsiVcode.value.trim()) {
    error.value = '请填写 16 位学信网在线验证码'
    return
  }
  chsiBusy.value = true
  try {
    const result = await api.chsiVerify(
      email.value.trim(),
      chsiVcode.value.trim(),
    )
    if (result.status === 'PASSED' && result.verification) {
      markPassed(result.verification, '学信网核验已通过，可继续注册。', '学信网认证通过')
      await refreshStatus()
      return
    }
    if (result.status === 'PENDING_MAIL') {
      if (result.verification) {
        latest.value = result.verification
      }
      successHint.value = result.message || `报告页已核验，请在 ${result.waitMinutes || mailWaitMinutes.value} 分钟内用学信网官方发送按钮把报告发到指定邮箱。`
      emit('done', { email: email.value.trim(), status: 'PENDING_MAIL' })
      toast.success('报告页已通过，请发送学信网官方邮件')
      startMailPoll()
      await refreshStatus()
      return
    }
    if (result.status === 'DEGRADED') {
      if (result.verification) {
        latest.value = result.verification
      }
      successHint.value = ''
      tab.value = 'MANUAL'
      error.value = result.message || '学信网核验暂不可用，已转入人工审核。请补充证明材料'
      toast.warning('已转入人工审核，请核对姓名、学校并补充证明材料')
      await refreshStatus()
    }
  }
  catch (cause) {
    error.value = errorMessage(cause, '学信网核验失败')
  }
  finally {
    chsiBusy.value = false
  }
}

async function submitManual(payload: {
  email: string
  realName: string
  schoolName: string
  note: string
  vcode: string
  materials: { fileId: string, filename: string, contentType: string, kind: string }[]
}) {
  submitting.value = true
  error.value = ''
  try {
    latest.value = await api.publicManualSubmit({
      email: payload.email,
      realName: payload.realName,
      schoolName: payload.schoolName,
      note: payload.note,
      vcode: payload.vcode,
      materials: payload.materials,
    })
    successHint.value = '人工审核已提交，审核通过后即可用该邮箱注册。'
    emit('done', { email: payload.email, status: 'PENDING' })
    await refreshStatus()
    toast.success('已提交人工审核')
  }
  catch (cause) {
    error.value = errorMessage(cause, '提交人工审核失败')
  }
  finally {
    submitting.value = false
  }
}

onMounted(async () => {
  email.value = String(props.initialEmail || '').trim()
  await loadMethods()
  if (email.value) {
    await refreshStatus()
  }
})

onUnmounted(() => {
  statusSequence += 1
  stopMailPoll()
  if (statusTimer) {
    clearTimeout(statusTimer)
    statusTimer = null
  }
})
</script>

<template>
  <div class="ev-stack">
    <FaAlert v-if="error" variant="destructive" title="操作未完成" :description="error" />
    <FaAlert v-if="successHint" title="认证进度" :description="successHint" />
    <FaAlert v-if="eduDomainPassed" title="教育邮箱已核验" description="该邮箱域名已在白名单中，无需验证码，可直接返回注册。" />
    <FaAlert v-if="pendingMail" title="等待学信网报告邮件" :description="`报告页已核验，请在 ${mailWaitMinutes} 分钟内用学信网官方「发送到邮箱」按钮把报告发到指定收件邮箱。系统会核对发件域、验证码和关键词，不会代发。`" />
    <FaAlert v-else-if="pending" title="人工审核中" description="该邮箱已提交人工审核，通过后即可用同一邮箱注册。可在下方查看当前进度。" />

    <FaCard v-if="latest" title="最近一次结果" content-class="ev-card-content">
      <VerificationStatusCard :record="latest" />
      <p class="ev-muted">认证通过后请使用同一邮箱完成账号注册。待审核记录不会立即放行。</p>
    </FaCard>

    <FaCard v-if="status?.records.length" title="该邮箱已有记录" content-class="ev-card-content">
      <div class="ev-record-list">
        <VerificationStatusCard v-for="item in status.records" :key="item.id" :record="item" />
      </div>
    </FaCard>

    <FaCard v-loading="loading" title="选择认证方式" :description="currentChannel?.description" content-class="ev-card-content">
      <FaTabs v-if="tabs.length" v-model="tab" :list="tabs" />
      <div v-if="currentChannel?.tutorialMarkdown" class="ev-actions ev-actions-end">
        <FaButton size="sm" variant="outline" @click="openTutorial(currentChannel)">
          <FaIcon name="i-ri:book-open-line" />
          查看教程
        </FaButton>
      </div>
      <p v-else class="ev-muted">当前没有可用的认证渠道，请联系管理员开启。</p>

      <template v-if="tab === 'EMAIL' && channelOf('EMAIL')?.enabled">
        <form class="ev-form" @submit.prevent="recognizeEduEmail">
          <FaLabel label="教育邮箱" class="ev-field">
            <FaInput v-model="email" class="w-full" maxlength="120" placeholder="name@xxx.edu.cn" />
            <span class="ev-field-hint">填写白名单内的高校教育邮箱即视为已核验，无需验证码。默认包含 edu.cn 及其子域，管理员可在域名白名单中增补。</span>
          </FaLabel>
          <div class="ev-actions ev-actions-end">
            <FaButton type="submit" :loading="loading">
              <FaIcon name="i-ri:shield-check-line" />
              识别教育邮箱
            </FaButton>
          </div>
        </form>
      </template>

      <template v-else-if="tab === 'CHSI' && channelOf('CHSI')?.enabled">
        <form class="ev-form" @submit.prevent="verifyChsi">
          <FaLabel label="联系邮箱" class="ev-field">
            <FaInput v-model="email" class="w-full" maxlength="120" placeholder="用于绑定认证记录并完成注册" />
            <span class="ev-field-hint">不必是教育邮箱。核验通过后请用同一邮箱注册。</span>
          </FaLabel>
          <FaLabel label="16 位在线验证码" class="ev-field">
            <FaInput v-model="chsiVcode" class="w-full" maxlength="16" placeholder="学信档案在线验证码" />
            <span class="ev-field-hint">{{ mailConfirmationEnabled ? '请先在学信网申请报告并填写 16 位验证码。姓名和学校从官方报告页截取，无需手填。报告页通过后，还须用学信网官方发送按钮把报告发到指定邮箱，两步都通过后才可注册。' : '请先在学信网申请《学籍在线验证报告》或《学历证书电子注册备案表》，再填写 16 位验证码。系统会读取官方报告页并截取姓名和学校，无需手填。' }}</span>
          </FaLabel>
          <div class="ev-actions ev-actions-end">
            <FaButton v-if="pendingMail" type="button" variant="outline" :loading="chsiBusy" @click="confirmChsiMail">
              <FaIcon name="i-ri:mail-check-line" />
              我已发送报告邮件
            </FaButton>
            <FaButton type="submit" :loading="chsiBusy">
              <FaIcon name="i-ri:shield-check-line" />
              提交学信网核验
            </FaButton>
          </div>
        </form>
      </template>

      <template v-else-if="tab === 'MANUAL' && channelOf('MANUAL')?.enabled">
        <ManualSubmitForm
          v-model:email="email"
          v-model:vcode="chsiVcode"
          v-model:real-name="chsiRealName"
          v-model:school-name="chsiSchoolName"
          show-email
          :sdk="sdk"
          :submitting="submitting"
          @submit="submitManual"
        />
      </template>
    </FaCard>

    <FaModal v-model="tutorialOpen" :title="tutorialChannel ? `${tutorialChannel.name}教程` : '认证教程'" :show-confirm-button="false">
      <MarkdownPreview :content="tutorialChannel?.tutorialMarkdown" />
    </FaModal>
  </div>
</template>
