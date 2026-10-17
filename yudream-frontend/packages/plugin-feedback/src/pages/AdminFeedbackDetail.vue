<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Feedback } from '../types'
import { YdAction, YdButton, YdIcon, YdPageHeader, YdPageMain, YdSelect, YdTextarea, useYdImagePreview, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createFeedbackAdminApi } from '../api/feedback-api'
import FeedbackSeverityTag from '../components/FeedbackSeverityTag.vue'
import FeedbackStatusTag from '../components/FeedbackStatusTag.vue'
import FeedbackTimeline from '../components/FeedbackTimeline.vue'
import FeedbackTypeTag from '../components/FeedbackTypeTag.vue'
import UserSelect from '../components/UserSelect.vue'
import { MAX_ASSIGNEE_COUNT, SEVERITY_FORM_OPTIONS, STATUS_FORM_OPTIONS, assigneeNames, errorMessage, personLabel, resolveImageUrl } from '../composables/utils'

/**
 * 反馈处理（管理端）：一条反馈的受理工作台。
 *
 * <p>左侧看内容与往来记录，右侧做受理动作（状态流转、处理人、严重程度）；
 * 官方回复与内部备注严格分开成两块，内部备注不会出现在用户端响应里。
 *
 * <p>每次动作只回写自身对应的草稿字段，避免改状态时把正在写的回复一起清空。
 * 所有受理动作都会让操作者自动加入处理人名单，因此动作完成后要重新同步处理人草稿，
 * 否则界面会停在一个已经被后端改过的旧值上。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const adminApi = createFeedbackAdminApi(props.sdk)
const route = useRoute()
const router = useRouter()
const toast = useYdToast()
const confirm = useYdModal()
const preview = useYdImagePreview()

const feedbackId = String(route.params.id ?? '')

const MAX_COMMENT_LENGTH = 2000

const loading = ref(true)
const loadError = ref('')
const detail = ref<Feedback | null>(null)

const reply = ref('')
const note = ref('')
const replying = ref(false)
const noting = ref(false)

const statusDraft = ref('')
const statusNote = ref('')
const statusSaving = ref(false)

const assigneeDraft = ref<string[]>([])
const assigneeSaving = ref(false)

const severityDraft = ref('')
const severitySaving = ref(false)

const images = computed(() => (detail.value?.attachments ?? []).map(item => resolveImageUrl(props.sdk, item)))

/**
 * 状态选项保留当前状态本身。
 *
 * <p>YdSelect 在选中值不在选项列表里时会回退展示第一个选项，若把当前状态过滤掉，
 * 下拉框会显示成「待处理」而实际是「已解决」；是否可提交交给 statusDirty 判断。
 */
const statusDirty = computed(() => Boolean(detail.value) && statusDraft.value !== detail.value!.status)
const severityDirty = computed(() => Boolean(detail.value) && severityDraft.value !== detail.value!.severity)
const assigneeDirty = computed(() => {
  if (!detail.value) {
    return false
  }
  const current = [...detail.value.assigneeIds].sort()
  const next = [...assigneeDraft.value].sort()
  return current.length !== next.length || current.some((item, index) => item !== next[index])
})

onMounted(() => {
  void load()
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await adminApi.detail(feedbackId)
    detail.value = data
    statusDraft.value = data.status
    statusNote.value = ''
    assigneeDraft.value = [...data.assigneeIds]
    severityDraft.value = data.severity
  }
  catch (cause: unknown) {
    detail.value = null
    loadError.value = errorMessage(cause, '反馈不存在或已被删除')
  }
  finally {
    loading.value = false
  }
}

/**
 * 受理动作统一收口：写入新详情后同步处理人草稿。
 *
 * <p>后端在这些动作里会顺带把操作者加进处理人，若不同步，右侧面板会一直显示旧名单，
 * 「保存处理人」按钮也会错误地处于可点击状态。
 */
function applyResult(next: Feedback) {
  detail.value = next
  assigneeDraft.value = [...next.assigneeIds]
}

