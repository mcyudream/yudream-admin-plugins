<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaCheckbox, FaCheckboxGroup, FaIcon, FaImageUpload, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTag } from '@yudream/components'
import { RangePicker as ARangePicker } from '@arco-design/web-vue'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import MarkdownEditor from '../components/MarkdownEditor.vue'
import { useActivityEdit } from '../composables/useActivityEdit'
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

function bindingTag(type: string) {
  if (type === 'PLAYTIME') {
    return { variant: 'default' as const, text: '服务器时长检测' }
  }
  if (type === 'QUIZ') {
    return { variant: 'secondary' as const, text: '答题达标' }
  }
  return { variant: 'outline' as const, text: '表单提交' }
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
      <div v-loading="loading" class="max-w-4xl">
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
                  <ARangePicker
                    v-model="form.signupRange"
                    value-format="YYYY-MM-DD"
                    format="YYYY-MM-DD"
                    :placeholder="['报名开始日期', '报名结束日期']"
                    allow-clear
                    style="width: 100%"
                  />
                </label>
                <label class="grid gap-2">
                  <span>活动时间</span>
                  <ARangePicker
                    v-model="form.activityRange"
                    value-format="YYYY-MM-DD"
                    format="YYYY-MM-DD"
                    :placeholder="['活动开始日期', '活动结束日期']"
                    allow-clear
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
                        <FaSelect v-model="binding.serverId" :options="serverSelectOptions" placeholder="选择服务器" />
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
                  <template v-else>
                    <span class="text-xs text-muted-foreground">
                      用户在活动详情完成答题并达标即通过核验；抽题数量、达标题数等规则在下方「答题环节」中配置（需先保存活动）。
                    </span>
                    <span v-if="isEdit && !quizForm.enabled" class="text-xs text-amber-600">
                      当前答题环节未开启，请在下方「答题环节」开启并保存，否则该核验方式始终不通过。
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
