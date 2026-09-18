<script setup lang="ts">
import type { QuestionBankPluginModel } from '../composables/useQuestionBankPlugin'
import type { QqCommandWindow, QqQuizGroup } from '../types'
import { FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTag, YdTimePicker, useFaToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import CategorySelect from '../components/CategorySelect.vue'

const props = defineProps<{ model: QuestionBankPluginModel }>()
const model = props.model
const toast = useFaToast()

const practiceEnabled = ref(true)
const aiProviderCode = ref('')
const aiModelCode = ref('')

// ---------- QQ 抽题 ----------
const qqGroups = ref<QqQuizGroup[]>([])
const qqDefaultGroup = ref('')
const qqAnswerSeconds = ref('60')
const qqAiGrading = ref(true)

// ---------- 群指令开放时间 ----------
// 每行就是时间选择器的 [开始, 结束]；未选全的行不提交，避免把半截时间段写进设置。
const qqCommandWindowEnabled = ref(false)
const qqCommandWindowRows = ref<(string | undefined)[][]>([])

function normalizeWindows(rows: (string | undefined)[][]): QqCommandWindow[] {
  return rows
    .map(row => ({ start: String(row?.[0] ?? '').trim(), end: String(row?.[1] ?? '').trim() }))
    .filter(row => row.start && row.end)
}

function addQqCommandWindow() {
  qqCommandWindowRows.value = [...qqCommandWindowRows.value, [undefined, undefined]]
}

function removeQqCommandWindow(index: number) {
  qqCommandWindowRows.value = qqCommandWindowRows.value.filter((_, item) => item !== index)
}

const qqCommandWindowPreview = computed(() => normalizeWindows(qqCommandWindowRows.value)
  .map(row => `${row.start}-${row.end}`).join('、'))

function normalizeGroups(groups: QqQuizGroup[]) {
  return groups
    .map(group => ({ name: group.name.trim(), categoryId: group.categoryId || undefined,
      tags: (group.tags ?? []).map(tag => tag.trim()).filter(Boolean) }))
    .filter(group => group.name)
}

const dirty = computed(() => model.settings !== null
  && (practiceEnabled.value !== model.settings.practiceEnabled
    || aiProviderCode.value !== (model.settings.aiProviderCode ?? '')
    || aiModelCode.value !== (model.settings.aiModelCode ?? '')
    || qqDefaultGroup.value !== (model.settings.qqDefaultGroup ?? '')
    || Number(qqAnswerSeconds.value) !== model.settings.qqAnswerSeconds
    || qqAiGrading.value !== model.settings.qqAiGrading
    || qqCommandWindowEnabled.value !== model.settings.qqCommandWindowEnabled
    || JSON.stringify(normalizeWindows(qqCommandWindowRows.value)) !== JSON.stringify(model.settings.qqCommandWindows ?? [])
    || JSON.stringify(normalizeGroups(qqGroups.value)) !== JSON.stringify(normalizeGroups(model.settings.qqGroups ?? []))))

/** 供应商选项：平台默认 + 平台已配置供应商；当前已保存但列表中不存在的编码保留展示，避免误清空。 */
const providerOptions = computed(() => {
  const options = [{ label: '平台默认供应商', value: '' },
    ...model.aiProviders.map(provider => ({ label: `${provider.name}（${provider.code}）`, value: provider.code }))]
  if (aiProviderCode.value && !options.some(option => option.value === aiProviderCode.value)) {
    options.push({ label: `${aiProviderCode.value}（当前配置，平台已下线）`, value: aiProviderCode.value })
  }
  return options
})

const modelOptions = computed(() => {
  const provider = model.aiProviders.find(item => item.code === aiProviderCode.value)
  const options = [{ label: provider ? '供应商默认模型' : '平台默认模型', value: '' },
    ...(provider?.models ?? []).map(item => ({ label: `${item.name}（${item.code}）`, value: item.code }))]
  if (aiModelCode.value && !options.some(option => option.value === aiModelCode.value)) {
    options.push({ label: `${aiModelCode.value}（当前配置，平台已下线）`, value: aiModelCode.value })
  }
  return options
})

function onProviderChange() {
  const provider = model.aiProviders.find(item => item.code === aiProviderCode.value)
  if (aiModelCode.value && provider && !provider.models.some(item => item.code === aiModelCode.value)) {
    aiModelCode.value = ''
  }
}

onMounted(async () => {
  await Promise.all([model.loadSettings(), model.loadAiProviders(), model.loadAdminCategories()])
  if (model.settings) {
    practiceEnabled.value = model.settings.practiceEnabled
    aiProviderCode.value = model.settings.aiProviderCode ?? ''
    aiModelCode.value = model.settings.aiModelCode ?? ''
    qqGroups.value = (model.settings.qqGroups ?? []).map(group => ({
      name: group.name,
      categoryId: group.categoryId ?? '',
      tags: [...(group.tags ?? [])],
    }))
    qqDefaultGroup.value = model.settings.qqDefaultGroup ?? ''
    qqAnswerSeconds.value = String(model.settings.qqAnswerSeconds || 60)
    qqAiGrading.value = model.settings.qqAiGrading
    qqCommandWindowEnabled.value = model.settings.qqCommandWindowEnabled ?? false
    qqCommandWindowRows.value = (model.settings.qqCommandWindows ?? [])
      .map(window => [window.start, window.end] as (string | undefined)[])
  }
})

function addQqGroup() {
  qqGroups.value = [...qqGroups.value, { name: '', categoryId: '', tags: [] }]
}

function removeQqGroup(index: number) {
  qqGroups.value = qqGroups.value.filter((_, item) => item !== index)
}

/** 标签输入：逗号/顿号/空格分隔转数组。 */
function updateGroupTags(group: QqQuizGroup, raw: string) {
  group.tags = raw.split(/[,，、\s]+/).map(tag => tag.trim()).filter(Boolean)
}

function groupTagsText(group: QqQuizGroup) {
  return (group.tags ?? []).join('，')
}

const qqGroupNameOptions = computed(() => [
  { label: '不限定（全库随机）', value: '' },
  ...normalizeGroups(qqGroups.value).map(group => ({ label: group.name, value: group.name })),
])

function save() {
  const seconds = Number.parseInt(qqAnswerSeconds.value, 10)
  if (!Number.isFinite(seconds) || seconds < 10 || seconds > 600) {
    toast.warning('抢答限时需为 10-600 秒的整数')
    return
  }
  const halfFilledWindow = qqCommandWindowRows.value
    .some(row => Boolean(row?.[0]) !== Boolean(row?.[1]))
  if (halfFilledWindow) {
    toast.warning('开放时间的开始与结束都要选择，或者整行删掉')
    return
  }
  void model.saveSettings({
    practiceEnabled: practiceEnabled.value,
    aiProviderCode: aiProviderCode.value,
    aiModelCode: aiModelCode.value,
    qqGroups: normalizeGroups(qqGroups.value),
    qqDefaultGroup: qqDefaultGroup.value || null,
    qqAnswerSeconds: seconds,
    qqAiGrading: qqAiGrading.value,
    qqCommandWindowEnabled: qqCommandWindowEnabled.value,
    qqCommandWindows: normalizeWindows(qqCommandWindowRows.value),
  })
}
</script>

<template>
  <FaPageHeader title="题库设置" description="控制题库对普通用户开放的功能范围与 AI 判分配置" />
  <FaPageMain>
    <div class="qb-form">
      <FaCard title="自由刷题" description="关闭后，用户端「抽题练习」入口不可用；已发布题单的作答不受影响">
        <div class="qb-form">
          <div class="qb-form-row">
            <span class="qb-form-label">自由刷题开关</span>
            <div class="flex items-center gap-3">
              <FaSwitch v-model="practiceEnabled" :disabled="model.settingsSaving" />
              <FaTag :variant="practiceEnabled ? 'default' : 'outline'">{{ practiceEnabled ? '已开启' : '已关闭' }}</FaTag>
            </div>
          </div>
          <p class="qb-muted text-sm">
            题库的推荐用法是发布题单给指定人群作答（招新笔试、活动答题等）；自由刷题适合作为公开练习入口，可按需关闭。
          </p>
        </div>
      </FaCard>
      <FaCard title="AI 能力" description="题单简答题评分方式选择「AI 判分」与题目管理「AI 导入」时生效；默认使用平台默认 AI 供应商与模型，判分失败自动转人工审核">
        <div class="qb-form">
          <div class="qb-form-row">
            <span class="qb-form-label">AI 供应商</span>
            <FaSelect v-model="aiProviderCode" :options="providerOptions" @change="onProviderChange" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">AI 模型</span>
            <FaSelect v-model="aiModelCode" :options="modelOptions" />
          </div>
          <p v-if="!model.aiProviders.length" class="qb-muted text-sm">
            平台当前未配置可用的 AI 供应商，AI 判分将自动转人工审核、AI 导入暂不可用；配置后此处会出现可选项。
          </p>
          <p v-else class="qb-muted text-sm">
            仅在需要为判分/导入指定专用模型时选择；留空使用平台默认供应商与模型。
          </p>
        </div>
      </FaCard>
      <FaCard title="QQ 群抽题" description="群内发送 /抽题 或 /抽题 <分组名> 发起限时抢答；客观题自动判分，简答题可用上方配置的 AI 模型判分">
        <div class="qb-form">
          <div class="qb-form-row">
            <span class="qb-form-label">默认分组</span>
            <FaSelect v-model="qqDefaultGroup" :options="qqGroupNameOptions" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">抢答限时（秒）</span>
            <FaInput v-model="qqAnswerSeconds" placeholder="10-600" />
          </div>
          <div class="qb-form-row">
            <span class="qb-form-label">简答题 AI 判分</span>
            <div class="flex items-center gap-3">
              <FaSwitch v-model="qqAiGrading" :disabled="model.settingsSaving" />
              <FaTag :variant="qqAiGrading ? 'default' : 'outline'">{{ qqAiGrading ? '已开启' : '已关闭' }}</FaTag>
            </div>
          </div>
          <div class="qb-form-row items-start">
            <span class="qb-form-label pt-2">抽题分组</span>
            <div class="flex flex-1 flex-col gap-2">
              <div v-for="(group, index) in qqGroups" :key="index" class="flex flex-wrap items-center gap-2 rounded-md border p-2">
                <FaInput v-model="group.name" class="max-w-36" placeholder="分组名（群内指令用）" />
                <CategorySelect v-model="group.categoryId" :categories="model.adminCategories" empty-label="不限分类" class="min-w-40" />
                <FaInput
                  :model-value="groupTagsText(group)"
                  class="min-w-40 flex-1"
                  placeholder="标签，逗号分隔（可选）"
                  @update:model-value="updateGroupTags(group, String($event))"
                />
                <FaButton size="sm" variant="ghost" @click="removeQqGroup(index)"><FaIcon name="i-ri:close-line" /></FaButton>
              </div>
              <div>
                <FaButton size="sm" variant="outline" @click="addQqGroup"><FaIcon name="i-ri:add-line" />添加分组</FaButton>
              </div>
              <p class="qb-muted text-sm">
                分组 = 一个指令名字 + 抽题范围（分类/标签，可都留空表示全库）。群内发送 /抽题 分组名 即按该范围抽一道题。
              </p>
            </div>
          </div>
        </div>
      </FaCard>
      <FaCard title="群指令开放时间" description="限制群内 /抽题、/抢答榜 的可用时段；不在时段内发送指令时，群内会收到「现在不在开放时间，开放时间为 …」">
        <div class="qb-form">
          <div class="qb-form-row">
            <span class="qb-form-label">限制调用时间</span>
            <div class="flex items-center gap-3">
              <FaSwitch v-model="qqCommandWindowEnabled" :disabled="model.settingsSaving" />
              <FaTag :variant="qqCommandWindowEnabled ? 'default' : 'outline'">{{ qqCommandWindowEnabled ? '已开启' : '已关闭' }}</FaTag>
            </div>
          </div>
          <div class="qb-form-row items-start">
            <span class="qb-form-label pt-2">开放时间段</span>
            <div class="flex flex-1 flex-col gap-2">
              <div v-for="(row, index) in qqCommandWindowRows" :key="index" class="flex flex-wrap items-center gap-2 rounded-md border p-2">
                <YdTimePicker v-model="qqCommandWindowRows[index]" range format="HH:mm" class="min-w-56" />
                <FaButton size="sm" variant="ghost" @click="removeQqCommandWindow(index)"><FaIcon name="i-ri:close-line" /></FaButton>
              </div>
              <div>
                <FaButton size="sm" variant="outline" @click="addQqCommandWindow"><FaIcon name="i-ri:add-line" />添加时间段</FaButton>
              </div>
              <p class="qb-muted text-sm">
                可以配置多段（例如 12:00-13:30、19:00-21:00）；开始时间晚于结束时间表示跨零点，例如 22:00-02:00 表示当天 22:00 到次日 02:00。
                留空则不限制——开关打开但没有时间段时同样不限制，避免漏填把群指令整个锁死。已经发出的题目照常作答，不受关闭时刻影响。
              </p>
              <p v-if="qqCommandWindowEnabled && !qqCommandWindowPreview" class="qb-muted text-sm">
                当前没有有效时间段，群指令不会被限制。
              </p>
            </div>
          </div>
        </div>
      </FaCard>
      <FaCard>
        <div class="flex justify-end gap-2">
          <FaButton :disabled="!dirty" :loading="model.settingsSaving" @click="save">
            <FaIcon name="i-ri:save-line" />保存设置
          </FaButton>
        </div>
      </FaCard>
    </div>
  </FaPageMain>
</template>
