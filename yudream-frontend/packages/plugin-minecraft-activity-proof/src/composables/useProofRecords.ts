import type { ActivityProofExportRecord } from '../types'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { useFaModal, useFaToast } from '@yudream/components'
import { reactive, ref } from 'vue'
import { createActivityProofApi } from '../api/activity-proof-api'
import { errorMessage, fileToBase64, saveBlobResponse } from './utils'

export function useProofRecords(sdk: YuDreamPluginSdk) {
  const api = createActivityProofApi(sdk)
  const toast = useFaToast()
  const modal = useFaModal()
  const loading = ref(false)
  const acting = ref(false)
  const records = ref<ActivityProofExportRecord[]>([])
  const pager = reactive({ page: 1, size: 10, total: 0 })

  async function load() {
    loading.value = true
    try {
      const result = await api.admin.exports(pager.page, pager.size)
      records.value = result.records
      pager.total = Number(result.total) || 0
    }
    finally {
      loading.value = false
    }
  }

  async function download(record: ActivityProofExportRecord) {
    await downloadFile(record.downloadPath, record.outputFilename || 'activity-proof.docx')
  }

  async function downloadStamped(record: ActivityProofExportRecord) {
    await downloadFile(record.stampedPdfDownloadPath, record.stampedPdfFilename || 'activity-proof.pdf')
  }

  async function downloadFile(path: string, fallbackName: string) {
    if (!path) {
      toast.warning('暂无可下载文件')
      return
    }
    acting.value = true
    try {
      saveBlobResponse(await api.download(path), fallbackName)
    }
    catch (error) {
      toast.warning(errorMessage(error, '文件下载失败'))
    }
    finally {
      acting.value = false
    }
  }

  async function uploadStampedPdf(record: ActivityProofExportRecord, file: File) {
    const nextRecord = await api.admin.uploadStampedPdf(record.id, {
      filename: file.name,
      contentType: file.type || 'application/pdf',
      base64: await fileToBase64(file),
    })
    records.value = records.value.map(item => item.id === nextRecord.id ? nextRecord : item)
    toast.success('盖章 PDF 已上传')
    return nextRecord
  }

  function remove(record: ActivityProofExportRecord) {
    modal.confirm({
      title: '删除导出记录',
      content: `确认删除「${record.outputFilename}」吗？相关 Word 和盖章 PDF 将同时删除。`,
      onConfirm: async () => {
        acting.value = true
        try {
          await api.admin.deleteExport(record.id)
          toast.success('导出记录已删除')
          if (records.value.length === 1 && pager.page > 1) {
            pager.page -= 1
          }
          await load()
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

  return {
    loading,
    acting,
    records,
    pager,
    load,
    download,
    downloadStamped,
    uploadStampedPdf,
    remove,
  }
}
