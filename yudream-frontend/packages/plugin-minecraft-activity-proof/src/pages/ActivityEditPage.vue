<script setup lang="ts">
import type { ActivityBindingForm, ActivityParamForm } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaCheckbox, FaCheckboxGroup, FaIcon, FaImageUpload, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTag, YdRangePicker } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import MarkdownEditor from '../components/MarkdownEditor.vue'
import { useActivityEdit } from '../composables/useActivityEdit'
import { paramKey, validateFormula } from '../composables/formula'
import { normalizeFileUrl } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const model = useActivityEdit(props.sdk)
const { loading, saving, uploadingCover, form, readonly, coverPreview, isEdit, minecraftReady, formReady, quizReady, deptOptions, servers, formOptions, quizAvailable, quizSaving, quizCategories, quizForm } = model

const editId = computed(() => String(props.route?.query?.id || ''))
watch(editId, id => model.load(id), { immediate: true })

const deptModeOptions = [
  { label: '全体成员可参与', value: 'ALL' },
  { label: '仅限指定部门', value: 'DEPTS' },
]

const deptSelectOptions = computed(() => deptOptions.value.map(item => ({ label: item.label || item.name, value: item.id })))
const serverSelectOptions = computed(() => servers.value.map(item => ({ label: item.name, value: item.id })))

/**
 * 子服选项；默认入口与已装传感器的子服在标签里标出来。
 *
 * 首项是空值的「整服」：FaSelect 没有 clearable，如果把「整服」只写成 placeholder，一旦选了
 * 某台子服就再也回不到整服口径——placeholder 只在无值时显示，点不到。
 */
const subServerOptions = (serverId: string) => [
  { label: '整服（不限子服）', value: '' },
  ...model.subServersOf(serverId).map(sub => ({
    value: sub.name,
    label: sub.name
      + (sub.defaultServer ? '（默认入口）' : '')
      + (sub.sensor ? '' : '（未装传感器）'),
  })),
]
const formSelectOptions = computed(() => formOptions.value.map(item => ({ label: item.description ? `${item.name}（${item.description}）` : item.name, value: item.code })))

// FaImageUpload 内部通过 push/splice 原地改数组，不会触发 update:modelValue；
// 用独立 ref 承接组件持有的数组引用，再用 watch 双向同步 form.coverUrl
const coverList = ref<string[]>([])

watch(coverList, (list) => {
  const last = list.length ? normalizeFileUrl(list[list.length - 1]) : ''
  if (last !== form.coverUrl) {
    form.coverUrl = last
  }
}, { deep: true })

watch(coverPreview, (url) => {
  const current = coverList.value[coverList.value.length - 1] || ''
  if (url !== current) {
    coverList.value = url ? [url] : []
  }
})

const quizCategoryOptions = computed(() => [
  { label: '全部分类', value: '' },
  ...quizCategories.value.map(item => ({ label: item.name, value: item.id })),
])
const quizTypeOptions = [
  { label: '单选题', value: 'SINGLE' },
  { label: '多选题', value: 'MULTI' },
  { label: '判断题', value: 'JUDGE' },
  { label: '填空题', value: 'BLANK' },
  { label: '简答题', value: 'SHORT' },
]
const quizDifficultyOptions = [
  { label: '难度 1', value: 1 },
  { label: '难度 2', value: 2 },
  { label: '难度 3', value: 3 },
  { label: '难度 4', value: 4 },
  { label: '难度 5', value: 5 },
]
const quizSubjectiveOptions = [
  { label: '用户自评', value: 'SELF' },
  { label: '管理员人工审核', value: 'REVIEW' },
  { label: 'AI 判分（失败转人工审核）', value: 'AI' },
]

const hasQuizBinding = computed(() => form.bindings.some(binding => binding.type === 'QUIZ'))
const hasAdvancedBinding = computed(() => form.bindings.some(binding => binding.type === 'ADVANCED'))

function bindingTag(type: string) {
  if (type === 'PLAYTIME') {
    return { variant: 'default' as const, text: '服务器时长检测' }
  }
  if (type === 'QUIZ') {
    return { variant: 'secondary' as const, text: '答题达标' }
  }
  if (type === 'ADVANCED') {
    return { variant: 'secondary' as const, text: '高级自定义计分' }
  }
  return { variant: 'outline' as const, text: '表单提交' }
}

