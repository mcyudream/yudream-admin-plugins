<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaAlert, FaButton, FaCard, FaInput, FaModal, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTable, FaTag, useFaModal, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelExtra } from '../../api/api-extra'
import { useBackupTargets } from '../../composables/useBackupTargets'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'
import { errorMessage, formatDateTime } from '../../composables/utils'

/** 实例计划任务（对标 MCSM Schedule：间隔 / 每日 + 控制台命令 / 本地备份 / 异地备份）。 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const extra = createMcPanelExtra(props.sdk)
const toast = useFaToast()
const modal = useFaModal()
const route = useRoute()
const router = useRouter()
const backupTargets = useBackupTargets(props.sdk)

const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const canDelete = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.delete))
const instanceId = computed(() => String(route.params.id ?? ''))

const loading = ref(false)
const rows = ref<Record<string, unknown>[]>([])
const pager = reactive({ page: 1, size: 20, total: 0 })

const formOpen = ref(false)
const editing = ref<Record<string, unknown> | null>(null)
const submitting = ref(false)
const formError = ref('')
const form = reactive({
  id: '',
  name: '',
  type: 'interval' as string,
  action: 'command' as string,
  intervalSeconds: 3600,
  dailyTime: '04:00',
  cron: '0 4 * * *',
  event: 'instance.start',
  payload: 'save-all flush',
  enabled: true,
})

const columns: TableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'name', header: '名称', minWidth: 120 },
  { accessorKey: 'payload', header: '命令 / 目标', minWidth: 180 },
  { id: 'time', header: '触发', minWidth: 140 },
  { accessorKey: 'count', header: '次数', width: 80, align: 'center' },
  { id: 'status', header: '状态', width: 90 },
  { id: 'next', header: '下次执行', width: 160 },
  { id: 'operation', header: '操作', width: 200 },
]

async function load() {
  if (!instanceId.value) {
    return
  }
  loading.value = true
  try {
    const page = await extra.pageSchedules(instanceId.value, pager.page, pager.size) as {
      records?: Record<string, unknown>[], total?: number
    }
    rows.value = page.records ?? []
    pager.total = Number(page.total ?? 0)
  }
  catch (e) {
    toast.error(errorMessage(e, '加载计划任务失败'))
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.id = ''
  form.name = ''
  form.type = 'interval'
  form.action = 'command'
  form.intervalSeconds = 3600
  form.dailyTime = '04:00'
  form.payload = 'save-all flush'
  form.enabled = true
  formError.value = ''
  formOpen.value = true
}

function openEdit(row: Record<string, unknown>) {
  editing.value = row
  form.id = String(row.id ?? '')
  form.name = String(row.name ?? '')
  form.type = (String(row.type) === 'daily' ? 'daily' : 'interval')
  const action = String(row.action)
  form.action = (action === 'offsite-backup' || action === 'local-backup') ? action : 'command'
  form.intervalSeconds = Number(row.intervalSeconds ?? 3600)
  form.dailyTime = String(row.dailyTime ?? '04:00')
  form.payload = String(row.payload ?? '')
  form.enabled = row.enabled !== false
  formError.value = ''
  formOpen.value = true
}

async function submit(done: () => void) {
  const needPayload = form.action !== 'local-backup'
  if (!form.name.trim() || (needPayload && !form.payload.trim())) {
    formError.value = form.action === 'offsite-backup'
      ? '请填写名称并选择异地目标'
      : form.action === 'local-backup' ? '请填写任务名称' : '请填写名称与命令'
    return
  }
  if (form.action === 'offsite-backup' && !/^[a-z0-9][a-z0-9-]{0,63}$/.test(form.payload.trim())) {
    formError.value = '异地目标编码须为小写字母/数字/连字符'
    return
  }
  submitting.value = true
  formError.value = ''
  try {
    await extra.saveSchedule({
      id: form.id || undefined,
      instanceId: instanceId.value,
      name: form.name.trim(),
      type: form.type,
      action: form.action,
      intervalSeconds: form.type === 'interval' ? Number(form.intervalSeconds) : 0,
      dailyTime: form.type === 'daily' ? form.dailyTime : '',
      cron: form.type === 'cron' ? form.cron.trim() : '',
      event: form.type === 'event' ? form.event : '',
      payload: form.payload.trim(),
      enabled: form.enabled,
    })
    done()
    toast.success('计划任务已保存')
    void load()
  }
  catch (e) {
    formError.value = errorMessage(e, '保存失败')
  }
  finally {
    submitting.value = false
  }
}

function confirmDelete(row: Record<string, unknown>) {
  modal.confirm({
    title: '删除计划任务',
    content: `确认删除「${row.name}」？`,
    confirmButtonText: '删除',
    onConfirm: async () => {
      try {
        await extra.deleteSchedule(String(row.id))
        toast.success('已删除')
        void load()
      }
      catch (e) {
        toast.error(errorMessage(e, '删除失败'))
      }
    },
  })
}

async function runNow(row: Record<string, unknown>) {
  try {
    await extra.runSchedule(String(row.id))
    toast.success('已触发执行')
    void load()
  }
  catch (e) {
    toast.error(errorMessage(e, '触发失败'))
  }
}

function timeText(row: Record<string, unknown>) {
  const type = String(row.type)
  if (type === 'daily') {
    return `每天 ${row.dailyTime}`
  }
  if (type === 'cron') {
    return `cron ${row.cron}`
  }
  if (type === 'event') {
    return `事件 ${row.event}`
  }
  return `每隔 ${row.intervalSeconds} 秒`
}

watch(() => route.params.id, () => void load())
onMounted(() => {
  void load()
  void backupTargets.load()
})

const ACTION_OPTIONS = [
  { label: '发送控制台命令', value: 'command' },
  { label: '本地备份（备份中心本机导出）', value: 'local-backup' },
  { label: '异地备份到指定目标', value: 'offsite-backup' },
]

/** 异地目标选项：SDK 目录可用时下拉选择；否则回落手输编码。 */
const targetOptions = computed(() => backupTargets.targets.value.map(target => ({
  label: `${target.name}（${target.code}）`,
  value: target.code,
})))
</script>

