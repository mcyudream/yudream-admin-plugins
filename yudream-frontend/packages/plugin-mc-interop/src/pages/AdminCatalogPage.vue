<script setup lang="ts">
import type { ActionMenuItem, SearchConfig, SearchFieldConfig, TableColumn, YdTablePageAction } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { InteropPackEntry, InteropSiteDocument, McInteropEntry } from '../types'
import { YdAlert, YdDynamicSearchForm, YdForm, YdFormItem, YdInput, YdLoadingOverlay, YdModal, YdNumberField, YdPagination, YdResponsiveEnhancedTable, YdSelect, YdSwitch, YdTablePageLayout, YdTag, YdTextarea, useYdModal, useYdToast } from '@yudream/components'
import { ref } from 'vue'
import { formatDateTime } from '../composables/useMcInteropShell'
import { useMcInteropCatalog } from '../composables/useMcInteropCatalog'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useMcInteropCatalog(props.sdk)
const toast = useYdToast()
const confirm = useYdModal()

/** ControlType 是 components 内部字符串枚举，桥接层只出类型，这里用字面量收敛一次 */
type SearchFieldInput = Partial<Omit<SearchFieldConfig, 'controlType'>> & Pick<SearchFieldConfig, 'fieldName' | 'label'> & { controlType?: 'TEXT' | 'SELECT' | 'DATE_RANGE' | 'NUMBER_RANGE' | 'CHECKBOX' | 'RADIO' }

function searchField(config: SearchFieldInput): SearchFieldConfig {
  const { controlType = 'TEXT', order = 0, showWhenFolded = false, width = '200px', ...rest } = config
  return { ...rest, fieldName: config.fieldName, label: config.label, controlType: controlType as SearchFieldConfig['controlType'], order, showWhenFolded, width }
}

const searchQuery = ref<Record<string, any>>({ keyword: '' })
const searchConfig: SearchConfig = {
  fields: [
    searchField({ fieldName: 'keyword', label: '关键词', controlType: 'TEXT', order: 1, showWhenFolded: true, width: '220px' }),
  ],
}

function applySearch() {
  model.keyword = String(searchQuery.value.keyword ?? '')
  void model.search()
}

const columns: TableColumn<McInteropEntry>[] = [
  { accessorKey: 'name', header: '名称', minWidth: 160 },
  { accessorKey: 'id', header: 'ID', minWidth: 130 },
  { id: 'version', header: '版本', width: 190, enableSorting: false },
  { id: 'artifact', header: '整合包', width: 120, enableSorting: false },
  { accessorKey: 'serverAddress', header: '服务器', minWidth: 170 },
  { id: 'published', header: '发布', width: 90, enableSorting: false },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
]

const rowActions: ActionMenuItem<McInteropEntry>[] = [
  { label: '编辑', icon: 'morph:pencil', onClick: entry => model.openEdit(entry) },
  {
    label: entry => (entry.published ? '取消发布' : '发布'),
    icon: 'morph:eye',
    onClick: entry => model.togglePublish(entry),
  },
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: entry => remove(entry) },
]

const pageActions: YdTablePageAction[] = [
  { label: '从适配器导入', icon: 'morph:download', onClick: importFromAdapter },
  { label: '加载合并视图', icon: 'morph:git-merge', onClick: loadMerged },
  { label: '新增条目', icon: 'morph:plus', onClick: () => model.openCreate() },
]

/** 合并视图：本站已发布条目 + peers 互采的友站条目（协议出口形状） */
const merged = ref<InteropSiteDocument | null>(null)
const mergedLoading = ref(false)

async function loadMerged() {
  mergedLoading.value = true
  try {
    merged.value = await model.mergedEntries()
  } catch (cause) {
    toast.error(cause instanceof Error ? cause.message : '合并视图加载失败')
  } finally {
    mergedLoading.value = false
  }
}

function mergedServer(pack: InteropPackEntry): string {
  const server = pack.server
  if (!server) return '-'
  return server.port ? `${server.address}:${server.port}` : server.address
}

