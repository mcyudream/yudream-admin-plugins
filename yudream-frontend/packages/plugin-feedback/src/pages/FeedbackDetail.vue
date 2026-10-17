<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { Feedback } from '../types'
import { YdAction, YdButton, YdIcon, YdPageHeader, YdPageMain, YdTextarea, useYdImagePreview, useYdModal, useYdToast } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createFeedbackUserApi } from '../api/feedback-api'
import FeedbackSeverityTag from '../components/FeedbackSeverityTag.vue'
import FeedbackStatusTag from '../components/FeedbackStatusTag.vue'
import FeedbackTimeline from '../components/FeedbackTimeline.vue'
import FeedbackTypeTag from '../components/FeedbackTypeTag.vue'
import { assigneeNames, errorMessage, personLabel, resolveImageUrl } from '../composables/utils'

/**
 * 反馈详情（用户端）：内容、截图、处理时间线与后续动作集中在一页。
 *
 * <p>只有提交人能进入本页，归属由后端按「反馈 ID + 登录者」定位；能做什么由后端给出的
 * {@code canEdit} / {@code canArchive} 决定，前端不再自行推测权限。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()

const api = createFeedbackUserApi(props.sdk)
const route = useRoute()
const router = useRouter()
const toast = useYdToast()
const confirm = useYdModal()
const preview = useYdImagePreview()

const feedbackId = String(route.params.id ?? '')

const loading = ref(true)
const loadError = ref('')
const detail = ref<Feedback | null>(null)
const comment = ref('')
const submitting = ref(false)
const acting = ref(false)

const MAX_COMMENT_LENGTH = 2000

const images = computed(() => (detail.value?.attachments ?? []).map(item => resolveImageUrl(props.sdk, item)))

const commentRemain = computed(() => MAX_COMMENT_LENGTH - comment.value.length)

onMounted(() => {
  void load()
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    detail.value = await api.detail(feedbackId)
  }
  catch (cause: unknown) {
    detail.value = null
    loadError.value = errorMessage(cause, '反馈不存在或已被撤回')
  }
  finally {
    loading.value = false
  }
}

async function submitComment() {
  if (submitting.value || !detail.value) {
    return
  }
  const content = comment.value.trim()
  if (!content) {
    toast.warning('请填写补充说明的内容')
    return
  }
  if (content.length > MAX_COMMENT_LENGTH) {
    toast.warning(`补充说明不能超过 ${MAX_COMMENT_LENGTH} 个字符`)
    return
  }
  submitting.value = true
  try {
    detail.value = await api.comment(detail.value.id, content)
    comment.value = ''
    toast.success('补充说明已提交')
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '提交失败，请稍后重试'))
  }
  finally {
    submitting.value = false
  }
}

/**
 * 归档：终态操作，先二次确认再提交。
 *
 * <p>归档后不能再修改内容（仍可追加说明），因此必须在弹窗里把这条后果写清楚，
 * 而不是让用户点一下就把反馈锁死。
 */
function confirmArchive() {
  if (!detail.value) {
    return
  }
  confirm.confirm({
    title: '归档反馈',
    content: `确认归档「${detail.value.title}」吗？归档后不能再修改内容，但仍可追加说明。`,
    onConfirm: async () => {
      if (!detail.value) {
        return
      }
      acting.value = true
      try {
        detail.value = await api.archive(detail.value.id)
        toast.success('反馈已归档')
      }
      catch (cause: unknown) {
        toast.error(errorMessage(cause, '归档失败'))
      }
      finally {
        acting.value = false
      }
    },
  })
}

function confirmWithdraw() {
  if (!detail.value) {
    return
  }
  confirm.confirm({
    title: '撤回反馈',
    content: `确认撤回「${detail.value.title}」吗？撤回后记录将被删除，且不可恢复；已受理出结论的反馈无法撤回。`,
    onConfirm: async () => {
      try {
        await api.withdraw(detail.value!.id)
        toast.success('反馈已撤回')
        void router.push('/platform/plugins/feedback/me/feedbacks')
      }
      catch (cause: unknown) {
        toast.error(errorMessage(cause, '撤回失败'))
      }
    },
  })
}

function goEdit() {
  if (detail.value) {
    void router.push(`/platform/plugins/feedback/me/feedbacks/${detail.value.id}/edit`)
  }
}

function back() {
  void router.push('/platform/plugins/feedback/me/feedbacks')
}
</script>

<template>
  <div class="fb-page">
    <YdPageHeader title="反馈详情" description="查看处理进度与往来记录，可补充说明；管理员给出结论后可归档">
      <div class="fb-detail__actions">
        <YdButton variant="outline" @click="back">
          <YdIcon name="i-ri:arrow-left-line" />
          返回列表
        </YdButton>
        <template v-if="detail">
          <YdButton v-if="detail.canEdit" variant="outline" @click="goEdit">
            <YdIcon name="i-ri:edit-line" />
            修改内容
          </YdButton>
          <YdButton v-if="detail.canArchive" :disabled="acting" @click="confirmArchive">
            <YdIcon name="i-ri:archive-line" />
            归档
          </YdButton>
          <YdButton v-if="detail.open" variant="destructive" @click="confirmWithdraw">
            <YdIcon name="i-ri:delete-bin-line" />
            撤回
          </YdButton>
        </template>
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
          返回我的反馈
        </YdButton>
      </div>

      <div v-else-if="detail" class="fb-detail">
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
            <span v-if="detail.autoArchiveAtLabel" class="fb-muted">
              未归档将于 {{ detail.autoArchiveAtLabel }} 自动归档
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
            <span class="fb-muted">官方回复、你的补充说明与状态变更按时间合并展示，同一次操作合并在一个气泡内</span>
          </div>
          <FeedbackTimeline :comments="detail.comments" :activities="detail.activities" />
        </section>

        <section class="fb-card">
          <div class="fb-detail__section-head">
            <span class="fb-section-title">追加说明</span>
            <span class="fb-muted">{{ detail.open ? '补充复现步骤或新的信息' : '该反馈已完结，仍可补充归档说明' }}</span>
          </div>
          <YdTextarea
            v-model="comment"
            :maxlength="MAX_COMMENT_LENGTH"
            :auto-size="{ minRows: 4, maxRows: 12 }"
            class="w-full"
            placeholder="补充说明会展示给处理人；例如换台设备、换个浏览器是否仍能复现。"
          />
          <div class="fb-detail__comment-foot">
            <span class="fb-muted">还可以输入 {{ commentRemain }} 个字符</span>
            <YdButton :loading="submitting" @click="submitComment">
              <YdIcon name="i-ri:send-plane-line" />
              提交补充
            </YdButton>
          </div>
        </section>
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

.fb-detail {
  display: grid;
  gap: var(--fb-sp-4);
}

.fb-card {
  display: grid;
  gap: var(--fb-sp-4);
  padding: var(--fb-sp-5);
  border: 1px solid var(--fb-border);
  border-radius: var(--fb-radius);
  background: var(--fb-surface);
  min-width: 0;
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
  align-items: baseline;
  gap: var(--fb-sp-3);
}

.fb-detail__comment-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--fb-sp-3);
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
</style>
