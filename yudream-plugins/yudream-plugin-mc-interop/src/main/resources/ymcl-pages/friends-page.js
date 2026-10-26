// YMCL 友站服务器页 module（YAP §6.8）：随 yudream-plugin-mc-interop jar
// 携带，经适配器 /v1/bundles 下发，由启动器 ModuleFrame 以 blob ESM 加载。
// 视觉骨架与 mc-server 服务器页（servers-page.js）同款；契约：默认导出工厂
// (ctx) => { component, unmount }；禁止裸 import 'vue'，一律从 ctx.vue 解构。
export default function (ctx) {
	const { h, ref, onMounted, onBeforeUnmount } = ctx.vue
	const host = ctx.host

	const CSS = `
.ymc-ifr { display: flex; flex-direction: column; gap: 12px; }
.ymc-ifr-toolbar { display: flex; align-items: center; gap: 10px; }
.ymc-ifr-count { font-size: 13px; color: var(--color-secondary); }
.ymc-ifr-toolbar .spacer { flex: 1; }
.ymc-ifr-refresh { display: inline-flex; align-items: center; gap: 6px; border: none; cursor: pointer; border-radius: 8px; padding: 6px 12px; font-size: 13px; background: var(--color-button-bg); color: var(--color-contrast); }
.ymc-ifr-refresh:hover { filter: brightness(1.1); }
.ymc-ifr-refresh .dot-spin { display: inline-block; width: 12px; height: 12px; border: 2px solid var(--color-secondary); border-top-color: transparent; border-radius: 50%; animation: ymc-ifr-spin 0.8s linear infinite; }
@keyframes ymc-ifr-spin { to { transform: rotate(360deg); } }
.ymc-ifr-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; }
.ymc-ifr-card { display: flex; flex-direction: column; gap: 10px; background: var(--color-raised-bg); border-radius: 14px; padding: 16px; }
.ymc-ifr-head { display: flex; align-items: center; gap: 12px; }
.ymc-ifr-favicon { width: 44px; height: 44px; border-radius: 10px; image-rendering: pixelated; background: var(--color-button-bg); flex-shrink: 0; }
.ymc-ifr-letter { width: 44px; height: 44px; border-radius: 10px; background: var(--color-button-bg); color: var(--color-brand); font-weight: 700; font-size: 18px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.ymc-ifr-titles { min-width: 0; flex: 1; }
.ymc-ifr-name { font-size: 15px; font-weight: 700; color: var(--color-contrast); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ymc-ifr-source { font-size: 11px; font-weight: 600; border-radius: 6px; padding: 1px 6px; margin-left: 6px; vertical-align: 2px; border: 1px solid var(--color-button-bg); color: var(--color-secondary); }
.ymc-ifr-source.is-friend { color: var(--color-brand); border-color: color-mix(in srgb, var(--color-brand) 40%, transparent); }
.ymc-ifr-status { font-size: 12px; display: inline-flex; align-items: center; gap: 5px; flex-shrink: 0; }
.ymc-ifr-status::before { content: ''; width: 8px; height: 8px; border-radius: 50%; background: var(--color-gray, #9aa); }
.ymc-ifr-status.is-online { color: var(--color-green); }
.ymc-ifr-status.is-online::before { background: var(--color-green); }
.ymc-ifr-status.is-offline { color: var(--color-red); }
.ymc-ifr-status.is-offline::before { background: var(--color-red); }
.ymc-ifr-desc { margin: 0; font-size: 13px; color: var(--color-secondary); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; white-space: pre-line; }
.ymc-ifr-bar { position: relative; height: 18px; border-radius: 9px; background: var(--color-button-bg); overflow: hidden; }
.ymc-ifr-bar-fill { position: absolute; inset: 0 auto 0 0; background: color-mix(in srgb, var(--color-brand) 70%, transparent); border-radius: 9px; transition: width 0.3s; }
.ymc-ifr-bar-text { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font-size: 11px; font-weight: 600; color: var(--color-contrast); }
.ymc-ifr-meta { font-size: 12px; color: var(--color-secondary); display: flex; gap: 8px; flex-wrap: wrap; }
.ymc-ifr-address { display: flex; align-items: center; gap: 8px; background: var(--color-button-bg); border-radius: 8px; padding: 6px 10px; }
.ymc-ifr-address code { flex: 1; font-size: 12px; color: var(--color-contrast); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; background: none; padding: 0; }
.ymc-ifr-foot { display: flex; gap: 8px; margin-top: auto; }
.ymc-ifr-btn { border: none; cursor: pointer; border-radius: 8px; padding: 8px 14px; font-size: 13px; font-weight: 600; background: var(--color-button-bg); color: var(--color-contrast); }
.ymc-ifr-btn:hover:not(:disabled) { filter: brightness(1.1); }
.ymc-ifr-btn:disabled { opacity: 0.5; cursor: default; }
.ymc-ifr-btn.primary { flex: 1; background: var(--color-brand); color: var(--color-brand-inverted, #fff); }
.ymc-ifr-empty, .ymc-ifr-error { background: var(--color-raised-bg); border-radius: 14px; padding: 32px; text-align: center; font-size: 13px; color: var(--color-secondary); }
.ymc-ifr-skeleton { height: 148px; border-radius: 14px; background: var(--color-raised-bg); animation: ymc-ifr-pulse 1.4s ease-in-out infinite; }
@keyframes ymc-ifr-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.55; } }
`

	const style = document.createElement('style')
	style.setAttribute('data-ymcl-module', 'friends-page')
	style.textContent = CSS
	document.head.appendChild(style)

	function addressText(record) {
		return record && record.address ? String(record.address) : ''
	}

	function faviconSrc(record) {
		const favicon = record && record.favicon
		if (typeof favicon !== 'string' || !favicon) return null
		return favicon.startsWith('data:') ? favicon : 'data:image/png;base64,' + favicon
	}

	function playersPercent(record) {
		const max = Number(record.maxPlayers) || 0
		if (max <= 0) return 0
		return Math.max(0, Math.min(100, Math.round(((Number(record.onlinePlayers) || 0) / max) * 100)))
	}

	function renderStatus(record) {
		if (record.online === true) {
			return h('span', { class: 'ymc-ifr-status is-online' }, '在线')
		}
		if (record.online === false) {
			return h('span', { class: 'ymc-ifr-status is-offline' }, '离线')
		}
		return null
	}

	const component = {
		setup() {
			const records = ref([])
			const loading = ref(true)
			const refreshing = ref(false)
			const errored = ref(false)
			const copiedId = ref('')
			const busyId = ref('')
			let timer = 0
			let copiedTimer = 0

			async function load(initial) {
				if (initial) loading.value = true
				else refreshing.value = true
				errored.value = false
				try {
					const envelope = await host.dataFetch('mc-interop', 'interop-catalog')
					const list = envelope && envelope.records
					records.value = Array.isArray(list) ? list : []
				} catch (error) {
					errored.value = true
					records.value = []
				} finally {
					loading.value = false
					refreshing.value = false
				}
			}

			async function copyAddress(record) {
				const address = addressText(record)
				if (!address) return
				try {
					await host.executeAction({
						code: 'copy-address',
						title: '复制地址',
						kind: 'client:copy',
						params: { text: address },
					})
					copiedId.value = record.id
					clearTimeout(copiedTimer)
					copiedTimer = setTimeout(() => {
						copiedId.value = ''
					}, 1600)
				} catch (error) {
					// 剪贴板不可用时静默——地址文本仍可直接选中复制。
				}
			}

			async function installAndJoin(record) {
				if (busyId.value) return
				busyId.value = record.id
				try {
					await host.executeAction({
						code: 'install-and-join',
						title: '一键安装并进服',
						kind: 'client:install-and-join',
						params: {
							origin: String(record.origin || ''),
							entryId: String(record.id || ''),
							address: addressText(record),
							title: String(record.title || record.id || ''),
						},
					})
				} catch (error) {
					// 执行器已给出错误反馈；这里只复位按钮。
				} finally {
					busyId.value = ''
				}
			}

			async function join(record) {
				const address = addressText(record)
				if (!address) return
				try {
					await host.joinServer(address)
				} catch (error) {
					// 未授权或弹窗不可用时忽略，复制地址路径仍可用。
				}
			}

			onMounted(() => {
				void load(true)
				timer = setInterval(() => void load(false), 60000)
			})
			onBeforeUnmount(() => {
				clearInterval(timer)
				clearTimeout(copiedTimer)
			})

			function renderSource(record) {
				const friend = record.friendSite
					? '友站 ' + String(record.friendSite)
					: '本站'
				return h('span', {
					class: 'ymc-ifr-source' + (record.friendSite ? ' is-friend' : ''),
				}, friend)
			}

			function renderCard(record) {
				const favicon = faviconSrc(record)
				const head = h('div', { class: 'ymc-ifr-head' }, [
					favicon
						? h('img', { class: 'ymc-ifr-favicon', src: favicon, alt: '' })
						: h(
								'div',
								{ class: 'ymc-ifr-letter' },
								String(record.title || record.id || '?').slice(0, 1).toUpperCase(),
							),
					h('div', { class: 'ymc-ifr-titles' }, [
						h('div', { class: 'ymc-ifr-name' }, [
							String(record.title || record.id || '未命名条目'),
							renderSource(record),
						]),
					]),
					renderStatus(record),
				])

				const children = [head]

				if (record.summary) {
					children.push(h('p', { class: 'ymc-ifr-desc' }, String(record.summary)))
				}

				if (record.online === true && Number(record.maxPlayers) > 0) {
					children.push(
						h('div', { class: 'ymc-ifr-bar' }, [
							h('div', {
								class: 'ymc-ifr-bar-fill',
								style: { width: playersPercent(record) + '%' },
							}),
							h(
								'span',
								{ class: 'ymc-ifr-bar-text' },
								`${Number(record.onlinePlayers) || 0} / ${Number(record.maxPlayers) || 0} 人在线`,
							),
						]),
					)
				}

				if (addressText(record)) {
					children.push(
						h('div', { class: 'ymc-ifr-address' }, [
							h('code', addressText(record)),
							h(
								'button',
								{ class: 'ymc-ifr-btn', onClick: () => void copyAddress(record) },
								copiedId.value === record.id ? '已复制' : '复制',
							),
						]),
					)
				}

				const meta = []
				if (record.versionName) meta.push(String(record.versionName))
				if (record.ping != null && record.online === true) {
					meta.push(`${Number(record.ping)} ms`)
				}
				if (meta.length) {
					children.push(h('div', { class: 'ymc-ifr-meta' }, meta.join(' · ')))
				}

				children.push(
					h('div', { class: 'ymc-ifr-foot' }, [
						record.hasPack
							? h(
									'button',
									{
										class: 'ymc-ifr-btn primary',
										disabled: busyId.value === record.id || !record.origin,
										onClick: () => void installAndJoin(record),
									},
									busyId.value === record.id ? '安装中…' : '一键安装并进服',
								)
							: h(
									'button',
									{
										class: 'ymc-ifr-btn primary',
										disabled: !addressText(record),
										onClick: () => void join(record),
									},
									'加入游戏',
								),
					]),
				)

				return h('div', { class: 'ymc-ifr-card', key: record.id }, children)
			}

			return () => {
				const toolbar = h('div', { class: 'ymc-ifr-toolbar' }, [
					h(
						'span',
						{ class: 'ymc-ifr-count' },
						records.value.length ? `${records.value.length} 个可入站条目` : '',
					),
					h('div', { class: 'spacer' }),
					h(
						'button',
						{ class: 'ymc-ifr-refresh', onClick: () => void load(false) },
						refreshing.value ? [h('span', { class: 'dot-spin' }), '刷新中'] : '刷新',
					),
				])

				if (loading.value) {
					return h('div', { class: 'ymc-ifr' }, [
						toolbar,
						h('div', { class: 'ymc-ifr-grid' }, [
							h('div', { class: 'ymc-ifr-skeleton' }),
							h('div', { class: 'ymc-ifr-skeleton' }),
							h('div', { class: 'ymc-ifr-skeleton' }),
						]),
					])
				}
				if (errored.value) {
					return h('div', { class: 'ymc-ifr' }, [
						toolbar,
						h('div', { class: 'ymc-ifr-error' }, [
							'跨站目录加载失败。',
							h('p', [
								h(
									'button',
									{ class: 'ymc-ifr-btn', onClick: () => void load(true) },
									'重试',
								),
							]),
						]),
					])
				}
				if (!records.value.length) {
					return h('div', { class: 'ymc-ifr' }, [
						toolbar,
						h('div', { class: 'ymc-ifr-empty' }, '暂无跨站条目：本站发布或互采友站后展示。'),
					])
				}
				return h('div', { class: 'ymc-ifr' }, [
					toolbar,
					h('div', { class: 'ymc-ifr-grid' }, records.value.map(renderCard)),
				])
			}
		},
	}

	return {
		component,
		unmount() {
			style.remove()
		},
	}
}
