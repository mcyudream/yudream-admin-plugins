<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { NotifyGroupTarget, VerifySettings } from '../types'
import { FaAlert, FaButton, FaCard, FaIcon, FaInput, FaLabel, FaNumberField, FaPageHeader, FaPageMain, FaSelect, FaSwitch, useFaToast } from '@yudream/components'
import { MdEditor } from 'md-editor-v3'
import { computed, onMounted, reactive, ref } from 'vue'
import { createEduVerifyApi } from '../api/edu-verify-api'
import { errorMessage } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createEduVerifyApi(props.sdk)
const toast = useFaToast()

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const DEFAULT_REPORT_URL = 'https://www.chsi.com.cn/xlcx/bg.do?vcode={code}&srcid=bgcx'
const DEFAULT_SELECTORS = {
  realName: 'td:matchesOwn(^\\s*姓名\\s*$) + td',
  schoolName: 'td:matchesOwn(^\\s*学校名称\\s*$) + td',
  studentStatus: 'td:matchesOwn(^\\s*学籍状态\\s*$) + td',
  vcode: 'td:matchesOwn(^\\s*在线验证码\\s*$) + td',
}

const form = reactive<VerifySettings>({
  emailEnabled: true,
  chsiEnabled: true,
  manualEnabled: true,
  codeTtlMinutes: 10,
  codeResendSeconds: 60,
  codeDailyLimit: 6,
  validityDays: 365,
  retentionDays: 30,
  chsiDailyLimit: 5,
  chsiReportUrlTemplate: DEFAULT_REPORT_URL,
  chsiSelectors: { ...DEFAULT_SELECTORS },
  emailTutorialMarkdown: '',
  chsiTutorialMarkdown: '',
  manualTutorialMarkdown: '',
  manualNotifyEnabled: false,
  manualNotifyGroups: [],
  manualNotifyTemplate: '',
  chsiMailConfirmationEnabled: false,
  chsiMailboxId: '',
  chsiAllowedFromDomains: ['chsi.com.cn'],
  chsiMailKeywords: ['在线验证报告'],
  chsiMailWaitMinutes: 15,
})
const fromDomainsText = ref('chsi.com.cn')
const mailKeywordsText = ref('在线验证报告')
const reportUrlTemplate = ref(DEFAULT_REPORT_URL)
const selectors = reactive({ ...DEFAULT_SELECTORS })
const notifyConnectionId = ref('')
const notifyGroupIds = ref<string[]>([])
const extraGroupId = ref('')
const qqConnections = ref<Array<{ id: string, name: string, protocol?: string | null, platform?: string | null }>>([])
const qqGroups = ref<Array<{ id: string, name: string }>>([])
const loadingQqGroups = ref(false)

function protocolLabel(item: { protocol?: string | null, platform?: string | null }) {
  if (item.protocol === 'official') {
    return '官方 QQ'
  }
  if (item.protocol === 'milky') {
    return 'Milky'
  }
  return item.platform || '未知平台'
}

const qqConnectionOptions = computed(() => [
  { label: '请选择消息连接', value: '' },
  ...qqConnections.value.map(item => ({ label: `${item.name || item.id}（${protocolLabel(item)}）`, value: item.id })),
])
const qqGroupOptions = computed(() => {
  const known = qqGroups.value.map(item => ({ label: item.name || item.id, value: item.id }))
  const knownIds = new Set(known.map(item => item.value))
  const extras = notifyGroupIds.value
    .filter(id => id && !knownIds.has(id))
    .map(id => ({ label: id, value: id }))
  return [...known, ...extras]
})

function splitList(value: string) {
  return value.split(/[\n,，;；]+/).map(item => item.trim()).filter(Boolean)
}

function selectedNotifyGroups(): NotifyGroupTarget[] {
  const connectionId = notifyConnectionId.value.trim()
  if (!connectionId) {
    return []
  }
  return notifyGroupIds.value
    .map(id => id.trim())
    .filter(Boolean)
    .map(groupId => ({ connectionId, groupId }))
}

