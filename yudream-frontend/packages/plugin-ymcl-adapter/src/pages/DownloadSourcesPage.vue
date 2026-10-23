<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn } from '@yudream/components'
import type { DownloadGatewayInfo, DownloadProvider, DownloadSourceEntry } from '../types'
import {
  YdButton,
  YdIcon,
  YdInput,
  YdPageHeader,
  YdSelect,
  YdSettingsLayout,
  YdSettingsSection,
  YdSwitch,
  YdTag,
  YdResponsiveEnhancedTable,
  useYdModal,
  useYdToast,
} from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createYmclApi } from '../api/ymcl-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createYmclApi(props.sdk)
const toast = useYdToast()
const modal = useYdModal()

const loading = ref(false)
const saving = ref(false)
const gatewayInfo = ref<DownloadGatewayInfo | null>(null)
const showSecret = ref(false)

interface SourceForm extends DownloadSourceEntry {}

const form = ref<{
  managed: boolean
  officialFallback: boolean
  gateway: { name: string, baseUrl: string, auth: 'token' | 'none' }
  sources: SourceForm[]
  categories: Record<string, string[]>
}>({
  managed: false,
  officialFallback: true,
  gateway: { name: '', baseUrl: '', auth: 'token' },
  sources: [],
  categories: {},
})

/** 设置页分区：左导航按此切换，窄屏折叠成分区选择器 */
const activeSection = ref('distribution')
const sectionList = [
  { label: '分发开关', value: 'distribution' },
  { label: '网关对接', value: 'gateway' },
  { label: '来源池', value: 'sources' },
  { label: '类别顺序', value: 'categories' },
]

const PROVIDER_OPTIONS: { label: string, value: DownloadProvider }[] = [
  { label: '官方源', value: 'official' },
  { label: 'BMCLAPI', value: 'bmclapi' },
  { label: 'MCIM', value: 'mcim' },
  { label: '天派', value: 'tianpao' },
  { label: '阿里云 Maven', value: 'aliyun' },
  { label: '域网关（YDA 适配镜像）', value: 'gateway' },
  { label: '自定义（BMCLAPI 兼容）', value: 'custom' },
]

const AUTH_OPTIONS = [
  { label: '下载令牌（玩家身份鉴权）', value: 'token' },
  { label: '匿名（网关自管门控）', value: 'none' },
]

const CATEGORY_DEFS: { key: string, label: string, description: string }[] = [
  { key: 'minecraft_metadata', label: '原版元数据', description: '版本清单、版本 JSON、asset index' },
  { key: 'minecraft_files', label: '基础资源', description: '客户端 jar、assets 资源、libraries' },
  { key: 'loaders', label: '加载器安装器', description: 'Forge / Fabric / NeoForge / OptiFine 等 maven 与元数据' },
  { key: 'modrinth', label: 'Modrinth', description: 'api.modrinth.com 与 cdn.modrinth.com' },
  { key: 'curseforge', label: 'CurseForge', description: 'api.curseforge.com 与 forgecdn 各 CDN 域' },
]

function providerLabel(provider: string) {
  return PROVIDER_OPTIONS.find(option => option.value === provider)?.label || provider
}

const sourceColumns: TableColumn<DownloadSourceEntry>[] = [
  { id: 'id', header: 'ID', width: 140 },
  { id: 'provider', header: '类型', width: 200 },
  { id: 'name', header: '显示名', minWidth: 140 },
  { id: 'baseUrl', header: '基址', minWidth: 220 },
  { id: 'actions', header: '操作', width: 90 },
]

const newSource = ref<{ id: string, provider: DownloadProvider, name: string, baseUrl: string }>({
  id: '',
  provider: 'custom',
  name: '',
  baseUrl: '',
})

function addSource() {
  const id = newSource.value.id.trim().toLowerCase()
  if (!/^[a-z0-9-]{1,48}$/.test(id)) {
    toast.error('来源 ID 只能是小写字母、数字与连字符（≤48 位）')
    return
  }
  if (form.value.sources.some(source => source.id === id)) {
    toast.error(`来源 ID ${id} 已存在`)
    return
  }
  form.value.sources.push({
    id,
    provider: newSource.value.provider,
    name: newSource.value.name.trim() || null,
    base_url: newSource.value.baseUrl.trim() || null,
  })
  newSource.value = { id: '', provider: 'custom', name: '', baseUrl: '' }
}