/** 官方回复：公开评论，用户端可见。 */
async function submitReply() {
  if (replying.value || !detail.value) {
    return
  }
  const content = reply.value.trim()
  if (!content) {
    toast.warning('请填写回复内容')
    return
  }
  if (content.length > MAX_COMMENT_LENGTH) {
    toast.warning(`回复不能超过 ${MAX_COMMENT_LENGTH} 个字符`)
    return
  }
  replying.value = true
  try {
    applyResult(await adminApi.reply(detail.value.id, content))
    reply.value = ''
    toast.success('官方回复已发出，提交人可见')
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '回复失败'))
  }
  finally {
    replying.value = false
  }
}

/** 内部备注：仅管理员可见，用于记录排查过程与处理依据。 */
async function submitNote() {
  if (noting.value || !detail.value) {
    return
  }
  const content = note.value.trim()
  if (!content) {
    toast.warning('请填写备注内容')
    return
  }
  if (content.length > MAX_COMMENT_LENGTH) {
    toast.warning(`备注不能超过 ${MAX_COMMENT_LENGTH} 个字符`)
    return
  }
  noting.value = true
  try {
    applyResult(await adminApi.note(detail.value.id, content))
    note.value = ''
    toast.success('内部备注已记录')
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '备注失败'))
  }
  finally {
    noting.value = false
  }
}

async function saveStatus() {
  if (statusSaving.value || !detail.value) {
    return
  }
  if (!statusDirty.value) {
    toast.warning('请先选择要流转到的状态')
    return
  }
  statusSaving.value = true
  try {
    applyResult(await adminApi.changeStatus(detail.value.id, statusDraft.value, statusNote.value.trim()))
    statusNote.value = ''
    toast.success(`状态已流转为「${detail.value.statusLabel}」`)
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '状态流转失败'))
  }
  finally {
    statusSaving.value = false
  }
}

/** 整体替换处理人名单；清空即取消全部指派。 */
async function saveAssignee() {
  if (assigneeSaving.value || !detail.value) {
    return
  }
  if (!assigneeDirty.value) {
    toast.warning('处理人没有变化')
    return
  }
  assigneeSaving.value = true
  try {
    applyResult(await adminApi.assign(detail.value.id, [...assigneeDraft.value]))
    toast.success(detail.value.assignees.length
      ? `处理人已更新为 ${assigneeNames(detail.value.assignees)}`
      : '已清空处理人')
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '处理人更新失败'))
  }
  finally {
    assigneeSaving.value = false
  }
}

async function saveSeverity() {
  if (severitySaving.value || !detail.value) {
    return
  }
  if (!severityDirty.value) {
    toast.warning('严重程度没有变化')
    return
  }
  severitySaving.value = true
  try {
    applyResult(await adminApi.changeSeverity(detail.value.id, severityDraft.value))
    toast.success(`严重程度已调整为「${detail.value.severityLabel}」`)
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '调整失败'))
  }
  finally {
    severitySaving.value = false
  }
}

function confirmDelete() {
  if (!detail.value) {
    return
  }
  confirm.confirm({
    title: '删除反馈',
    content: `确认删除「${detail.value.title}」吗？全部回复与处理记录会一并删除且不可恢复；通常应改为流转状态而不是删除。`,
    onConfirm: async () => {
      try {
        await adminApi.remove(detail.value!.id)
        toast.success('反馈已删除')
        void router.push('/platform/plugins/feedback/admin/feedbacks')
      }
      catch (cause: unknown) {
        toast.error(errorMessage(cause, '删除失败'))
      }
    },
  })
}

function back() {
  void router.push('/platform/plugins/feedback/admin/feedbacks')
}
</script>

