import type { UserActivity } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useFaModal, useFaToast } from '@yudream/components'
import QRCode from 'qrcode'
import { computed, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { errorMessage } from './utils'

const DETAIL_PATH = '/platform/plugins/yudream-student-info/activity-square/detail'

export function useActivityDetail(sdk: YuDreamPluginSdk) {
  const api = createActivityProofApi(sdk)
  const toast = useFaToast()
  const modal = useFaModal()
  const loading = ref(false)
  const acting = ref(false)
  const activity = ref<UserActivity | null>(null)
  const qrVisible = ref(false)
  const qrDataUrl = ref('')

  const joined = computed(() => activity.value?.participationStatus === 'JOINED')
  const canVerify = computed(() => joined.value && !!activity.value && activity.value.verifyStatus !== 'PASSED')

  async function load(id: string) {
    if (!id) {
      activity.value = null
      return
    }
    loading.value = true
    try {
      activity.value = await api.me.activity(id)
    }
    finally {
      loading.value = false
    }
  }

  async function join() {
    if (!activity.value || acting.value) {
      return
    }
    acting.value = true
    try {
      activity.value = await api.me.join(activity.value.id)
      toast.success('参与成功')
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      acting.value = false
    }
  }

  function cancel() {
    if (!activity.value || acting.value) {
      return
    }
    const target = activity.value
    modal.confirm({
      title: '取消参与',
      content: `确认取消参与「${target.title}」吗？取消后需要重新报名才能再次参与。`,
      onConfirm: async () => {
        acting.value = true
        try {
          activity.value = await api.me.cancel(target.id)
          toast.success('已取消参与')
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

  async function verify() {
    if (!activity.value || acting.value) {
      return
    }
    acting.value = true
    try {
      const participation = await api.me.verify(activity.value.id)
      activity.value = {
        ...activity.value,
        verifyStatus: participation.verifyStatus,
        verifyNote: participation.verifyNote,
      }
      if (participation.verifyStatus === 'PASSED') {
        toast.success('核验通过')
      }
      else {
        toast.warning(participation.verifyNote || '暂未达成核验条件')
      }
    }
    catch (error) {
      toast.warning(errorMessage(error))
    }
    finally {
      acting.value = false
    }
  }

  function coverOf(value: UserActivity) {
    return sdk.files.assetUrl(value.coverUrl || undefined)
  }

  function shareUrl(value: UserActivity) {
    if (typeof window === 'undefined') {
      return `${DETAIL_PATH}?id=${value.id}`
    }
    return `${window.location.origin}${DETAIL_PATH}?id=${encodeURIComponent(value.id)}`
  }

  async function openQr() {
    if (!activity.value) {
      return
    }
    try {
      qrDataUrl.value = await QRCode.toDataURL(shareUrl(activity.value), { margin: 1, width: 320 })
      qrVisible.value = true
    }
    catch (error) {
      toast.warning(errorMessage(error, '二维码生成失败'))
    }
  }

  function printQr() {
    if (!activity.value || !qrDataUrl.value || typeof window === 'undefined') {
      return
    }
    const target = activity.value
    const printWindow = window.open('', '_blank', 'width=480,height=640')
    if (!printWindow) {
      toast.warning('浏览器拦截了打印窗口，请允许弹出窗口后重试')
      return
    }
    printWindow.document.write(`<!DOCTYPE html><html><head><meta charset="utf-8"><title>活动二维码 - ${escapeHtml(target.title)}</title><style>body{font-family:system-ui,"Microsoft YaHei",sans-serif;display:flex;flex-direction:column;align-items:center;gap:16px;padding:48px 24px;color:#111}h1{font-size:22px;margin:0;text-align:center}p{margin:0;font-size:14px;color:#555;text-align:center}img{width:320px;height:320px}.url{font-size:12px;word-break:break-all;color:#888}</style></head><body><h1>${escapeHtml(target.title)}</h1><p>扫码查看活动并参与</p><img src="${qrDataUrl.value}" alt="活动二维码"><p class="url">${escapeHtml(shareUrl(target))}</p><script>window.onload=function(){window.print();window.onafterprint=function(){window.close();};};<\/script></body></html>`)
    printWindow.document.close()
  }

  return {
    loading,
    acting,
    activity,
    qrVisible,
    qrDataUrl,
    joined,
    canVerify,
    load,
    join,
    cancel,
    verify,
    coverOf,
    shareUrl,
    openQr,
    printQr,
  }
}

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', '\'': '&#39;' }[char] || char))
}
