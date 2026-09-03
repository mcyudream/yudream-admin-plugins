<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { FaButton, FaCheckbox, FaIcon, FaImageUpload, FaInput, FaNumberField, FaPageHeader, FaPageMain, FaSelect, FaTag } from '@yudream/components'
import { RangePicker as ARangePicker } from '@arco-design/web-vue'
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import MarkdownEditor from '../components/MarkdownEditor.vue'
import { useActivityEdit } from '../composables/useActivityEdit'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const model = useActivityEdit(props.sdk)
const { loading, saving, uploadingCover, form, readonly, coverPreview, isEdit, minecraftReady, formReady, deptOptions, servers, formOptions } = model

const editId = computed(() => String(props.route?.query?.id || ''))
watch(editId, id => model.load(id), { immediate: true })

const deptModeOptions = [
  { label: '全体成员可参与', value: 'ALL' },
  { label: '仅限指定部门', value: 'DEPTS' },
]

const deptSelectOptions = computed(() => deptOptions.value.map(item => ({ label: item.label || item.name, value: item.id })))
const serverSelectOptions = computed(() => servers.value.map(item => ({ label: item.name, value: item.id })))
const formSelectOptions = computed(() => formOptions.value.map(item => ({ label: item.description ? `${item.name}（${item.description}）` : item.name, value: item.code })))

const coverList = computed<string[]>(() => (coverPreview.value ? [coverPreview.value] : []))

function onCoverChange(list: string[]) {
  if (!list.length) {
    form.coverUrl = ''
    return
  }
  const last = list[list.length - 1]
  if (last && last !== coverPreview.value) {
    form.coverUrl = last
  }
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
                  @update:model-value="onCoverChange"
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
                </div>
              </div>
              <p class="text-sm text-muted-foreground">
                不配置核验方式时，用户参与活动即视为达标；配置多项时满足任意一项即通过核验。
                <template v-if="!minecraftReady">服务器时长检测需要启用 Minecraft 服务器插件。</template>
                <template v-if="!formReady">表单提交需要系统启用表单收集能力。</template>
              </p>
              <div v-if="form.bindings.length" class="grid gap-3">
                <div v-for="(binding, index) in form.bindings" :key="index" class="grid gap-3 rounded-md border p-3">
                  <div class="flex items-center justify-between gap-2">
                    <FaTag :variant="binding.type === 'PLAYTIME' ? 'default' : 'outline'">
                      {{ binding.type === 'PLAYTIME' ? '服务器时长检测' : '表单提交' }}
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
                  </template>
                  <template v-else>
                    <label class="grid gap-2">
                      <span>表单 <em class="required-mark">*</em></span>
                      <FaSelect v-model="binding.formCode" :options="formSelectOptions" placeholder="选择已发布的表单" />
                    </label>
                    <span class="text-xs text-muted-foreground">用户在活动时段内提交该表单即通过核验。</span>
                  </template>
                </div>
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
