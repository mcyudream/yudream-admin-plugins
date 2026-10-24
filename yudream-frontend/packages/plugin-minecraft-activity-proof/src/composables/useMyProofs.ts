import type { ActivityProofExportRecord } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useYdToast } from '@yudream/components'
import { reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { errorMessage, saveBlobResponse } from './utils'

export function useMyProofs(sdk: YuDreamPluginSdk) {
  const api = createActivityProofApi(sdk)
  const toast = useYdToast()
  const loading = ref(false)
  const downloading = ref(false)
  const records = ref<ActivityProofExportRecord[]>([])
  const pager = reactive({ page: 1, size: 10, total: 0 })

  async function load() {
    loading.value = true
    try {
      const result = await api.me.exports(pager.page, pager.size)
      records.value = result.records
      pager.total = Number(result.total) || 0
    }
    catch (error) {
      toast.warning(errorMessage(error, '加载盖章记录失败'))
    }
    finally {
      loading.value = false
    }
  }

  async function downloadStamped(record: ActivityProofExportRecord) {
    if (!record.stampedPdfDownloadPath) {
      toast.warning('暂无可下载的盖章 PDF')
      return
    }
    downloading.value = true
    try {
      saveBlobResponse(await api.download(record.stampedPdfDownloadPath), record.stampedPdfFilename || 'activity-proof.pdf')
    }
    catch (error) {
      toast.warning(errorMessage(error, '文件下载失败'))
    }
    finally {
      downloading.value = false
    }
  }

  return {
    loading,
    downloading,
    records,
    pager,
    load,
    downloadStamped,
  }
}
