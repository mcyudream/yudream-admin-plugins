<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaAlert, FaButton, FaCard, FaInput, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTextarea, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelExtra } from '../../api/api-extra'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'
import { errorMessage } from '../../composables/utils'

/**
 * 实例设置（对标 MCSM InstanceDetail / TermConfig / Rcon / Ping 对话框集合）：
 * 启动命令、环境变量、Java 运行时（镜像目录）、RCON、Ping、终端展示配置 → 写入实例 spec/config。
 */
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const extra = createMcPanelExtra(props.sdk)
const toast = useFaToast()
const route = useRoute()
const router = useRouter()

const canManage = computed(() => accountHasPermission(props.sdk.account, MCPANEL_PERMISSION.manage))
const instanceId = computed(() => String(route.params.id ?? ''))

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const instance = ref<Record<string, unknown> | null>(null)

/** Java 运行时镜像目录（管理端维护的内部名 + 多标签）。 */
const imageOptions = ref<Array<{ id: string, name: string, primaryImage: string, tags: string[], javaVersion?: string }>>([])
const imageDirectoryId = ref('')
const imageTag = ref('')

const imageDirectoryOptions = computed(() => [
  { label: '（不修改）', value: '' },
  ...imageOptions.value.map(item => ({
    label: `${item.name}${item.javaVersion ? ` · ${item.javaVersion}` : ''}`,
    value: item.id,
  })),
])

const selectedDirectory = computed(() =>
  imageOptions.value.find(item => item.id === imageDirectoryId.value) ?? null)

const imageTagOptions = computed(() => (selectedDirectory.value?.tags ?? [])
  .map(tag => ({ label: tag, value: tag })))

const resolvedImage = computed(() => {
  if (!selectedDirectory.value) {
    return ''
  }
  return imageTag.value || selectedDirectory.value.primaryImage
})

const imageDirty = computed(() =>
  Boolean(instance.value?.image) && resolvedImage.value !== ''
  && resolvedImage.value !== String(instance.value?.image ?? ''))

async function loadImageOptions() {
  try {
    const result = await extra.dockerImageOptions() as {
      records?: Array<{ id?: string, name?: string, primaryImage?: string, tags?: string[], javaVersion?: string }>
    }
    imageOptions.value = (result.records ?? []).map(item => ({
      id: String(item.id ?? ''),
      name: String(item.name ?? item.id ?? ''),
      primaryImage: String(item.primaryImage ?? ''),
      tags: Array.isArray(item.tags) ? item.tags.map(String) : [],
      javaVersion: String(item.javaVersion ?? ''),
    }))
    // 当前镜像反查目录：优先标签精确命中，再按 primaryImage 前缀。
    const current = String(instance.value?.image ?? '')
    const direct = imageOptions.value.find(item => item.tags.includes(current))
      ?? imageOptions.value.find(item => item.primaryImage && current.startsWith(item.primaryImage.split(':')[0]))
    if (direct) {
      imageDirectoryId.value = direct.id
      imageTag.value = direct.tags.includes(current) ? current : direct.primaryImage
    }
  }
  catch {
    imageOptions.value = []
  }
}

watch(imageDirectoryId, () => {
  // 切目录时标签回落到该目录首选镜像。
  imageTag.value = selectedDirectory.value?.primaryImage ?? ''
})

const form = reactive({
  name: '',
  command: '',
  memoryMb: 2048,
  cpuMillis: 1000,
  diskMb: 10240,
  remark: '',
  envText: '',
  rconHost: '',
  rconPort: 25575,
  rconPassword: '',
  pingAddress: '',
  pingPort: 25565,
  terminalFontSize: 13,
  terminalTimestamp: true,
  p2pEnabled: false,
  p2pWhitelistText: '',
})