const advancedParamTypeOptions = [
  { label: '服务器在线时长', value: 'PLAYTIME' },
  { label: '答题得分', value: 'QUIZ' },
  { label: '表单提交', value: 'FORM' },
]

function serverLabel(serverId: string) {
  return servers.value.find(item => item.id === serverId)?.name || ''
}

function formLabel(formCode: string) {
  return formOptions.value.find(item => item.code === formCode)?.name || ''
}

/** 参数含义（自动生成，用于参数映射表格与管理端文案对齐）。 */
function advancedParamMeaning(param: ActivityParamForm) {
  if (param.type === 'PLAYTIME') {
    const server = serverLabel(param.serverId)
    const scope = param.subServer ? `的子服「${param.subServer}」` : ''
    return `${server || '未选择服务器'}${scope}活动时段在线时长（分钟）`
  }
  if (param.type === 'QUIZ') {
    return '活动答题得分（答对题数）'
  }
  const name = formLabel(param.formCode)
  return `表单「${name || '未选择表单'}」是否提交（1/0）`
}

/** 参数映射表：变量名 → 含义，供书写计算式时对照。 */
function advancedParamRows(binding: ActivityBindingForm) {
  return binding.params.map((param, index) => ({
    key: paramKey(index),
    meaning: advancedParamMeaning(param),
  }))
}

/** 公式实时校验：错误时给出可直接展示的原因，正确时提示已引用的变量。 */
function advancedFormulaState(binding: ActivityBindingForm) {
  const keys = binding.params.map((_, index) => paramKey(index))
  const check = validateFormula(binding.expression, keys)
  if (check.ok) {
    const used = new Set((binding.expression.match(/[a-zA-Z]/g) || []).map(char => char.toLowerCase()))
    const referenced = keys.filter(key => used.has(key))
    return {
      ok: true,
      message: referenced.length
        ? `公式语法正确，引用参数：${referenced.join('、')}`
        : '公式语法正确（尚未引用任何参数变量）',
    }
  }
  return { ok: false, message: check.message }
}

/** 含答题得分参数但答题环节未开启时给出预警，与「答题」核验方式的提示口径一致。 */
function advancedQuizWarning(binding: ActivityBindingForm) {
  if (!binding.params.some(param => param.type === 'QUIZ')) {
    return ''
  }
  if (!isEdit.value) {
    return '含答题得分参数的活动需先保存，并在下方「答题环节」开启答题，否则该参数始终无法计分。'
  }
  if (!quizForm.enabled) {
    return '当前答题环节未开启，请在下方「答题环节」开启并保存，否则答题得分参数始终无法计分。'
  }
  return ''
}

async function coverUpload(options: { file: File }) {
  return await model.uploadCover(options.file)
}

function afterUpload(response: unknown) {
  return typeof response === 'string' ? response : ''
}

async function uploadMarkdownImage(file: File) {
  const uploaded = await props.sdk.files.uploadImage(file, { module: 'minecraft-activity-proof', publicAccess: true })
  return uploaded.assetUrl || uploaded.url || ''
}

function back() {
  router.push({ path: '/platform/plugins/yudream-student-info/activity-proof/activities' })
}

async function save() {
  await model.save()
}
</script>

