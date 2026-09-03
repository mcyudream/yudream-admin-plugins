<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import type { ActivityParticipantAdmin } from '../types'
import { FaButton, FaCard, FaDescriptions, FaIcon, FaInput, FaModal, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaSwitch, FaTag, YdTablePicker } from '@yudream/components'
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminActivityDetail } from '../composables/useAdminActivityDetail'
import { userPickerColumns } from '../composables/user-picker'
import { formatTime, formatTimeRange } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  route?: RouteLocationNormalizedLoaded
}>()

const router = useRouter()
const model = useAdminActivityDetail(props.sdk)
const { loading, acting, verifyingId, verifyingAll, activity, participants, pager, exportVisible, exporting, exportForm, addVisible, adding, addSelectedKeys, addForm } = model

const activityId = computed(() => String(props.route?.query?.id || ''))
watch(activityId, id => model.load(id), { immediate: true })

const coverUrl = computed(() => (activity.value?.coverUrl ? props.sdk.files.assetUrl(activity.value.coverUrl) : ''))

const statusText = computed(() => {
  const status = activity.value?.status
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'CLOSED') return '已结束'
  return '草稿'
})

const infoItems = computed(() => {
  const value = activity.value
  if (!value) {
    return []
  }
  return [
    { key: 'status', label: '活动状态', value: statusText.value },
    { key: 'signup', label: '报名时间', value: formatTimeRange(value.signupStart, value.signupEnd) },
    { key: 'activityTime', label: '活动时间', value: formatTimeRange(value.activityStart, value.activityEnd) },
    { key: 'dept', label: '参与范围', value: value.deptMode === 'DEPTS' ? (value.allowedDeptNames || []).join('、') || '指定部门' : '全体成员' },
    { key: 'bindings', label: '核验方式', value: value.bindings.length ? value.bindings.map(item => item.requirementText).join('；') : '参与活动即视为达标' },
    { key: 'participants', label: '参与 / 通过', value: `${value.participantCount} 人 / ${value.verifiedCount} 人` },
    { key: 'publishedAt', label: '发布时间', value: formatTime(value.publishedAt) },
    { key: 'createdAt', label: '创建时间', value: formatTime(value.createdAt) },
  ]
})

const participantColumns: TableColumn<ActivityParticipantAdmin>[] = [
  { id: 'student', header: '学生', width: 200, fixed: 'left' },
  { id: 'classCollege', header: '班级 / 学院', width: 220 },
  { id: 'player', header: '玩家', width: 180 },
  { id: 'status', header: '参与状态', width: 110, align: 'center' },
  { id: 'joinedAt', header: '参与时间', width: 180 },
  { id: 'verifyStatus', header: '核验状态', width: 110, align: 'center' },
  { id: 'verifyNote', header: '核验说明', width: 240 },
  { id: 'operation', header: '操作', width: 170, align: 'center', fixed: 'right' },
]

function statusTag(status: string) {
  return status === 'CANCELLED'
    ? { variant: 'secondary' as const, text: '已取消' }
    : { variant: 'default' as const, text: '已参与' }
}

function verifyTag(status: string) {
  if (status === 'PASSED') return { variant: 'default' as const, text: '已通过' }
  if (status === 'FAILED') return { variant: 'destructive' as const, text: '未通过' }
  return { variant: 'secondary' as const, text: '待核验' }
}

function back() {
  router.push({ path: '/platform/plugins/yudream-student-info/activity-proof/activities' })
}

function openEdit() {
  if (!activity.value) return
  router.push({ path: '/platform/plugins/yudream-student-info/activity-proof/activities/edit', query: { id: activity.value.id } })
}
</script>

