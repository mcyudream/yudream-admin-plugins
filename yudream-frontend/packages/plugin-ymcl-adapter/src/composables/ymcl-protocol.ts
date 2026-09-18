export function siteOrigin(url = window.location.origin) {
	return url.replace(/\/$/, '')
}

/** YMCL-Axolotl 注册的深链 scheme（tauri deep-link desktop.schemes = ["ymcl"]）。 */
export function ymclAddSiteUri(origin = siteOrigin()) {
	return `ymcl://add-site?url=${encodeURIComponent(origin)}`
}

/**
 * 用临时 <a> 触发自定义协议，避免 location.href 在部分浏览器/WebView
 * 下静默失败或把当前页导航走。协议未注册时浏览器通常无反应。
 */
export function openYmcl(origin = siteOrigin()): boolean {
	const uri = ymclAddSiteUri(origin)
	try {
		const link = document.createElement('a')
		link.href = uri
		link.rel = 'noreferrer'
		link.style.display = 'none'
		document.body.appendChild(link)
		link.click()
		window.setTimeout(() => link.remove(), 0)
		return true
	}
	catch {
		return false
	}
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

export function formatSize(bytes?: number | null) {
	if (!bytes || bytes <= 0) {
		return '-'
	}
	const units = ['B', 'KB', 'MB', 'GB']
	let value = bytes
	let unit = 0
	while (value >= 1024 && unit < units.length - 1) {
		value /= 1024
		unit++
	}
	return `${value.toFixed(value >= 100 || unit === 0 ? 0 : 1)} ${units[unit]}`
}
