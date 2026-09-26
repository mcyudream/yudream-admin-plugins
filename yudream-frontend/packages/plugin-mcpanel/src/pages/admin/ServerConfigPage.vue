<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaAlert, FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaSelect, FaSwitch, FaTabs, FaTag, FaTextarea, useFaToast } from '@yudream/components'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createMcPanelExtra } from '../../api/api-extra'
import {
  CONFIG_GROUP_META,
  CONFIG_GROUP_ORDER,
  configDocMap,
  filterPanelKeys,
  groupForUnknownKey,
  typeLabel,
  type McpConfigFieldDoc,
  type McpConfigGroup,
} from '../../composables/serverConfigDocs'
import { MCPANEL_PERMISSION, accountHasPermission } from '../../composables/permissions'
import { errorMessage } from '../../composables/utils'
import { decodeBase64Utf8Lenient, encodeUtf8ToBase64, eulaAcceptedFromText, withTimeout } from '../../utils/fileContent'
import { readRawChunk, type FileChunk } from '../../utils/textFileAccess'

/** 服务端/代理配置：分组说明 + 按类型控件；过滤面板内部 install* 键。
 * 代理实例（velocity/bungee）按 kind 切换到 velocity.toml / config.yml 结构化编辑。 */
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
const path = ref('')
const instanceKind = ref('')
const parseError = ref('')
const content = ref('')
const rawMode = ref(false)
const properties = reactive<Record<string, string>>({})
const baseline = reactive<Record<string, string>>({})
const filter = ref('')
const groupFilter = ref<'all' | McpConfigGroup>('all')
const showOnlyChanged = ref(false)

const docs = computed(() => configDocMap(path.value, instanceKind.value))

/** 结构化可编辑的扩展名：properties（平面）+ yml/yaml/toml（点路径扁平）。 */
function isStructuredFile(target: string): boolean {
  return /\.(properties|ya?ml|toml)$/.test(target.toLowerCase())
}

const MC_CONFIG_FILES = [
  { label: 'server.properties', path: 'server.properties', icon: 'i-ri:file-settings-line', desc: 'MC 服务端核心配置：端口、玩法、世界、性能' },
  { label: 'spigot.yml', path: 'spigot.yml', icon: 'i-ri:file-list-3-line', desc: 'Spigot/Paper 性能与行为（需节点已有该文件）' },
  { label: 'bukkit.yml', path: 'bukkit.yml', icon: 'i-ri:file-list-3-line', desc: 'Bukkit 插件与世界加载' },
  { label: 'config/paper-global.yml', path: 'config/paper-global.yml', icon: 'i-ri:file-list-3-line', desc: 'Paper 全局配置（新版 Paper 拆分自 paper.yml）' },
  { label: 'config/paper-world-defaults.yml', path: 'config/paper-world-defaults.yml', icon: 'i-ri:file-list-3-line', desc: 'Paper 世界默认配置（新版 Paper 拆分自 paper.yml）' },
  { label: 'paper.yml', path: 'paper.yml', icon: 'i-ri:file-list-3-line', desc: '旧版 Paper 配置（1.19.3 前的合并格式）' },
  { label: 'ops.json', path: 'ops.json', icon: 'i-ri:shield-user-line', desc: '管理员（OP）列表 JSON' },
  { label: 'whitelist.json', path: 'whitelist.json', icon: 'i-ri:shield-check-line', desc: '白名单玩家列表' },
  { label: 'eula.txt', path: 'eula.txt', icon: 'i-ri:file-text-line', desc: 'EULA 同意标志（eula=true）' },
]

const PROXY_CONFIG_FILES: Record<string, Array<{ label: string, path: string, icon: string, desc: string }>> = {
  velocity: [
    { label: 'velocity.toml', path: 'velocity.toml', icon: 'i-ri:shuffle-line', desc: 'Velocity 代理配置：子服、转发模式、监听（TOML）' },
  ],
  bungee: [
    { label: 'config.yml', path: 'config.yml', icon: 'i-ri:shuffle-line', desc: 'BungeeCord 代理配置：子服、IP 转发（YAML；listeners 段请用原文模式）' },
  ],
}

/** 实例根目录实际存在的文件（tab 按存在过滤，不再一股脑全列）。 */
const existingFiles = ref<Set<string>>(new Set())