/** 事件触发型任务（对标 MCSM eventTask）：面板侧语义，运行中可随时保存。 */
const eventTask = reactive({ autoRestart: false, autoStart: false })
const savingEventTask = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const detail = await extra.instanceDetail(instanceId.value) as Record<string, unknown>
    instance.value = detail
    const config = (detail.config ?? {}) as Record<string, unknown>
    form.name = String(detail.name ?? '')
    form.command = Array.isArray(detail.command) ? (detail.command as string[]).join(' ') : String(detail.command ?? '')
    form.memoryMb = Number(detail.memoryMb ?? 2048)
    form.cpuMillis = Number(detail.cpuMillis ?? 1000)
    form.diskMb = Number(detail.diskMb ?? 10240)
    form.remark = String(detail.remark ?? '')
    const env = (detail.env ?? {}) as Record<string, string>
    form.envText = Object.entries(env).map(([k, v]) => `${k}=${v}`).join('\n')
    form.rconHost = String(config.rconHost ?? '')
    form.rconPort = Number(config.rconPort ?? 25575)
    form.rconPassword = String(config.rconPassword ?? '')
    form.pingAddress = String(config.pingAddress ?? '')
    form.pingPort = Number(config.pingPort ?? 25565)
    form.terminalFontSize = Number(config.terminalFontSize ?? 13)
    form.terminalTimestamp = config.terminalTimestamp !== false
    eventTask.autoRestart = detail.autoRestart === true
    eventTask.autoStart = detail.autoStart === true
    form.p2pEnabled = detail.p2pEnabled === true
    form.p2pWhitelistText = Array.isArray(detail.p2pWhitelist)
      ? (detail.p2pWhitelist as unknown[]).map(item => String(item)).join('\n')
      : ''
  }
  catch (e) {
    error.value = errorMessage(e, '加载实例设置失败')
  }
  finally {
    loading.value = false
  }
}

async function save() {
  if (!canManage.value || saving.value || !instance.value) {
    return
  }
  saving.value = true
  try {
    const env: Record<string, string> = {}
    form.envText.split('\n').forEach((line) => {
      const idx = line.indexOf('=')
      if (idx > 0) {
        env[line.slice(0, idx).trim()] = line.slice(idx + 1)
      }
    })
    const prevConfig = (instance.value.config ?? {}) as Record<string, unknown>
    const nextImage = resolvedImage.value || String(instance.value.image ?? '')
    await extra.updateInstance(instanceId.value, {
      ...(instance.value as Record<string, unknown>),
      id: instanceId.value,
      name: form.name.trim(),
      command: form.command.trim().split(/\s+/).filter(Boolean),
      image: nextImage,
      memoryMb: Number(form.memoryMb),
      cpuMillis: Number(form.cpuMillis),
      diskMb: Number(form.diskMb),
      remark: form.remark,
      p2pEnabled: form.p2pEnabled,
      p2pWhitelist: form.p2pWhitelistText
        .split(/[\s,，]+/)
        .map(item => item.trim())
        .filter(Boolean),
      env,
      config: {
        ...prevConfig,
        rconHost: form.rconHost.trim(),
        rconPort: Number(form.rconPort),
        rconPassword: form.rconPassword,
        pingAddress: form.pingAddress.trim(),
        pingPort: Number(form.pingPort),
        terminalFontSize: Number(form.terminalFontSize),
        terminalTimestamp: form.terminalTimestamp,
      },
    })
    toast.success(`实例设置已保存${imageDirty.value ? '；镜像已更新，下次启动按新 Java 运行时重建容器' : '（运行中实例需先停止才能改规格）'}`)
    void load()
    void loadImageOptions()
  }
  catch (e) {
    toast.error(errorMessage(e, '保存失败：运行中实例禁止改配置，请先停止'))
  }
  finally {
    saving.value = false
  }
}

/** 事件任务开关独立保存：纯面板侧字段，不受「运行中禁改规格」限制。 */
async function saveEventTask() {
  if (!canManage.value || savingEventTask.value) {
    return
  }
  savingEventTask.value = true
  try {
    await extra.saveEventTask(instanceId.value, eventTask.autoRestart, eventTask.autoStart)
    toast.success('事件任务已保存')
    void load()
  }
  catch (e) {
    toast.error(errorMessage(e, '事件任务保存失败'))
  }
  finally {
    savingEventTask.value = false
  }
}