function removeSource(id: string) {
  form.value.sources = form.value.sources.filter(source => source.id !== id)
  for (const category of Object.keys(form.value.categories)) {
    form.value.categories[category] = form.value.categories[category].filter(entry => entry !== id)
  }
}

function sourceFor(id: string) {
  return form.value.sources.find(source => source.id === id)
}

function availableForCategory(category: string) {
  const used = form.value.categories[category] || []
  return form.value.sources
    .filter(source => !used.includes(source.id))
    .map(source => ({ label: `${source.name || source.id}（${providerLabel(source.provider)}）`, value: source.id }))
}

function addSourceToCategory(category: string, id: string) {
  if (!id) {
    return
  }
  const list = form.value.categories[category] || []
  if (list.length >= 8) {
    toast.error('每个类别最多 8 个来源')
    return
  }
  form.value.categories[category] = [...list, id]
}

function moveCategoryEntry(category: string, index: number, delta: number) {
  const list = [...(form.value.categories[category] || [])]
  const target = index + delta
  if (target < 0 || target >= list.length) {
    return
  }
  ;[list[index], list[target]] = [list[target], list[index]]
  form.value.categories[category] = list
}

function removeCategoryEntry(category: string, index: number) {
  form.value.categories[category] = (form.value.categories[category] || []).filter((_, i) => i !== index)
}

function categoryLabel(id: string | undefined | null) {
  if (!id) {
    return '（未知来源）'
  }
  const source = sourceFor(id)
  if (!source) {
    return id
  }
  const provider = providerLabel(source.provider)
  if (!source.name || source.name === provider) {
    return provider
  }
  return `${source.name}（${provider}）`
}

const managedCategoryCount = computed(() =>
  CATEGORY_DEFS.filter(def => (form.value.categories[def.key] || []).length > 0).length)

async function load() {
  loading.value = true
  try {
    const [config, gateway] = await Promise.all([
      api.downloadConfig(),
      api.downloadGatewayInfo(),
    ])
    gatewayInfo.value = gateway
    form.value = {
      managed: Boolean(config.managed),
      officialFallback: Boolean(config.official_fallback),
      gateway: {
        name: config.gateway?.name || '',
        // 协议视图是 snake_case；Long 字段宿主会字符串化，这里只取展示值。
        baseUrl: config.gateway?.base_url || '',
        auth: config.gateway?.auth === 'none' ? 'none' : 'token',
      },
      sources: (config.sources || []).map(source => ({
        id: source.id,
        provider: source.provider,
        name: source.name ?? null,
        baseUrl: source.base_url ?? null,
      })),
      categories: Object.fromEntries(
        CATEGORY_DEFS.map(def => [def.key, [...(config.categories?.[def.key] || [])]]),
      ),
    }
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '加载分发配置失败')
  }
  finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await api.saveDownloadConfig({
      managed: form.value.managed,
      officialFallback: form.value.officialFallback,
      gateway: {
        name: form.value.gateway.name.trim() || null,
        baseUrl: form.value.gateway.baseUrl.trim() || null,
        auth: form.value.gateway.auth,
      },
      sources: form.value.sources.map(source => ({
        id: source.id,
        provider: source.provider,
        name: source.name || null,
        // 保存契约是 camelCase（GET 协议视图是 snake_case，回显时已转换）
        baseUrl: source.base_url || null,
      })),
      categories: Object.fromEntries(
        CATEGORY_DEFS.map(def => [def.key, form.value.categories[def.key] || []]),
      ),
    })
    toast.success('分发配置已保存并下发')
    await load()
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '保存分发配置失败')
  }
  finally {
    saving.value = false
  }
}