const CONFIG_FILES = computed(() => {
  const source = PROXY_CONFIG_FILES[instanceKind.value] ?? MC_CONFIG_FILES
  const present = source.filter(file => existingFiles.value.has(file.path))
  // eula.txt 特殊化：单独卡片处理，不进通用 tab。
  return present.filter(file => file.path !== 'eula.txt')
})

const fileTabs = computed(() => CONFIG_FILES.value.map(file => ({ label: file.label, value: file.path, icon: file.icon })))

const isStructured = computed(() => isStructuredFile(path.value))
const currentFile = computed(() => CONFIG_FILES.value.find(item => item.path === path.value))

// ---------- EULA（一键同意） ----------

/** 一键写入的 eula.txt 内容（固定 ASCII 键值 + 中文注释，整体按 UTF-8 转 base64 下发）。 */
const EULA_FILE_TEXT = 'eula=true\n# 由 YuDream 面板一键写入\n'

const eulaExists = computed(() => existingFiles.value.has('eula.txt'))
const eulaText = ref('')
const eulaAccepted = computed(() => eulaAcceptedFromText(eulaText.value))
const eulaBusy = ref(false)

/**
 * eula.txt 不参与 tab 结构化解析（不进 properties），状态必须单独读文件判定。
 * 读失败按「未同意」处理：卡片与按钮仍可用，不会谎报已同意。
 */
async function loadEulaState() {
  eulaText.value = ''
  const id = instanceId.value
  if (!id || !eulaExists.value) {
    return
  }
  try {
    const chunk = await withTimeout(extra.readFileChunk(id, 'eula.txt', 0, 4096), 10_000, 'eula.txt 读取超时') as FileChunk
    eulaText.value = decodeBase64Utf8Lenient(readRawChunk(chunk).rawBase64)
  }
  catch {
    eulaText.value = ''
  }
}

/** 实例已有 eula.txt 但未同意（eula=false）时：写入 eula=true 覆盖同名文件。 */
async function acceptEula() {
  if (!canManage.value || eulaBusy.value) {
    return
  }
  eulaBusy.value = true
  try {
    await extra.writeFile(instanceId.value, 'eula.txt', encodeUtf8ToBase64(EULA_FILE_TEXT), 'base64')
    eulaText.value = EULA_FILE_TEXT
    toast.success('已同意 Minecraft EULA（eula=true），服务端下次启动不再拦截')
    existingFiles.value.add('eula.txt')
    await loadInstanceFiles()
  }
  catch (e) {
    toast.error(errorMessage(e, '写入 eula.txt 失败'))
  }
  finally {
    eulaBusy.value = false
  }
}

type FieldRow = {
  key: string
  label: string
  desc: string
  tip?: string
  group: McpConfigGroup
  type: McpConfigFieldDoc['type']
  known: boolean
  value: string
  defaultValue?: string
  options?: Array<{ label: string, value: string }>
  dirty: boolean
}

function cleanValue(key: string): string {
  const v = properties[key]
  return v === undefined || v === null ? '' : String(v)
}

function baselineValue(key: string): string {
  const v = baseline[key]
  return v === undefined || v === null ? '' : String(v)
}

const displayRows = computed<FieldRow[]>(() => {
  const cleaned = filterPanelKeys(properties)
  const docsKeys = [...docs.value.keys()]
  const merged = [...new Set([...docsKeys, ...Object.keys(cleaned)])]
  const kw = filter.value.trim().toLowerCase()
  return merged
    .map((key) => {
      const doc = docs.value.get(key)
      const value = cleaned[key] ?? ''
      const fallbackGroup = groupForUnknownKey(path.value, instanceKind.value, key)
      return {
        key,
        label: doc?.label || key,
        desc: doc?.desc || (fallbackGroup
          ? '配置文件中的子服条目。地址为 host:port；面板识别代理后可在实例页自动绑定到面板实例。'
          : '文件中的自定义配置项。面板按键名读写，保存时保留该键。'),
        tip: doc?.tip,
        group: (doc?.group || fallbackGroup || '其他') as McpConfigGroup,
        type: doc?.type || 'text',
        known: Boolean(doc),
        value,
        defaultValue: doc?.defaultValue,
        options: doc?.options,
        dirty: cleanValue(key) !== baselineValue(key),
      }
    })
    .filter((row) => {
      if (groupFilter.value !== 'all' && row.group !== groupFilter.value) {
        return false
      }
      if (showOnlyChanged.value && !row.dirty) {
        return false
      }
      if (!kw) {
        return true
      }
      return row.key.toLowerCase().includes(kw)
        || row.label.toLowerCase().includes(kw)
        || row.desc.toLowerCase().includes(kw)
        || row.value.toLowerCase().includes(kw)
    })
})