watch(() => route.params.id, () => {
  void load().then(() => void loadImageOptions())
})
onMounted(() => {
  void load().then(() => void loadImageOptions())
})
</script>

<template>
  <FaPageHeader :title="`实例设置 · ${instance?.name || instanceId}`" description="启动命令、环境变量、RCON / Ping、终端展示（对标 MCSM 实例设置）">
    <FaButton variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/instances/${instanceId}`)">
      返回终端
    </FaButton>
    <FaButton v-if="canManage" :loading="saving" @click="save">
      保存设置
    </FaButton>
  </FaPageHeader>
  <FaPageMain v-loading="loading">
    <FaAlert v-if="error" variant="destructive" class="mb-4">
  <template #description>
      {{ error }}
  </template>
    </FaAlert>
    <div class="mcp-grid-2">
      <FaCard title="基础与启动">
        <div class="mcp-form">
          <label class="mcp-form-item"><span class="mcp-form-label">名称</span><FaInput v-model="form.name" class="mcp-w-full" /></label>
          <label class="mcp-form-item"><span class="mcp-form-label">启动命令</span><FaTextarea v-model="form.command" :rows="2" class="mcp-w-full mcp-mono" /></label>
          <div class="mcp-grid-3">
            <label class="mcp-form-item"><span class="mcp-form-label">内存 MB</span><FaInput v-model="form.memoryMb" type="number" class="mcp-w-full" /></label>
            <label class="mcp-form-item"><span class="mcp-form-label">CPU mCPU</span><FaInput v-model="form.cpuMillis" type="number" class="mcp-w-full" /></label>
            <label class="mcp-form-item"><span class="mcp-form-label">磁盘 MB</span><FaInput v-model="form.diskMb" type="number" class="mcp-w-full" /></label>
          </div>
          <label class="mcp-form-item"><span class="mcp-form-label">环境变量（每行 KEY=VALUE）</span><FaTextarea v-model="form.envText" :rows="4" class="mcp-w-full mcp-mono" /></label>
          <label class="mcp-form-item"><span class="mcp-form-label">备注</span><FaInput v-model="form.remark" class="mcp-w-full" /></label>
        </div>
      </FaCard>
      <FaCard title="事件触发型任务">
        <div class="mcp-form">
          <label class="flex items-start gap-3 text-sm">
            <FaSwitch v-model="eventTask.autoRestart" />
            <span>
              <b>自动重启</b>
              <span class="mcp-form-hint block">
                若实例在未经面板操作的情况下变为非运行状态（崩溃、意外退出），将立刻发起一次启动实例操作。
                面板发起的停止 / 重启 / 强杀 / 删除，以及控制台输入 stop，都不会触发自动重启。
              </span>
            </span>
          </label>
          <label class="flex items-start gap-3 text-sm">
            <FaSwitch v-model="eventTask.autoStart" />
            <span>
              <b>自动启动</b>
              <span class="mcp-form-hint block">
                只要远程节点处于运行（与面板连接）状态，就自动发起一次启动实例操作。
                如果将节点程序设为开机自启，则可用于开机自启实例。
              </span>
            </span>
          </label>
          <span class="mcp-form-hint">
            自动拉起后若实例持续快速崩溃（60 秒内连续 3 次），会暂停自动重启并在审计中提示；手动启动一次即恢复。
          </span>
          <div v-if="canManage" class="flex justify-end">
            <FaButton :loading="savingEventTask" @click="saveEventTask">
              保存事件任务
            </FaButton>
          </div>
        </div>
      </FaCard>
      <FaCard title="Java 运行时（换版本 / 镜像源）">
        <div class="mcp-form">
          <label class="mcp-form-item">
            <span class="mcp-form-label">运行时目录</span>
            <FaSelect v-model="imageDirectoryId" :options="imageDirectoryOptions" class="mcp-w-full" placeholder="选择 Java 版本对应的运行时" />
          </label>
          <label v-if="selectedDirectory?.tags?.length" class="mcp-form-item">
            <span class="mcp-form-label">标签 / 拉取地址</span>
            <FaSelect v-model="imageTag" :options="imageTagOptions" class="mcp-w-full" />
          </label>
          <div class="mcp-form-item">
            <span class="mcp-form-label">生效镜像</span>
            <div class="rounded-lg border bg-muted/40 px-3 py-2 font-mono text-xs break-all">
              {{ resolvedImage || String(instance?.image ?? '-') }}
              <FaTag v-if="imageDirty" variant="secondary" class="ml-2">
                已修改
              </FaTag>
            </div>
            <p class="mcp-form-hint">
              换 Java 版本 = 换 JRE 镜像。运行中的实例需先停止，保存后下次启动按新镜像重建容器；
              数据目录（存档/配置）不受影响。
            </p>
          </div>
        </div>
      </FaCard>
      <FaCard title="P2P 直连（启动器无感接入）">
        <div class="mcp-form">
          <label class="flex items-start gap-3 text-sm">
            <FaSwitch v-model="form.p2pEnabled" />
            <span>
              允许玩家经启动器 P2P 直连本实例（面板不中转玩家流量）
            </span>
          </label>
          <label class="mcp-form-item">
            <span class="mcp-form-label">白名单（选填，每行或逗号分隔用户 ID）</span>
            <FaTextarea v-model="form.p2pWhitelistText" :rows="2" class="w-full" placeholder="留空 = 所有玩家可连" />
          </label>
          <span class="mcp-form-hint">
            前置条件：① 面板设置 →「启动器 P2P 直连」已开启并签发票据；② 节点程序支持 p2p.* 能力；
            ③ 实例处于运行中。玩家侧**无感**：启动器点「连接」即自动开会话与建隧道，玩家不需要任何配置或入口；
            当前传输为直连（节点公网可达或已做端口映射），打洞落地后 NAT 场景自动可用。
          </span>
        </div>
      </FaCard>

      <FaCard title="RCON / Ping / 终端">
        <div class="mcp-form">
          <label class="mcp-form-item"><span class="mcp-form-label">RCON 主机</span><FaInput v-model="form.rconHost" class="mcp-w-full" placeholder="127.0.0.1" /></label>
          <div class="mcp-grid-2">
            <label class="mcp-form-item"><span class="mcp-form-label">RCON 端口</span><FaInput v-model="form.rconPort" type="number" class="mcp-w-full" /></label>
            <label class="mcp-form-item"><span class="mcp-form-label">RCON 密码</span><FaInput v-model="form.rconPassword" type="password" class="mcp-w-full" /></label>
          </div>
          <div class="mcp-grid-2">
            <label class="mcp-form-item"><span class="mcp-form-label">Ping 地址</span><FaInput v-model="form.pingAddress" class="mcp-w-full" /></label>
            <label class="mcp-form-item"><span class="mcp-form-label">Ping 端口</span><FaInput v-model="form.pingPort" type="number" class="mcp-w-full" /></label>
          </div>
          <div class="mcp-grid-2">
            <label class="mcp-form-item"><span class="mcp-form-label">终端字号</span><FaInput v-model="form.terminalFontSize" type="number" class="mcp-w-full" /></label>
            <label class="mcp-form-item"><span class="mcp-form-label">时间戳</span><FaSwitch v-model="form.terminalTimestamp" /></label>
          </div>
          <p class="mcp-form-hint">
            RCON/Ping 为实例级配置存储；面板侧 RCON 协议发送可在节点能力具备后接入。当前控制台命令仍走容器 stdin。
          </p>
        </div>
      </FaCard>
    </div>
  </FaPageMain>
</template>
