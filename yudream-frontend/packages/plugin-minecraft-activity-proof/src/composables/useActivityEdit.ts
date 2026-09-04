import type { Activity, ActivityBindingForm, ActivityDeptOption, ActivityFormOption, ActivityQuizCategoryOption, ActivitySaveForm, QuizSubjectiveMode, TimeValue } from '../types'
import type { ActivitySavePayload } from '../api/activity-proof-api'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { dateRangeToEpochs, errorMessage, normalizeFileUrl, normalizeMarkdownFileUrls, resolveFileUrl, resolveMarkdownFileUrls, toDateText } from './utils'

export function useActivityEdit(sdk: YuDreamPluginSdk) {
  const api = createActivityProofApi(sdk)
  const toast = useFaToast()
  const loading = ref(false)
  const saving = ref(false)
  const uploadingCover = ref(false)
  const editingId = ref('')
  const activityStatus = ref('')
  const deptOptions = ref<ActivityDeptOption[]>([])
  const formOptions = ref<ActivityFormOption[]>([])
  const minecraftReady = ref(false)
  const formReady = ref(false)
  const quizReady = ref(false)
  const servers = ref<{ id: string, name: string }[]>([])

  const form = reactive<ActivitySaveForm>({
    id: '',
    title: '',
    summary: '',
    description: '',
    coverUrl: '',
    signupRange: [],
    activityRange: [],
    deptMode: 'ALL',
    allowedDeptIds: [],
    bindings: [],
  })

  const isEdit = computed(() => !!editingId.value)
  const readonly = computed(() => activityStatus.value === 'CLOSED')
  const coverPreview = computed(() => resolveFileUrl(sdk, form.coverUrl))

  // 答题环节（题库软依赖）：独立文档存储，编辑模式下单独保存
  const quizAvailable = ref(false)
  const quizSaving = ref(false)
  const quizCategories = ref<ActivityQuizCategoryOption[]>([])
  const quizForm = reactive({
    enabled: false,
    categoryId: '',
    tagsText: '',
    types: [] as string[],
    difficulties: [] as number[],
    count: 10,
    passCorrect: 10,
    subjectiveMode: 'SELF' as QuizSubjectiveMode,
  })

  async function loadQuiz(id: string) {
    try {
      const [config, categories] = await Promise.all([
        api.admin.quizConfig(id),
        api.admin.quizCategoryOptions(),
      ])
      quizAvailable.value = config.quizAvailable
      quizCategories.value = categories
      quizForm.enabled = config.enabled
      quizForm.categoryId = config.categoryId || ''
      quizForm.tagsText = (config.tags || []).join('，')
      quizForm.types = [...(config.types || [])]
      quizForm.difficulties = [...(config.difficulties || [])]
      quizForm.count = config.count || 10
      quizForm.passCorrect = config.passCorrect || config.count || 10
      quizForm.subjectiveMode = config.subjectiveMode || 'SELF'
    }
    catch {
      quizAvailable.value = false
    }
  }

  function validateQuiz() {
    if (!quizForm.enabled) {
      return ''
    }
    if (!quizForm.count || quizForm.count < 1 || quizForm.count > 50) {
      return '抽题数量需在 1-50 之间'
    }
    if (!quizForm.passCorrect || quizForm.passCorrect < 1 || quizForm.passCorrect > quizForm.count) {
      return '达标题数需在 1 与抽题数量之间'
    }
    return ''
  }

  async function saveQuiz() {
    if (!editingId.value) {
      toast.warning('请先保存活动后再配置答题环节')
      return
    }
    const message = validateQuiz()
    if (message) {
      toast.warning(message)
      return
    }
    quizSaving.value = true
    try {
      const tags = quizForm.tagsText.split(/[,，]/).map(item => item.trim()).filter(Boolean)
      const config = await api.admin.saveQuizConfig(editingId.value, {
        enabled: quizForm.enabled,
        categoryId: quizForm.categoryId || undefined,
        tags,
        types: quizForm.types.length ? [...quizForm.types] : undefined,
        difficulties: quizForm.difficulties.length ? [...quizForm.difficulties] : undefined,
        count: quizForm.count,
        passCorrect: quizForm.passCorrect,
        subjectiveMode: quizForm.subjectiveMode,
      })
      quizAvailable.value = config.quizAvailable
      toast.success('答题环节配置已保存')
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      quizSaving.value = false
    }
  }

  async function load(id: string) {
    loading.value = true
    editingId.value = id || ''
    try {
      const status = await api.admin.status()
      minecraftReady.value = status.dependencies.minecraftReady
      formReady.value = status.dependencies.formReady
      quizReady.value = status.dependencies.quizReady
      const [depts, forms, serverList] = await Promise.all([
        api.admin.departments(),
        status.dependencies.formReady ? api.admin.forms() : Promise.resolve([]),
        status.dependencies.minecraftReady ? api.admin.servers() : Promise.resolve([]),
      ])
      deptOptions.value = depts
      formOptions.value = forms
      servers.value = serverList.map(item => ({ id: item.id, name: item.name }))
      if (id) {
        const activity = await api.admin.activity(id)
        applyActivity(activity)
        await loadQuiz(id)
      }
      else {
        resetForm()
      }
    }
    finally {
      loading.value = false
    }
  }

  function applyActivity(activity: Activity) {
    activityStatus.value = activity.status
    form.id = activity.id
    form.title = activity.title || ''
    form.summary = activity.summary || ''
    form.description = resolveMarkdownFileUrls(sdk, activity.description)
    form.coverUrl = normalizeFileUrl(activity.coverUrl)
    form.signupRange = toRange(activity.signupStart, activity.signupEnd)
    form.activityRange = toRange(activity.activityStart, activity.activityEnd)
    form.deptMode = activity.deptMode || 'ALL'
    form.allowedDeptIds = [...(activity.allowedDeptIds || [])]
    form.bindings = (activity.bindings || []).map(binding => ({
      type: binding.type,
      serverId: binding.serverId || '',
      minOnlineMinutes: binding.minOnlineMinutes || 0,
      includeAfk: binding.includeAfk,
      autoJoin: binding.autoJoin,
      formCode: binding.formCode || '',
    }))
  }

  // 范围选择器要求两端都有值：历史数据只填了一端时，用已有的一端补齐展示
  function toRange(start: TimeValue, end: TimeValue) {
    const startText = toDateText(start)
    const endText = toDateText(end)
    if (startText && endText) {
      return [startText, endText]
    }
    if (startText) {
      return [startText, startText]
    }
    if (endText) {
      return [endText, endText]
    }
    return []
  }

  function resetForm() {
    activityStatus.value = ''
    form.id = ''
    form.title = ''
    form.summary = ''
    form.description = ''
    form.coverUrl = ''
    form.signupRange = []
    form.activityRange = []
    form.deptMode = 'ALL'
    form.allowedDeptIds = []
    form.bindings = []
  }

  async function uploadCover(file: File) {
    uploadingCover.value = true
    try {
      const uploaded = await sdk.files.uploadImage(file, { module: 'minecraft-activity-proof', publicAccess: true })
      const url = normalizeFileUrl(uploaded.assetUrl || uploaded.url)
      toast.success('封面已上传')
      return url
    }
    catch (error) {
      toast.warning(errorMessage(error, '封面上传失败'))
      throw error
    }
    finally {
      uploadingCover.value = false
    }
  }

  function addBinding(type: 'PLAYTIME' | 'FORM' | 'QUIZ') {
    if (type === 'QUIZ' && form.bindings.some(binding => binding.type === 'QUIZ')) {
      toast.warning('答题核验方式至多添加一个')
      return
    }
    form.bindings.push({
      type,
      serverId: '',
      minOnlineMinutes: 0,
      includeAfk: false,
      autoJoin: false,
      formCode: '',
    })
  }

  function removeBinding(index: number) {
    form.bindings.splice(index, 1)
  }

  function validate() {
    if (!form.title.trim()) {
      return '请填写活动标题'
    }
    if (form.deptMode === 'DEPTS' && !form.allowedDeptIds.length) {
      return '请选择允许参与的部门'
    }
    for (const binding of form.bindings) {
      if (binding.type === 'PLAYTIME' && !binding.serverId) {
        return '时长检测绑定需要选择服务器'
      }
      if (binding.type === 'FORM' && !binding.formCode) {
        return '表单绑定需要选择表单'
      }
    }
    return ''
  }

  function toPayload(): ActivitySavePayload {
    const signup = dateRangeToEpochs(form.signupRange)
    const activity = dateRangeToEpochs(form.activityRange)
    return {
      id: form.id || undefined,
      title: form.title.trim(),
      summary: form.summary,
      description: normalizeMarkdownFileUrls(form.description),
      coverUrl: normalizeFileUrl(form.coverUrl),
      signupStart: signup.start || undefined,
      signupEnd: signup.end || undefined,
      activityStart: activity.start || undefined,
      activityEnd: activity.end || undefined,
      deptMode: form.deptMode,
      allowedDeptIds: form.deptMode === 'DEPTS' ? [...form.allowedDeptIds] : [],
      bindings: form.bindings.map((binding) => {
        if (binding.type === 'PLAYTIME') {
          return { type: 'PLAYTIME', serverId: binding.serverId, minOnlineMinutes: binding.minOnlineMinutes, includeAfk: binding.includeAfk, autoJoin: binding.autoJoin }
        }
        if (binding.type === 'QUIZ') {
          return { type: 'QUIZ' }
        }
        return { type: 'FORM', formCode: binding.formCode }
      }),
    }
  }

  async function save() {
    if (readonly.value) {
      toast.warning('已结束的活动不可编辑')
      return
    }
    const message = validate()
    if (message) {
      toast.warning(message)
      return
    }
    saving.value = true
    try {
      const saved = editingId.value
        ? await api.admin.updateActivity(editingId.value, toPayload())
        : await api.admin.createActivity(toPayload())
      editingId.value = saved.id
      applyActivity(saved)
      toast.success('活动已保存')
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      saving.value = false
    }
  }

  return {
    loading,
    saving,
    uploadingCover,
    editingId,
    activityStatus,
    deptOptions,
    formOptions,
    minecraftReady,
    formReady,
    quizReady,
    servers,
    form,
    isEdit,
    readonly,
    coverPreview,
    load,
    uploadCover,
    addBinding,
    removeBinding,
    save,
    quizAvailable,
    quizSaving,
    quizCategories,
    quizForm,
    saveQuiz,
  }
}

export type { ActivityBindingForm }