function confirmRotateSecret() {
  modal.confirm({
    title: '轮换网关密钥',
    content: '轮换后旧密钥立即失效，所有未过期下载令牌作废；镜像网关需同步更新配置。确认继续？',
    onConfirm: async () => {
      try {
        const result = await api.rotateDownloadGatewaySecret()
        toast.success('网关密钥已轮换，请更新镜像网关配置')
        await load()
        if (gatewayInfo.value) {
          gatewayInfo.value.gatewaySecret = result.gatewaySecret
        }
        showSecret.value = true
      }
      catch (error) {
        toast.error(error instanceof Error ? error.message : '轮换失败')
      }
    },
  })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="settings-page-shell">
    <YdPageHeader
      title="下载分发"
      description="按类别维护启动器下载源链：分发激活期间，成员启动器按站点下发的顺序使用镜像与回退降级，不再由启动器本地设置控制；未分发的类别自动回退成员本地设置。"
      class="mb-0"
    >
      <YdButton :loading="saving" @click="save">
        <YdIcon name="i-ri:save-line" />
        保存并下发
      </YdButton>
    </YdPageHeader>

    <YdSettingsLayout
      v-model="activeSection"
      :list="sectionList"
      surface
      scroll
      width-class="max-w-[960px]"
    >
      <template #distribution>
        <YdSettingsSection
          title="分发开关"
          description="关闭后立即停止分发，成员启动器恢复各自本地下载源设置"
        >
          <div class="ymcl-switch-row">
            <YdSwitch
              :model-value="form.managed"
              @update:model-value="value => { form.managed = value === true }"
            />
            <div class="ymcl-switch-row__text">
              <strong>启用下载源分发</strong>
              <p class="ymcl-muted">启用时 capabilities 宣告 download 能力，已加入域的启动器自动拉取本配置。</p>
            </div>
          </div>
          <div class="ymcl-switch-row">
            <YdSwitch
              :model-value="form.officialFallback"
              @update:model-value="value => { form.officialFallback = value === true }"
            />
            <div class="ymcl-switch-row__text">
              <strong>官方源链尾兜底</strong>
              <p class="ymcl-muted">即使来源列表未包含官方源，也在链尾追加官方直连作为最后回退，防止镜像整体不可用时无法安装。</p>
            </div>
          </div>
          <dl class="ymcl-info-list">
            <div>
              <dt>已配置类别</dt>
              <dd>{{ managedCategoryCount ? `${managedCategoryCount} / ${CATEGORY_DEFS.length}` : '无（全部回退本地设置）' }}</dd>
            </div>
            <div>
              <dt>来源池</dt>
              <dd>{{ form.sources.length ? `${form.sources.length} 个来源` : '空' }}</dd>
            </div>
            <div>
              <dt>最后更新</dt>
              <dd>{{ gatewayInfo?.config?.updated_at ? new Date(Number(gatewayInfo.config.updated_at)).toLocaleString() : '未配置' }}</dd>
            </div>
          </dl>
        </YdSettingsSection>
      </template>

      <template #gateway>
        <YdSettingsSection
          title="网关对接"
          description="YDA 适配的镜像网关（BMCLAPI 路由兼容）：玩家经下载令牌鉴权，网关回调本站校验身份并回传统计"
        >
          <div class="ymcl-form-row">
            <label class="ymcl-label">显示名</label>
            <YdInput v-model="form.gateway.name" placeholder="如：YDA 镜像" />
          </div>
          <div class="ymcl-form-row">
            <label class="ymcl-label">网关基址</label>
            <YdInput v-model="form.gateway.baseUrl" placeholder="https://mirror.example.com" />
            <p class="ymcl-muted">BMCLAPI 路由兼容基址；域网关来源缺省继承该地址。</p>
          </div>
          <div class="ymcl-form-row">
            <label class="ymcl-label">鉴权方式</label>
            <YdSelect v-model="form.gateway.auth" :options="AUTH_OPTIONS" />
          </div>
        </YdSettingsSection>
        <YdSettingsSection
          title="回调与密钥"
          description="在镜像网关侧配置以下端点与共享密钥（契约见 docs/ymcl-download-gateway-v1.md）"
        >
          <dl class="ymcl-info-list">
            <div>
              <dt>令牌校验端点</dt>
              <dd><code class="ymcl-hash">{{ gatewayInfo?.verifyEndpoint || '/api/plugins/ymcl-adapter/v1/download/token/verify' }}</code></dd>
            </div>
            <div>
              <dt>统计上报端点</dt>
              <dd><code class="ymcl-hash">{{ gatewayInfo?.reportEndpoint || '/api/plugins/ymcl-adapter/v1/download/stats/report' }}</code></dd>
            </div>
            <div>
              <dt>密钥请求头</dt>
              <dd><code class="ymcl-hash">{{ gatewayInfo?.secretHeader || 'X-YDA-Gateway-Secret' }}</code></dd>
            </div>
            <div>
              <dt>共享密钥</dt>
              <dd>
                <code v-if="showSecret" class="ymcl-hash">{{ gatewayInfo?.gatewaySecret }}</code>
                <span v-else class="ymcl-muted">••••••••（已隐藏）</span>
              </dd>
            </div>
          </dl>
          <div class="ymcl-card-header-row">
            <YdButton variant="outline" @click="showSecret = !showSecret">
              <YdIcon :name="showSecret ? 'i-ri:eye-off-line' : 'i-ri:eye-line'" />
              {{ showSecret ? '隐藏密钥' : '显示密钥' }}
            </YdButton>
            <YdButton variant="destructive" @click="confirmRotateSecret">
              <YdIcon name="i-ri:refresh-line" />
              轮换密钥
            </YdButton>
          </div>
        </YdSettingsSection>
      </template>

      <template #sources>
        <YdSettingsSection
          title="来源池"
          description="定义可引用的下载源；类别顺序里按 ID 引用并排序"
        >
          <YdResponsiveEnhancedTable
            table-root-class="max-w-full rounded-lg" table-class="min-w-[640px]"
            :columns="sourceColumns"
            :data="form.sources"
            row-key="id"
          >
            <template #empty>
              <span class="ymcl-muted">尚未定义来源</span>
            </template>
            <template #cell-provider="{ row }">
              <YdTag>{{ providerLabel(row.original.provider) }}</YdTag>
            </template>
            <template #cell-name="{ row }">
              {{ row.original.name || '—' }}
            </template>
            <template #cell-baseUrl="{ row }">
              <code class="ymcl-hash ymcl-break">{{ row.original.base_url || '随类别路由' }}</code>
            </template>
            <template #cell-actions="{ row }">
              <YdButton
                variant="ghost"
                @click="removeSource(row.original.id)"
              >
                <YdIcon name="i-ri:delete-bin-line" />
              </YdButton>
            </template>
          </YdResponsiveEnhancedTable>

          <div class="ymcl-form-row">
            <label class="ymcl-label">新增来源</label>
            <div class="ymcl-theme-color-row">
              <YdInput v-model="newSource.id" placeholder="ID（小写字母/数字/连字符）" />
              <YdSelect v-model="newSource.provider" :options="PROVIDER_OPTIONS" />
            </div>
            <div class="ymcl-theme-color-row">
              <YdInput v-model="newSource.name" placeholder="显示名（可选）" />
              <YdInput
                v-if="newSource.provider === 'custom' || newSource.provider === 'gateway'"
                v-model="newSource.baseUrl"
                placeholder="https://（BMCLAPI 兼容基址）"
              />
            </div>
            <YdButton variant="outline" @click="addSource">
              <YdIcon name="i-ri:add-line" />
              添加来源
            </YdButton>
          </div>
        </YdSettingsSection>
      </template>

      <template #categories>
        <YdSettingsSection
          title="类别顺序"
          description="列表顺序即回退顺序：启动器依次尝试，失败自动落到下一源；留空的类别回退成员本地设置"
        >
          <div v-for="def in CATEGORY_DEFS" :key="def.key" class="ymcl-form-row">
            <label class="ymcl-label">{{ def.label }}</label>
            <p class="ymcl-muted">{{ def.description }}</p>
            <div class="ymcl-download-chain">
              <template v-if="(form.categories[def.key] || []).length">
                <div
                  v-for="(id, index) in form.categories[def.key]"
                  :key="`${def.key}-${id}`"
                  class="ymcl-download-chain__item"
                >
                  <span class="ymcl-download-chain__rank">{{ index + 1 }}</span>
                  <YdTag>{{ categoryLabel(id) }}</YdTag>
                  <span class="ymcl-download-chain__spacer" />
                  <YdButton variant="ghost" :disabled="index === 0" @click="moveCategoryEntry(def.key, index, -1)">
                    <YdIcon name="i-ri:arrow-up-line" />
                  </YdButton>
                  <YdButton
                    variant="ghost"
                    :disabled="index === (form.categories[def.key]?.length || 0) - 1"
                    @click="moveCategoryEntry(def.key, index, 1)"
                  >
                    <YdIcon name="i-ri:arrow-down-line" />
                  </YdButton>
                  <YdButton variant="ghost" @click="removeCategoryEntry(def.key, index)">
                    <YdIcon name="i-ri:close-line" />
                  </YdButton>
                </div>
              </template>
              <p v-else class="ymcl-muted">未分发——成员沿用本地设置</p>
              <YdSelect
                placeholder="添加来源…"
                :options="availableForCategory(def.key)"
                :model-value="''"
                @update:model-value="value => addSourceToCategory(def.key, String(value || ''))"
              />
            </div>
          </div>
        </YdSettingsSection>
      </template>
    </YdSettingsLayout>
  </div>
</template>
