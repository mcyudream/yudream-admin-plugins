// YMCL 服务器页 module（YAP §6.8）：随 yudream-plugin-minecraft-server jar
// 携带，经适配器 /v1/bundles 下发，由启动器 ModuleFrame 以 blob ESM 加载。
// 契约：默认导出工厂 (ctx) => { component, unmount }；禁止裸 import 'vue'，
// 一律从 ctx.vue 解构；样式注入 <style>，配色用启动器 CSS 变量。
export default function (ctx) {
	const { h, ref, onMounted, onBeforeUnmount } = ctx.vue
	const host = ctx.host

	const CSS = `
.ymc-srv { display: flex; flex-direction: column; gap: 12px; }
.ymc-srv-toolbar { display: flex; align-items: center; gap: 10px; }
.ymc-srv-count { font-size: 13px; color: var(--color-secondary); }
.ymc-srv-toolbar .spacer { flex: 1; }
.ymc-srv-refresh { display: inline-flex; align-items: center; gap: 6px; border: none; cursor: pointer; border-radius: 8px; padding: 6px 12px; font-size: 13px; background: var(--color-button-bg); color: var(--color-contrast); }
.ymc-srv-refresh:hover { filter: brightness(1.1); }
.ymc-srv-refresh .dot-spin { display: inline-block; width: 12px; height: 12px; border: 2px solid var(--color-secondary); border-top-color: transparent; border-radius: 50%; animation: ymc-spin 0.8s linear infinite; }
@keyframes ymc-spin { to { transform: rotate(360deg); } }
.ymc-srv-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; }
.ymc-srv-card { display: flex; flex-direction: column; gap: 10px; background: var(--color-raised-bg); border-radius: 14px; padding: 16px; }
.ymc-srv-head { display: flex; align-items: center; gap: 12px; }
.ymc-srv-favicon { width: 44px; height: 44px; border-radius: 10px; image-rendering: pixelated; background: var(--color-button-bg); flex-shrink: 0; }
.ymc-srv-letter { width: 44px; height: 44px; border-radius: 10px; background: var(--color-button-bg); color: var(--color-brand); font-weight: 700; font-size: 18px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.ymc-srv-titles { min-width: 0; flex: 1; }
.ymc-srv-name { font-size: 15px; font-weight: 700; color: var(--color-contrast); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ymc-srv-edition { font-size: 11px; font-weight: 600; color: var(--color-secondary); border: 1px solid var(--color-button-bg); border-radius: 6px; padding: 1px 6px; margin-left: 6px; vertical-align: 2px; }
.ymc-srv-season { font-size: 12px; color: var(--color-brand); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ymc-srv-status { font-size: 12px; display: inline-flex; align-items: center; gap: 5px; flex-shrink: 0; }
.ymc-srv-status::before { content: ''; width: 8px; height: 8px; border-radius: 50%; background: var(--color-gray, #9aa); }
.ymc-srv-status.is-online { color: var(--color-green); }
.ymc-srv-status.is-online::before { background: var(--color-green); }
.ymc-srv-status.is-offline { color: var(--color-red); }
.ymc-srv-status.is-offline::before { background: var(--color-red); }
.ymc-srv-motd { margin: 0; font-size: 13px; color: var(--color-secondary); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; white-space: pre-line; }
.ymc-srv-bar { position: relative; height: 18px; border-radius: 9px; background: var(--color-button-bg); overflow: hidden; }
.ymc-srv-bar-fill { position: absolute; inset: 0 auto 0 0; background: color-mix(in srgb, var(--color-brand) 70%, transparent); border-radius: 9px; transition: width 0.3s; }
.ymc-srv-bar-text { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; font-size: 11px; font-weight: 600; color: var(--color-contrast); }
.ymc-srv-meta { font-size: 12px; color: var(--color-secondary); display: flex; gap: 8px; flex-wrap: wrap; }
.ymc-srv-address { display: flex; align-items: center; gap: 8px; background: var(--color-button-bg); border-radius: 8px; padding: 6px 10px; }
.ymc-srv-address code { flex: 1; font-size: 12px; color: var(--color-contrast); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; background: none; padding: 0; }
.ymc-srv-foot { display: flex; gap: 8px; margin-top: auto; }
.ymc-btn { border: none; cursor: pointer; border-radius: 8px; padding: 8px 14px; font-size: 13px; font-weight: 600; background: var(--color-button-bg); color: var(--color-contrast); }
.ymc-btn:hover:not(:disabled) { filter: brightness(1.1); }
.ymc-btn:disabled { opacity: 0.5; cursor: default; }
.ymc-btn.primary { flex: 1; background: var(--color-brand); color: var(--color-brand-inverted, #fff); }
.ymc-srv-empty, .ymc-srv-error { background: var(--color-raised-bg); border-radius: 14px; padding: 32px; text-align: center; font-size: 13px; color: var(--color-secondary); }
.ymc-srv-skeleton { height: 148px; border-radius: 14px; background: var(--color-raised-bg); animation: ymc-pulse 1.4s ease-in-out infinite; }
@keyframes ymc-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.55; } }
`

	const style = document.createElement('style')
	style.setAttribute('data-ymcl-module', 'servers-page')
	style.textContent = CSS
	document.head.appendChild(style)

	function faviconSrc(server) {
		const favicon = server && server.favicon
		if (typeof favicon !== 'string' || !favicon) return null
		return favicon.startsWith('data:') ? favicon : 'data:image/png;base64,' + favicon
	}

	function playersPercent(server) {
		const max = Number(server.maxPlayers) || 0
		if (max <= 0) return 0
		return Math.max(0, Math.min(100, Math.round(((Number(server.onlinePlayers) || 0) / max) * 100)))
	}

	const component = {
		setup() {
			const servers = ref([])
			const loading = ref(true)
			const refreshing = ref(false)
			const errored = ref(false)
			const copiedId = ref('')
			let timer = 0
			let copiedTimer = 0

			async function load(initial) {
				if (initial) loading.value = true
				else refreshing.value = true
				errored.value = false
				try {
					const envelope = await host.dataFetch('minecraft-server', 'servers')
					const records = envelope && envelope.records
					servers.value = Array.isArray(records) ? records : []
				} catch (error) {
					errored.value = true
					servers.value = []
				} finally {
					loading.value = false
					refreshing.value = false
				}
			}

			async function copyAddress(server) {
				if (!server.address) return
				try {
					await host.executeAction({
						code: 'copy-address',
						title: '复制地址',
						kind: 'client:copy',
						params: { text: server.address },
					})
					copiedId.value = server.id
					clearTimeout(copiedTimer)
					copiedTimer = setTimeout(() => {
						copiedId.value = ''
					}, 1600)
				} catch (error) {
					// 剪贴板不可用时静默——按钮仍在，用户可手动复制。
				}
			}

			async function join(server) {
				if (!server.address) return
				try {
					await host.joinServer(server.address)
				} catch (error) {
					// 未授权或弹窗不可用时忽略，地址复制路径仍可用。
				}
			}

			onMounted(() => {
				void load(true)
				timer = setInterval(() => void load(false), 30000)
			})
			onBeforeUnmount(() => {
				clearInterval(timer)
				clearTimeout(copiedTimer)
			})

			function renderStatus(server) {
				if (server.online === true) {
					return h('span', { class: 'ymc-srv-status is-online' }, '在线')
				}
				if (server.online === false) {
					return h('span', { class: 'ymc-srv-status is-offline' }, '离线')
				}
				return h('span', { class: 'ymc-srv-status' }, '未知')
			}

			function renderCard(server) {
				const favicon = faviconSrc(server)
				const head = h('div', { class: 'ymc-srv-head' }, [
					favicon
						? h('img', { class: 'ymc-srv-favicon', src: favicon, alt: '' })
						: h(
								'div',
								{ class: 'ymc-srv-letter' },
								String(server.name || '?').slice(0, 1).toUpperCase(),
							),
					h('div', { class: 'ymc-srv-titles' }, [
						h('div', { class: 'ymc-srv-name' }, [
							String(server.name || '未命名服务器'),
							server.edition
								? h('span', { class: 'ymc-srv-edition' }, String(server.edition))
								: null,
						]),
						server.currentSeason && server.currentSeason.name
							? h(
									'div',
									{ class: 'ymc-srv-season' },
									'当前赛季 · ' + String(server.currentSeason.name),
								)
							: null,
					]),
					renderStatus(server),
				])

				const children = [head]

				if (server.motd) {
					children.push(h('p', { class: 'ymc-srv-motd' }, String(server.motd)))
				}

				if (server.online === true && Number(server.maxPlayers) > 0) {
					children.push(
						h('div', { class: 'ymc-srv-bar' }, [
							h('div', {
								class: 'ymc-srv-bar-fill',
								style: { width: playersPercent(server) + '%' },
							}),
							h(
								'span',
								{ class: 'ymc-srv-bar-text' },
								`${Number(server.onlinePlayers) || 0} / ${Number(server.maxPlayers) || 0} 人在线`,
							),
						]),
					)
				}

				const meta = []
				if (server.versionName) meta.push(String(server.versionName))
				if (server.ping != null) meta.push(`${server.ping} ms`)
				if (meta.length) {
					children.push(h('div', { class: 'ymc-srv-meta' }, meta.join(' · ')))
				}

				if (server.address) {
					children.push(
						h('div', { class: 'ymc-srv-address' }, [
							h('code', String(server.address)),
							h(
								'button',
								{
									class: 'ymc-btn',
									onClick: () => void copyAddress(server),
								},
								copiedId.value === server.id ? '已复制' : '复制',
							),
						]),
					)
				}

				children.push(
					h('div', { class: 'ymc-srv-foot' }, [
						h(
							'button',
							{
								class: 'ymc-btn primary',
								disabled: server.online !== true || !server.address,
								onClick: () => void join(server),
							},
							'加入游戏',
						),
					]),
				)

				return h('div', { class: 'ymc-srv-card', key: server.id }, children)
			}

			return () => {
				const toolbar = h('div', { class: 'ymc-srv-toolbar' }, [
					h(
						'span',
						{ class: 'ymc-srv-count' },
						servers.value.length ? `${servers.value.length} 台服务器` : '',
					),
					h('div', { class: 'spacer' }),
					h(
						'button',
						{ class: 'ymc-srv-refresh', onClick: () => void load(false) },
						refreshing.value ? [h('span', { class: 'dot-spin' }), '刷新中'] : '刷新',
					),
				])

				if (loading.value) {
					return h('div', { class: 'ymc-srv' }, [
						toolbar,
						h('div', { class: 'ymc-srv-grid' }, [
							h('div', { class: 'ymc-srv-skeleton' }),
							h('div', { class: 'ymc-srv-skeleton' }),
							h('div', { class: 'ymc-srv-skeleton' }),
						]),
					])
				}
				if (errored.value) {
					return h('div', { class: 'ymc-srv' }, [
						toolbar,
						h('div', { class: 'ymc-srv-error' }, [
							'服务器列表加载失败。',
							h('p', [
								h(
									'button',
									{ class: 'ymc-btn', onClick: () => void load(true) },
									'重试',
								),
							]),
						]),
					])
				}
				if (!servers.value.length) {
					return h('div', { class: 'ymc-srv' }, [
						toolbar,
						h('div', { class: 'ymc-srv-empty' }, '域内暂无可用服务器。'),
					])
				}
				return h('div', { class: 'ymc-srv' }, [
					toolbar,
					h('div', { class: 'ymc-srv-grid' }, servers.value.map(renderCard)),
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
