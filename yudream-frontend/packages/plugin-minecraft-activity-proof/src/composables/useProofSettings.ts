import type { ActivityProofSettings, ActivityProofStatus, ActivityProofTemplate, ActivityQqConnection, ActivityQqGroup, ActivityUserOption, ActivityUserPickerRow } from '../types'
import type { YdTablePickerQuery, YdTablePickerResult } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useFaModal, useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { toUserPickerResult } from './user-picker'
import { errorMessage } from './utils'

export function useProofSettings(sdk: YuDreamPluginSdk) {
  const api = createActivityProofApi(sdk)
  const toast = useFaToast()
  const modal = useFaModal()
  const loading = ref(false)
  const saving = ref(false)
  const status = ref<ActivityProofStatus | null>(null)
  const templates = ref<ActivityProofTemplate[]>([])

  const qqConnections = ref<ActivityQqConnection[]>([])
  const qqGroups = ref<ActivityQqGroup[]>([])
  const loadingQqGroups = ref(false)

  const templateMembers = ref<ActivityUserOption[]>([])
  const membersLoading = ref(false)
  const savingMembers = ref(false)

  const settingsForm = reactive({
    templateId: '',
    defaultActivityName: '',
    defaultCollege: '',
    defaultIssuer: '',
    qqNotifyEnabled: false,
    qqConnectionId: '',
    qqGroupIds: [] as string[],
    qqMessageTemplate: '',
  })

  const settings = computed(() => status.value?.settings || null)
  const selectedTemplate = computed(() => templates.value.find(item => item.id === settingsForm.templateId) || null)
  const memberIds = computed(() => templateMembers.value.map(item => item.id))
  const memberLabels = computed(() => Object.fromEntries(
    templateMembers.value.map(item => [item.id, item.nickname || item.username || item.id]),
  ))

  async function load() {
    loading.value = true
    try {
      const nextStatus = await api.admin.status()
      status.value = nextStatus
      syncSettingsForm(nextStatus.settings)
      const nextTemplates = nextStatus.dependencies.wordTemplateReady ? await api.admin.templates() : []
      templates.value = nextTemplates
      qqConnections.value = await api.admin.qqConnections().catch(() => [])
      await Promise.all([loadQqGroups(false), loadTemplateMembers()])
    }
    finally {
      loading.value = false
    }
  }

  async function reloadTemplates() {
    if (!status.value?.dependencies.wordTemplateReady) {
      templates.value = []
      return
    }
    templates.value = await api.admin.templates()
    toast.success('模板列表已刷新')
  }

  async function selectTemplate() {
    const templateId = settingsForm.templateId.trim()
    if (!templateId) {
      return
    }
    saving.value = true
    try {
      applySettings(await api.admin.selectTemplate(templateId))
      toast.success('模板已选择')
      await loadTemplateMembers()
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      saving.value = false
    }
  }

  async function changeTemplate() {
    if (!settingsForm.templateId.trim()) {
      templateMembers.value = []
      return
    }
    await selectTemplate()
  }

  async function save() {
    if (settingsForm.qqNotifyEnabled && (!settingsForm.qqConnectionId || !settingsForm.qqGroupIds.length)) {
      toast.warning('开启 QQ 群通知需要选择消息连接与至少一个群')
      return
    }
    saving.value = true
    try {
      applySettings(await api.admin.saveSettings({
        defaultActivityName: settingsForm.defaultActivityName,
        defaultCollege: settingsForm.defaultCollege,
        defaultIssuer: settingsForm.defaultIssuer,
        templateId: settingsForm.templateId.trim() || null,
        qqNotifyEnabled: settingsForm.qqNotifyEnabled,
        qqConnectionId: settingsForm.qqConnectionId,
        qqGroupIds: settingsForm.qqGroupIds,
        qqMessageTemplate: settingsForm.qqMessageTemplate,
      }))
      toast.success('配置已保存')
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      saving.value = false
    }
  }

  async function loadQqGroups(resetSelection = false) {
    if (!settingsForm.qqConnectionId) {
      qqGroups.value = []
      return
    }
    loadingQqGroups.value = true
    try {
      qqGroups.value = await api.admin.qqGroups(settingsForm.qqConnectionId)
      if (resetSelection) {
        const valid = new Set(qqGroups.value.map(item => item.id))
        settingsForm.qqGroupIds = settingsForm.qqGroupIds.filter(id => valid.has(id))
      }
    }
    catch (error) {
      qqGroups.value = []
      toast.warning(errorMessage(error))
    }
    finally {
      loadingQqGroups.value = false
    }
  }

  function changeQqConnection() {
    loadQqGroups(true)
  }

  async function loadTemplateMembers() {
    const templateId = settingsForm.templateId.trim()
    if (!templateId) {
      templateMembers.value = []
      return
    }
    membersLoading.value = true
    try {
      templateMembers.value = (await api.admin.templateMembers(templateId)).members
    }
    catch (error) {
      templateMembers.value = []
      toast.warning(errorMessage(error))
    }
    finally {
      membersLoading.value = false
    }
  }

  async function fetchUserOptions(query: YdTablePickerQuery): Promise<YdTablePickerResult<ActivityUserPickerRow>> {
    try {
      return toUserPickerResult(await api.admin.userOptions(query.keyword, query.page, query.size))
    }
    catch (error) {
      toast.warning(errorMessage(error))
      return { list: [], total: 0 }
    }
  }

  function applyMemberSelection(keys: string[]) {
    if (savingMembers.value) {
      return
    }
    const current = memberIds.value
    const removed = current.filter(id => !keys.includes(id))
    const added = keys.filter(id => !current.includes(id))
    if (!added.length && !removed.length) {
      return
    }
    if (removed.length && !added.length) {
      const names = removed.map(id => memberLabels.value[id] || id).join('、')
      modal.confirm({
        title: '移除模板默认人员',
        content: `确认将「${names}」从模板默认人员中移除吗？后续导出活动证明将不再自动带上。`,
        onConfirm: async () => {
          await saveMembers(keys, '已移除模板默认人员')
        },
      })
      return
    }
    saveMembers(keys, '模板默认人员已保存')
  }

  async function saveMembers(userIds: string[], successText: string) {
    const templateId = settingsForm.templateId.trim()
    if (!templateId || savingMembers.value) {
      return
    }
    savingMembers.value = true
    try {
      const result = await api.admin.saveTemplateMembers({ templateId, userIds })
      templateMembers.value = result.members
      toast.success(successText)
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      savingMembers.value = false
    }
  }

  function applySettings(next: ActivityProofSettings) {
    if (status.value) {
      status.value.settings = next
    }
    syncSettingsForm(next)
  }

  function syncSettingsForm(next: ActivityProofSettings) {
    settingsForm.templateId = next.templateId || ''
    settingsForm.defaultActivityName = next.defaultActivityName || ''
    settingsForm.defaultCollege = next.defaultCollege || ''
    settingsForm.defaultIssuer = next.defaultIssuer || ''
    settingsForm.qqNotifyEnabled = Boolean(next.qqNotifyEnabled)
    settingsForm.qqConnectionId = next.qqConnectionId || ''
    settingsForm.qqGroupIds = [...(next.qqGroupIds || [])]
    settingsForm.qqMessageTemplate = next.qqMessageTemplate || ''
  }

  return {
    loading,
    saving,
    status,
    settings,
    templates,
    settingsForm,
    selectedTemplate,
    qqConnections,
    qqGroups,
    loadingQqGroups,
    templateMembers,
    membersLoading,
    savingMembers,
    memberIds,
    memberLabels,
    load,
    reloadTemplates,
    selectTemplate,
    changeTemplate,
    save,
    loadQqGroups,
    changeQqConnection,
    loadTemplateMembers,
    fetchUserOptions,
    applyMemberSelection,
  }
}