<template>
  <FaPageHeader :title="`计划任务 · ${instanceId}`" description="间隔 / 每日定时向控制台发送命令">
    <FaButton variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/instances/${instanceId}`)">
      返回终端
    </FaButton>
    <FaButton v-if="canManage" @click="openCreate">
      新建任务
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaAlert class="mb-4" title="说明">
  <template #description>
      任务由面板调度线程驱动。动作「发送控制台命令」到点向实例控制台发送命令（如 save-all）；动作「本地备份」到点经宿主数据备份中心做本机导出；动作「异地备份」到点把实例数据整包推送到宿主数据备份中心配置的异地目标，可在备份中心任务记录与实例备份列表查看进度。
  </template>
    </FaAlert>
    <FaCard>
      <FaTable
        v-loading="loading"
        :columns="columns"
        :data="rows"
        row-key="id"
        table-root-class="rounded-lg overflow-hidden"
        table-class="min-w-[900px]"
        border
        stripe
        empty-text="暂无计划任务"
      >
        <template #cell-time="{ row }">
          {{ timeText(row.original) }}
        </template>
        <template #cell-payload="{ row }">
          <span v-if="String(row.original.action) === 'offsite-backup'" class="flex items-center gap-1">
            <FaTag variant="secondary">异地备份</FaTag>
            <span class="mcp-mono">{{ row.original.payload }}</span>
          </span>
          <span v-else-if="String(row.original.action) === 'local-backup'" class="flex items-center gap-1">
            <FaTag variant="secondary">本地备份</FaTag>
          </span>
          <span v-else class="mcp-mono">{{ row.original.payload }}</span>
        </template>
        <template #cell-status="{ row }">
          <FaTag :variant="row.original.enabled === false ? 'secondary' : 'default'">
            {{ row.original.enabled === false ? '停用' : '启用' }}
          </FaTag>
        </template>
        <template #cell-next="{ row }">
          {{ formatDateTime(row.original.nextRunAt as number) }}
        </template>
        <template #cell-operation="{ row }">
          <div class="mcp-op-cell">
            <FaButton v-if="canManage" size="sm" variant="outline" @click="runNow(row.original)">
              立即执行
            </FaButton>
            <FaButton v-if="canManage" size="sm" variant="outline" @click="openEdit(row.original)">
              编辑
            </FaButton>
            <FaButton v-if="canDelete" size="sm" variant="destructive" @click="confirmDelete(row.original)">
              删除
            </FaButton>
          </div>
        </template>
      </FaTable>
    </FaCard>

    <FaModal
      v-model="formOpen"
      :title="editing ? '编辑计划任务' : '新建计划任务'"
      :show-cancel-button="true"
      :confirm-button-text="editing ? '保存' : '创建'"
      :confirm-button-loading="submitting"
      class="max-w-[min(32rem,calc(100vw-2rem))]"
      :before-close="(action: 'confirm' | 'cancel' | 'close', done: () => void) => action === 'confirm' ? submit(done) : done()"
    >
      <div class="mcp-form">
        <label class="mcp-form-item">
          <span class="mcp-form-label">名称</span>
          <FaInput v-model="form.name" class="mcp-w-full" placeholder="每日备份刷新" />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">触发类型</span>
          <FaSelect
            v-model="form.type"
            :options="[
              { label: '间隔执行', value: 'interval' },
              { label: '每天固定时间', value: 'daily' },
              { label: 'Cron 表达式', value: 'cron' },
              { label: '实例事件', value: 'event' },
            ]"
            class="mcp-w-full"
          />
        </label>
        <label class="mcp-form-item">
          <span class="mcp-form-label">动作</span>
          <FaSelect v-model="form.action" :options="ACTION_OPTIONS" class="mcp-w-full" />
          <span v-if="form.action === 'offsite-backup'" class="mcp-form-hint">
            到点把本实例的世界/配置数据整包推送到宿主「数据备份中心」配置的异地目标。
          </span>
          <span v-else-if="form.action === 'local-backup'" class="mcp-form-hint">
            到点经宿主「数据备份中心」做一次本机导出，在备份中心可下载归档。
          </span>
        </label>
        <label v-if="form.type === 'interval'" class="mcp-form-item">
          <span class="mcp-form-label">间隔（秒）</span>
          <FaInput v-model="form.intervalSeconds" type="number" class="mcp-w-full" />
        </label>
        <label v-else-if="form.type === 'daily'" class="mcp-form-item">
          <span class="mcp-form-label">每天时间 HH:mm</span>
          <FaInput v-model="form.dailyTime" class="mcp-w-full" placeholder="04:00" />
        </label>
        <label v-else-if="form.type === 'cron'" class="mcp-form-item">
          <span class="mcp-form-label">Cron（分 时 日 月 周）</span>
          <FaInput v-model="form.cron" class="mcp-w-full mcp-mono" placeholder="0 4 * * *" />
          <span class="mcp-form-hint">示例：0 4 * * * 每天 04:00；*/30 * * * * 每 30 分钟</span>
        </label>
        <label v-else class="mcp-form-item">
          <span class="mcp-form-label">触发事件</span>
          <FaSelect
            v-model="form.event"
            :options="[
              { label: '实例启动', value: 'instance.start' },
              { label: '实例退出', value: 'instance.exit' },
            ]"
            class="mcp-w-full"
          />
          <span class="mcp-form-hint">事件任务在状态变化时由面板触发（如启动后广播）。</span>
        </label>
        <label v-if="form.action !== 'local-backup'" class="mcp-form-item">
          <span class="mcp-form-label">{{ form.action === 'offsite-backup' ? '异地目标' : '控制台命令' }}</span>
          <template v-if="form.action === 'offsite-backup' && backupTargets.catalogReady.value">
            <FaSelect v-model="form.payload" :options="targetOptions" class="mcp-w-full" />
            <span class="mcp-form-hint">目标来自宿主「数据备份中心」，如需新增请先在备份中心配置。</span>
          </template>
          <template v-else>
            <FaInput
              v-model="form.payload"
              class="mcp-w-full mcp-mono"
              :placeholder="form.action === 'offsite-backup' ? 'nas-webdav' : 'save-all flush'"
            />
            <span v-if="form.action === 'offsite-backup'" class="mcp-form-hint">
              无法读取目标目录（宿主 SDK 过旧或暂无启用目标），请手输目标编码。
            </span>
          </template>
        </label>
        <label class="flex items-center gap-2 text-sm">
          <FaSwitch v-model="form.enabled" />
          启用
        </label>
        <FaAlert v-if="formError" variant="destructive" title="无法保存">
  <template #description>
          {{ formError }}
  </template>
        </FaAlert>
      </div>
    </FaModal>
  </FaPageMain>
</template>
