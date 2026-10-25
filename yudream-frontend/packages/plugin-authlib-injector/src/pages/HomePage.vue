<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { AuthlibEndpoint } from '../types'
import type { AuthlibPluginModel } from '../composables/useAuthlibPlugin'
import { YdButton, YdCard, YdCopyButton, YdInput, YdLabel, YdLoadingOverlay, YdPageHeader, YdPageMain, YdResponsiveEnhancedTable, YdTag } from '@yudream/components'
import { ref, watch } from 'vue'

const props = defineProps<{ model: AuthlibPluginModel }>()

const columns: TableColumn<AuthlibEndpoint>[] = [
  { accessorKey: 'method', header: '方法', width: 100 },
  { accessorKey: 'path', header: '协议路径', width: 420 },
  { accessorKey: 'note', header: '用途', width: 360 },
]

const serverNameDraft = ref('')

// 设置加载完成或保存成功后，把草稿同步回后端权威值
watch(() => props.model.settings?.serverNameOverride, (value) => {
  serverNameDraft.value = value ?? ''
}, { immediate: true })

function saveServerName() {
  void props.model.saveServerName(serverNameDraft.value)
}
</script>

<template>
  <section class="authlib-home">
    <YdPageHeader title="Authlib 运行状态" description="查看 Yggdrasil 协议服务地址、依赖状态和固定协议端点。" class="mb-0">
      <YdCopyButton :text="model.launcherUrl" label="复制 API 地址" />
    </YdPageHeader>

    <YdPageMain>
      <div class="authlib-summary-grid">
        <YdCard title="启动器配置" description="Yggdrasil 服务根地址" content-class="authlib-card-content">
          <div class="authlib-address-row">
            <code>{{ model.launcherUrl }}</code>
            <YdCopyButton :text="model.launcherUrl" label="复制" size="sm" variant="outline" />
          </div>
          <p class="authlib-muted">将此地址填写到支持 authlib-injector 的启动器或服务端配置中。</p>
        </YdCard>

        <YdCard title="展示名称" description="启动器中显示的验证服名称" content-class="authlib-card-content">
          <div class="authlib-name-row">
            <YdLabel>
              <span>自定义展示名</span>
              <YdInput v-model="serverNameDraft" placeholder="留空使用站点默认" />
            </YdLabel>
            <YdButton :loading="model.savingSettings" :disabled="model.savingSettings" @click="saveServerName">保存</YdButton>
          </div>
          <p class="authlib-muted">
            当前生效：<strong>{{ model.settings?.serverName || '-' }}</strong>。优先级：自定义 &gt; 站点描述 &gt; 站点名称；留空保存即回到站点默认。
          </p>
        </YdCard>

        <YdCard title="服务状态" description="仅管理端可见" content-class="authlib-card-content">
          <div class="authlib-status-line">
            <span>连接状态</span>
            <span class="yd-status-tone" :data-tone="model.status ? 'success' : 'warning'">{{ model.statusText }}</span>
          </div>
          <div class="authlib-status-line"><span>账号来源</span><strong>{{ model.status?.accountSource || '-' }}</strong></div>
          <div class="authlib-status-line">
            <span>皮肤插件</span>
            <span class="yd-status-tone" :data-tone="model.status?.skinPluginEnabled ? 'success' : 'neutral'">{{ model.status?.skinPluginEnabled ? '已启用' : '未启用' }}</span>
          </div>
          <div class="authlib-status-line"><span>材质地址</span><code>{{ model.status?.textureBaseUrl || '-' }}</code></div>
        </YdCard>
      </div>

      <div class="authlib-endpoint-head mt-4">
        <strong>协议端点</strong>
        <span>固定协议清单，不作为管理 CRUD 数据。</span>
        <YdTag variant="secondary">{{ model.endpoints.length }} 个端点</YdTag>
      </div>

      <YdLoadingOverlay :loading="model.loading">
        <YdResponsiveEnhancedTable
          row-key="path"
          table-root-class="rounded-lg overflow-hidden"
          table-class="min-w-[860px]"
          border
          stripe
          column-visibility
          :columns="columns"
          :data="model.endpoints"
          empty-text="暂无协议端点"
        >
          <template #cell-method="{ row }">
            <span class="yd-status-tone" :data-tone="row.original.method === 'GET' ? 'neutral' : 'info'">{{ row.original.method }}</span>
          </template>
          <template #cell-path="{ row }"><code>{{ row.original.path }}</code></template>
        </YdResponsiveEnhancedTable>
      </YdLoadingOverlay>
    </YdPageMain>
  </section>
</template>
