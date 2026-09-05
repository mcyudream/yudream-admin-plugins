import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TimelineEventPayload, TimelineEventSummary, TimelineEventView, TimelineStatusFilter } from '../types'
import { useFaToast } from '@yudream/components'
import { reactive, ref } from 'vue'
import { createTimelineApi } from '../api/timeline-api'
import { errorMessage, normalizeFileUrl } from './utils'

export function useTimelinePlugin(sdk: YuDreamPluginSdk) {
  const api = createTimelineApi(sdk)
  const toast = useFaToast()

  // 公开时间轴
  const publicEvents = ref<TimelineEventSummary[]>([])
  const publicLoading = ref(false)
  const publicError = ref('')
  const publicDetail = ref<TimelineEventView | null>(null)
  const publicDetailLoading = ref(false)

  // 管理端
  const adminEvents = ref<TimelineEventSummary[]>([])
  const adminPager = reactive({ page: 1, size: 20, total: 0 })
  const adminFilters = reactive({ keyword: '', status: '' as TimelineStatusFilter })
  const adminLoading = ref(false)
  const saving = ref(false)
  const toggling = ref(false)

  async function loadPublicEvents() {
    publicLoading.value = true
    publicError.value = ''
    try {
      publicEvents.value = await api.publicEvents()
    }
    catch (error) {
      publicError.value = errorMessage(error, '加载失败')
    }
    finally {
      publicLoading.value = false
    }
  }

  async function loadPublicDetail(eventId: string) {
    publicDetailLoading.value = true
    try {
      publicDetail.value = await api.publicEvent(eventId)
    }
    catch (error) {
      publicDetail.value = null
      toast.error(errorMessage(error, '加载详情失败'))
    }
    finally {
      publicDetailLoading.value = false
    }
  }

  function closePublicDetail() {
    publicDetail.value = null
  }

  async function loadAdminEvents(page = adminPager.page) {
    adminLoading.value = true
    try {
      const result = await api.adminEvents(adminFilters.keyword.trim(), adminFilters.status, page, adminPager.size)
      adminEvents.value = result.records
      adminPager.total = Number(result.total) || 0
      adminPager.page = page
    }
    catch (error) {
      toast.error(errorMessage(error, '加载事件列表失败'))
    }
    finally {
      adminLoading.value = false
    }
  }

  // 删除后当前页可能变空，回退到仍存在的最后一页
  async function reloadWithClamp() {
    const maxPage = Math.max(1, Math.ceil((adminPager.total) / adminPager.size))
    await loadAdminEvents(Math.min(adminPager.page, maxPage))
  }

  async function loadAdminEvent(eventId: string) {
    return await api.adminEvent(eventId)
  }

  async function saveEvent(eventId: string | null, payload: TimelineEventPayload) {
    if (saving.value) {
      return false
    }
    saving.value = true
    try {
      if (eventId) {
        await api.updateEvent(eventId, payload)
        toast.success('事件已更新')
      }
      else {
        await api.createEvent(payload)
        toast.success('事件已创建')
      }
      return true
    }
    catch (error) {
      toast.error(errorMessage(error, '保存失败'))
      return false
    }
    finally {
      saving.value = false
    }
  }

  async function setPublished(eventId: string, published: boolean) {
    if (toggling.value) {
      return
    }
    toggling.value = true
    try {
      await api.setEventPublished(eventId, published)
      toast.success(published ? '已发布，公开时间轴立即可见' : '已下架，公开时间轴不再展示')
      await reloadWithClamp()
    }
    catch (error) {
      toast.error(errorMessage(error, '操作失败'))
    }
    finally {
      toggling.value = false
    }
  }

  async function removeEvent(row: TimelineEventSummary) {
    try {
      await api.deleteEvent(row.id)
      toast.success(`「${row.title}」已删除`)
      adminPager.total = Math.max(0, adminPager.total - 1)
      await reloadWithClamp()
    }
    catch (error) {
      toast.error(errorMessage(error, '删除失败'))
    }
  }

  async function uploadImage(file: File) {
    const uploaded = await sdk.files.uploadImage(file, { module: 'timeline', publicAccess: true })
    return normalizeFileUrl(uploaded.assetUrl || uploaded.url || '')
  }

  // FaImageUpload 列表项直接作为 <img src> 渲染，必须保留可展示的完整 URL；
  // 归一化相对路径由编辑器同步回表单时统一处理
  async function uploadImageForDisplay(file: File) {
    const uploaded = await sdk.files.uploadImage(file, { module: 'timeline', publicAccess: true })
    return uploaded.assetUrl || uploaded.url || ''
  }

  // reactive 解包 ref，页面里直接 model.publicEvents 使用；sdk/api/toast 不进响应式代理
  const model = reactive({
    publicEvents,
    publicLoading,
    publicError,
    publicDetail,
    publicDetailLoading,
    loadPublicEvents,
    loadPublicDetail,
    closePublicDetail,
    adminEvents,
    adminPager,
    adminFilters,
    adminLoading,
    saving,
    toggling,
    loadAdminEvents,
    loadAdminEvent,
    saveEvent,
    setPublished,
    removeEvent,
    uploadImage,
    uploadImageForDisplay,
  })
  return Object.assign(model, { sdk, api, toast })
}

export type TimelinePluginModel = ReturnType<typeof useTimelinePlugin>