const groupedRows = computed(() => {
  const buckets = CONFIG_GROUP_ORDER.map((group) => {
    const items = displayRows.value.filter(row => row.group === group)
    return {
      group,
      meta: CONFIG_GROUP_META[group],
      items,
    }
  }).filter(section => section.items.length > 0)
  return buckets
})

const dirtyCount = computed(() => {
  return displayRows.value.filter(row => row.dirty).length
})

const filterOptions = [
  { label: '全部分组', value: 'all' },
  ...CONFIG_GROUP_ORDER.map(g => ({ label: `${g} · ${CONFIG_GROUP_META[g].title}`, value: g })),
]

function selectOptions(row: FieldRow) {
  const base = row.options || []
  if (!row.value) {
    return base
  }
  if (base.some(opt => String(opt.value) === row.value)) {
    return base
  }
  return [{ label: `当前值 ${row.value}`, value: row.value }, ...base]
}

function setRowValue(key: string, value: string) {
  properties[key] = value
}

function resetRow(key: string) {
  properties[key] = baselineValue(key)
}

function resetAll() {
  Object.keys(properties).forEach((key) => {
    delete properties[key]
  })
  Object.entries(baseline).forEach(([key, value]) => {
    properties[key] = value
  })
  toast.success('已恢复为刚读取到的配置')
}

function selectFile(next: string) {
  if (path.value === next) {
    return
  }
  path.value = next
  rawMode.value = !isStructuredFile(next)
  void load()
}

async function loadInstanceKind() {
  if (!instanceId.value) {
    return
  }
  try {
    const detail = await extra.instanceDetail(instanceId.value) as { kind?: string }
    instanceKind.value = String(detail?.kind ?? '')
    await loadInstanceFiles()
    void loadEulaState()
    const files = CONFIG_FILES.value
    if (files.length && !files.some(file => file.path === path.value)) {
      path.value = files[0].path
      rawMode.value = !isStructuredFile(path.value)
    }
    else if (!files.length) {
      // 实例还没有任何已知配置文件：保持默认路径但进入原文空态提示。
      path.value = PROXY_CONFIG_FILES[instanceKind.value]?.[0]?.path ?? 'server.properties'
      rawMode.value = !isStructuredFile(path.value)
    }
  }
  catch {
    instanceKind.value = ''
  }
}

/** 根目录文件清单：tab 只显示真实存在的配置文件。 */
async function loadInstanceFiles() {
  try {
    const result = await extra.listFiles(instanceId.value, '', 1, 300) as {
      entries?: Array<{ name?: string }>
    }
    existingFiles.value = new Set((result?.entries ?? [])
      .map(entry => String(entry?.name ?? '')).filter(Boolean))
  }
  catch {
    existingFiles.value = new Set()
  }
}

async function load() {
  if (!instanceId.value) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    const result = await extra.serverConfig(instanceId.value, path.value) as {
      content?: string
      properties?: Record<string, string>
      path?: string
      parseError?: string
    }
    path.value = result?.path || path.value
    content.value = result?.content ?? ''
    parseError.value = result?.parseError ?? ''
    Object.keys(properties).forEach(key => delete properties[key])
    Object.keys(baseline).forEach(key => delete baseline[key])
    const filtered = filterPanelKeys(result?.properties ?? {})
    Object.entries(filtered).forEach(([key, value]) => {
      const s = String(value)
      properties[key] = s
      baseline[key] = s
    })
    if (!isStructuredFile(path.value)) {
      rawMode.value = true
    }
  }
  catch (e) {
    error.value = errorMessage(e, '读取配置失败（文件可能尚不存在，可先启动一次实例生成）')
  }
  finally {
    loading.value = false
  }
}

async function save() {
  if (!canManage.value || saving.value) {
    return
  }
  saving.value = true
  try {
    if (rawMode.value || !isStructured.value) {
      await extra.saveServerConfig(instanceId.value, path.value, {}, content.value, true)
    }
    else {
      const payload: Record<string, string> = {}
      Object.keys(properties).forEach((key) => {
        if (!key) {
          return
        }
        payload[key] = properties[key] ?? ''
      })
      await extra.saveServerConfig(instanceId.value, path.value, payload, '', false)
    }
    toast.success('配置已保存（运行中的实例改配置需重启生效）')
    void load()
  }
  catch (e) {
    toast.error(errorMessage(e, '保存失败：运行中实例可能需先停止'))
  }
  finally {
    saving.value = false
  }
}

