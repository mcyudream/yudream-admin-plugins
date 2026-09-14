export const YMCL_SITE_FILE_NAME = 'yudream.ymclsite'

export function siteOrigin(url = window.location.origin) {
  return url.replace(/\/$/, '')
}

export function ymclAddSiteUri(origin = siteOrigin()) {
  return `ymcl://add-site?url=${encodeURIComponent(origin)}`
}

export function ymclSiteToken(origin = siteOrigin()) {
  return `ymcl:site:${encodeURIComponent(origin)}`
}

export function applyYmclDragPayload(event: DragEvent, origin = siteOrigin()) {
  if (!event.dataTransfer) {
    return
  }
  const token = ymclSiteToken(origin)
  const addSiteUri = ymclAddSiteUri(origin)
  event.stopPropagation()
  event.dataTransfer.effectAllowed = 'copy'
  event.dataTransfer.dropEffect = 'copy'
  event.dataTransfer.setData('text/plain', token)
  event.dataTransfer.setData('text/uri-list', addSiteUri)
  event.dataTransfer.setData(
    'DownloadURL',
    `text/plain:${YMCL_SITE_FILE_NAME}:data:text/plain;charset=utf-8,${encodeURIComponent(token)}`,
  )
  try {
    const file = new File([token], YMCL_SITE_FILE_NAME, { type: 'text/plain' })
    event.dataTransfer.items.add(file)
  }
  catch {
    // Chromium 以外的浏览器可能拒绝往 DataTransfer 塞 File，文本协议仍可粘贴。
  }
}

export function openYmcl(origin = siteOrigin()) {
  window.location.href = ymclAddSiteUri(origin)
}

export function absoluteUrl(url: string) {
  if (/^https?:\/\//i.test(url)) {
    return url
  }
  return `${window.location.origin}${url.startsWith('/') ? url : `/${url}`}`
}

export function formatTime(value?: string | number | null) {
  if (value === undefined || value === null || value === '') {
    return '-'
  }
  const numeric = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(numeric) || numeric <= 0) {
    return '-'
  }
  return new Date(numeric).toLocaleString('zh-CN', { hour12: false })
}