function apply(data: VerifySettings) {
  Object.assign(form, {
    ...data,
    chsiMailConfirmationEnabled: Boolean(data.chsiMailConfirmationEnabled),
    chsiMailboxId: data.chsiMailboxId || '',
    chsiAllowedFromDomains: data.chsiAllowedFromDomains?.length ? data.chsiAllowedFromDomains : ['chsi.com.cn'],
    chsiMailKeywords: data.chsiMailKeywords?.length ? data.chsiMailKeywords : ['在线验证报告'],
    chsiMailWaitMinutes: data.chsiMailWaitMinutes || 15,
    manualNotifyGroups: Array.isArray(data.manualNotifyGroups) ? data.manualNotifyGroups : [],
  })
  fromDomainsText.value = (form.chsiAllowedFromDomains || []).join('\n')
  mailKeywordsText.value = (form.chsiMailKeywords || []).join('\n')
  reportUrlTemplate.value = data.chsiReportUrlTemplate || DEFAULT_REPORT_URL
  Object.assign(selectors, DEFAULT_SELECTORS, data.chsiSelectors || {})
  const groups = form.manualNotifyGroups || []
  notifyConnectionId.value = groups.find(item => item.connectionId)?.connectionId || ''
  notifyGroupIds.value = groups
    .filter(item => item.connectionId === notifyConnectionId.value && item.groupId)
    .map(item => item.groupId)
}

async function loadQqGroups(resetSelection = false) {
  if (!notifyConnectionId.value) {
    qqGroups.value = []
    if (resetSelection) {
      notifyGroupIds.value = []
    }
    return
  }
  loadingQqGroups.value = true
  try {
    qqGroups.value = await props.sdk.messaging.groups(notifyConnectionId.value)
    if (resetSelection) {
      const valid = new Set(qqGroups.value.map(item => item.id))
      notifyGroupIds.value = notifyGroupIds.value.filter(id => valid.has(id))
    }
  }
  catch (cause) {
    qqGroups.value = []
    toast.warning(errorMessage(cause, '加载 QQ 群失败'))
  }
  finally {
    loadingQqGroups.value = false
  }
}

function changeNotifyConnection() {
  extraGroupId.value = ''
  void loadQqGroups(true)
}

function addExtraGroup() {
  const id = extraGroupId.value.trim()
  if (!id) {
    return
  }
  if (!notifyGroupIds.value.includes(id)) {
    notifyGroupIds.value = [...notifyGroupIds.value, id]
  }
  extraGroupId.value = ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    apply(await api.settings())
    qqConnections.value = await props.sdk.messaging.connections().catch(() => [])
    await loadQqGroups()
  }
  catch (cause) {
    error.value = errorMessage(cause, '加载设置失败')
  }
  finally {
    loading.value = false
  }
}