/** 手动重新读取：配置正文 + EULA 状态（可能被文件编辑或服务端改过）。 */
function reloadAll() {
  void loadEulaState()
  void load()
}

watch(() => route.params.id, () => {
  void loadInstanceKind().then(() => void load())
})

watch(() => rawMode.value, (raw) => {
  if (!raw && !isStructured.value) {
    rawMode.value = true
  }
})

onMounted(() => {
  void loadInstanceKind().then(() => void load())
})
</script>

<template>
  <FaPageHeader
    :title="`服务端配置 · ${instanceId}`"
    description="按分组查看并修改配置，每项均附中文说明；布尔/枚举使用选择器，保存后重启实例生效"
  >
    <FaButton variant="outline" @click="router.push(`/platform/plugins/mcpanel/admin/instances/${instanceId}`)">
      返回终端
    </FaButton>
    <FaButton v-if="canManage && isStructured && !rawMode" variant="outline" :disabled="!dirtyCount" @click="resetAll">
      放弃修改
    </FaButton>
    <FaButton v-if="canManage" variant="outline" @click="reloadAll">
      重新读取
    </FaButton>
    <FaButton v-if="canManage" :loading="saving" @click="save">
      {{ dirtyCount && isStructured && !rawMode ? `保存配置（${dirtyCount}）` : '保存配置' }}
    </FaButton>
  </FaPageHeader>

  <FaPageMain v-loading="loading" class="flex flex-col gap-4">
    <FaAlert v-if="error" variant="destructive" title="提示">
  <template #description>
      {{ error }}
  </template>
    </FaAlert>

    <FaAlert v-if="!error && parseError && isStructured && !rawMode" variant="destructive" title="无法结构化解析">
  <template #description>
      {{ parseError }}。可切换「原始文本」直接编辑；修复格式后结构化视图会自动恢复。
  </template>
    </FaAlert>

    <!-- EULA 一键同意：仅当实例目录里实际存在 eula.txt 时提示（代理端无 eula，不会出现） -->
    <div v-if="eulaExists && !eulaAccepted" class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-500/40 bg-amber-500/5 p-4">
      <div class="min-w-0">
        <div class="flex flex-wrap items-center gap-2 text-sm font-medium">
          <FaIcon name="i-ri:file-paper-2-line" class="text-amber-600" />
          Minecraft EULA 尚未同意
        </div>
        <p class="mt-1 text-xs text-muted-foreground">
          服务端首次启动要求 eula=true，否则会自动退出。同意即写入 <code class="rounded bg-muted px-1 font-mono">eula.txt</code>；
          见 <a href="https://aka.ms/MinecraftEULA" target="_blank" rel="noopener" class="text-primary hover:underline">Minecraft EULA 条款</a>。
        </p>
      </div>
      <FaButton v-if="canManage" size="sm" :loading="eulaBusy" @click="acceptEula">
        <FaIcon name="i-ri:check-line" />
        一键同意 EULA
      </FaButton>
    </div>

    <FaAlert v-if="!fileTabs.length" variant="default" title="暂无可编辑的配置文件" class="mb-0">
  <template #description>
      实例数据目录里还没有已知配置文件（server.properties 等）。先启动一次实例让其生成，
      或到「文件管理」上传；代理实例需先启动一次生成 velocity.toml / config.yml。
  </template>
    </FaAlert>

    <FaTabs v-else :model-value="path" :list="fileTabs" @update:model-value="(v: string | number) => selectFile(String(v))" />

    <div v-if="fileTabs.length" class="flex flex-col gap-4">
      <div class="flex flex-wrap items-center gap-3">
        <div class="flex min-w-0 flex-1 items-center gap-3">
          <FaIcon name="i-ri:file-cog-line" class="text-xl text-muted-foreground" />
          <div class="min-w-0">
            <div class="truncate font-mono font-medium">
              /{{ path }}
            </div>
            <div class="text-xs text-muted-foreground">
              {{ currentFile?.desc || '配置文件' }}
            </div>
          </div>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <FaInput
            v-if="isStructured && !rawMode"
            v-model="filter"
            clearable
            class="w-full sm:w-64"
            placeholder="搜索配置项 / 说明 / 键名…"
          />
          <FaSelect
            v-if="isStructured && !rawMode"
            v-model="groupFilter"
            :options="filterOptions"
            class="w-full sm:w-48"
          />
          <label v-if="isStructured && !rawMode" class="flex items-center gap-2 text-sm">
            <FaSwitch v-model="showOnlyChanged" />
            仅看已改
          </label>
          <label v-if="isStructured" class="flex items-center gap-2 text-sm">
            <FaSwitch v-model="rawMode" />
            原始文本
          </label>
        </div>
      </div>

      <FaTextarea
        v-if="rawMode || !isStructured"
        v-model="content"
        :rows="24"
        class="w-full font-mono"
        :placeholder="`编辑 /${path} 的原文…`"
      />

      <div v-else class="grid gap-4">
        <div v-if="!groupedRows.length" class="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
          没有匹配的配置项。可切换分组、关闭「仅看已改」，或清空搜索关键词。
        </div>

        <FaCard
          v-for="section in groupedRows"
          :key="section.group"
          :title="section.meta.title"
          :description="section.meta.desc"
        >
          <div class="mb-3 flex items-center gap-2 text-sm text-muted-foreground">
            {{ section.items.length }} 项
            <FaTag v-if="section.items.some(i => i.dirty)" variant="secondary">
              含未保存修改
            </FaTag>
          </div>

          <div class="grid gap-3">
            <article
              v-for="row in section.items"
              :key="row.key"
              class="flex flex-col gap-3 rounded-lg border p-4 lg:flex-row lg:items-start lg:justify-between"
              :class="row.dirty ? 'border-primary/60 bg-primary/5' : ''"
            >
              <div class="min-w-0 flex-1 space-y-1.5">
                <div class="flex flex-wrap items-center gap-2">
                  <span class="font-medium">{{ row.label }}</span>
                  <code class="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{{ row.key }}</code>
                  <FaTag variant="outline">
                    {{ typeLabel(row.type) }}
                  </FaTag>
                  <FaTag v-if="!row.known" variant="secondary">自定义</FaTag>
                  <FaTag v-if="row.dirty">已修改</FaTag>
                </div>
                <p class="text-sm text-muted-foreground">
                  {{ row.desc }}
                </p>
                <div v-if="row.tip" class="flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400">
                  <FaIcon name="i-ri:lightbulb-flash-line" />
                  {{ row.tip }}
                </div>
                <div v-if="row.defaultValue !== undefined && row.defaultValue !== ''" class="text-xs text-muted-foreground">
                  默认参考：<code class="rounded bg-muted px-1 py-0.5 font-mono">{{ row.defaultValue }}</code>
                </div>
              </div>

              <div class="flex shrink-0 items-center gap-2">
                <FaSelect
                  v-if="row.type === 'boolean' || row.type === 'enum'"
                  :model-value="row.value"
                  :options="selectOptions(row)"
                  :disabled="!canManage"
                  class="w-56"
                  placeholder="未设置"
                  @update:model-value="(v: unknown) => setRowValue(row.key, String(v ?? ''))"
                />
                <FaInput
                  v-else
                  :model-value="row.value"
                  class="w-56"
                  :placeholder="row.value || (row.defaultValue !== undefined ? `默认 ${row.defaultValue}` : '（未设置）')"
                  :disabled="!canManage"
                  @update:model-value="(v: unknown) => setRowValue(row.key, String(v ?? ''))"
                />
                <FaButton
                  v-if="row.dirty && canManage"
                  variant="ghost"
                  size="sm"
                  @click="resetRow(row.key)"
                >
                  撤销本项
                </FaButton>
              </div>
            </article>
          </div>
        </FaCard>
      </div>
    </div>

    <FaAlert v-if="!error && isStructured && !rawMode" variant="default" title="编辑提示">
  <template #description>
      布尔项请用「是/否」；枚举项从下拉中选择。未写入文件的键默认不落盘，改完后点右上角保存。
      世界种子、端口等仅在服务端下次启动时读取；改错时可用「重新读取」或「放弃修改」。
      <template v-if="/\.(ya?ml|toml)$/.test(path.toLowerCase())">
        yml/toml 结构化保存会重写整个文件（注释与顺序不保留），只改填写的键；需要保留注释请切换「原始文本」编辑。
      </template>
  </template>
    </FaAlert>
  </FaPageMain>
</template>
