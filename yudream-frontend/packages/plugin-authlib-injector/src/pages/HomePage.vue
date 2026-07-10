<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { AuthlibEndpoint } from '../types'
import type { AuthlibPluginModel } from '../composables/useAuthlibPlugin'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain, FaTable, FaTag } from '@yudream/components'

defineProps<{ model: AuthlibPluginModel }>()

const columns: TableColumn<AuthlibEndpoint>[] = [
  { accessorKey: 'method', header: '方法', width: 100, fixed: 'left' },
  { accessorKey: 'path', header: '协议路径', width: 420 },
  { accessorKey: 'note', header: '用途', width: 360 },
]
</script>

<template>
  <section class="authlib-home">
    <FaPageHeader title="Authlib 运行状态" description="查看 Yggdrasil 协议服务地址、依赖状态和固定协议端点。" class="mb-0">
      <FaButton @click="model.copy(model.launcherUrl)">
        <FaIcon name="i-ri:file-copy-line" />
        复制 API 地址
      </FaButton>
    </FaPageHeader>

    <FaPageMain>
      <div class="authlib-summary-grid">
        <FaCard title="启动器配置" description="Yggdrasil 服务根地址" content-class="authlib-card-content">
          <div class="authlib-address-row">
            <code>{{ model.launcherUrl }}</code>
            <FaButton size="sm" variant="outline" @click="model.copy(model.launcherUrl)">
              <FaIcon name="i-ri:file-copy-line" />
              复制
            </FaButton>
          </div>
          <p class="authlib-muted">将此地址填写到支持 authlib-injector 的启动器或服务端配置中。</p>
        </FaCard>

        <FaCard title="服务状态" description="仅管理端可见" content-class="authlib-card-content">
          <div class="authlib-status-line">
            <span>连接状态</span>
            <FaTag :variant="model.status ? 'default' : 'secondary'">{{ model.statusText }}</FaTag>
          </div>
          <div class="authlib-status-line"><span>账号来源</span><strong>{{ model.status?.accountSource || '-' }}</strong></div>
          <div class="authlib-status-line"><span>皮肤插件</span><strong>{{ model.status?.skinPluginEnabled ? '已启用' : '未启用' }}</strong></div>
          <div class="authlib-status-line"><span>材质地址</span><code>{{ model.status?.textureBaseUrl || '-' }}</code></div>
        </FaCard>
      </div>

      <FaTable
        v-loading="model.loading"
        row-key="path"
        table-root-class="authlib-endpoint-table-root rounded-lg overflow-hidden"
        table-class="min-w-[880px]"
        border
        stripe
        column-visibility
        :columns="columns"
        :data="model.endpoints"
      >
        <template #toolbar>
          <div class="authlib-table-toolbar">
            <div>
              <strong>协议端点</strong>
              <span>固定协议清单，不作为管理 CRUD 数据。</span>
            </div>
            <FaTag variant="secondary">{{ model.endpoints.length }} 个端点</FaTag>
          </div>
        </template>
        <template #cell-method="{ row }">
          <FaTag :variant="row.original.method === 'GET' ? 'secondary' : 'default'">{{ row.original.method }}</FaTag>
        </template>
        <template #cell-path="{ row }"><code>{{ row.original.path }}</code></template>
      </FaTable>
    </FaPageMain>
  </section>
</template>
