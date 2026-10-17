<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TodoDeptOption, TodoPriority, TodoSubTaskInput, TodoTask, TodoTaskPayload } from '../types'
import { YdButton, YdInput, YdModal, YdSelect, YdTextarea, YdDatePicker, useYdToast } from '@yudream/components'
import { computed, ref, watch } from 'vue'
import { VISIBILITY_OPTIONS } from '../types'
import { createTodoAdminApi, createTodoUserApi } from '../api/todo-api'
import SubTaskEditor from '../components/SubTaskEditor.vue'
import UserPicker from '../components/UserPicker.vue'
import { useUserDirectory } from '../composables/useUserDirectory'
import { PRIORITY_OPTIONS, errorMessage, parseDateTimeInput, toDateTimeInput } from '../composables/utils'

const props = defineProps<{
  sdk: YuDreamPluginSdk
  task: TodoTask | null
  surface: 'user' | 'admin'
}>()

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (event: 'saved'): void
}>()

const toast = useYdToast()
const userApi = createTodoUserApi(props.sdk)
const adminApi = createTodoAdminApi(props.sdk)
const directory = useUserDirectory(props.sdk, props.surface === 'admin' ? 'admin' : 'user')

const submitting = ref(false)
const dueInput = ref('')
const titleError = ref('')
const form = ref({
  title: '',
  remark: '',
  priority: 'normal' as TodoPriority,
  subTasks: [] as TodoSubTaskInput[],
  participantIds: [] as string[],
  visibility: 'PRIVATE',
  deptIds: [] as string[],
})

/** 可见范围部门选项：用户端只列自己加入的部门，管理端列全量部门树拍平选项。 */
const deptOptions = ref<TodoDeptOption[]>([])
const deptSelectOptions = computed(() => deptOptions.value.map(dept => ({ label: dept.label, value: dept.id })))

const visibilityHint = computed(() => {
  if (form.value.visibility === 'PUBLIC') {
    return '全站成员可在「从已有任务添加」中看到并加入这条待办'
  }
  if (form.value.visibility === 'DEPT') {
    return deptOptions.value.length > 0
      ? '仅所选部门的成员可在「从已有任务添加」中看到并加入这条待办'
      : '暂无可选部门，请先加入部门或联系管理员'
  }
  return '仅您与任务参与人可见'
})

async function loadDepartments() {
  try {
    const res = props.surface === 'admin'
      ? await adminApi.departments()
      : await userApi.departments()
    deptOptions.value = res.records
  }
  catch {
    deptOptions.value = []
  }
}

const editing = computed(() => props.task !== null)

/** 参与人只在新建时可选；编辑页（含管理端）一律只读展示，不允许改选。 */
const participantsEditable = computed(() => props.task === null)

const participantLabels = computed(() => directory.labels(form.value.participantIds))

function reset(task: TodoTask | null) {
  form.value = {
    title: task?.title ?? '',
    remark: task?.remark ?? '',
    priority: task?.priority ?? 'normal',
    subTasks: (task?.subTasks ?? []).map(item => ({
      title: item.title,
      assigneeIds: [...(item.assigneeIds ?? [])],
      priority: item.priority ?? '',
      dueAt: item.dueAt ?? null,
    })),
    participantIds: [...(task?.participantIds ?? [])],
    visibility: task?.visibility ?? 'PRIVATE',
    deptIds: [...(task?.deptIds ?? [])],
  }
  dueInput.value = toDateTimeInput(task?.dueAt ?? null)
  void loadDepartments()
  titleError.value = ''
  if (form.value.participantIds.length > 0) {
    void directory.ensure(form.value.participantIds)
  }
}

watch(
  () => [open.value, props.task] as const,
  ([isOpen, task]) => {
    if (isOpen) {
      reset(task)
    }
  },
  { immediate: true },
)

