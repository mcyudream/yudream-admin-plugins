import type { Activity, ActivityBindingForm, ActivityDeptOption, ActivityFormOption, ActivitySaveForm } from '../types'
import type { ActivitySavePayload } from '../api/activity-proof-api'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { datetimeLocalToEpoch, errorMessage, toDatetimeLocalText } from './utils'

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
  const servers = ref<{ id: string, name: string }[]>([])

  const form = reactive<ActivitySaveForm>({
    id: '',
    title: '',
    summary: '',
    description: '',
    coverUrl: '',
    signupStartText: '',
    signupEndText: '',
    activityStartText: '',
    activityEndText: '',
    deptMode: 'ALL',
    allowedDeptIds: [],
    bindings: [],
  })

  const isEdit = computed(() => !!editingId.value)
  const readonly = computed(() => activityStatus.value === 'CLOSED')
  const coverPreview = computed(() => sdk.files.assetUrl(form.coverUrl || undefined))

  async function load(id: string) {
    loading.value = true
    editingId.value = id || ''
    try {
      const status = await api.admin.status()
      minecraftReady.value = status.dependencies.minecraftReady
      formReady.value = status.dependencies.formReady
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
    form.description = activity.description || ''
    form.coverUrl = activity.coverUrl || ''
    form.signupStartText = toDatetimeLocalText(activity.signupStart)
    form.signupEndText = toDatetimeLocalText(activity.signupEnd)
    form.activityStartText = toDatetimeLocalText(activity.activityStart)
    form.activityEndText = toDatetimeLocalText(activity.activityEnd)
    form.deptMode = activity.deptMode || 'ALL'
    form.allowedDeptIds = [...(activity.allowedDeptIds || [])]
    form.bindings = (activity.bindings || []).map(binding => ({
      type: binding.type,
      serverId: binding.serverId || '',
      minOnlineMinutes: binding.minOnlineMinutes || 0,
      includeAfk: binding.includeAfk,
      formCode: binding.formCode || '',
    }))
  }

  function resetForm() {
    activityStatus.value = ''
    form.id = ''
    form.title = ''
    form.summary = ''
    form.description = ''
    form.coverUrl = ''
    form.signupStartText = ''
    form.signupEndText = ''
    form.activityStartText = ''
    form.activityEndText = ''
    form.deptMode = 'ALL'
    form.allowedDeptIds = []
    form.bindings = []
  }

  async function uploadCover(file: File) {
    uploadingCover.value = true
    try {
      const uploaded = await sdk.files.uploadImage(file, { module: 'minecraft-activity-proof', publicAccess: true })
      const url = uploaded.assetUrl || uploaded.url || ''
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

  function addBinding(type: 'PLAYTIME' | 'FORM') {
    form.bindings.push({
      type,
      serverId: '',
      minOnlineMinutes: 0,
      includeAfk: false,
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
    return {
      id: form.id || undefined,
      title: form.title.trim(),
      summary: form.summary,
      description: form.description,
      coverUrl: form.coverUrl,
      signupStart: datetimeLocalToEpoch(form.signupStartText) || undefined,
      signupEnd: datetimeLocalToEpoch(form.signupEndText) || undefined,
      activityStart: datetimeLocalToEpoch(form.activityStartText) || undefined,
      activityEnd: datetimeLocalToEpoch(form.activityEndText) || undefined,
      deptMode: form.deptMode,
      allowedDeptIds: form.deptMode === 'DEPTS' ? [...form.allowedDeptIds] : [],
      bindings: form.bindings.map(binding => binding.type === 'PLAYTIME'
        ? { type: 'PLAYTIME', serverId: binding.serverId, minOnlineMinutes: binding.minOnlineMinutes, includeAfk: binding.includeAfk }
        : { type: 'FORM', formCode: binding.formCode }),
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
  }
}

export type { ActivityBindingForm }