async function save() {
  const groups = selectedNotifyGroups()
  if (form.manualNotifyEnabled && (!notifyConnectionId.value || !groups.length)) {
    toast.warning('开启群通知需要选择消息连接与至少一个群')
    return
  }
  saving.value = true
  error.value = ''
  try {
    apply(await api.saveSettings({
      ...form,
      manualNotifyGroups: groups,
      chsiAllowedFromDomains: splitList(fromDomainsText.value),
      chsiMailKeywords: splitList(mailKeywordsText.value),
      chsiReportUrlTemplate: reportUrlTemplate.value,
      chsiSelectors: { ...selectors },
    }))
    toast.success('认证设置已保存')
  }
  catch (cause) {
    error.value = errorMessage(cause, '保存失败')
  }
  finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="ev-page">
    <FaPageHeader title="认证设置" description="控制教育邮箱、学信网与人工审核三个渠道，以及有效期与材料保留策略。">
      <FaButton variant="outline" :loading="loading" @click="load">
        <FaIcon name="i-ri:refresh-line" />
        刷新
      </FaButton>
    </FaPageHeader>

    <FaPageMain>
      <form class="ev-stack" @submit.prevent="save">
        <FaAlert v-if="error" variant="destructive" title="操作未完成" :description="error" />
        <FaCard title="渠道开关" description="CARSI 仍为预留渠道，无法在此开启。" content-class="ev-card-content">
          <div class="ev-switch-row">
            <FaSwitch v-model="form.emailEnabled" />
            <div class="ev-switch-copy">
              <strong>教育邮箱</strong>
              <small>填写白名单内的高校教育邮箱即视为已核验，无需验证码。</small>
            </div>
          </div>
          <div class="ev-switch-row">
            <FaSwitch v-model="form.chsiEnabled" />
            <div class="ev-switch-copy">
              <strong>学信网在线验证码</strong>
              <small>读取官方 16 位报告页核验；开启邮件二次确认后还须匹配学信网官方报告邮件。失败转入人工时须已填写姓名与学校。</small>
            </div>
          </div>
          <div class="ev-switch-row">
            <FaSwitch v-model="form.manualEnabled" />
            <div class="ev-switch-copy">
              <strong>人工审核</strong>
              <small>作为兜底渠道，也承接学信网降级申请。姓名与学校必填，管理员可纠正并写入人员管理。</small>
            </div>
          </div>
        </FaCard>

        <FaCard title="限额与有效期" content-class="ev-card-content">
          <div class="ev-settings-grid">
            <FaLabel label="验证码有效期（分钟）" class="ev-field">
              <FaNumberField v-model="form.codeTtlMinutes" :min="1" :max="60" class="w-full" />
            </FaLabel>
            <FaLabel label="重发间隔（秒）" class="ev-field">
              <FaNumberField v-model="form.codeResendSeconds" :min="10" :max="600" class="w-full" />
            </FaLabel>
            <FaLabel label="每日验证码次数" class="ev-field">
              <FaNumberField v-model="form.codeDailyLimit" :min="1" :max="30" class="w-full" />
            </FaLabel>
            <FaLabel label="学信网每日次数" class="ev-field">
              <FaNumberField v-model="form.chsiDailyLimit" :min="1" :max="30" class="w-full" />
            </FaLabel>
            <FaLabel label="认证有效期（天）" class="ev-field">
              <FaNumberField v-model="form.validityDays" :min="0" :max="3650" class="w-full" />
              <span class="ev-field-hint">0 表示不过期。</span>
            </FaLabel>
            <FaLabel label="材料保留天数" class="ev-field">
              <FaNumberField v-model="form.retentionDays" :min="1" :max="365" class="w-full" />
              <span class="ev-field-hint">审核完成后到期清理证明材料。</span>
            </FaLabel>
          </div>
        </FaCard>

        <FaCard title="渠道教程" description="用户在认证弹窗中选择渠道后可查看对应说明。仅支持 Markdown 文本与链接。" content-class="ev-card-content">
          <FaLabel label="教育邮箱教程" class="ev-field">
            <MdEditor v-model="form.emailTutorialMarkdown" language="zh-CN" preview-theme="github" code-theme="github" :no-upload-img="true" :style="{ height: '320px' }" />
          </FaLabel>
          <FaLabel label="学信网教程" class="ev-field">
            <MdEditor v-model="form.chsiTutorialMarkdown" language="zh-CN" preview-theme="github" code-theme="github" :no-upload-img="true" :style="{ height: '320px' }" />
          </FaLabel>
          <FaLabel label="人工审核教程" class="ev-field">
            <MdEditor v-model="form.manualTutorialMarkdown" language="zh-CN" preview-theme="github" code-theme="github" :no-upload-img="true" :style="{ height: '320px' }" />
          </FaLabel>
        </FaCard>

        <FaCard title="人工审核群通知" description="人工审核提交后向选定的 QQ 群推送不含验证码和材料地址的待审提醒。" content-class="ev-card-content">
          <div class="ev-switch-row">
            <FaSwitch v-model="form.manualNotifyEnabled" />
            <div class="ev-switch-copy">
              <strong>启用群通知</strong>
              <small>通知失败不会阻断用户提交，失败会记录到审核审计日志。</small>
            </div>
          </div>
          <template v-if="form.manualNotifyEnabled">
            <div class="ev-settings-grid">
              <FaLabel label="消息连接" class="ev-field">
                <FaSelect v-model="notifyConnectionId" :options="qqConnectionOptions" :disabled="saving" @update:model-value="changeNotifyConnection" />
              </FaLabel>
              <FaLabel label="通知群（可多选）" class="ev-field">
                <FaSelect v-model="notifyGroupIds" multiple :options="qqGroupOptions" placeholder="请选择 QQ 群" :disabled="saving || !notifyConnectionId || loadingQqGroups" />
                <FaInput v-model="extraGroupId" placeholder="官方群 openid，回车添加" :disabled="saving || !notifyConnectionId" @keydown.enter.prevent="addExtraGroup" />
                <span class="ev-field-hint">官方 QQ 没有完整历史群列表，选项来自本进程收到过的群消息；已保存的群会保留，也可粘贴群 openid。</span>
              </FaLabel>
            </div>
          </template>
          <FaLabel label="通知模板" class="ev-field">
            <FaInput v-model="form.manualNotifyTemplate" class="w-full" maxlength="2000" />
            <span class="ev-field-hint">可用变量：{email}、{realName}、{schoolName}、{materialCount}</span>
          </FaLabel>
        </FaCard>

        <FaCard title="学信网邮件二次确认" description="开启后，官方报告页解析成功不会立即放行，须再匹配学信网官方发出的同一 16 位验证码邮件。收件箱由宿主「平台能力 › 入站邮箱」统一配置，插件不会代发或代登录。" content-class="ev-card-content">
          <div class="ev-switch-row">
            <FaSwitch v-model="form.chsiMailConfirmationEnabled" />
            <div class="ev-switch-copy">
              <strong>启用官方报告邮件确认</strong>
              <small>用户须在学信网报告页使用官方「发送到邮箱」按钮，把报告发到站点指定收件邮箱。系统会从邮件正文或 PDF 附件抽取 16 位验证码并与用户填写的码比对。错误发件域、错误验证码、超时或 IMAP 不可用都不会通过。</small>
            </div>
          </div>
          <div class="ev-settings-grid">
            <FaLabel label="收件箱标识" class="ev-field">
              <FaInput v-model="form.chsiMailboxId" class="w-full" maxlength="64" placeholder="留空跟随宿主配置" />
              <span class="ev-field-hint">留空则使用宿主「平台能力 › 入站邮箱」中的收件箱标识。</span>
            </FaLabel>
            <FaLabel label="等待时长（分钟）" class="ev-field">
              <FaNumberField v-model="form.chsiMailWaitMinutes" :min="1" :max="60" class="w-full" />
            </FaLabel>
          </div>
          <FaLabel label="允许发件域" class="ev-field">
            <FaInput v-model="fromDomainsText" class="w-full" maxlength="400" placeholder="chsi.com.cn" />
            <span class="ev-field-hint">按实际 From 域名匹配，可用逗号或换行分隔。默认 chsi.com.cn。</span>
          </FaLabel>
          <FaLabel label="邮件关键词" class="ev-field">
            <FaInput v-model="mailKeywordsText" class="w-full" maxlength="400" placeholder="在线验证报告" />
            <span class="ev-field-hint">主题、正文或 PDF 附件必须同时包含验证码和这些关键词。验证码会忽略空格与大小写。</span>
          </FaLabel>
        </FaCard>

        <FaCard title="学信网报告页解析" description="留空则使用当前官方报告页默认选择器。模板必须包含 {code}，且仅允许 www.chsi.com.cn。" content-class="ev-card-content">
          <FaLabel label="报告页地址模板" class="ev-field">
            <FaInput v-model="reportUrlTemplate" class="w-full" maxlength="240" placeholder="https://www.chsi.com.cn/xlcx/bg.do?vcode={code}&srcid=bgcx" />
          </FaLabel>
          <div class="ev-settings-grid">
            <FaLabel label="姓名选择器" class="ev-field">
              <FaInput v-model="selectors.realName" class="w-full" maxlength="200" />
            </FaLabel>
            <FaLabel label="学校名称选择器" class="ev-field">
              <FaInput v-model="selectors.schoolName" class="w-full" maxlength="200" />
            </FaLabel>
            <FaLabel label="学籍状态选择器" class="ev-field">
              <FaInput v-model="selectors.studentStatus" class="w-full" maxlength="200" />
            </FaLabel>
            <FaLabel label="在线验证码选择器" class="ev-field">
              <FaInput v-model="selectors.vcode" class="w-full" maxlength="200" />
            </FaLabel>
          </div>
        </FaCard>

        <div class="ev-actions ev-actions-end">
          <FaButton type="submit" :loading="saving" :disabled="loading">
            <FaIcon name="i-ri:save-3-line" />
            保存设置
          </FaButton>
        </div>
      </form>
    </FaPageMain>
  </section>
</template>
