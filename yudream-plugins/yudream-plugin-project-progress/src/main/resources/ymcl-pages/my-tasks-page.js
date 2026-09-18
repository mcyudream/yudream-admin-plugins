// YMCL「我的任务」页 module（YAP §6.8）：随 yudream-plugin-project-progress
// jar 携带，经适配器 /v1/bundles 下发，由启动器 ModuleFrame 以 blob ESM 加载。
// 契约：默认导出工厂 (ctx) => { component, unmount }；禁止裸 import 'vue'，
// 一律从 ctx.vue 解构；样式注入 <style>，配色用启动器 CSS 变量。
// 详情视图：任务卡片自带全字段（description/assignee/验收结论等），点击进
// 任务详情；「所属项目」经 project-detail 数据源按 ?id= 拉项目+成员统计+
// 任务清单（YmclDataContext.query 透传）。
export default function (ctx) {
	const { h, ref, computed, onMounted, onBeforeUnmount } = ctx.vue
	const host = ctx.host

	const PROVIDER = 'project-progress'

	const CSS = `
.ymc-task { display: flex; flex-direction: column; gap: 12px; }
.ymc-task-toolbar { display: flex; align-items: center; gap: 10px; }
.ymc-task-count { font-size: 13px; color: var(--color-secondary); }
.ymc-task-toolbar .spacer { flex: 1; }
.ymc-task-refresh { display: inline-flex; align-items: center; gap: 6px; border: none; cursor: pointer; border-radius: 8px; padding: 6px 12px; font-size: 13px; background: var(--color-button-bg); color: var(--color-contrast); }
.ymc-task-refresh:hover { filter: brightness(1.1); }
.ymc-task-refresh .dot-spin { display: inline-block; width: 12px; height: 12px; border: 2px solid var(--color-secondary); border-top-color: transparent; border-radius: 50%; animation: ymc-spin 0.8s linear infinite; }
@keyframes ymc-spin { to { transform: rotate(360deg); } }
.ymc-task-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 10px; }
.ymc-task-stat { background: var(--color-raised-bg); border-radius: 12px; padding: 12px 14px; display: flex; flex-direction: column; gap: 2px; }
.ymc-task-stat-value { font-size: 22px; font-weight: 800; color: var(--color-brand); }
.ymc-task-stat-label { font-size: 12px; color: var(--color-secondary); }
.ymc-task-list { display: flex; flex-direction: column; gap: 8px; }
.ymc-task-row { display: flex; align-items: center; gap: 12px; background: var(--color-raised-bg); border-radius: 12px; padding: 12px 16px; }
.ymc-task-main { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 3px; cursor: pointer; }
.ymc-task-title { font-size: 14px; font-weight: 700; color: var(--color-contrast); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ymc-task-sub { font-size: 12px; color: var(--color-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ymc-task-badge { font-size: 11px; font-weight: 700; padding: 3px 10px; border-radius: 999px; background: var(--color-button-bg); color: var(--color-secondary); flex-shrink: 0; }
.ymc-task-badge.is-todo { color: var(--color-contrast); }
.ymc-task-badge.is-reviewing { background: color-mix(in srgb, var(--color-blue) 20%, transparent); color: var(--color-blue); }
.ymc-task-badge.is-repairing { background: color-mix(in srgb, var(--color-orange) 20%, transparent); color: var(--color-orange); }
.ymc-task-badge.is-done { background: color-mix(in srgb, var(--color-green) 20%, transparent); color: var(--color-green); }
.ymc-task-pending { font-size: 11px; font-weight: 700; padding: 3px 10px; border-radius: 999px; background: color-mix(in srgb, var(--color-orange) 20%, transparent); color: var(--color-orange); flex-shrink: 0; }
.ymc-btn { border: none; cursor: pointer; border-radius: 8px; padding: 8px 14px; font-size: 13px; font-weight: 600; background: var(--color-button-bg); color: var(--color-contrast); flex-shrink: 0; }
.ymc-btn:hover:not(:disabled) { filter: brightness(1.1); }
.ymc-btn:disabled { opacity: 0.5; cursor: default; }
.ymc-btn.primary { background: var(--color-brand); color: var(--color-brand-inverted, #fff); }
.ymc-task-empty, .ymc-task-error { background: var(--color-raised-bg); border-radius: 14px; padding: 32px; text-align: center; font-size: 13px; color: var(--color-secondary); }
.ymc-task-skeleton { height: 64px; border-radius: 12px; background: var(--color-raised-bg); animation: ymc-pulse 1.4s ease-in-out infinite; }
@keyframes ymc-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.55; } }
.ymc-dt-back { display: inline-flex; align-items: center; gap: 6px; border: none; cursor: pointer; border-radius: 8px; padding: 6px 12px; font-size: 13px; background: var(--color-button-bg); color: var(--color-contrast); align-self: flex-start; }
.ymc-dt-back:hover { filter: brightness(1.1); }
.ymc-dt-card { background: var(--color-raised-bg); border-radius: 14px; padding: 18px 20px 20px; display: flex; flex-direction: column; gap: 14px; }
.ymc-dt-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.ymc-dt-title { margin: 0; font-size: 17px; font-weight: 800; color: var(--color-contrast); }
.ymc-dt-rows { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 8px 16px; }
.ymc-dt-row { display: flex; gap: 8px; font-size: 13px; line-height: 1.5; }
.ymc-dt-row .k { color: var(--color-secondary); flex-shrink: 0; }
.ymc-dt-row .v { color: var(--color-contrast); word-break: break-word; }
.ymc-dt-block-t { margin: 0 0 6px; font-size: 13px; font-weight: 700; color: var(--color-contrast); }
.ymc-dt-desc { margin: 0; font-size: 13px; line-height: 1.7; color: var(--color-contrast); white-space: pre-wrap; word-break: break-word; }
.ymc-dt-foot { display: flex; gap: 8px; flex-wrap: wrap; }
.ymc-dt-member { display: flex; align-items: center; gap: 10px; font-size: 13px; padding: 8px 12px; border-radius: 10px; background: var(--color-button-bg); }
.ymc-dt-member .n { font-weight: 700; color: var(--color-contrast); }
.ymc-dt-member .s { color: var(--color-secondary); font-size: 12px; }
.ymc-dt-members { display: flex; flex-direction: column; gap: 6px; }
`

	const style = document.createElement('style')
	style.setAttribute('data-ymcl-module', 'my-tasks-page')
	style.textContent = CSS
	document.head.appendChild(style)

	function badgeClass(statusCode) {
		switch (statusCode) {
			case 'TODO':
				return 'ymc-task-badge is-todo'
			case 'REVIEWING':
				return 'ymc-task-badge is-reviewing'
			case 'REPAIRING':
				return 'ymc-task-badge is-repairing'
			case 'DONE':
				return 'ymc-task-badge is-done'
			default:
				return 'ymc-task-badge'
		}
	}

	/** epoch 毫秒 → 「yyyy-MM-dd HH:mm」，<=0 视为未设置。 */
	function fmtTime(ms) {
		const value = Number(ms)
		if (!value || value <= 0) return ''
		const d = new Date(value)
		const pad = (n) => String(n).padStart(2, '0')
		return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
	}

	function assignmentText(mode) {
		switch (mode) {
			case 'ASSIGN':
				return '指派'
			case 'CLAIM':
				return '认领'
			case 'RANDOM':
				return '随机分配'
			default:
				return mode || ''
		}
	}

	const component = {
		setup() {
			const tasks = ref([])
			const stats = ref([])
			const loading = ref(true)
			const refreshing = ref(false)
			const errored = ref(false)
			const busyId = ref('')
			const detailTaskId = ref('')
			// 项目详情里点开的任务不在「我的任务」列表中，直接持有记录。
			const detailTaskRecord = ref(null)
			const projectState = ref(null)
			let timer = 0

			const detailTask = computed(() => {
				if (detailTaskRecord.value) return detailTaskRecord.value
				return tasks.value.find((task) => String(task.id) === detailTaskId.value) || null
			})

			async function load(initial) {
				if (initial) loading.value = true
				else refreshing.value = true
				errored.value = false
				try {
					const results = await Promise.all([
						host.dataFetch(PROVIDER, 'my-tasks'),
						host.dataFetch(PROVIDER, 'my-stats'),
					])
					const taskRecords = results[0] && results[0].records
					const statRecords = results[1] && results[1].records
					tasks.value = Array.isArray(taskRecords) ? taskRecords : []
					stats.value = Array.isArray(statRecords) ? statRecords : []
				} catch (error) {
					errored.value = true
					tasks.value = []
					stats.value = []
				} finally {
					loading.value = false
					refreshing.value = false
				}
			}

			async function checkIn(task) {
				if (busyId.value) return
				busyId.value = task.id
				try {
					const result = await host.executeAction({
						code: 'minecraft-check-in',
						title: 'MC 时长打卡',
						kind: 'server:' + PROVIDER + ':minecraft-check-in',
						params: { detailId: task.id },
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

			function openTask(task, record) {
				detailTaskRecord.value = record || null
				detailTaskId.value = String((record && record.id) ?? task.id ?? '')
			}

			function closeTask() {
				detailTaskId.value = ''
				detailTaskRecord.value = null
			}

			async function openProject(projectId) {
				if (!projectId) return
				projectState.value = {
					loading: true,
					error: false,
					project: null,
					tasks: [],
					memberStats: [],
				}
				try {
					const envelope = await host.dataFetch(
						PROVIDER,
						'project-detail',
						'id=' + encodeURIComponent(String(projectId)),
					)
					projectState.value = {
						loading: false,
						error: false,
						project: envelope && envelope.project ? envelope.project : null,
						tasks: envelope && Array.isArray(envelope.records) ? envelope.records : [],
						memberStats:
							envelope && Array.isArray(envelope.memberStats) ? envelope.memberStats : [],
					}
				} catch (error) {
					projectState.value = { loading: false, error: true, project: null, tasks: [], memberStats: [] }
				}
			}

			function closeProject() {
				projectState.value = null
			}

			onMounted(() => {
				void load(true)
				timer = setInterval(() => void load(false), 60000)
			})
			onBeforeUnmount(() => clearInterval(timer))

			function renderStats() {
				if (!stats.value.length) return null
				return h(
					'div',
					{ class: 'ymc-task-stats' },
					stats.value.map((stat) =>
						h('div', { class: 'ymc-task-stat', key: stat.label }, [
							h('span', { class: 'ymc-task-stat-value' }, String(stat.value ?? 0)),
							h('span', { class: 'ymc-task-stat-label' }, String(stat.label || '')),
						]),
					),
				)
			}

			function renderRow(task) {
				const sub = []
				if (task.dueText) sub.push(String(task.dueText))
				if (task.summary) sub.push(String(task.summary))
				return h('div', { class: 'ymc-task-row', key: task.id }, [
					h('div', { class: 'ymc-task-main', onClick: () => openTask(task) }, [
						h('span', { class: 'ymc-task-title' }, String(task.title || '未命名任务')),
						sub.length ? h('span', { class: 'ymc-task-sub' }, sub.join(' · ')) : null,
					]),
					task.pendingAcceptance ? h('span', { class: 'ymc-task-pending' }, '待验收') : null,
					h(
						'span',
						{ class: badgeClass(String(task.statusCode || '')) },
						String(task.statusText || task.statusCode || '未知状态'),
					),
					h(
						'button',
						{
							class: 'ymc-btn primary',
							disabled: task.statusCode === 'DONE' || busyId.value === task.id,
							onClick: () => void checkIn(task),
						},
						busyId.value === task.id ? '打卡中…' : 'MC 时长打卡',
					),
					h('button', { class: 'ymc-btn', onClick: () => openTask(task) }, '详情'),
				])
			}

			function detailRow(label, value) {
				const text = String(value || '').trim()
				if (!text) return null
				return h('div', { class: 'ymc-dt-row' }, [
					h('span', { class: 'k' }, label),
					h('span', { class: 'v' }, text),
				])
			}

			function renderTaskDetail(task) {
				const rows = [
					detailRow('截止时间', task.dueText || fmtTime(task.dueAt)),
					detailRow('认领方式', assignmentText(task.assignmentMode)),
					Number(task.requiredAssigneeCount) > 0
						? detailRow(
								'需要人数',
								`${Number(task.assigneeCount) || 0} / ${Number(task.requiredAssigneeCount)} 人`,
							)
						: null,
					detailRow('验收结论', task.acceptanceSummary),
					detailRow('创建时间', fmtTime(task.createdAt)),
					detailRow('更新时间', fmtTime(task.updatedAt)),
				].filter(Boolean)

				const foot = [
					h(
						'button',
						{
							class: 'ymc-btn primary',
							disabled: task.statusCode === 'DONE' || busyId.value === task.id,
							onClick: () => void checkIn(task),
						},
						busyId.value === task.id ? '打卡中…' : 'MC 时长打卡',
					),
				]
				if (task.projectId) {
					foot.push(
						h(
							'button',
							{ class: 'ymc-btn', onClick: () => void openProject(task.projectId) },
							'所属项目',
						),
					)
				}

				return h('div', { class: 'ymc-task' }, [
					h('button', { class: 'ymc-dt-back', onClick: closeTask }, '← 返回列表'),
					h('div', { class: 'ymc-dt-card' }, [
						h('div', { class: 'ymc-dt-head' }, [
							h('h2', { class: 'ymc-dt-title' }, String(task.title || '未命名任务')),
							h(
								'span',
								{ class: badgeClass(String(task.statusCode || '')) },
								String(task.statusText || task.statusCode || '未知状态'),
							),
							task.pendingAcceptance ? h('span', { class: 'ymc-task-pending' }, '待验收') : null,
						]),
						h('div', { class: 'ymc-dt-rows' }, rows),
						task.description && String(task.description).trim()
							? h('div', [
									h('p', { class: 'ymc-dt-block-t' }, '任务描述'),
									h('p', { class: 'ymc-dt-desc' }, String(task.description)),
								])
							: null,
						h('div', { class: 'ymc-dt-foot' }, foot),
					]),
				])
			}

			function renderProject(state) {
				const children = [
					h('button', { class: 'ymc-dt-back', onClick: closeProject }, '← 返回'),
				]
				if (state.loading) {
					children.push(h('div', { class: 'ymc-task-skeleton' }))
					return h('div', { class: 'ymc-task' }, children)
				}
				if (state.error || !state.project) {
					children.push(
						h('div', { class: 'ymc-task-error' }, [
							'项目详情加载失败。',
							h('p', [
								h('button', { class: 'ymc-btn', onClick: closeProject }, '返回'),
							]),
						]),
					)
					return h('div', { class: 'ymc-task' }, children)
				}

				const project = state.project
				children.push(
					h('div', { class: 'ymc-dt-card' }, [
						h('div', { class: 'ymc-dt-head' }, [
							h('h2', { class: 'ymc-dt-title' }, String(project.name || '未命名项目')),
							h(
								'span',
								{ class: 'ymc-task-badge' + (project.enabled ? ' is-done' : '') },
								project.enabled ? '进行中' : '已停用',
							),
						]),
						h(
							'div',
							{ class: 'ymc-dt-rows' },
							[
								detailRow(
									'管理者',
									Array.isArray(project.managerNames) ? project.managerNames.join('、') : '',
								),
								detailRow('成员数', `${Number(project.memberCount) || 0} 人`),
								detailRow('MC 打卡', project.minecraftPolicyText),
								detailRow('创建时间', fmtTime(project.createdAt)),
							].filter(Boolean),
						),
						project.description && String(project.description).trim()
							? h('div', [
									h('p', { class: 'ymc-dt-block-t' }, '项目简介'),
									h('p', { class: 'ymc-dt-desc' }, String(project.description)),
								])
							: null,
					]),
				)

				if (state.memberStats.length) {
					children.push(
						h('div', { class: 'ymc-dt-card' }, [
							h('p', { class: 'ymc-dt-block-t' }, '成员进展'),
							h(
								'div',
								{ class: 'ymc-dt-members' },
								state.memberStats.map((member) =>
									h('div', { class: 'ymc-dt-member', key: member.userId }, [
										h('span', { class: 'n' }, String(member.name || member.userId)),
										h(
											'span',
											{ class: 's' },
											`任务 ${Number(member.assignedDetails) || 0} · 完成 ${Number(member.completedDetails) || 0} · 待验收 ${Number(member.pendingAcceptanceDetails) || 0} · 打卡 ${Number(member.checkIns) || 0}`,
										),
									]),
								),
							),
						]),
					)
				}

				children.push(
					h('div', { class: 'ymc-dt-card' }, [
						h('p', { class: 'ymc-dt-block-t' }, `任务清单（${state.tasks.length}）`),
						state.tasks.length
							? h(
									'div',
									{ class: 'ymc-task-list' },
									state.tasks.map((task) =>
										h('div', { class: 'ymc-task-row', key: task.id }, [
											h(
												'div',
												{ class: 'ymc-task-main', onClick: () => openTask(task, task) },
												[
													h(
														'span',
														{ class: 'ymc-task-title' },
														String(task.title || '未命名任务'),
													),
													task.dueText
														? h('span', { class: 'ymc-task-sub' }, String(task.dueText))
														: null,
												],
											),
											h(
												'span',
												{ class: badgeClass(String(task.statusCode || '')) },
												String(task.statusText || task.statusCode || '未知状态'),
											),
										]),
									),
								)
							: h('div', { class: 'ymc-task-empty' }, '项目下还没有任务。'),
					]),
				)
				return h('div', { class: 'ymc-task' }, children)
			}

			return () => {
				if (projectState.value) {
					return renderProject(projectState.value)
				}
				if (detailTaskId.value) {
					if (detailTask.value) {
						return renderTaskDetail(detailTask.value)
					}
					return h('div', { class: 'ymc-task' }, [
						h('button', { class: 'ymc-dt-back', onClick: closeTask }, '← 返回列表'),
						h('div', { class: 'ymc-task-empty' }, '任务已不在列表中。'),
					])
				}

				const toolbar = h('div', { class: 'ymc-task-toolbar' }, [
					h(
						'span',
						{ class: 'ymc-task-count' },
						tasks.value.length ? `${tasks.value.length} 项任务` : '',
					),
					h('div', { class: 'spacer' }),
					h(
						'button',
						{ class: 'ymc-task-refresh', onClick: () => void load(false) },
						refreshing.value ? [h('span', { class: 'dot-spin' }), '刷新中'] : '刷新',
					),
				])

				if (loading.value) {
					return h('div', { class: 'ymc-task' }, [
						toolbar,
						h('div', { class: 'ymc-task-list' }, [
							h('div', { class: 'ymc-task-skeleton' }),
							h('div', { class: 'ymc-task-skeleton' }),
							h('div', { class: 'ymc-task-skeleton' }),
						]),
					])
				}
				if (errored.value) {
					return h('div', { class: 'ymc-task' }, [
						toolbar,
						h('div', { class: 'ymc-task-error' }, [
							'任务列表加载失败。',
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

				const children = [toolbar, renderStats()]
				if (!tasks.value.length) {
					children.push(h('div', { class: 'ymc-task-empty' }, '当前没有分配给你的任务。'))
				} else {
					children.push(h('div', { class: 'ymc-task-list' }, tasks.value.map(renderRow)))
				}
				return h('div', { class: 'ymc-task' }, children)
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