<template>
  <div class="fb-page">
    <YdPageHeader title="反馈处理" description="受理一条反馈：回复、备注、指派处理人并流转状态">
      <div class="fb-detail__actions">
        <YdButton variant="outline" @click="back">
          <YdIcon name="i-ri:arrow-left-line" />
          返回列表
        </YdButton>
        <YdButton v-if="detail" variant="destructive" @click="confirmDelete">
          <YdIcon name="i-ri:delete-bin-line" />
          删除
        </YdButton>
      </div>
    </YdPageHeader>

    <YdPageMain>
      <div v-if="loading" class="fb-state">
        正在加载反馈详情…
      </div>

      <div v-else-if="loadError" class="fb-state is-error">
        <YdIcon name="i-ri:error-warning-line" />
        <p>{{ loadError }}</p>
        <YdButton variant="outline" @click="back">
          返回反馈管理
        </YdButton>
      </div>

      <div v-else-if="detail" class="fb-console">
        <div class="fb-console__main">
          <section class="fb-card">
            <div class="fb-detail__tags">
              <FeedbackStatusTag :status="detail.status" :label="detail.statusLabel" />
              <FeedbackTypeTag :type="detail.type" :label="detail.typeLabel" />
              <FeedbackSeverityTag :severity="detail.severity" :label="detail.severityLabel" />
            </div>

            <h2 class="fb-detail__title">{{ detail.title }}</h2>

            <div class="fb-meta-row">
              <span>提交人：{{ personLabel(detail.authorName, detail.authorId) }}</span>
              <span>提交于 {{ detail.createdAtLabel }}</span>
              <span>最近更新 {{ detail.updatedAtLabel }}</span>
              <span>处理人：{{ detail.assignees.length ? assigneeNames(detail.assignees) : '尚未指派' }}</span>
              <span v-if="detail.closedAtLabel">
              {{ detail.status === 'archived' ? '归档于' : '结论于' }} {{ detail.closedAtLabel }}
            </span>
            </div>

            <p class="fb-body fb-detail__description">{{ detail.description }}</p>

            <div v-if="images.length" class="fb-detail__images">
              <span class="fb-section-title">截图</span>
              <div class="fb-detail__image-grid">
                <YdAction
                  v-for="(src, index) in images"
                  :key="src"
                  type="button"
                  class="fb-detail__thumb"
                  @click="preview.open(images, index)"
                >
                  <img :src="src" :alt="`反馈截图 ${index + 1}`">
                </YdAction>
              </div>
            </div>
          </section>

          <section class="fb-card">
            <div class="fb-detail__section-head">
              <span class="fb-section-title">处理记录</span>
              <span class="fb-muted">同一次操作的系统留痕与内容合并展示在同一气泡内</span>
            </div>
            <FeedbackTimeline :comments="detail.comments" :activities="detail.activities" />
          </section>

          <section class="fb-card">
            <div class="fb-detail__section-head">
              <span class="fb-section-title">官方回复</span>
              <span class="fb-badge is-public">提交人可见</span>
            </div>
            <YdTextarea
              v-model="reply"
              :maxlength="MAX_COMMENT_LENGTH"
              :auto-size="{ minRows: 4, maxRows: 12 }"
              class="w-full"
              placeholder="说明结论或下一步安排；这条内容会出现在提交人的详情页里。"
            />
            <div class="fb-card__foot">
              <span class="fb-muted">还可以输入 {{ MAX_COMMENT_LENGTH - reply.length }} 个字符</span>
              <YdButton :loading="replying" @click="submitReply">
                <YdIcon name="i-ri:send-plane-line" />
                发送回复
              </YdButton>
            </div>
          </section>

          <section class="fb-card">
            <div class="fb-detail__section-head">
              <span class="fb-section-title">内部备注</span>
              <span class="fb-badge is-internal">仅管理员可见</span>
            </div>
            <YdTextarea
              v-model="note"
              :maxlength="MAX_COMMENT_LENGTH"
              :auto-size="{ minRows: 3, maxRows: 10 }"
              class="w-full"
              placeholder="记录排查过程、临时结论或需要其他同事知悉的信息，不会发给提交人。"
            />
            <div class="fb-card__foot">
              <span class="fb-muted">还可以输入 {{ MAX_COMMENT_LENGTH - note.length }} 个字符</span>
              <YdButton variant="outline" :loading="noting" @click="submitNote">
                <YdIcon name="i-ri:lock-line" />
                记录备注
              </YdButton>
            </div>
          </section>
        </div>

        <aside class="fb-console__aside">
          <section class="fb-card">
            <span class="fb-section-title">状态流转</span>
            <YdSelect v-model="statusDraft" class="w-full" :options="STATUS_FORM_OPTIONS" />
            <YdTextarea
              v-model="statusNote"
              :maxlength="200"
              :auto-size="{ minRows: 2, maxRows: 5 }"
              class="w-full"
              placeholder="结论说明（可留空），会记入处理留痕"
            />
            <YdButton :loading="statusSaving" :disabled="!statusDirty" @click="saveStatus">
              <YdIcon name="i-ri:progress-2-line" />
              提交状态变更
            </YdButton>
            <span class="fb-muted">
              已解决 / 不予处理 表示给出结论，之后提交人可归档；7 天未归档的由系统自动归档。
              「已归档」表示处理结束，归档后不能再修改内容。
            </span>
          </section>

          <section class="fb-card">
            <span class="fb-section-title">处理人</span>
            <UserSelect
              v-model="assigneeDraft"
              :sdk="sdk"
              scope="manage"
              multiple
              :max="MAX_ASSIGNEE_COUNT"
              placeholder="尚未指派"
              title="指派处理人（可多选）"
            />
            <YdButton :loading="assigneeSaving" :disabled="!assigneeDirty" @click="saveAssignee">
              <YdIcon name="i-ri:user-received-line" />
              保存处理人
            </YdButton>
            <span class="fb-muted">
              支持 {{ MAX_ASSIGNEE_COUNT }} 位处理人；回复、备注、调整处理人或调整严重程度时，你也会自动加入名单，
              且处于「待处理」的反馈会同时进入「处理中」。
            </span>
          </section>

          <section class="fb-card">
            <span class="fb-section-title">严重程度</span>
            <YdSelect v-model="severityDraft" class="w-full" :options="SEVERITY_FORM_OPTIONS" />
            <YdButton :loading="severitySaving" :disabled="!severityDirty" @click="saveSeverity">
              <YdIcon name="i-ri:alarm-warning-line" />
              保存严重程度
            </YdButton>
            <span class="fb-muted">作为列表默认排序依据，可按实际影响修正提交人的自评。</span>
          </section>
        </aside>
      </div>
    </YdPageMain>
  </div>
