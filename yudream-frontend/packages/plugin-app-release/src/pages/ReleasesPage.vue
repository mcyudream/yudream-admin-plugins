<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { TableColumn, ActionMenuItem, StatusColumn } from '@yudream/components'
import type { AppReleaseSettingsView, AppReleaseView } from '../types'
import { YdButton, YdCard, YdIcon, YdInput, YdModal, YdNumberField, YdPageHeader, YdPageMain, YdSwitch, YdTag, YdTextarea, useYdModal, useYdToast, YdFilePicker, YdResponsiveEnhancedTable } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { createAppReleaseApi, errorMessage } from '../api/app-release-api'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createAppReleaseApi(props.sdk)
const toast = useYdToast()
const confirm = useYdModal()

const loading = ref(false)
const rows = ref<AppReleaseView[]>([])
const settings = ref<AppReleaseSettingsView | null>(null)
const minVersionCode = ref(0)
const savingSettings = ref(false)
const publishingId = ref('')
const deletingId = ref('')

const uploadOpen = ref(false)
const uploading = ref(false)
const uploadFile = ref<File | null>(null)
const uploadForm = ref({
  platform: 'android',
  versionCode: 0,
  versionName: '',
  changelog: '',
  forceUpdate: false,
})

const latestVersionCode = computed(() =>
  rows.value.filter(item => item.published).reduce((max, item) => Math.max(max, item.versionCode), 0))

const columns: TableColumn<AppReleaseView>[] = [
  { id: 'version', header: '版本', width: 170 },
  { accessorKey: 'platform', header: '平台', width: 90 },
  { id: 'state', header: '状态', width: 150 },
  { id: 'file', header: '更新包', width: 220 },
  { id: 'changelog', header: '更新日志', minWidth: 260 },
  { accessorKey: 'publishedAtLabel', header: '发布时间', width: 150 },
]

function fmtSize(size: number): string {
  if (!size) {
    return '-'
  }
  if (size >= 1024 * 1024) {
    return `${(size / 1024 / 1024).toFixed(1)} MB`
  }
  return `${Math.max(1, Math.round(size / 1024))} KB`
}