<template>
  <section class="proof-page">
    <FaPageHeader :title="activity ? activity.title : '活动详情'" class="mb-0">
      <div class="flex flex-wrap gap-2">
        <FaButton variant="outline" @click="back">
          <FaIcon name="i-ri:arrow-left-line" />返回列表
        </FaButton>
        <FaButton v-if="activity && activity.status !== 'CLOSED'" variant="outline" @click="openEdit">
          <FaIcon name="i-ri:edit-2-line" />编辑
        </FaButton>
        <FaButton v-if="activity?.status === 'DRAFT'" :loading="acting" @click="model.publish">
          <FaIcon name="i-ri:send-plane-line" />发布
        </FaButton>
        <FaButton v-if="activity?.status === 'PUBLISHED'" variant="destructive" :loading="acting" @click="model.close">
          <FaIcon name="i-ri:stop-circle-line" />结束活动
        </FaButton>
        <FaButton variant="outline" :loading="verifyingAll" :disabled="!participants.length" @click="model.verifyAll">
          <FaIcon name="i-ri:checkbox-multiple-line" />批量核验
        </FaButton>
        <FaButton @click="model.openExport">
          <FaIcon name="i-ri:file-word-2-line" />导出证明
        </FaButton>
      </div>
    </FaPageHeader>
    <FaPageMain>
      <div v-loading="loading" class="grid gap-4">
        <template v-if="activity">
          <div class="grid gap-4 rounded-lg border p-4 md:grid-cols-[320px_minmax(0,1fr)]">
            <img v-if="coverUrl" :src="coverUrl" :alt="activity.title" class="activity-admin-cover" />
            <FaDescriptions :items="infoItems" :column="2" border />
          </div>

          <div class="grid gap-3">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <h3 class="text-base font-semibold">参与名单（{{ pager.total }} 人，已通过 {{ model.passedCount }} 人）</h3>
              <div class="flex flex-wrap gap-2">
                <FaButton size="sm" variant="outline" @click="model.openAddParticipant">
                  <FaIcon name="i-ri:user-add-line" />添加人员
                </FaButton>
                <FaButton size="sm" variant="outline" :loading="loading" @click="model.refresh">
                  <FaIcon name="i-ri:refresh-line" />刷新
                </FaButton>
              </div>
            </div>
            <FaResponsiveTable
              v-loading="loading"
              row-key="userId"
              table-root-class="max-w-full overflow-x-auto rounded-lg"
              table-class="min-w-[1440px]"
              border
              stripe
              column-visibility
              :columns="participantColumns"
              :data="participants"
            >
              <template #cell-student="{ row }">
                <strong>{{ row.original.studentName || row.original.username || '-' }}</strong>
                <div>{{ row.original.studentNo || '未绑定学号' }}</div>
              </template>
              <template #cell-classCollege="{ row }">
                <strong>{{ row.original.className || '-' }}</strong>
                <div>{{ row.original.college || '-' }}</div>
              </template>
              <template #cell-player="{ row }">
                <strong>{{ row.original.playerName || '-' }}</strong>
                <div>{{ row.original.playerId || '' }}</div>
              </template>
              <template #cell-status="{ row }">
                <FaTag :variant="statusTag(row.original.status).variant">{{ statusTag(row.original.status).text }}</FaTag>
              </template>
              <template #cell-joinedAt="{ row }">{{ formatTime(row.original.joinedAt) }}</template>
              <template #cell-verifyStatus="{ row }">
                <FaTag :variant="verifyTag(row.original.verifyStatus).variant">{{ verifyTag(row.original.verifyStatus).text }}</FaTag>
              </template>
              <template #cell-verifyNote="{ row }">{{ row.original.verifyNote || '-' }}</template>
              <template #cell-operation="{ row }">
                <div class="flex-center gap-2">
                  <FaButton
                    v-if="row.original.status === 'JOINED' && row.original.verifyStatus !== 'PASSED'"
                    size="sm"
                    variant="outline"
                    :loading="verifyingId === row.original.userId"
                    @click="model.verify(row.original)"
                  >
                    核验
                  </FaButton>
                  <FaButton size="sm" variant="destructive" @click="model.removeParticipant(row.original)">
                    移除
                  </FaButton>
                </div>
              </template>
              <template #card="{ row }">
                <FaCard class="w-full">
                  <div class="flex flex-col gap-3">
                    <div class="flex items-center justify-between gap-2">
                      <span class="min-w-0 break-words text-base font-semibold">{{ row.studentName || row.username || '-' }}</span>
                      <FaTag :variant="verifyTag(row.verifyStatus).variant">{{ verifyTag(row.verifyStatus).text }}</FaTag>
                    </div>
                    <div class="flex flex-col gap-1 text-sm">
                      <div class="flex gap-2">
                        <span class="shrink-0 text-secondary-foreground/60">学号</span>
                        <span class="break-all">{{ row.studentNo || '未绑定' }}</span>
                      </div>
                      <div class="flex gap-2">
                        <span class="shrink-0 text-secondary-foreground/60">班级 / 学院</span>
                        <span class="break-all">{{ row.className || '-' }} / {{ row.college || '-' }}</span>
                      </div>
                      <div class="flex gap-2">
                        <span class="shrink-0 text-secondary-foreground/60">玩家</span>
                        <span class="break-all">{{ row.playerName || '-' }}</span>
                      </div>
                      <div class="flex gap-2">
                        <span class="shrink-0 text-secondary-foreground/60">参与时间</span>
                        <span>{{ formatTime(row.joinedAt) }}</span>
                      </div>
                      <div v-if="row.verifyNote" class="flex gap-2">
                        <span class="shrink-0 text-secondary-foreground/60">核验说明</span>
                        <span class="break-all">{{ row.verifyNote }}</span>
                      </div>
                    </div>
                    <div class="flex flex-wrap gap-2 border-t pt-3">
                      <FaButton
                        v-if="row.status === 'JOINED' && row.verifyStatus !== 'PASSED'"
                        size="sm"
                        variant="outline"
                        :loading="verifyingId === row.userId"
                        @click="model.verify(row)"
                      >
                        核验
                      </FaButton>
                      <FaButton size="sm" variant="destructive" @click="model.removeParticipant(row)">
                        移除
                      </FaButton>
                    </div>
                  </div>
                </FaCard>
              </template>
            </FaResponsiveTable>
            <FaPagination
              v-model:page="pager.page"
              v-model:size="pager.size"
              :total="pager.total"
              class="mt-3"
              @page-change="model.loadParticipants"
              @size-change="model.loadParticipants"
            />
          </div>
        </template>
      </div>

      <FaModal
        v-model="exportVisible"
        title="导出活动证明"
        :description="activity ? `为「${activity.title}」的已通过参与者生成活动证明 Word。` : '生成活动证明 Word。'"
        confirm-button-text="生成并下载"
        :confirm-loading="exporting"
        show-cancel-button
        cancel-button-text="取消"
        @confirm="model.submitExport"
      >
        <div class="grid gap-3">
          <label class="grid gap-2">
            <span>证明编号</span>
            <FaInput v-model="exportForm.proofNo" placeholder="如：2026-证明-001" />
          </label>
          <label class="grid gap-2">
            <span>学院</span>
            <FaInput v-model="exportForm.college" placeholder="默认取全局配置" />
          </label>
          <label class="grid gap-2">
            <span>落款单位</span>
            <FaInput v-model="exportForm.issuer" placeholder="默认取全局配置" />
          </label>
          <label class="grid gap-2">
            <span>开具日期</span>
            <FaInput v-model="exportForm.issueDate" placeholder="如：2026年9月2日" />
          </label>
        </div>
      </FaModal>

      <FaModal
        v-model="addVisible"
        title="添加参与人员"
        description="手动将系统用户绑定到本活动的参与名单，用于补录或活动证明记录绑定。"
        confirm-button-text="添加"
        :confirm-loading="adding"
        show-cancel-button
        cancel-button-text="取消"
        @confirm="model.submitAddParticipant"
      >
        <div class="grid gap-3">
          <label class="grid gap-2">
            <span>选择用户</span>
            <YdTablePicker
              v-model="addSelectedKeys"
              :columns="userPickerColumns"
              :fetcher="model.fetchUserOptions"
              row-key="id"
              label-key="label"
              :multiple="false"
              title="选择参与人员"
              placeholder="点击搜索并选择用户"
              search-placeholder="输入用户名 / 昵称后回车"
            >
              <template #cell-deptNames="{ row }">
                {{ (row.original.deptNames || []).join('、') || '-' }}
              </template>
            </YdTablePicker>
          </label>
          <label class="flex items-center gap-2">
            <FaSwitch v-model="addForm.passed" />
            <span class="text-sm">直接标记为核验通过（不勾选则按活动核验方式立即核验）</span>
          </label>
          <label class="grid gap-2">
            <span>备注</span>
            <FaInput v-model="addForm.note" placeholder="默认：管理员手动添加" />
          </label>
        </div>
      </FaModal>
    </FaPageMain>
  </section>
</template>