async function importFromAdapter() {
  try {
    const body = (await model.importFromAdapter()) ?? { imported: 0, skipped: [] }
    toast.success(`已从适配器导入 ${body.imported} 条${body.skipped.length ? `，跳过 ${body.skipped.length} 条` : ''}`)
  } catch (cause) {
    toast.error(cause instanceof Error ? cause.message : '导入失败')
  }
}

function remove(entry: McInteropEntry) {
  confirm.confirm({
    title: '删除目录条目',
    content: `确认删除「${entry.name || entry.id}」吗？已发布的启动器将不再看到它。`,
    onConfirm: async () => {
      try {
        await model.remove(entry)
        toast.success('条目已删除')
      }
      catch (cause) {
        toast.error(cause instanceof Error ? cause.message : '删除失败')
      }
    },
  })
}

function save() {
  return model.save().then(() => {
    toast.success('条目已保存')
  }).catch((cause: unknown) => {
    toast.error(cause instanceof Error ? cause.message : '保存失败')
  })
}
</script>

<template>
  <YdTablePageLayout title="清单目录" :actions="pageActions" stats-title="出口说明">
    <template #search="{ fold }">
      <YdDynamicSearchForm
        v-model="searchQuery"
        :config="searchConfig"
        :fold="fold"
        @search="applySearch"
        @reset="applySearch"
      />
    </template>
    <template #stats>
      <p class="text-sm text-muted-foreground">
        只有「已发布」的条目会出现在 mc-interop v1 统一出口（/.well-known/mc-interop-v1.json）；
        artifact 内容变化时 revision 会自动单调递增。
      </p>
    </template>
    <template #table>
      <YdAlert v-if="model.error" class="mb-3" variant="destructive" title="加载失败" :description="model.error" />
      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable
          :columns="columns"
          :data="model.entries"
          row-key="id"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[820px]"
          border
          stripe
          column-visibility
          :actions="rowActions"
          empty-text="暂无目录条目"
        >
          <template #cell-version="{ row }">
            MC {{ row.original.gameVersion }} · {{ row.original.loader }}{{ row.original.loaderVersion ? ` ${row.original.loaderVersion}` : '' }}
            <span v-if="row.original.revision != null" class="ml-1 text-xs text-muted-foreground">r{{ row.original.revision }}</span>
          </template>
          <template #cell-artifact="{ row }">
            <YdTag v-if="row.original.artifactUrl" variant="secondary">{{ row.original.artifactFormat }}</YdTag>
            <span v-else class="text-muted-foreground">纯服务器</span>
          </template>
          <template #cell-published="{ row }">
            <YdSwitch :model-value="Boolean(row.original.published)" @update:model-value="model.togglePublish(row.original)" />
          </template>
          <template #cell-updatedAt="{ row }">{{ formatDateTime(row.original.updatedAt) }}</template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>

      <template v-if="merged">
        <h3 class="m-0 mt-4 text-base font-semibold text-contrast">合并视图（本站已发布 + 友站互采）</h3>
        <div class="flex flex-col gap-2">
          <div
            v-for="pack in merged.packs"
            :key="pack.id + ':' + (pack.source?.site_id ?? 'own')"
            class="flex flex-wrap items-center gap-2 rounded-lg border border-solid border-surface-5 p-2"
          >
            <span class="font-semibold text-contrast">{{ pack.name || pack.id }}</span>
            <span
              v-if="pack.source"
              class="rounded-full bg-surface-5 px-2 py-0.5 text-xs font-medium text-secondary"
            >友站 {{ pack.source.site_id }}</span>
            <span v-if="pack.revision != null" class="text-xs text-secondary">r{{ pack.revision }}</span>
            <span v-if="pack.game_version" class="text-xs text-secondary">MC {{ pack.game_version }}</span>
            <span v-if="pack.loader" class="text-xs text-secondary">{{ pack.loader }}{{ pack.loader_version ? ` ${pack.loader_version}` : '' }}</span>
            <code class="text-xs text-secondary">{{ mergedServer(pack) }}</code>
          </div>
        </div>
      </template>
    </template>
    <template #footer>
      <YdPagination
        v-model:page="model.page"
        v-model:size="model.size"
        :total="model.total"
        @page-change="page => model.changePage(page)"
        @size-change="size => model.changePage(1, size)"
      />
    </template>
  </YdTablePageLayout>

  <YdModal
    v-model="model.editOpen"
    :title="model.editing ? '编辑条目' : '新增条目'"
    show-cancel-button
    class="sm:max-w-2xl"
    :confirm-loading="model.loading"
    @confirm="save"
  >
    <YdForm :model="model.form" layout="vertical">
      <div class="flex flex-col gap-4">
        <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <YdFormItem label="ID" required extra="站点内唯一，字母数字 ._ -">
            <YdInput v-model="model.form.id" :disabled="!!model.editing" maxlength="128" />
          </YdFormItem>
          <YdFormItem label="名称" required>
            <YdInput v-model="model.form.name" maxlength="128" />
          </YdFormItem>
        </div>
        <YdFormItem label="说明">
          <YdTextarea v-model="model.form.summary" :maxlength="2000" :rows="2" />
        </YdFormItem>
        <div class="grid grid-cols-1 gap-4 md:grid-cols-3">
          <YdFormItem label="MC 版本" required>
            <YdInput v-model="model.form.gameVersion" maxlength="64" placeholder="如 1.21.10" />
          </YdFormItem>
          <YdFormItem label="加载器" required>
            <YdSelect
              v-model="model.form.loader"
              :options="['vanilla', 'fabric', 'forge', 'neoforge', 'quilt'].map(value => ({ label: value, value }))"
            />
          </YdFormItem>
          <YdFormItem v-if="model.form.loader !== 'vanilla'" label="加载器版本" required>
            <YdInput v-model="model.form.loaderVersion" maxlength="64" />
          </YdFormItem>
        </div>
        <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <YdFormItem label="服务器地址" required>
            <YdInput v-model="model.form.serverAddress" maxlength="255" placeholder="如 play.example.com" />
          </YdFormItem>
          <YdFormItem label="端口" extra="留空交给 SRV / 客户端默认，禁止手填 25565">
            <YdNumberField v-model="model.form.serverPort" :min="1" :max="65535" :step="1" class="w-full" />
          </YdFormItem>
        </div>
        <div class="rounded-lg border p-4">
          <div class="mb-3 flex items-center justify-between">
            <strong>整合包（可选，纯服务器条目留空）</strong>
            <YdSwitch v-model="model.form.published">
              发布到出口
            </YdSwitch>
          </div>
          <template v-if="model.form.artifactUrl || !model.editing || true">
            <div class="flex flex-col gap-4">
              <YdFormItem label="下载地址（https）">
                <YdInput v-model="model.form.artifactUrl" maxlength="2048" placeholder="https://…" />
              </YdFormItem>
              <div class="grid grid-cols-1 gap-4 md:grid-cols-3">
                <YdFormItem label="容器格式">
                  <YdSelect
                    v-model="model.form.artifactFormat"
                    :options="['mrpack', 'multimc', 'curseforge-zip', 'server-zip', 'raw'].map(value => ({ label: value, value }))"
                  />
                </YdFormItem>
                <YdFormItem label="大小（字节）">
                  <YdNumberField v-model="model.form.artifactSize" :min="1" :step="1" class="w-full" />
                </YdFormItem>
                <YdFormItem label="revision" extra="内容变化时自动 +1">
                  <YdNumberField v-model="model.form.revision" :min="0" :step="1" class="w-full" />
                </YdFormItem>
              </div>
              <YdFormItem label="sha1（40 位 hex）">
                <YdInput v-model="model.form.sha1" maxlength="40" />
              </YdFormItem>
              <YdFormItem label="sha512（128 位 hex，二选一必填）">
                <YdInput v-model="model.form.sha512" maxlength="128" />
              </YdFormItem>
            </div>
          </template>
        </div>
      </div>
    </YdForm>
  </YdModal>
</template>
