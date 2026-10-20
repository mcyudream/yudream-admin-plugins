<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions, TableColumn } from '@yudream/components'
import type { MinecraftEndpoint } from '../types'
import type { MinecraftServerPluginModel } from '../composables/useMinecraftServerPlugin'
import { YdAlert, YdButton, YdCard, YdFileUpload, YdIcon, YdInput, YdNumberField, YdPageHeader, YdPageMain, YdResponsiveTable, YdSelect, YdSwitch, useYdModal, useYdToast } from '@yudream/components'
import { computed, nextTick, ref } from 'vue'
import MarkdownEditor from '../components/MarkdownEditor.vue'
import { zipValidationError } from '../utils/mapFile'
const props = defineProps<{ model: MinecraftServerPluginModel }>()
const modal = useYdModal()
const toast = useYdToast()
const editionOptions = [{ label: 'Java', value: 'JAVA' }, { label: '基岩版', value: 'BEDROCK' }]
const bridgeConnectionOptions = computed(() => props.model.bridgeConnections.map(connection => ({ label: connection.platform ? `${connection.name}（${connection.platform}）` : connection.name, value: connection.id })))
const bridgeGroupOptions = computed(() => props.model.bridgeGroups.map(group => ({ label: group.name, value: group.id })))
const bridgeForwardToggles = computed(() => [
  { key: 'forwardChat' as const, label: '聊天消息', hint: '玩家发言转发到群聊' },
  { key: 'forwardJoinQuit' as const, label: '进退服通知', hint: '附带在线人数与前三个玩家名' },
  { key: 'forwardDeath' as const, label: '死亡消息', hint: '玩家死亡时转发' },
  { key: 'forwardAdvancement' as const, label: '成就通知', hint: '达成进度时转发' },
  { key: 'forwardStartStop' as const, label: '启停通报', hint: '服务器启动/关闭时转发' },
  { key: 'forwardToGame' as const, label: '群聊转游戏', hint: '绑定群的普通消息广播进游戏' },
])
const columns: TableColumn<MinecraftEndpoint>[] = [{ id: 'name', header: '线路名称', width: 160 }, { id: 'host', header: '主机', width: 220 }, { id: 'port', header: '端口', width: 120 }, { id: 'edition', header: '版本', width: 130 }, { id: 'primary', header: '主线', width: 90 }, { id: 'enabled', header: '启用', width: 90 }, { id: 'operation', header: '操作', width: 90, fixed: 'right' }]
function confirmDelete() { const server = props.model.servers.find(item => item.id === props.model.serverForm.id); if (server) modal.confirm({ title: '删除服务器', content: `确认删除“${server.name}”吗？相关状态、周目操作和玩家记录也会删除。`, onConfirm: () => props.model.deleteServer(server) }) }
const mapUploadFiles = ref<FileItem[]>([])
const mapLinkUrl = ref('')
const mapLinkName = ref('')
function beforeUploadMap(file: File) {
  const validationError = zipValidationError(file)
  if (validationError) {
    toast.error(validationError)
    return false
  }
  if (!props.model.serverForm.id) {
    toast.error('请先保存服务器，再上传地图')
    return false
  }
  if (props.model.selectedServer?.map?.externalUrl) {
    modal.confirm({
      title: '覆盖网盘链接',
      content: '上传 ZIP 会替换当前网盘链接。确认继续吗？',
      onConfirm: () => {
        void props.model.uploadMap(file)
      },
    })
    return false
  }
  return true
}
async function uploadMapRequest(options: FileUploadRequestOptions) {
  try {
    await props.model.uploadMap(options.file, options.onProgress)
    return { uploaded: true }
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '地图上传失败')
    throw error
  }
}
function clearUploadedMapFiles() { nextTick(() => { mapUploadFiles.value = [] }) }
function confirmDeleteMap() { modal.confirm({ title: '删除地图', content: '确认删除此地图附件或网盘链接吗？', onConfirm: () => props.model.deleteMap() }) }
async function saveMapLink() {
  if (!props.model.serverForm.id) {
    toast.error('请先保存服务器，再填写网盘链接')
    return
  }
  const url = mapLinkUrl.value.trim()
  if (!url) {
    toast.error('请填写网盘下载链接')
    return
  }
  const persist = () => props.model.saveMapLink(url, mapLinkName.value.trim() || undefined)
    .then(() => {
      mapLinkUrl.value = ''
      mapLinkName.value = ''
    })
    .catch((error: unknown) => {
      toast.error(error instanceof Error ? error.message : '保存网盘链接失败')
    })
  if (props.model.selectedServer?.map && !props.model.selectedServer.map.externalUrl) {
    modal.confirm({
      title: '覆盖地图 ZIP',
      content: '保存网盘链接会删除已上传的地图 ZIP。确认继续吗？',
      onConfirm: persist,
    })
    return
  }
  await persist()
}
</script>
<template>
  <YdPageHeader :title="model.serverForm.id ? '编辑服务器' : '新增服务器'" class="mb-0" />
  <YdPageMain><form class="grid gap-4" @submit.prevent="model.saveServer">
    <div class="grid grid-cols-1 gap-3 md:grid-cols-[minmax(220px,1fr)_160px_120px]"><label class="grid gap-2"><span>名称</span><YdInput v-model="model.serverForm.name" /></label><label class="grid gap-2"><span>排序</span><YdNumberField v-model="model.serverForm.sort" /></label><label class="grid gap-2"><span>启用</span><YdSwitch v-model="model.serverForm.enabled" /></label></div>
    <div v-if="model.serverForm.id" class="mt-4 grid gap-3 rounded-lg border p-4">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 class="text-base font-semibold">地图存档</h3>
          <p class="text-sm text-muted-foreground">可上传 ZIP，或填写网盘下载链接。大文件建议用网盘；上传后默认私有，公开后普通用户可见。</p>
        </div>
        <YdFileUpload v-model="mapUploadFiles" :max="1" :disabled="model.mapOperating" :before-upload="beforeUploadMap" :http-request="uploadMapRequest" @on-success="clearUploadedMapFiles">
          <YdButton as="span" type="button" variant="outline" :loading="model.mapOperating"><YdIcon name="i-ri:upload-2-line" />上传 ZIP</YdButton>
        </YdFileUpload>
      </div>
      <div class="mc-map-link-form">
        <label class="grid gap-2">
          <span>网盘下载链接</span>
          <YdInput v-model="mapLinkUrl" placeholder="https://pan.example/s/..." />
        </label>
        <label class="grid gap-2">
          <span>显示名（可选）</span>
          <YdInput v-model="mapLinkName" placeholder="如 S1 存档" />
        </label>
        <div>
          <YdButton type="button" variant="outline" :loading="model.mapOperating" @click="saveMapLink"><YdIcon name="i-ri:link" />保存链接</YdButton>
        </div>
      </div>
      <template v-if="model.selectedServer?.map">
        <div class="mc-map-admin-row">
          <span>{{ model.selectedServer.map.originalName || (model.selectedServer.map.externalUrl ? '网盘下载' : '地图 ZIP') }}</span>
          <label class="inline-flex items-center gap-2 text-sm">公开下载 <YdSwitch :model-value="model.selectedServer.map.publicAccess" :disabled="model.mapOperating" @update:model-value="model.setMapPublicAccess(Boolean($event))" /></label>
          <div class="flex gap-2">
            <YdButton
              v-if="model.selectedServer.map.externalUrl"
              as="a"
              size="sm"
              variant="outline"
              :href="model.selectedServer.map.externalUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              <YdIcon name="i-ri:external-link-line" />打开网盘
            </YdButton>
            <YdButton v-else size="sm" type="button" variant="outline" :loading="model.mapOperating" @click="model.downloadMap(true)">
              <YdIcon name="i-ri:download-line" />下载
            </YdButton>
            <YdButton size="sm" type="button" variant="destructive" :disabled="model.mapOperating" @click="confirmDeleteMap">
              <YdIcon name="i-ri:delete-bin-line" />删除
            </YdButton>
          </div>
        </div>
      </template>
      <p v-else class="text-sm text-muted-foreground">尚未上传地图 ZIP 或填写网盘链接。</p>
    </div>
    <div class="mt-4 grid gap-3"><div class="flex flex-wrap items-center justify-between gap-2"><h3 class="text-base font-semibold">多线路地址</h3><YdButton size="sm" variant="outline" type="button" @click="model.addEndpoint"><YdIcon name="i-ri:add-line" />新增线路</YdButton></div><YdResponsiveTable :row-key="(_row, index) => String(index)" table-root-class="max-w-full overflow-x-auto rounded-lg" table-class="min-w-[980px]" border stripe :columns="columns" :data="model.serverForm.endpoints"><template #cell-name="{ row }"><YdInput v-model="row.original.name" /></template><template #cell-host="{ row }"><YdInput v-model="row.original.host" /></template><template #cell-port="{ row }"><YdInput :model-value="row.original.port ?? ''" @update:model-value="row.original.port = $event" /></template><template #cell-edition="{ row }"><YdSelect v-model="row.original.edition" :options="editionOptions" /></template><template #cell-primary="{ row }"><YdSwitch v-model="row.original.primaryLine" /></template><template #cell-enabled="{ row }"><YdSwitch v-model="row.original.enabled" /></template><template #cell-operation="{ index }"><YdButton size="sm" variant="destructive" type="button" @click="model.removeEndpoint(index)"><YdIcon name="i-ri:delete-bin-line" /></YdButton></template><template #card="{ row, index }"><YdCard class="w-full"><div class="flex flex-col gap-3"><div class="flex items-center justify-between gap-2"><span class="min-w-0 break-words text-base font-semibold">线路名称</span><YdInput v-model="row.name" class="w-36 shrink-0" /></div><div class="flex flex-col gap-2 text-sm"><div class="flex items-center justify-between gap-2"><span class="shrink-0 text-secondary-foreground/60">主机</span><YdInput v-model="row.host" class="w-44 shrink-0" /></div><div class="flex items-center justify-between gap-2"><span class="shrink-0 text-secondary-foreground/60">端口</span><YdInput :model-value="row.port ?? ''" class="w-24 shrink-0" @update:model-value="row.port = $event" /></div><div class="flex items-center justify-between gap-2"><span class="shrink-0 text-secondary-foreground/60">版本</span><YdSelect v-model="row.edition" :options="editionOptions" class="w-28 shrink-0" /></div><div class="flex items-center justify-between gap-2"><span class="shrink-0 text-secondary-foreground/60">主线</span><YdSwitch v-model="row.primaryLine" /></div><div class="flex items-center justify-between gap-2"><span class="shrink-0 text-secondary-foreground/60">启用</span><YdSwitch v-model="row.enabled" /></div></div><div class="flex flex-wrap gap-2 border-t pt-3"><YdButton size="sm" variant="destructive" type="button" @click="model.removeEndpoint(index)"><YdIcon name="i-ri:delete-bin-line" /></YdButton></div></div></YdCard></template></YdResponsiveTable></div>
    <div v-if="model.serverForm.id" class="mt-4 grid gap-3 rounded-lg border p-4">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 class="text-base font-semibold">群服互联</h3>
          <p class="text-sm text-muted-foreground">把进退服、聊天、死亡与成就消息转发到指定 QQ 群，并把群聊消息转进游戏。需要服务器内安装 YuDream 桥接插件并完成上报配置。</p>
        </div>
        <label class="inline-flex items-center gap-2 text-sm">启用 <YdSwitch v-model="model.bridgeForm.enabled" /></label>
      </div>
      <YdAlert v-if="!model.bridgeSettings?.configured && model.bridgeConnections.length === 0" title="尚未配置消息连接">
        <template #description>
          没有可用的消息连接。请先在宿主的消息/机器人设置中启用至少一个群聊连接，再回到这里选择群聊。
        </template>
      </YdAlert>
      <div class="grid gap-3 md:grid-cols-2">
        <label class="grid gap-2">
          <span>消息连接</span>
          <YdSelect
            v-model="model.bridgeForm.connectionId"
            :options="bridgeConnectionOptions"
            placeholder="选择机器人连接"
            @change="model.onBridgeConnectionChange(String($event ?? ''))"
          />
        </label>
        <label class="grid gap-2">
          <span>群聊</span>
          <YdSelect
            v-model="model.bridgeForm.channelId"
            :options="bridgeGroupOptions"
            placeholder="选择要互通的群聊"
            :disabled="!model.bridgeForm.connectionId"
            @change="model.onBridgeGroupChange(String($event ?? ''))"
          />
        </label>
      </div>
      <div class="grid gap-2 sm:grid-cols-[repeat(2,minmax(0,1fr))] lg:grid-cols-3">
        <label v-for="toggle in bridgeForwardToggles" :key="toggle.key" class="flex items-start justify-between gap-3 rounded-lg border p-3">
          <span class="grid gap-1">
            <span class="text-sm font-medium">{{ toggle.label }}</span>
            <span class="text-xs text-muted-foreground">{{ toggle.hint }}</span>
          </span>
          <YdSwitch v-model="model.bridgeForm[toggle.key]" />
        </label>
      </div>
      <label class="grid gap-2">
        <span class="text-sm font-medium">聊天合并窗口（秒）</span>
        <YdInput v-model="model.bridgeForm.forwardMergeSeconds" type="number" :min="0" :max="60" placeholder="0-60，0=不合并" class="w-full" />
        <span class="text-xs text-muted-foreground">窗口内多条游戏聊天合并为一条 QQ 消息发送（官方机器人频限友好），0=不合并。</span>
      </label>
      <div>
        <YdButton type="button" :loading="model.bridgeSaving" @click="model.saveBridgeSettings"><YdIcon name="i-ri:save-3-line" />保存群服互联设置</YdButton>
      </div>
    </div>
    <div class="mt-4 grid gap-3"><h3 class="text-base font-semibold">Markdown 描述</h3><MarkdownEditor v-model="model.serverForm.descriptionMarkdown" :upload-image="model.uploadMarkdownImage" /></div><div class="mt-4 flex flex-wrap justify-end gap-2"><YdButton v-if="model.serverForm.id" type="button" variant="destructive" :loading="model.saving" @click="confirmDelete">删除</YdButton><YdButton type="submit" :loading="model.saving"><YdIcon name="i-ri:save-3-line" />保存</YdButton></div>
  </form></YdPageMain>
</template>
