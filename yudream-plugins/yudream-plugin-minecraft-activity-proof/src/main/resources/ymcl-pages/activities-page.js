// YMCL 活动页 module（YAP §6.8）：随 yudream-plugin-minecraft-activity-proof
// jar 携带，经适配器 /v1/bundles 下发，由启动器 ModuleFrame 以 blob ESM 加载。
// 契约：默认导出工厂 (ctx) => { component, unmount }；禁止裸 import 'vue'，
// 一律从 ctx.vue 解构；样式注入 <style>，配色用启动器 CSS 变量。
// 封面：activity.cover 是域相对路径（/api/files/{id}/content），须经
// host.resolveUrl 还原成绝对地址；无封面或加载失败时用程序化像素画兜底
// （灵感来自 neco-pixel：贴图不复制，按活动 id 确定性自绘）。
export default function (ctx) {
	const { h, ref, computed, onMounted, onBeforeUnmount } = ctx.vue
	const host = ctx.host

	const PROVIDER = 'minecraft-activity-proof'

	const CSS = `
.ymc-act { display: flex; flex-direction: column; gap: 12px; }
.ymc-act-toolbar { display: flex; align-items: center; gap: 10px; }
.ymc-act-count { font-size: 13px; color: var(--color-secondary); }
.ymc-act-toolbar .spacer { flex: 1; }
.ymc-act-refresh { display: inline-flex; align-items: center; gap: 6px; border: none; cursor: pointer; border-radius: 8px; padding: 6px 12px; font-size: 13px; background: var(--color-button-bg); color: var(--color-contrast); }
.ymc-act-refresh:hover { filter: brightness(1.1); }
.ymc-act-refresh .dot-spin { display: inline-block; width: 12px; height: 12px; border: 2px solid var(--color-secondary); border-top-color: transparent; border-radius: 50%; animation: ymc-spin 0.8s linear infinite; }
@keyframes ymc-spin { to { transform: rotate(360deg); } }
.ymc-act-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.ymc-act-card { display: flex; flex-direction: column; background: var(--color-raised-bg); border-radius: 14px; overflow: hidden; }
.ymc-act-cover { position: relative; aspect-ratio: 16 / 7; background: var(--color-button-bg); }
.ymc-act-cover img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ymc-act-cover img.is-generated { image-rendering: pixelated; }
.ymc-act-badge { position: absolute; top: 10px; left: 10px; font-size: 11px; font-weight: 700; padding: 3px 10px; border-radius: 999px; background: var(--color-gray, #9aa); color: #fff; }
.ymc-act-badge.is-open { background: var(--color-green); }
.ymc-act-badge.is-running { background: var(--color-blue); }
.ymc-act-badge.is-soon { background: var(--color-orange); }
.ymc-act-joined { position: absolute; top: 10px; right: 10px; font-size: 11px; font-weight: 700; padding: 3px 10px; border-radius: 999px; background: color-mix(in srgb, var(--color-brand) 85%, transparent); color: #fff; }
.ymc-act-body { display: flex; flex-direction: column; gap: 8px; padding: 14px 16px 16px; flex: 1; }
.ymc-act-title { margin: 0; font-size: 15px; font-weight: 700; color: var(--color-contrast); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ymc-act-meta { font-size: 12px; color: var(--color-secondary); display: flex; gap: 8px; flex-wrap: wrap; }
.ymc-act-summary { margin: 0; font-size: 12px; color: var(--color-secondary); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.ymc-act-foot { display: flex; gap: 8px; margin-top: auto; padding-top: 4px; }
.ymc-btn { border: none; cursor: pointer; border-radius: 8px; padding: 8px 14px; font-size: 13px; font-weight: 600; background: var(--color-button-bg); color: var(--color-contrast); }
.ymc-btn:hover:not(:disabled) { filter: brightness(1.1); }
.ymc-btn:disabled { opacity: 0.5; cursor: default; }
.ymc-btn.primary { flex: 1; background: var(--color-brand); color: var(--color-brand-inverted, #fff); }
.ymc-btn.danger { flex: 1; color: var(--color-red); }
.ymc-act-empty, .ymc-act-error { background: var(--color-raised-bg); border-radius: 14px; padding: 32px; text-align: center; font-size: 13px; color: var(--color-secondary); }
.ymc-act-skeleton { height: 200px; border-radius: 14px; background: var(--color-raised-bg); animation: ymc-pulse 1.4s ease-in-out infinite; }
@keyframes ymc-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.55; } }
.ymc-act-back { display: inline-flex; align-items: center; gap: 6px; border: none; cursor: pointer; border-radius: 8px; padding: 6px 12px; font-size: 13px; background: var(--color-button-bg); color: var(--color-contrast); align-self: flex-start; }
.ymc-act-back:hover { filter: brightness(1.1); }
.ymc-act-detail-card { background: var(--color-raised-bg); border-radius: 14px; overflow: hidden; display: flex; flex-direction: column; }
.ymc-act-hero { position: relative; aspect-ratio: 16 / 5; background: var(--color-button-bg); cursor: default; }
.ymc-act-hero img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ymc-act-hero img.is-generated { image-rendering: pixelated; }
.ymc-act-detail-body { display: flex; flex-direction: column; gap: 14px; padding: 18px 20px 20px; }
.ymc-act-detail-title { margin: 0; font-size: 18px; font-weight: 800; color: var(--color-contrast); }
.ymc-act-rows { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 8px 16px; }
.ymc-act-row { display: flex; gap: 8px; font-size: 13px; line-height: 1.5; }
.ymc-act-row .k { color: var(--color-secondary); flex-shrink: 0; }
.ymc-act-row .v { color: var(--color-contrast); word-break: break-word; }
.ymc-act-block-t { margin: 0 0 6px; font-size: 13px; font-weight: 700; color: var(--color-contrast); }
.ymc-act-desc { margin: 0; font-size: 13px; line-height: 1.7; color: var(--color-contrast); white-space: pre-wrap; word-break: break-word; }
.ymc-act-req { margin: 0; padding-left: 18px; font-size: 13px; line-height: 1.7; color: var(--color-contrast); }
.ymc-act-reason { margin: 0; font-size: 12px; color: var(--color-orange); }
.ymc-act-detail-foot { display: flex; gap: 8px; padding-top: 4px; }
.ymc-act-clickable { cursor: pointer; }
`

	const style = document.createElement('style')
	style.setAttribute('data-ymcl-module', 'activities-page')
	style.textContent = CSS
	document.head.appendChild(style)

	function badgeClass(statusText) {
		switch (statusText) {
			case '报名中':
				return 'ymc-act-badge is-open'
			case '进行中':
				return 'ymc-act-badge is-running'
			case '即将开始':
				return 'ymc-act-badge is-soon'
			default:
				return 'ymc-act-badge'
		}
	}

	// ── 程序化像素封面（无封面/加载失败兜底，neco-pixel 式自绘贴图） ──────

	const coverCache = new Map()
	const resolveUrl = typeof host.resolveUrl === 'function' ? (url) => host.resolveUrl(url) : (url) => url

	function hashSeed(text) {
		let hash = 2166136261
		for (const ch of String(text)) {
			hash ^= ch.codePointAt(0)
			hash = Math.imul(hash, 16777619)
		}
		return hash >>> 0
	}

	function mulberry32(seed) {
		let state = seed >>> 0
		return () => {
			state = (state + 0x6d2b79f5) | 0
			let t = Math.imul(state ^ (state >>> 15), 1 | state)
			t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
			return ((t ^ (t >>> 14)) >>> 0) / 4294967296
		}
	}

	function mixColor(from, to, t) {
		const a = parseInt(from.slice(1), 16)
		const b = parseInt(to.slice(1), 16)
		const r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t)
		const g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t)
		const bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t)
		return `rgb(${r},${g},${bl})`
	}

	const COVER_THEMES = [
		{ sky: ['#7ec8f2', '#bfe3f7'], hill: '#6f9d5f', grass: '#5f8f3e', dirt: '#6b4f2e', cloud: 'rgba(255,255,255,0.85)', sun: '#fff7d6' },
		{ sky: ['#f2a65e', '#f7d28e'], hill: '#8a6a4f', grass: '#7a5a36', dirt: '#5e452a', cloud: 'rgba(255,240,220,0.85)', sun: '#ffdf9e' },
		{ sky: ['#232f4b', '#3d517a'], hill: '#2f3d33', grass: '#35502e', dirt: '#3d3626', cloud: 'rgba(220,230,255,0.35)', sun: '#e8eefc' },
	]

	/** 128×56 画布按种子确定性绘制「天空+云+远山+草方块」横幅，输出 data URL。 */
	function defaultCover(seedText) {
		const key = String(seedText || 'ymc-activity')
		if (coverCache.has(key)) return coverCache.get(key)
		const W = 128
		const H = 56
		const GROUND = 18
		const canvas = document.createElement('canvas')
		canvas.width = W
		canvas.height = H
		const g = canvas.getContext('2d')
		const rnd = mulberry32(hashSeed(key))
		const theme = COVER_THEMES[Math.floor(rnd() * COVER_THEMES.length)]

		// 天空：横向条带堆出块状渐变
		const bands = 7
		for (let i = 0; i < bands; i++) {
			g.fillStyle = mixColor(theme.sky[0], theme.sky[1], i / (bands - 1))
			const y = Math.floor(((H - GROUND) * i) / bands)
			g.fillRect(0, y, W, Math.ceil((H - GROUND) / bands) + 1)
		}
		// 太阳/月亮：方块
		g.fillStyle = theme.sun
		g.fillRect(10 + Math.floor(rnd() * 96), 4 + Math.floor(rnd() * 8), 8, 8)
		// 云：两三个块状组合
		g.fillStyle = theme.cloud
		for (let c = 0; c < 3; c++) {
			const cx = Math.floor(rnd() * (W - 24))
			const cy = 4 + Math.floor(rnd() * 14)
			g.fillRect(cx, cy, 14 + Math.floor(rnd() * 10), 3)
			g.fillRect(cx + 3, cy + 3, 8 + Math.floor(rnd() * 8), 3)
		}
		// 远山：阶梯剪影
		g.fillStyle = theme.hill
		let hx = 0
		while (hx < W) {
			const step = 8 + Math.floor(rnd() * 10)
			const hh = 6 + Math.floor(rnd() * 12)
			g.fillRect(hx, H - GROUND - hh, step, hh)
			hx += step
		}
		// 地面：草顶 + 泥土，撒噪点
		g.fillStyle = theme.grass
		g.fillRect(0, H - GROUND, W, 6)
		g.fillStyle = theme.dirt
		g.fillRect(0, H - GROUND + 6, W, GROUND - 6)
		for (let i = 0; i < 140; i++) {
			const px = Math.floor(rnd() * W)
			const py = H - GROUND + Math.floor(rnd() * GROUND)
			const inGrass = py < H - GROUND + 6
			g.fillStyle = mixColor(inGrass ? theme.grass : theme.dirt, rnd() > 0.5 ? '#000000' : '#ffffff', 0.18)
			g.fillRect(px, py, 2, 2)
		}

		const url = canvas.toDataURL('image/png')
		coverCache.set(key, url)
		return url
	}

	/** epoch 毫秒 → 「yyyy-MM-dd HH:mm」，<=0 视为未设置。 */
	function fmtTime(ms) {
		const value = Number(ms)
		if (!value || value <= 0) return ''
		const d = new Date(value)
		const pad = (n) => String(n).padStart(2, '0')
		return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
	}

	const component = {
		setup() {
			const activities = ref([])
			const loading = ref(true)
			const refreshing = ref(false)
			const errored = ref(false)
			const busyId = ref('')
			const brokenCovers = ref({})
			const detailId = ref('')
			const detail = computed(
				() => activities.value.find((item) => String(item.id) === detailId.value) || null,
			)
			let timer = 0

			async function load(initial) {
				if (initial) loading.value = true
				else refreshing.value = true
				errored.value = false
				try {
					const envelope = await host.dataFetch(PROVIDER, 'activities')
					const records = envelope && envelope.records
					activities.value = Array.isArray(records) ? records : []
				} catch (error) {
					errored.value = true
					activities.value = []
				} finally {
					loading.value = false
					refreshing.value = false
				}
			}

			async function runAction(activity, actionCode) {
				if (busyId.value) return
				busyId.value = activity.id
				try {
					const result = await host.executeAction({
						code: actionCode,
						title: actionCode === 'join' ? '报名' : '取消报名',
						kind: 'server:' + PROVIDER + ':' + actionCode,
						params: { activityId: activity.id },
					})
					if (!result || typeof result !== 'object' || result.refresh !== false) {
						await load(false)
					}
				} catch (error) {
					// 权限被拒或网络异常：toast 由宿主透出，这里只解锁按钮。
				} finally {
					busyId.value = ''
				}
			}

			onMounted(() => {
				void load(true)
				timer = setInterval(() => void load(false), 60000)
			})
			onBeforeUnmount(() => clearInterval(timer))

			function renderCover(activity, extraClass, onOpen) {
				const children = []
				const key = String(activity.id ?? activity.title ?? '')
				const raw = activity.cover ? String(activity.cover) : ''
				const useReal = raw !== '' && !brokenCovers.value[key]
				children.push(
					h('img', {
						src: useReal ? resolveUrl(raw) : defaultCover(key || activity.title),
						alt: '',
						class: useReal ? '' : 'is-generated',
						onError: useReal
							? () => {
									brokenCovers.value = { ...brokenCovers.value, [key]: true }
								}
							: undefined,
					}),
				)
				if (activity.statusText) {
					children.push(
						h('span', { class: badgeClass(String(activity.statusText)) }, String(activity.statusText)),
					)
				}
				if (activity.joined) {
					children.push(h('span', { class: 'ymc-act-joined' }, '已报名'))
				}
				return h('div', { class: extraClass, onClick: onOpen }, children)
			}

			function openDetail(activity) {
				detailId.value = String(activity.id ?? '')
			}

			function closeDetail() {
				detailId.value = ''
			}

			function joinButton(activity, extraClass) {
				return h(
					'button',
					{
						class: extraClass,
						disabled: !activity.joinable || busyId.value === activity.id,
						onClick: () => void runAction(activity, 'join'),
					},
					busyId.value === activity.id ? '处理中…' : '报名',
				)
			}

			function cancelButton(activity, extraClass) {
				return h(
					'button',
					{
						class: extraClass,
						disabled: busyId.value === activity.id,
						onClick: () => void runAction(activity, 'cancel'),
					},
					busyId.value === activity.id ? '处理中…' : '取消报名',
				)
			}

			function renderCard(activity) {
				const meta = []
				if (activity.timeText) meta.push(String(activity.timeText))
				meta.push(`${Number(activity.participantCount) || 0} 人参与`)

				const foot = []
				if (activity.joined) {
					foot.push(cancelButton(activity, 'ymc-btn danger'))
				} else {
					foot.push(joinButton(activity, 'ymc-btn primary'))
				}
				foot.push(
					h('button', { class: 'ymc-btn', onClick: () => openDetail(activity) }, '详情'),
				)

				return h('div', { class: 'ymc-act-card', key: activity.id }, [
					renderCover(activity, 'ymc-act-cover ymc-act-clickable', () => openDetail(activity)),
					h('div', { class: 'ymc-act-body' }, [
						h(
							'h3',
							{
								class: 'ymc-act-title ymc-act-clickable',
								onClick: () => openDetail(activity),
							},
							String(activity.title || '未命名活动'),
						),
						h('div', { class: 'ymc-act-meta' }, meta.join(' · ')),
						activity.summary
							? h('p', { class: 'ymc-act-summary' }, String(activity.summary))
							: null,
						h('div', { class: 'ymc-act-foot' }, foot),
					]),
				])
			}

			function detailRow(label, value) {
				const text = String(value || '').trim()
				if (!text) return null
				return h('div', { class: 'ymc-act-row' }, [
					h('span', { class: 'k' }, label),
					h('span', { class: 'v' }, text),
				])
			}

			function renderDetail(activity) {
				const signup =
					fmtTime(activity.signupStart) || fmtTime(activity.signupEnd)
						? `${fmtTime(activity.signupStart) || '不限'} ~ ${fmtTime(activity.signupEnd) || '不限'}`
						: ''
				const during =
					fmtTime(activity.activityStart) || fmtTime(activity.activityEnd)
						? `${fmtTime(activity.activityStart) || '即日起'} ~ ${fmtTime(activity.activityEnd) || '长期'}`
						: ''
				const deptText = activity.deptRestricted
					? (Array.isArray(activity.allowedDeptNames) && activity.allowedDeptNames.length
							? activity.allowedDeptNames.join('、')
							: '限指定部门')
					: '不限'
				const verifyText = activity.verifyStatus
					? activity.verifyStatus === 'PASSED'
						? '核验通过'
						: activity.verifyStatus === 'UNVERIFIED'
							? '待核验'
							: `核验未通过${activity.verifyNote ? '：' + activity.verifyNote : ''}`
					: ''

				const rows = [
					detailRow('状态', activity.statusText),
					detailRow('报名时间', signup),
					detailRow('活动时间', during),
					detailRow('参与人数', `${Number(activity.participantCount) || 0} 人`),
					detailRow('参与范围', deptText),
					activity.joined && Number(activity.joinedAt) > 0
						? detailRow('我的报名', fmtTime(activity.joinedAt))
						: null,
					activity.joined ? detailRow('核验状态', verifyText) : null,
				].filter(Boolean)

				const blocks = []
				if (Array.isArray(activity.requirements) && activity.requirements.length) {
					blocks.push(
						h('div', [
							h('p', { class: 'ymc-act-block-t' }, '参与要求'),
							h(
								'ul',
								{ class: 'ymc-act-req' },
								activity.requirements.map((req) => h('li', String(req))),
							),
						]),
					)
				}
				if (activity.description && String(activity.description).trim()) {
					blocks.push(
						h('div', [
							h('p', { class: 'ymc-act-block-t' }, '活动详情'),
							h('p', { class: 'ymc-act-desc' }, String(activity.description)),
						]),
					)
				}

				const foot = []
				if (activity.joined) {
					foot.push(cancelButton(activity, 'ymc-btn danger'))
				} else {
					foot.push(joinButton(activity, 'ymc-btn primary'))
				}
				if (!activity.joined && !activity.joinable && activity.joinDisabledReason) {
					foot.push(h('p', { class: 'ymc-act-reason' }, String(activity.joinDisabledReason)))
				}

				return h('div', { class: 'ymc-act' }, [
					h('button', { class: 'ymc-act-back', onClick: closeDetail }, '← 返回列表'),
					h('div', { class: 'ymc-act-detail-card' }, [
						renderCover(activity, 'ymc-act-hero'),
						h('div', { class: 'ymc-act-detail-body' }, [
							h('h2', { class: 'ymc-act-detail-title' }, String(activity.title || '未命名活动')),
							h('div', { class: 'ymc-act-rows' }, rows),
							...blocks,
							h('div', { class: 'ymc-act-detail-foot' }, foot),
						]),
					]),
				])
			}

			return () => {
				// 详情视图：记录由列表信封携带全字段，报名/取消后 load 刷新，
				// detail computed 自动跟进；活动被撤下时给出回退提示。
				if (detailId.value) {
					if (detail.value) {
						return renderDetail(detail.value)
					}
					return h('div', { class: 'ymc-act' }, [
						h('button', { class: 'ymc-act-back', onClick: closeDetail }, '← 返回列表'),
						h('div', { class: 'ymc-act-empty' }, '活动已下线或不再可见。'),
					])
				}

				const toolbar = h('div', { class: 'ymc-act-toolbar' }, [
					h(
						'span',
						{ class: 'ymc-act-count' },
						activities.value.length ? `${activities.value.length} 个活动` : '',
					),
					h('div', { class: 'spacer' }),
					h(
						'button',
						{ class: 'ymc-act-refresh', onClick: () => void load(false) },
						refreshing.value ? [h('span', { class: 'dot-spin' }), '刷新中'] : '刷新',
					),
				])

				if (loading.value) {
					return h('div', { class: 'ymc-act' }, [
						toolbar,
						h('div', { class: 'ymc-act-grid' }, [
							h('div', { class: 'ymc-act-skeleton' }),
							h('div', { class: 'ymc-act-skeleton' }),
							h('div', { class: 'ymc-act-skeleton' }),
						]),
					])
				}
				if (errored.value) {
					return h('div', { class: 'ymc-act' }, [
						toolbar,
						h('div', { class: 'ymc-act-error' }, [
							'活动列表加载失败。',
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
				if (!activities.value.length) {
					return h('div', { class: 'ymc-act' }, [
						toolbar,
						h('div', { class: 'ymc-act-empty' }, '暂时没有进行中的活动。'),
					])
				}
				return h('div', { class: 'ymc-act' }, [
					toolbar,
					h('div', { class: 'ymc-act-grid' }, activities.value.map(renderCard)),
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
