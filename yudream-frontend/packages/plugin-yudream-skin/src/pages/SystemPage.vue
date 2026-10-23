<script setup lang="ts">
import type { FileItem, FileUploadRequestOptions } from '@yudream/components'
import type { SkinPluginModel } from '../composables/useSkinPlugin'
import { YdButton, YdFileUpload, YdIcon, YdInput, YdLabel, YdModal, YdNumberField, YdPageHeader, YdSettingsLayout, YdSettingsSection, YdSwitch, YdTag } from '@yudream/components'
import { ref } from 'vue'

const props = defineProps<{ model: SkinPluginModel }>()

/** 设置页分区：常规配置与数据迁移共用同一页面 */
const activeSection = ref('main')
const sectionList: { icon?: string, label: string, value: string }[] = [
  { icon: 'i-ri:settings-3-line', label: '常规设置', value: 'main' },
  { icon: 'i-ri:verified-badge-line', label: '正版绑定', value: 'official' },
  { icon: 'i-ri:database-2-line', label: '数据迁移', value: 'migration' },
]

const migrationFiles = ref<FileItem[]>([])

async function selectMigrationArchive(options: FileUploadRequestOptions) {
  await props.model.handleMigrationArchive(options.file)
  options.onProgress(100)
  return { selected: true }
}

function stateText(state: string) {
  return ({ IDLE: '未迁移', PENDING: '等待迁移', RUNNING: '正在迁移', SUCCESS: '迁移完成', FAILED: '迁移失败' } as Record<string, string>)[state] || state
}
</script>

