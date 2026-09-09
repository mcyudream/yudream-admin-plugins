import type { Activity, ActivityParticipantAdmin, ActivityProofExportRecord, ActivityUserPickerRow } from '../types'
import type { YdTablePickerQuery, YdTablePickerResult } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useFaModal, useFaToast } from '@yudream/components'
import { computed, reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { toUserPickerResult } from './user-picker'
import { errorMessage, saveBlobResponse, todayText } from './utils'

export function useAdminActivityDetail(sdk: YuDreamPluginSdk) {
  const api = createActivityProofApi(sdk)
  const toast = useFaToast()
  const modal = useFaModal()
  const loading = ref(false)
  const acting = ref(false)
  const verifyingId = ref('')
  const verifyingAll = ref(false)
  const syncing = ref(false)
  const activity = ref<Activity | null>(null)
  const participants = ref<ActivityParticipantAdmin[]>([])
  const pager = reactive({ page: 1, size: 10, total: 0 })

  const exportVisible = ref(false)
  const exporting = ref(false)
  const exportForm = reactive({
    proofNo: '',
    college: '',
    issuer: '',
    issueDate: todayText(),
  })

  const addVisible = ref(false)
  const adding = ref(false)
  const addSelectedKeys = ref<string[]>([])
  const addForm = reactive({
    passed: true,
    note: '',
  })

  const passedCount = computed(() => participants.value.filter(item => item.verifyStatus === 'PASSED').length)
  const hasAutoJoinBinding = computed(() => (activity.value?.bindings || []).some(item => item.type === 'PLAYTIME' && item.autoJoin))

  async function load(id: string) {
    if (!id) {
      activity.value = null
      participants.value = []
      return
    }
    loading.value = true
    try {
      const [nextActivity, nextSettings] = await Promise.all([
        api.admin.activity(id),
        api.admin.settings().catch(() => null),
      ])
      activity.value = nextActivity
      if (nextSettings) {
        exportForm.college = exportForm.college || nextSettings.defaultCollege || ''
        exportForm.issuer = exportForm.issuer || nextSettings.defaultIssuer || ''
      }
      await loadParticipants()
    }
    finally {
      loading.value = false
    }
  }

  async function loadParticipants() {
    if (!activity.value) {
      return
    }
    const result = await api.admin.participants(activity.value.id, pager.page, pager.size)
    participants.value = result.records
    pager.total = Number(result.total) || 0
  }

  async function refresh() {
    if (!activity.value) {
      return
    }
    loading.value = true
    try {
      activity.value = await api.admin.activity(activity.value.id)
      await loadParticipants()
    }
    finally {
      loading.value = false
    }
  }

  async function publish() {
    if (!activity.value || acting.value) {
      return
    }
    acting.value = true
    try {
      activity.value = await api.admin.publishActivity(activity.value.id)
      toast.success('活动已发布')
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      acting.value = false
    }
  }

  async function close() {
    if (!activity.value || acting.value) {
      return
    }
    acting.value = true
    try {
      activity.value = await api.admin.closeActivity(activity.value.id)
      toast.success('活动已结束')
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      acting.value = false
    }
  }

  function remove(onDeleted?: () => void) {
    const current = activity.value
    if (!current || acting.value) {
      return
    }
    modal.confirm({
      title: '删除活动',
      content: `确认删除活动「${current.title}」吗？参与记录、答题进度和该活动的证明导出将一并删除，且不可恢复。`,
      onConfirm: async () => {
        acting.value = true
        try {
          await api.admin.deleteActivity(current.id)
          toast.success('活动已删除')
          onDeleted?.()
        }
        catch (error) {
          toast.warning(errorMessage(error))
        }
        finally {
          acting.value = false
        }
      },
    })
  }

  async function verify(row: ActivityParticipantAdmin) {
    if (!activity.value || verifyingId.value) {
      return
    }
    verifyingId.value = row.userId
    try {
      const next = await api.admin.verifyParticipant(activity.value.id, row.userId)
      participants.value = participants.value.map(item => item.userId === next.userId ? next : item)
      if (next.verifyStatus === 'PASSED') {
        toast.success(`「${next.studentName || next.username || next.userId}」核验通过`)
      }
      else {
        toast.warning(next.verifyNote || '核验未通过')
      }
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      verifyingId.value = ''
    }
  }

  async function verifyAll() {
    if (!activity.value || verifyingAll.value) {
      return
    }
    verifyingAll.value = true
    try {
      const result = await api.admin.verifyAllParticipants(activity.value.id)
      toast.success(`批量核验完成：共 ${result.total} 人，通过 ${result.passed} 人，未通过 ${result.failed} 人`)
      await refresh()
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      verifyingAll.value = false
    }
  }

  async function syncServerParticipants() {
    if (!activity.value || syncing.value) {
      return
    }
    syncing.value = true
    try {
      const result = await api.admin.syncServerParticipants(activity.value.id)
      toast.success(`同步完成：扫描 ${result.scanned} 名玩家，新增 ${result.added} 人（核验通过 ${result.verifiedPassed} 人），无法识别身份 ${result.unresolved} 人`)
      await refresh()
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      syncing.value = false
    }
  }

  function openAddParticipant() {
    addSelectedKeys.value = []
    addForm.passed = true
    addForm.note = ''
    addVisible.value = true
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

  async function submitAddParticipant() {
    if (!activity.value || adding.value) {
      return
    }
    const userId = addSelectedKeys.value[0]
    if (!userId) {
      toast.warning('请先搜索并选择要添加的用户')
      return
    }
    adding.value = true
    try {
      const next = await api.admin.addParticipant(activity.value.id, {
        userId,
        passed: addForm.passed,
        note: addForm.note,
      })
      toast.success(`已将「${next.studentName || next.username || next.userId}」加入参与名单`)
      addVisible.value = false
      await refresh()
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      adding.value = false
    }
  }

  function removeParticipant(row: ActivityParticipantAdmin) {
    const current = activity.value
    if (!current) {
      return
    }
    const name = row.studentName || row.username || row.userId
    const rejoinNote = row.source === 'AUTO' ? '该记录由服务器同步产生，移除后不会再被同步加回。' : '移除后该用户可重新报名。'
    modal.confirm({
      title: '移除参与记录',
      content: `确认将「${name}」从参与名单中移除吗？其核验状态与证明绑定将一并删除，${rejoinNote}`,
      onConfirm: async () => {
        try {
          await api.admin.removeParticipant(current.id, row.userId)
          toast.success('参与记录已移除')
          if (participants.value.length === 1 && pager.page > 1) {
            pager.page -= 1
          }
          await refresh()
        }
        catch (error) {
          toast.warning(errorMessage(error))
        }
      },
    })
  }

  function openExport() {
    if (!activity.value) {
      return
    }
    exportVisible.value = true
  }

  async function submitExport() {
    if (!activity.value || exporting.value) {
      return
    }
    exporting.value = true
    try {
      const record = await api.admin.exportWord({
        activityId: activity.value.id,
        proofNo: exportForm.proofNo,
        college: exportForm.college,
        issuer: exportForm.issuer,
        issueDate: exportForm.issueDate,
      })
      toast.success('活动证明已生成')
      exportVisible.value = false
      await downloadRecord(record)
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      exporting.value = false
    }
  }

  async function downloadRecord(record: ActivityProofExportRecord) {
    if (!record.downloadPath) {
      toast.warning('暂无可下载文件')
      return
    }
    saveBlobResponse(await api.download(record.downloadPath), record.outputFilename || 'activity-proof.docx')
  }

  return {
    loading,
    acting,
    verifyingId,
    verifyingAll,
    syncing,
    activity,
    participants,
    pager,
    exportVisible,
    exporting,
    exportForm,
    passedCount,
    hasAutoJoinBinding,
    load,
    loadParticipants,
    refresh,
    publish,
    close,
    remove,
    verify,
    verifyAll,
    syncServerParticipants,
    addVisible,
    adding,
    addSelectedKeys,
    addForm,
    openAddParticipant,
    fetchUserOptions,
    submitAddParticipant,
    removeParticipant,
    openExport,
    submitExport,
  }
}
