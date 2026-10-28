import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { DeploymentJob } from '../api/deployment-api'
import { useYdModal, useYdToast } from '@yudream/components'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { createDeploymentApi } from '../api/deployment-api'
import { createMcPanelExtra } from '../api/api-extra'
import { accountHasPermission, MCPANEL_PERMISSION } from './permissions'
import { errorMessage } from './utils'

export const deploymentLabels: Record<DeploymentJob['state'], string> = {
  running: '执行中', done: '已完成，可回退', 'rolled-back': '已回退',
  'rolling-back': '回退中', 'recovery-required': '需要恢复', failed: '失败',
}
export const deploymentInProgress = (job: DeploymentJob) => ['running', 'rolling-back'].includes(job.state)

/** 三个独立部署页面共用权限、实例上下文、持久任务观察与回退行为。 */
export function useInstanceDeployments(sdk: YuDreamPluginSdk) {
  const route = useRoute()
  const api = createDeploymentApi(sdk)
  const extra = createMcPanelExtra(sdk)
  const toast = useYdToast()
  const modal = useYdModal()
  const id = computed(() => String(route.params.id ?? ''))
  const canManage = computed(() => accountHasPermission(sdk.account, MCPANEL_PERMISSION.manage))
  const instance = ref<Record<string, unknown> | null>(null)
  const loading = ref(false)
  const busy = ref(false)
  const error = ref('')
  const historyError = ref('')
  const historyLoading = ref(false)
  const history = ref<DeploymentJob[]>([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(10)
  const activeJob = ref<DeploymentJob | null>(null)
  const stopped = computed(() => ['created', 'exited', 'stopped'].includes(String(instance.value?.liveState ?? instance.value?.state)))
  const canSubmit = computed(() => canManage.value && stopped.value && !instance.value?.maintenanceJobId && (!activeJob.value || !deploymentInProgress(activeJob.value)))
  const title = computed(() => String(instance.value?.name ?? id.value))
  let generation = 0
  let historyRequest = 0
  let polling = false

  async function loadHistory() {
    const token = generation
    const request = ++historyRequest
    historyLoading.value = true
    try {
      const result = await api.list(id.value, page.value, size.value)
      if (token !== generation || request !== historyRequest) return
      const last = Math.max(1, Math.ceil(result.total / size.value))
      if (page.value > last) { page.value = last; return }
      history.value = result.records
      total.value = result.total
      historyError.value = ''
    }
    catch (cause) {
      if (token === generation && request === historyRequest) historyError.value = errorMessage(cause, '部署记录读取失败')
    }
    finally {
      if (token === generation && request === historyRequest) historyLoading.value = false
    }
  }

  async function load() {
    const token = generation
    loading.value = true
    error.value = ''
    try {
      const [detail] = await Promise.all([extra.instanceDetail(id.value), loadHistory()])
      if (token === generation) instance.value = detail as Record<string, unknown>
    }
    catch (cause) {
      if (token === generation) error.value = errorMessage(cause, '实例信息读取失败')
    }
    finally {
      if (token === generation) loading.value = false
    }
  }

  async function operate<T>(action: (current: string) => Promise<T>, apply: (result: T) => void, token = generation) {
    if (busy.value || !canManage.value || token !== generation) return
    busy.value = true
    error.value = ''
    try {
      const result = await action(id.value)
      if (token === generation) apply(result)
    }
    catch (cause) {
      if (token === generation) error.value = errorMessage(cause, '操作失败，请核对实例和节点状态')
    }
    finally {
      if (token === generation) busy.value = false
    }
  }

  function acceptJob(job: DeploymentJob) {
    activeJob.value = job
    void loadHistory()
  }

  function confirm(title: string, content: string, action: (current: string) => Promise<DeploymentJob>, applied?: () => void) {
    const token = generation
    modal.confirm({ title, content, onConfirm: () => operate(action, (job) => {
      acceptJob(job)
      applied?.()
      toast.success('任务已受理')
    }, token) })
  }

  function rollback(job: DeploymentJob) {
    confirm('回退此次部署', '恢复本次替换前的文件，并移除本次新增的文件；部署后对这些文件的修改也会被撤销。实例须停止，请先回退较新的部署。', current => api.rollback(current, job.id))
  }

  async function poll() {
    if (polling || document.hidden || ![activeJob.value, ...history.value].some(job => job && deploymentInProgress(job))) return
    const token = generation
    polling = true
    try {
      const job = activeJob.value
      if (job && deploymentInProgress(job)) {
        const result = await api.status(job.targetId, job.id)
        if (token !== generation) return
        activeJob.value = result
        if (!deploymentInProgress(result)) {
          await load()
          if (token === generation) toast[result.state === 'done' || result.state === 'rolled-back' ? 'success' : 'warning'](deploymentLabels[result.state])
        }
      }
      else {
        await loadHistory()
        if (token === generation && !history.value.some(deploymentInProgress)) await load()
      }
    }
    catch (cause) {
      if (token === generation) error.value = errorMessage(cause, '进度读取失败，后台任务仍会继续')
    }
    finally { polling = false }
  }

  watch([page, size], ([, nextSize], [, previousSize]) => {
    if (nextSize !== previousSize && page.value !== 1) { page.value = 1; return }
    void loadHistory()
  })
  watch(id, () => {
    generation++
    instance.value = null
    activeJob.value = null
    history.value = []
    historyError.value = ''
    total.value = 0
    page.value = 1
    busy.value = false
    void load()
  }, { immediate: true })
  const timer = setInterval(() => void poll(), 3000)
  onBeforeUnmount(() => { generation++; clearInterval(timer) })

  return { id, api, instance, title, canManage, stopped, canSubmit, loading, busy, error, history, historyError, historyLoading, total, page, size, activeJob, load, loadHistory, operate, acceptJob, confirm, rollback }
}