function fmtTime(ts: number): string {
  if (!ts) {
    return '-'
  }
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function load() {
  loading.value = true
  try {
    const [list, setting] = await Promise.all([api.list(), api.settings()])
    rows.value = list
    settings.value = setting
    minVersionCode.value = Number(setting.minVersionCode ?? 0)
  }
  catch (error) {
    toast.error(errorMessage(error, '加载更新发布数据失败'))
  }
  finally {
    loading.value = false
  }
}

async function saveSettings() {
  const parsed = Math.max(0, Math.round(Number(minVersionCode.value) || 0))
  savingSettings.value = true
  try {
    settings.value = await api.saveSettings(parsed)
    toast.success(parsed > 0 ? `已保存：低于 versionCode ${parsed} 的客户端将强制更新` : '已关闭强制更新线')
  }
  catch (error) {
    toast.error(errorMessage(error, '保存设置失败'))
  }
  finally {
    savingSettings.value = false
  }
}

function openUpload() {
  uploadFile.value = null
  uploadForm.value = {
    platform: 'android',
    versionCode: latestVersionCode.value + 1,
    versionName: '',
    changelog: '',
    forceUpdate: false,
  }
  uploadOpen.value = true
}

const uploadVersionCode = computed({
  get: () => uploadForm.value.versionCode,
  set: (value: number | undefined) => { uploadForm.value.versionCode = Math.max(0, Math.round(Number(value) || 0)) },
})

function onFileSelect(files: File[]) {
  const file = files[0] ?? null
  uploadFile.value = file
  if (file && !uploadForm.value.versionName) {
    // 从文件名推断版本号（ydam-0.2.1.apk / app-0.2.1-release.apk）
    const match = /(\d+\.\d+(?:\.\d+)?)/.exec(file.name)
    if (match) {
      uploadForm.value.versionName = match[1]
    }
  }
}

async function submitUpload() {
  const versionCode = Number(uploadForm.value.versionCode)
  if (!uploadFile.value) {
    toast.error('请选择更新包文件（APK）')
    return
  }
  if (!Number.isInteger(versionCode) || versionCode <= 0) {
    toast.error('versionCode 必须为正整数（客户端比较依据）')
    return
  }
  if (!uploadForm.value.versionName.trim()) {
    toast.error('请填写版本名（如 0.2.1）')
    return
  }
  uploading.value = true
  try {
    await api.upload({
      platform: uploadForm.value.platform,
      versionCode,
      versionName: uploadForm.value.versionName.trim(),
      changelog: uploadForm.value.changelog,
      forceUpdate: uploadForm.value.forceUpdate,
      file: uploadFile.value,
    })
    toast.success('已上传，发布后客户端可见')
    uploadOpen.value = false
    await load()
  }
  catch (error) {
    toast.error(errorMessage(error, '上传失败'))
  }
  finally {
    uploading.value = false
  }
}

/** 发布状态列：语义色胶囊 + 强制更新标记单独成格 */
const stateColumn: StatusColumn<AppReleaseView> = {
  statusKey: 'published',
  header: '发布状态',
  width: 110,
  statusMap: {
    'true': { label: '已发布', variant: 'default', tone: 'success' },
    'false': { label: '未发布', variant: 'secondary', tone: 'neutral' },
  },
}

/** 行操作统一收进操作菜单，删除保持确认流程 */
const rowActions: ActionMenuItem<AppReleaseView>[] = [
  { label: row => (row.published ? '下架' : '发布'), icon: 'morph:eye', disabled: () => publishingId.value !== '', onClick: row => togglePublish(row) },
  { label: '下载', icon: 'morph:download', onClick: (row) => { window.open(api.downloadUrl(row.id), '_blank', 'noopener') } },
  { label: '删除', icon: 'morph:trash-2', danger: true, disabled: () => deletingId.value !== '', onClick: row => confirmDelete(row) },
]

async function togglePublish(row: AppReleaseView) {
  publishingId.value = row.id
  try {
    await (row.published ? api.unpublish(row.id) : api.publish(row.id))
    toast.success(row.published ? '已下架，客户端不再提示该版本' : '已发布，客户端将收到更新提示')
    await load()
  }
  catch (error) {
    toast.error(errorMessage(error, '操作失败'))
  }
  finally {
    publishingId.value = ''
  }
}

function confirmDelete(row: AppReleaseView) {
  confirm.confirm({
    title: '删除版本包',
    content: `确定删除 ${row.versionName}（versionCode ${row.versionCode}）？更新包文件将一并删除，不可恢复。`,
    onConfirm: async () => {
      deletingId.value = row.id
      try {
        await api.remove(row.id)
        toast.success('已删除')
        await load()
      }
      catch (error) {
        toast.error(errorMessage(error, '删除失败'))
      }
      finally {
        deletingId.value = ''
      }
    },
  })
}

onMounted(load)
</script>

<template>
  <YdPageHeader title="更新发布" class="mb-0">
    <span class="text-sm text-secondary-foreground/60">
      最新 versionCode {{ latestVersionCode }} · 强制更新线 {{ settings?.minVersionCode ?? 0 }}
    </span>
    <YdButton @click="openUpload">
      <YdIcon name="i-ri:upload-2-line" />发布新版本
    </YdButton>
  </YdPageHeader>

  <YdPageMain>
    <YdCard class="mb-4">
      <div class="app-release-toolbar">
        <span class="text-sm font-medium">强制更新线（versionCode）</span>
        <YdNumberField v-model="minVersionCode" class="app-release-filter-input" :min="0" :max="2147483647" />
        <YdButton variant="outline" :loading="savingSettings" @click="saveSettings">
          保存
        </YdButton>
        <span class="text-xs text-secondary-foreground/60">
          低于该 versionCode 的客户端必须更新后才能继续使用；发布时也可单独勾选「强制更新」。
        </span>
      </div>
    </YdCard>

    <YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable
      :columns="columns"
      :data="rows"
      row-key="id"
      table-root-class="rounded-lg overflow-hidden"
      table-class="min-w-[820px]"
      border
      stripe
      column-visibility
      :status-column="stateColumn"
      :actions="rowActions"
      empty-text="暂无版本包，点击右上角「发布新版本」上传 APK"
    >
      <template #cell-version="{ row }">
        <span class="app-release-title">{{ row.original.versionName }}</span>
        <span class="app-release-subtitle">versionCode {{ row.original.versionCode }}</span>
      </template>
      <template #cell-state="{ row }">
        <span v-if="row.original.forceUpdate" class="yd-status-tone" data-tone="danger">强制更新</span>
      </template>
      <template #cell-file="{ row }">
        <span class="break-all">{{ row.original.fileName }}</span>
        <span class="app-release-subtitle">{{ fmtSize(row.original.fileSize) }}</span>
      </template>
      <template #cell-changelog="{ row }">
        <span v-if="row.original.changelog" class="app-release-changelog line-clamp-3">{{ row.original.changelog }}</span>
        <span v-else class="app-release-subtitle">未填写</span>
      </template>
    </YdResponsiveEnhancedTable></YdLoadingOverlay>
  </YdPageMain>

  <YdModal v-model="uploadOpen" title="发布新版本" class="sm:max-w-[560px]" :close-on-click-overlay="false">
      <div class="flex flex-col gap-4">
        <div>
          <div class="mb-1 text-sm font-medium">
            更新包（APK）
          </div>
          <YdFilePicker accept=".apk,application/vnd.android.package-archive" label="选择 APK 文件" @select="onFileSelect">
            <template #default="{ open }">
              <YdButton variant="outline" @click="open">
                <YdIcon name="i-ri:file-upload-line" />{{ uploadFile ? uploadFile.name : '选择 APK 文件' }}
              </YdButton>
            </template>
          </YdFilePicker>
          <div v-if="uploadFile" class="app-release-subtitle mt-1">
            {{ fmtSize(uploadFile.size) }}
          </div>
        </div>
        <div class="flex gap-3">
          <div class="flex-1">
            <div class="mb-1 text-sm font-medium">
              versionCode
            </div>
            <YdNumberField v-model="uploadVersionCode" :min="1" :max="2147483647" />
          </div>
          <div class="flex-1">
            <div class="mb-1 text-sm font-medium">
              版本名
            </div>
            <YdInput v-model="uploadForm.versionName" placeholder="如 0.2.1" clearable />
          </div>
        </div>
        <div>
          <div class="mb-1 text-sm font-medium">
            更新日志
          </div>
          <YdTextarea v-model="uploadForm.changelog" placeholder="本次更新内容（将展示在 App 更新弹窗与「关于软件」）" :auto-size="{ minRows: 4, maxRows: 10 }" />
        </div>
        <div class="flex items-center gap-2">
          <YdSwitch v-model="uploadForm.forceUpdate" />
          <span class="text-sm">强制更新（低于该版本的客户端必须升级后才能继续使用）</span>
        </div>
        <YdTag v-if="uploadForm.forceUpdate" variant="destructive">
          注意：发布后将阻断所有旧版本客户端
        </YdTag>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <YdButton variant="outline" @click="uploadOpen = false">
            取消
          </YdButton>
          <YdButton :loading="uploading" @click="submitUpload">
            上传
          </YdButton>
        </div>
      </template>
    </YdModal>
</template>