async function submit() {
  if (submitting.value) {
    return
  }
  form.value.title = form.value.title.trim()
  if (!form.value.title) {
    titleError.value = '请填写待办标题'
    return
  }
  titleError.value = ''
  // 小任务负责人恢复单独指派：按行提交负责人；未指派的小任务由后端对全体参与人生效。
  const participantIds = participantsEditable.value
    ? form.value.participantIds.filter(Boolean)
    : [...(props.task?.participantIds ?? [])]
  const payload: TodoTaskPayload = {
    title: form.value.title,
    remark: form.value.remark.trim(),
    priority: form.value.priority,
    dueAt: parseDateTimeInput(dueInput.value),
    subTasks: form.value.subTasks
      .map(item => ({
        title: item.title.trim(),
        assigneeIds: item.assigneeIds ?? [],
        priority: item.priority || undefined,
        dueAt: item.dueAt ?? null,
      }))
      .filter(item => item.title),
    participantIds,
    visibility: form.value.visibility,
    deptIds: form.value.visibility === 'DEPT' ? form.value.deptIds.filter(Boolean) : [],
  }
  submitting.value = true
  try {
    if (props.surface === 'admin' && props.task) {
      await adminApi.update(props.task.id, payload)
    }
    else if (props.task) {
      await userApi.update(props.task.id, payload)
    }
    else {
      await userApi.create(payload)
    }
    toast.success(editing.value ? '待办已更新' : '待办已创建')
    open.value = false
    emit('saved')
  }
  catch (cause: unknown) {
    toast.error(errorMessage(cause, '保存失败，请稍后重试'))
  }
  finally {
    submitting.value = false
  }
}

function cancel() {
  open.value = false
}
</script>

<template>
  <YdModal v-model="open" :title="editing ? '编辑待办' : '新建待办'" width="560px" :footer="false">
    <div class="todo-plugin">
      <div class="todo-plugin__form">
        <div class="todo-plugin__field">
          <span>标题</span>
          <YdInput
            v-model="form.title"
            placeholder="例如：完成季度汇报初稿"
            :maxlength="120"
            @keydown.enter="submit"
          />
          <small v-if="titleError" class="todo-plugin__field-error">{{ titleError }}</small>
        </div>

        <div class="todo-plugin__field">
          <span>备注</span>
          <YdTextarea
            v-model="form.remark"
            placeholder="可选：补充执行要点、验收标准或关联链接"
            :maxlength="2000"
          />
        </div>

        <div class="todo-plugin__row">
          <div class="todo-plugin__field">
            <span>优先级</span>
            <YdSelect v-model="form.priority" :options="PRIORITY_OPTIONS" />
          </div>

          <div class="todo-plugin__field">
            <span>截止时间</span>
            <YdDatePicker
              v-model="dueInput"
              show-time
              format="YYYY-MM-DD HH:mm"
              value-format="YYYY-MM-DD HH:mm"
              placeholder="留空表示不限时"
            />
            <small class="todo-plugin__field-hint">超时未完成会标记为逾期</small>
          </div>
        </div>

        <div class="todo-plugin__field">
          <span>任务参与人</span>
          <UserPicker
            v-if="participantsEditable"
            v-model="form.participantIds"
            :sdk="sdk"
            :surface="surface"
            :disabled="submitting"
            title="选择任务参与人"
            placeholder="选择任务参与人"
            hint="参与人可在自己的清单中看到并推进这条待办，但不能编辑或删除。"
          />
          <div v-else-if="form.participantIds.length > 0" class="todo-plugin__chips">
            <span v-for="(label, index) in participantLabels" :key="index" class="todo-plugin__chip">
              {{ label }}
            </span>
          </div>
          <small v-else class="todo-plugin__field-hint">暂无参与人</small>
        </div>

        <div class="todo-plugin__field">
          <span>可见范围</span>
          <YdSelect v-model="form.visibility" :options="VISIBILITY_OPTIONS" />
          <YdSelect
            v-if="form.visibility === 'DEPT'"
            v-model="form.deptIds"
            :options="deptSelectOptions"
            multiple
            placeholder="选择可见部门（可多选）"
          />
          <small class="todo-plugin__field-hint">{{ visibilityHint }}</small>
        </div>

        <div class="todo-plugin__field">
          <span>二级小任务</span>
          <SubTaskEditor v-model="form.subTasks" :sdk="sdk" :surface="surface" :disabled="submitting" />
        </div>

        <div class="todo-plugin__row-actions" style="justify-content: flex-end; margin-top: 4px">
          <YdButton variant="outline" :disabled="submitting" @click="cancel">
            取消
          </YdButton>
          <YdButton :loading="submitting" @click="submit">
            {{ editing ? '保存修改' : '创建待办' }}
          </YdButton>
        </div>
      </div>
    </div>
  </YdModal>
</template>