<template>
  <section class="proof-page">
    <FaPageHeader :title="isEdit ? '编辑活动' : '发布活动'" class="mb-0">
      <div class="flex flex-wrap gap-2">
        <FaButton variant="outline" @click="back">
          <FaIcon name="i-ri:arrow-left-line" />返回列表
        </FaButton>
        <FaButton :loading="saving" :disabled="readonly" @click="save">
          <FaIcon name="i-ri:save-3-line" />保存活动
        </FaButton>
      </div>
    </FaPageHeader>
    <FaPageMain>
      <div v-loading="loading">
        <div v-if="readonly" class="activity-readonly-banner">
          <FaIcon name="i-ri:lock-line" />该活动已结束，内容不可再编辑。
        </div>
        <form class="grid gap-5" @submit.prevent="save">
          <fieldset :disabled="readonly" class="grid gap-5">
            <div class="grid gap-3 rounded-lg border p-4">
              <h3 class="text-base font-semibold">基本信息</h3>
              <label class="grid gap-2">
                <span>活动标题 <em class="required-mark">*</em></span>
                <FaInput v-model="form.title" placeholder="请输入活动标题" />
              </label>
              <label class="grid gap-2">
                <span>活动简介</span>
                <FaInput v-model="form.summary" placeholder="一句话介绍，展示在活动广场卡片上" />
              </label>
              <div class="grid gap-2">
                <span>活动封面</span>
                <FaImageUpload
                  :model-value="coverList"
                  :max="1"
                  :width="240"
                  :height="135"
                  :disabled="readonly || uploadingCover"
                  :http-request="coverUpload"
                  :after-upload="afterUpload"
                />
                <span class="text-xs text-muted-foreground">建议 16:9 图片，上传后自动设为公开访问。</span>
              </div>
              <div class="grid gap-2">
                <span>活动详情</span>
                <MarkdownEditor
                  v-model="form.description"
                  placeholder="活动规则、流程、奖励等详细说明，支持 Markdown 语法与插图"
                  :upload-image="uploadMarkdownImage"
                />
              </div>
            </div>

            <div class="grid gap-3 rounded-lg border p-4">
              <h3 class="text-base font-semibold">时间安排</h3>
              <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
                <label class="grid gap-2">
                  <span>报名时间</span>
                  <YdRangePicker
                    v-model="form.signupRange"
                    :placeholder="['报名开始日期', '报名结束日期']"
                    :disabled="readonly"
                    style="width: 100%"
                  />
                </label>
                <label class="grid gap-2">
                  <span>活动时间</span>
                  <YdRangePicker
                    v-model="form.activityRange"
                    :placeholder="['活动开始日期', '活动结束日期']"
                    :disabled="readonly"
                    style="width: 100%"
                  />
                </label>
              </div>
              <span class="text-xs text-muted-foreground">时间精确到日，所选结束日期当天全天有效；留空表示不限制。时长与表单核验以活动时间（报名开始后）作为核验窗口。</span>
            </div>

            <div class="grid gap-3 rounded-lg border p-4">
              <h3 class="text-base font-semibold">参与范围</h3>
              <label class="grid max-w-md gap-2">
                <span>参与限制</span>
                <FaSelect v-model="form.deptMode" :options="deptModeOptions" />
              </label>
              <label v-if="form.deptMode === 'DEPTS'" class="grid gap-2">
                <span>允许参与的部门 <em class="required-mark">*</em></span>
                <FaSelect v-model="form.allowedDeptIds" :options="deptSelectOptions" multiple placeholder="选择一个或多个部门" />
              </label>
            </div>

            <div class="grid gap-3 rounded-lg border p-4">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <h3 class="text-base font-semibold">核验方式</h3>
                <div class="flex flex-wrap gap-2">
                  <FaButton size="sm" variant="outline" type="button" :disabled="!minecraftReady" @click="model.addBinding('PLAYTIME')">
                    <FaIcon name="i-ri:timer-line" />添加时长检测
                  </FaButton>
                  <FaButton size="sm" variant="outline" type="button" :disabled="!formReady" @click="model.addBinding('FORM')">
                    <FaIcon name="i-ri:file-list-3-line" />添加表单提交
                  </FaButton>
                  <FaButton size="sm" variant="outline" type="button" :disabled="!quizReady || hasQuizBinding" @click="model.addBinding('QUIZ')">
                    <FaIcon name="i-ri:questionnaire-line" />添加答题
                  </FaButton>
                  <FaButton size="sm" variant="outline" type="button" :disabled="hasAdvancedBinding" @click="model.addBinding('ADVANCED')">
                    <FaIcon name="i-ri:function-line" />添加高级自定义
                  </FaButton>
                </div>
              </div>
              <p class="text-sm text-muted-foreground">
                不配置核验方式时，用户参与活动即视为达标；配置多项时满足任意一项即通过核验。
                <template v-if="!minecraftReady">服务器时长检测需要启用 Minecraft 服务器插件。</template>
                <template v-if="!formReady">表单提交需要系统启用表单收集能力。</template>
                <template v-if="!quizReady">答题达标需要启用题库插件。</template>
              </p>
              <div v-if="form.bindings.length" class="grid gap-3">
                <div v-for="(binding, index) in form.bindings" :key="index" class="grid gap-3 rounded-md border p-3">
                  <div class="flex items-center justify-between gap-2">
                    <FaTag :variant="bindingTag(binding.type).variant">
                      {{ bindingTag(binding.type).text }}
                    </FaTag>
                    <FaButton size="sm" variant="destructive" type="button" @click="model.removeBinding(index)">
                      <FaIcon name="i-ri:delete-bin-line" />移除
                    </FaButton>
                  </div>
                  <template v-if="binding.type === 'PLAYTIME'">
                    <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
                      <label class="grid gap-2">
                        <span>服务器 <em class="required-mark">*</em></span>
                        <FaSelect :model-value="binding.serverId" :options="serverSelectOptions" placeholder="选择服务器" @update:model-value="value => model.changeServer(binding, String(value || ''))" />
                      </label>
                      <!-- 代理端才有下游子服；单机服的 subServers 为空，这里整块不出现。 -->
                      <label v-if="model.subServersOf(binding.serverId).length" class="grid gap-2">
                        <span>子服</span>
                        <FaSelect v-model="binding.subServer" :options="subServerOptions(binding.serverId)" placeholder="整服（不限子服）" />
                        <span class="text-xs text-muted-foreground">留空表示整服口径：把该玩家在这台服务器全部子服上的时长相加。选择某一台子服后只按那台计时长。</span>
                      </label>
                      <label class="grid gap-2">
                        <span>活动时段在线时长（分钟）</span>
                        <FaNumberField v-model="binding.minOnlineMinutes" :min="0" placeholder="0 表示不限时长" />
                      </label>
                    </div>
                    <FaCheckbox v-model="binding.includeAfk">
                    挂机时间也计入在线时长
                    </FaCheckbox>
                    <FaCheckbox v-model="binding.autoJoin">
                    加入该服务器的玩家自动算作参与活动（可事后移除）
                    </FaCheckbox>
                    <span v-if="binding.autoJoin" class="text-xs text-muted-foreground">
                      开启后可在活动管理页手动同步活动时间窗内上线过的玩家为参与者并自动核验；被管理员移除的参与者不会再被同步加回。
                    </span>
                  </template>
                  <template v-else-if="binding.type === 'FORM'">
                    <label class="grid gap-2">
                      <span>表单 <em class="required-mark">*</em></span>
                      <FaSelect v-model="binding.formCode" :options="formSelectOptions" placeholder="选择已发布的表单" />
                    </label>
                    <span class="text-xs text-muted-foreground">用户在活动时段内提交该表单即通过核验。</span>
                  </template>
                  <template v-else-if="binding.type === 'QUIZ'">
                    <span class="text-xs text-muted-foreground">
                      用户在活动详情完成答题并达标即通过核验；抽题数量、达标题数等规则在下方「答题环节」中配置（需先保存活动）。
                    </span>
                    <span v-if="isEdit && !quizForm.enabled" class="text-xs text-amber-600">
                      当前答题环节未开启，请在下方「答题环节」开启并保存，否则该核验方式始终不通过。
                    </span>
                  </template>
                  <template v-else>
                    <p class="text-sm text-muted-foreground">
                      把服务器在线时长、答题得分、表单提交等数据加为计分参数，再按参数映射表书写带权重的计算公式；
                      参与者的综合得分达到达标分数线（且不超过可选上限）即通过该核验方式。
                    </p>
                    <div class="grid gap-2">
                      <div class="flex flex-wrap items-center justify-between gap-2">
                        <span>计分参数 <em class="required-mark">*</em></span>
                        <div class="flex flex-wrap gap-2">
                          <FaButton size="sm" variant="outline" type="button" :disabled="!minecraftReady" @click="model.addAdvancedParam(binding, 'PLAYTIME')">
                            <FaIcon name="i-ri:timer-line" />服务器在线时长
                          </FaButton>
                          <FaButton size="sm" variant="outline" type="button" :disabled="!quizReady" @click="model.addAdvancedParam(binding, 'QUIZ')">
                            <FaIcon name="i-ri:questionnaire-line" />答题得分
                          </FaButton>
                          <FaButton size="sm" variant="outline" type="button" :disabled="!formReady" @click="model.addAdvancedParam(binding, 'FORM')">
                            <FaIcon name="i-ri:file-list-3-line" />表单提交
                          </FaButton>
                        </div>
                      </div>
                      <template v-if="binding.params.length">
                        <div class="grid gap-2 rounded-md border p-2">
                          <div class="hidden md:grid md:grid-cols-[3rem_minmax(0,9rem)_minmax(0,1fr)_2.5rem] md:gap-2 text-xs text-muted-foreground">
                            <span>变量</span>
                            <span>参数类型</span>
                            <span>取值来源</span>
                            <span />
                          </div>
                          <div
                            v-for="(param, paramIndex) in binding.params"
                            :key="paramIndex"
                            class="grid gap-2 border-t pt-2 first:border-t-0 first:pt-0 md:grid-cols-[3rem_minmax(0,9rem)_minmax(0,1fr)_2.5rem] md:items-start md:gap-2"
                          >
                            <span class="inline-flex h-6 w-9 items-center justify-center rounded bg-muted font-mono text-sm font-semibold">
                              {{ paramKey(paramIndex) }}
                            </span>
                            <FaSelect
                              :model-value="param.type"
                              :options="advancedParamTypeOptions"
                              @update:model-value="value => model.changeAdvancedParamType(param, String(value || ''))"
                            />
                            <div class="grid gap-2 md:grid-cols-2">
                              <template v-if="param.type === 'PLAYTIME'">
                                <FaSelect
                                  :model-value="param.serverId"
                                  :options="serverSelectOptions"
                                  placeholder="选择服务器"
                                  @update:model-value="value => model.changeServer(param, String(value || ''))"
                                />
                                <FaSelect
                                  v-if="model.subServersOf(param.serverId).length"
                                  v-model="param.subServer"
                                  :options="subServerOptions(param.serverId)"
                                  placeholder="整服（不限子服）"
                                />
                                <FaCheckbox v-model="param.includeAfk" class="md:col-span-2">
                                  挂机时间也计入（在线口径）
                                </FaCheckbox>
                              </template>
                              <FaSelect
                                v-else-if="param.type === 'FORM'"
                                v-model="param.formCode"
                                class="md:col-span-2"
                                :options="formSelectOptions"
                                placeholder="选择已发布的表单"
                              />
                              <span v-else class="text-sm text-muted-foreground md:col-span-2">
                                取本活动答题环节的答对题数（答对题数在「答题环节」配置）。
                              </span>
                            </div>
                            <FaButton size="sm" variant="destructive" type="button" @click="model.removeAdvancedParam(binding, paramIndex)">
                              <FaIcon name="i-ri:delete-bin-line" />
                            </FaButton>
                          </div>
                        </div>
                        <div class="grid gap-2 rounded-md border bg-muted/40 p-3">
                          <span class="text-sm font-medium">参数映射表（公式中直接使用「变量」列的字母）</span>
                          <div class="grid gap-1 font-mono text-sm">
                            <div
                              v-for="row in advancedParamRows(binding)"
                              :key="row.key"
                              class="grid grid-cols-[3rem_minmax(0,1fr)] items-center gap-2"
                            >
                              <span class="font-semibold">{{ row.key }}</span>
                              <span class="break-all font-sans text-muted-foreground">{{ row.meaning }}</span>
                            </div>
                          </div>
                        </div>
                      </template>
                      <p v-else class="text-sm text-muted-foreground">
                        还没有计分参数，请先点击上方按钮添加，例如添加两台服务器的在线时长和答题得分。
                      </p>
                    </div>
                    <label class="grid gap-2">
                      <span>计分公式 <em class="required-mark">*</em></span>
                      <FaInput
                        v-model="binding.expression"
                        placeholder="如：0.5*a/30 + 0.5*b/30 + c*0.2"
                      />
                      <span :class="advancedFormulaState(binding).ok ? 'text-xs text-muted-foreground' : 'text-xs text-red-600'">
                        {{ advancedFormulaState(binding).message }}
                      </span>
                      <span class="text-xs text-muted-foreground">
                        支持 + - * / ( )、数字与参数变量；示例表示服务器时长除以 30 折算后各占 50%，答题每答对一题加 0.2 分。
                      </span>
                    </label>
                    <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
                      <label class="grid gap-2">
                        <span>达标分数线 <em class="required-mark">*</em></span>
                        <FaNumberField v-model="binding.minScore" :min="0" placeholder="综合得分达到该分数线即通过" />
                      </label>
                      <label class="grid gap-2">
                        <span>分数上限（可选）</span>
                        <FaNumberField v-model="binding.maxScore" :min="0" placeholder="0 表示不设上限" />
                      </label>
                    </div>
                    <span class="text-xs text-muted-foreground">
                      核验时逐人计算综合得分，达到「分数线 ~ 上限」区间即通过；上限填 0 表示只按下限判定。
                    </span>
                    <span v-if="advancedQuizWarning(binding)" class="text-xs text-amber-600">
                      {{ advancedQuizWarning(binding) }}
                    </span>
                    <span v-if="binding.params.some(param => param.type === 'PLAYTIME') && !form.activityRange?.length" class="text-xs text-amber-600">
                      含服务器时长参数的活动需要配置活动时间，在线时长按活动时间窗统计。
                    </span>
                  </template>
                </div>
              </div>
            </div>

            <div v-if="isEdit" class="grid gap-3 rounded-lg border p-4">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <h3 class="text-base font-semibold">答题环节（题库随机抽题）</h3>
                <FaSwitch v-model="quizForm.enabled" />
              </div>
              <p class="text-sm text-muted-foreground">
                开启后，参与用户可从活动详情进入答题，系统按下方规则现场随机抽题；答对达到达标题数即视为答题达标。
                答题达标作为核验方式生效时，请同时在上方「核验方式」中添加「答题达标」。
                <template v-if="!quizAvailable">当前题库插件未启用，配置可先保存，用户端将在题库可用后开放答题。</template>
              </p>
              <template v-if="quizForm.enabled">
                <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
                  <label class="grid gap-2">
                    <span>抽题数量 <em class="required-mark">*</em></span>
                    <FaNumberField v-model="quizForm.count" :min="1" :max="50" />
                  </label>
                  <label class="grid gap-2">
                    <span>达标题数（答对题数） <em class="required-mark">*</em></span>
                    <FaNumberField v-model="quizForm.passCorrect" :min="1" :max="quizForm.count || 50" />
                  </label>
                  <label class="grid gap-2">
                    <span>限定分类</span>
                    <FaSelect v-model="quizForm.categoryId" :options="quizCategoryOptions" />
                  </label>
                  <label class="grid gap-2">
                    <span>限定标签（逗号分隔，留空不限）</span>
                    <FaInput v-model="quizForm.tagsText" placeholder="如：招新，笔试" />
                  </label>
                </div>
                <div class="grid gap-2">
                  <span>限定题型（留空不限）</span>
                  <FaCheckboxGroup v-model="quizForm.types" :options="quizTypeOptions" class="flex flex-wrap gap-3" />
                </div>
                <div class="grid gap-2">
                  <span>限定难度（留空不限）</span>
                  <FaCheckboxGroup v-model="quizForm.difficulties" :options="quizDifficultyOptions" class="flex flex-wrap gap-3" />
                </div>
                <label class="grid max-w-md gap-2">
                  <span>简答题判分方式</span>
                  <FaSelect v-model="quizForm.subjectiveMode" :options="quizSubjectiveOptions" />
                </label>
              </template>
              <div class="flex justify-end">
                <FaButton size="sm" variant="outline" type="button" :loading="quizSaving" :disabled="readonly" @click="model.saveQuiz()">
                  <FaIcon name="i-ri:save-3-line" />保存答题配置
                </FaButton>
              </div>
            </div>

            <div class="flex justify-end gap-2">
              <FaButton variant="outline" type="button" @click="back">取消</FaButton>
              <FaButton type="submit" :loading="saving" :disabled="readonly">
                <FaIcon name="i-ri:save-3-line" />保存活动
              </FaButton>
            </div>
          </fieldset>
        </form>
      </div>
    </FaPageMain>
  </section>
</template>