</template>

<style scoped>
.fb-detail__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fb-sp-2);
}

/* 左侧看内容、右侧做受理动作；窄屏时收敛成单列，动作面板自然落到内容下方。 */
.fb-console {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: var(--fb-sp-4);
  align-items: start;
}

.fb-console__main,
.fb-console__aside {
  display: grid;
  gap: var(--fb-sp-4);
  min-width: 0;
}

.fb-console__aside {
  position: sticky;
  top: var(--fb-sp-4);
}

.fb-card {
  display: grid;
  gap: var(--fb-sp-3);
  padding: var(--fb-sp-5);
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius);
  background: var(--fb-surface);
  min-width: 0;
}

.fb-card__foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--fb-sp-3);
}

.fb-detail__tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fb-sp-3);
}

.fb-detail__title {
  margin: 0;
  color: var(--fb-text-1);
  font-size: 17px;
  font-weight: 600;
  line-height: 1.5;
  word-break: break-word;
}

.fb-detail__description {
  margin: 0;
  padding-top: var(--fb-sp-4);
  border-top: 1px solid var(--fb-border);
}

.fb-detail__images {
  display: grid;
  gap: var(--fb-sp-3);
}

.fb-detail__image-grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fb-sp-3);
}

.fb-detail__thumb {
  width: 140px;
  height: 110px;
  padding: 0;
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius-sm);
  background: var(--fb-surface-muted);
  overflow: hidden;
  cursor: zoom-in;
}

.fb-detail__thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.fb-detail__section-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--fb-sp-3);
}

.fb-badge {
  padding: 0 var(--fb-sp-2);
  border-radius: var(--fb-radius-pill);
  font-size: 11px;
  line-height: 18px;
}

.fb-badge.is-public {
  background: var(--color-primary-light-1, #e8f3ff);
  color: var(--color-primary-6, #165dff);
}

.fb-badge.is-internal {
  background: rgb(var(--warning-1, 255 247 232));
  color: rgb(var(--warning-6));
}

.fb-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--fb-sp-3);
  min-height: 200px;
  padding: var(--fb-sp-6);
  border: 1px dashed var(--fb-border);
  border-radius: var(--fb-radius);
  background: var(--fb-surface);
  color: var(--fb-text-3);
  font-size: 13px;
}

.fb-state.is-error {
  color: rgb(var(--danger-6));
}

.fb-state p {
  margin: 0;
}

@media (max-width: 1100px) {
  .fb-console {
    grid-template-columns: minmax(0, 1fr);
  }

  .fb-console__aside {
    position: static;
  }
}
</style>
