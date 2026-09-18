// YMCL「可认领任务」页 module（YAP §6.8）：随 yudream-plugin-project-progress
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
.ymc-claim { display: flex; flex-direction: column; gap: 12px; }
.ymc-claim-toolbar { display: flex; align-items: center; gap: 10px; }
.ymc-claim-count { font-size: 13px; color: var(--color-secondary); }
.ymc-claim-toolbar .spacer { flex: 1; }
.ymc-claim-refresh { display: inline-flex; align-items: center; gap: 6px; border: none; cursor: pointer; border-radius: 8px; padding: 6px 12px; font-size: 13px; background: var(--color-button-bg); color: var(--color-contrast); }
.ymc-claim-refresh:hover { filter: brightness(1.1); }
.ymc-claim-refresh .dot-spin { display: inline-block; width: 12px; height: 12px; border: 2px solid var(--color-secondary); border-top-color: transparent; border-radius: 50%; animation: ymc-spin 0.8s linear infinite; }
@keyframes ymc-spin { to { transform: rotate(360deg); } }
.ymc-claim-list { display: flex; flex-direction: column; gap: 8px; }
.ymc-claim-row { display: flex; align-items: center; gap: 12px; background: var(--color-raised-bg); border-radius: 12px; padding: 12px 16px; }
.ymc-claim-main { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 3px; cursor: pointer; }
.ymc-claim-title { font-size: 14px; font-weight: 700; color: var(--color-contrast); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ymc-claim-sub { font-size: 12px; color: var(--color-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ymc-claim-badge { font-size: 11px; font-weight: 700; padding: 3px 10px; border-radius: 999px; background: var(--color-button-bg); color: var(--color-secondary); flex-shrink: 0; }
.ymc-btn { border: none; cursor: pointer; border-radius: 8px; padding: 8px 14px; font-size: 13px; font-weight: 600; background: var(--color-button-bg); color: var(--color-contrast); flex-shrink: 0; }
.ymc-btn:hover:not(:disabled) { filter: brightness(1.1); }
.ymc-btn:disabled { opacity: 0.5; cursor: default; }
.ymc-btn.primary { background: var(--color-brand); color: var(--color-brand-inverted, #fff); }
.ymc-claim-empty, .ymc-claim-error { background: var(--color-raised-bg); border-radius: 14px; padding: 32px; text-align: center; font-size: 13px; color: var(--color-secondary); }
.ymc-claim-skeleton { height: 64px; border-radius: 12px; background: var(--color-raised-bg); animation: ymc-pulse 1.4s ease-in-out infinite; }
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
	style.setAttribute('data-ymcl-module', 'claimable-page')
	style.textContent = CSS
	document.head.appendChild(style)

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
			const loading = ref(true)
			const refreshing = ref(false)
			const errored = ref(false)
			const busyId = ref('')
			const detailTaskId = ref('')
			// 项目详情里点开的任务不在「可认领」列表中，直接持有记录。
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
					const envelope = await host.dataFetch(PROVIDER, 'claimable-tasks')
					const records = envelope && envelope.records
					tasks.value = Array.isArray(records) ? records : []
				} catch (error) {
					errored.value = true
					tasks.value = []
				} finally {
					loading.value = false
					refreshing.value = false
				}
			}

			async function claim(task) {
				if (busyId.value) return
				busyId.value = task.id
				try {
					const result = await host.executeAction({
						code: 'claim',
						title: '认领任务',
						kind: 'server:' + PROVIDER + ':claim',
						params: { detailId: task.id },
					})
					if (!result || typeof result !== 'object' || result.refresh !== false) {
						// 认领成功后任务离开列表，详情页一并退回，避免悬空空态。
						closeTask()
						await load(false)
					}
				} catch (error) {
					// 认领失败：toast 由宿主透出，这里只解锁按钮，详情保持打开。
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

			function renderRow(task) {
				return h('div', { class: 'ymc-claim-row', key: task.id }, [
					h('div', { class: 'ymc-claim-main', onClick: () => openTask(task) }, [
						h('span', { class: 'ymc-claim-title' }, String(task.title || '未命名任务')),
						task.summary
							? h('span', { class: 'ymc-claim-sub' }, String(task.summary))
							: null,
					]),
					task.dueText
						? h('span', { class: 'ymc-claim-badge' }, String(task.dueText))
						: null,
					h(
						'button',
						{
							class: 'ymc-btn primary',
							disabled: busyId.value === task.id,
							onClick: () => void claim(task),
						},
						busyId.value === task.id ? '认领中…' : '认领任务',
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
							disabled: busyId.value === task.id,
							onClick: () => void claim(task),
						},
						busyId.value === task.id ? '认领中…' : '认领任务',
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

				return h('div', { class: 'ymc-claim' }, [
					h('button', { class: 'ymc-dt-back', onClick: closeTask }, '← 返回列表'),
					h('div', { class: 'ymc-dt-card' }, [
						h('div', { class: 'ymc-dt-head' }, [
							h('h2', { class: 'ymc-dt-title' }, String(task.title || '未命名任务')),
							h(
								'span',
								{ class: 'ymc-claim-badge' },
								String(task.statusText || task.statusCode || '未知状态'),
							),
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
					children.push(h('div', { class: 'ymc-claim-skeleton' }))
					return h('div', { class: 'ymc-claim' }, children)
				}
				if (state.error || !state.project) {
					children.push(
						h('div', { class: 'ymc-claim-error' }, [
							'项目详情加载失败。',
							h('p', [
								h('button', { class: 'ymc-btn', onClick: closeProject }, '返回'),
							]),
						]),
					)
					return h('div', { class: 'ymc-claim' }, children)
				}

				const project = state.project
				children.push(
					h('div', { class: 'ymc-dt-card' }, [
						h('div', { class: 'ymc-dt-head' }, [
							h('h2', { class: 'ymc-dt-title' }, String(project.name || '未命名项目')),
							h(
								'span',
								{ class: 'ymc-claim-badge' },
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
									{ class: 'ymc-claim-list' },
									state.tasks.map((task) =>
										h('div', { class: 'ymc-claim-row', key: task.id }, [
											h(
												'div',
												{ class: 'ymc-claim-main', onClick: () => openTask(task, task) },
												[
													h(
														'span',
														{ class: 'ymc-claim-title' },
														String(task.title || '未命名任务'),
													),
													task.dueText
														? h('span', { class: 'ymc-claim-sub' }, String(task.dueText))
														: null,
												],
											),
											h(
												'span',
												{ class: 'ymc-claim-badge' },
												String(task.statusText || task.statusCode || '未知状态'),
											),
										]),
									),
								)
							: h('div', { class: 'ymc-claim-empty' }, '项目下还没有任务。'),
					]),
				)
				return h('div', { class: 'ymc-claim' }, children)
			}

			return () => {
				if (projectState.value) {
					return renderProject(projectState.value)
				}
				if (detailTaskId.value) {
					if (detailTask.value) {
						return renderTaskDetail(detailTask.value)
					}
					return h('div', { class: 'ymc-claim' }, [
						h('button', { class: 'ymc-dt-back', onClick: closeTask }, '← 返回列表'),
						h('div', { class: 'ymc-claim-empty' }, '任务已不在列表中。'),
					])
				}

				const toolbar = h('div', { class: 'ymc-claim-toolbar' }, [
					h(
						'span',
						{ class: 'ymc-claim-count' },
						tasks.value.length ? `${tasks.value.length} 项可认领` : '',
					),
					h('div', { class: 'spacer' }),
					h(
						'button',
						{ class: 'ymc-claim-refresh', onClick: () => void load(false) },
						refreshing.value ? [h('span', { class: 'dot-spin' }), '刷新中'] : '刷新',
					),
				])

				if (loading.value) {
					return h('div', { class: 'ymc-claim' }, [
						toolbar,
						h('div', { class: 'ymc-claim-list' }, [
							h('div', { class: 'ymc-claim-skeleton' }),
							h('div', { class: 'ymc-claim-skeleton' }),
							h('div', { class: 'ymc-claim-skeleton' }),
						]),
					])
				}
				if (errored.value) {
					return h('div', { class: 'ymc-claim' }, [
						toolbar,
						h('div', { class: 'ymc-claim-error' }, [
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
				if (!tasks.value.length) {
					return h('div', { class: 'ymc-claim' }, [
						toolbar,
						h('div', { class: 'ymc-claim-empty' }, '暂时没有可认领的任务。'),
					])
				}
				return h('div', { class: 'ymc-claim' }, [
					toolbar,
					h('div', { class: 'ymc-claim-list' }, tasks.value.map(renderRow)),
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