<template>
  <div class="settings-page-shell">
    <YdPageHeader title="皮肤插件设置" class="mb-0">
      <YdButton v-if="activeSection === 'main'" :loading="model.saving === 'settings'" @click="model.saveSettings">
        <YdIcon name="i-ri:save-3-line" />
        保存设置
      </YdButton>
      <YdButton v-else-if="activeSection === 'official'" :loading="model.saving === 'official-bind-settings'" @click="model.saveOfficialBindSettings">
        <YdIcon name="i-ri:save-3-line" />
        保存正版绑定配置
      </YdButton>
      <YdButton v-else variant="outline" @click="model.openMigrationLog">
        <YdIcon name="i-ri:file-list-3-line" />
        查看日志
      </YdButton>
    </YdPageHeader>

    <YdSettingsLayout
      v-model="activeSection"
      :list="sectionList"
      surface
      scroll
      width-class="max-w-[880px]"
    >
      <template #main>
        <YdSettingsSection
          title="常规设置"
          description="皮肤站容量与公开上传策略。"
        >
          <div class="grid max-w-3xl gap-4">
            <YdLabel class="grid gap-2">
              <span>每个用户最大角色数</span>
              <YdNumberField v-model="model.settingsForm.maxPlayersPerUser" :min="0" :step="1" class="w-full" />
              <span class="text-sm text-muted-foreground">0 表示不限制。</span>
            </YdLabel>
            <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
              <div class="grid gap-1">
                <strong>公开上传材质</strong>
                <span class="text-sm text-muted-foreground">{{ model.settingsForm.allowPublicUpload ? '允许普通用户公开上传' : '仅管理员可公开材质' }}</span>
              </div>
              <YdSwitch v-model="model.settingsForm.allowPublicUpload" />
            </div>
          </div>
        </YdSettingsSection>
      </template>

      <template #official>
        <YdSettingsSection
          title="Microsoft 正版角色绑定"
          description="配置 Azure public-client 后，用户可以通过 Microsoft OAuth 验证 Java 版角色所有权。refresh token 仅保存在服务端 SecretStore，用于官方会话自动续签。"
        >
          <div class="grid max-w-3xl gap-4">
            <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
              <div class="grid gap-1">
                <strong>开放正版角色绑定</strong>
                <span class="text-sm text-muted-foreground">关闭后不再允许发起新绑定；既有绑定的续签也应在维护窗口前先解除。</span>
              </div>
              <YdSwitch v-model="model.officialBindSettings.enabled" />
            </div>
            <YdLabel class="grid gap-2">
              <span>Azure 应用 Client ID</span>
              <YdInput v-model="model.officialBindSettings.clientId" placeholder="Microsoft Entra public client 的 application (client) ID" />
            </YdLabel>
            <div class="grid gap-1 rounded-lg border p-3">
              <span class="text-sm font-semibold">回调地址（在 Azure 应用“身份验证”中以 Web 平台原样注册）</span>
              <code class="skin-admin-code">{{ model.officialBindSettings.redirectUri || '宿主未配置 app.web-url / app.base-url，无法推导回调地址' }}</code>
              <span class="text-sm text-muted-foreground">Azure 按整串精确匹配：协议、域名、端口、路径必须与注册值完全一致，末尾不能多斜杠；平台类型必须选 Web（不能选 SPA，服务端令牌交换会被 CORS 拦截），且需启用“允许公共客户端流”。</span>
            </div>
          </div>
        </YdSettingsSection>
      </template>

      <template #migration>
        <YdSettingsSection
          title="Blessing Skin 数据迁移"
          description="从 Blessing Skin 数据库导入用户、角色、材质与衣柜数据；可同时上传材质 ZIP 压缩包。"
        >
          <form class="grid gap-4" @submit.prevent="model.runMigration">
            <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
              <YdLabel class="grid gap-2"><span>数据库主机</span><YdInput v-model="model.migrationForm.host" placeholder="localhost" /></YdLabel>
              <YdLabel class="grid gap-2"><span>端口</span><YdNumberField v-model="model.migrationForm.port" :min="1" :max="65535" :step="1" class="w-full" /></YdLabel>
              <YdLabel class="grid gap-2"><span>数据库名</span><YdInput v-model="model.migrationForm.database" placeholder="blessing_skin" /></YdLabel>
              <YdLabel class="grid gap-2"><span>账号</span><YdInput v-model="model.migrationForm.username" /></YdLabel>
              <YdLabel class="grid gap-2"><span>密码</span><YdInput v-model="model.migrationForm.password" type="password" /></YdLabel>
              <YdLabel class="grid gap-2"><span>材质压缩包</span><YdFileUpload v-model="migrationFiles" :max="1" :http-request="selectMigrationArchive" description="拖放或点击选择 ZIP 压缩包" /></YdLabel>
            </div>
            <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
              <div class="grid gap-1">
                <strong>{{ stateText(model.migrationStatus.state) }}</strong>
                <span v-if="model.migrationStatus.startedAt" class="text-sm text-muted-foreground">开始于 {{ model.dateText(model.migrationStatus.startedAt) }}</span>
              </div>
              <YdButton type="submit" :loading="model.saving === 'migration' || model.migrationStatus.running">
                <YdIcon name="i-ri:database-2-line" />
                {{ model.migrationStatus.running ? '正在迁移' : '开始迁移' }}
              </YdButton>
            </div>
            <div v-if="model.migrationReport" class="report-grid mt-4">
              <span>用户 {{ model.migrationReport.users }}</span>
              <span>角色 {{ model.migrationReport.players }}</span>
              <span>材质 {{ model.migrationReport.textures }}</span>
              <span>衣柜 {{ model.migrationReport.closetItems }}</span>
              <span>配置 {{ model.migrationReport.options }}</span>
            </div>
          </form>
          <YdModal v-model="model.migrationLogVisible" title="迁移日志" :footer="false" class="sm:max-w-4xl">
            <div class="migration-log">
              <div class="migration-log__head"><strong>{{ stateText(model.migrationStatus.state) }}</strong></div>
              <div class="migration-log__body">
                <div v-if="!model.migrationLogs.length" class="empty-state compact">暂无迁移日志</div>
                <p v-for="item in model.migrationLogs" :key="`${item.time}-${item.message}`">
                  <time>{{ model.dateText(item.time) }}</time>
                  <span>
                    <YdTag v-if="item.level === 'ERROR'" variant="destructive" class="mr-2">错误</YdTag>
                    {{ item.message }}
                  </span>
                </p>
              </div>
            </div>
          </YdModal>
        </YdSettingsSection>
      </template>
    </YdSettingsLayout>
  </div>
</template>
