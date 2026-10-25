<script setup lang="ts">
import type { ActionMenuItem, TableColumn } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McInteropPeer } from '../types'
import { YdAlert, YdButton, YdCopyButton, YdForm, YdFormItem, YdInput, YdLoadingOverlay, YdModal, YdPageHeader, YdResponsiveEnhancedTable, YdSettingsLayout, YdSettingsSection, useYdModal, useYdToast } from '@yudream/components'
import { ref } from 'vue'
import { useMcInteropSite } from '../composables/useMcInteropSite'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useMcInteropSite(props.sdk)
const toast = useYdToast()
const confirm = useYdModal()
const activeSection = ref('site')

// 协议入口挂在插件分发路径下：零反代零网关，dev/生产任何部署形态直接可达。
const wellKnownUrl = `${window.location.origin}/api/plugins/mc-interop/.well-known/mc-interop-v1.json`

const sectionList = [
  { icon: 'i-ri:global-line', label: '站点身份', value: 'site' },
  { icon: 'i-ri:links-line', label: '友站声明', value: 'peers' },
]

const peerColumns: TableColumn<McInteropPeer>[] = [
  { accessorKey: 'siteId', header: 'siteId', minWidth: 120 },
  { accessorKey: 'name', header: '名称', minWidth: 160 },
  { accessorKey: 'domain', header: '域名', minWidth: 180 },
  { accessorKey: 'manifestUrl', header: '清单地址', minWidth: 280 },
]

const peerRowActions: ActionMenuItem<McInteropPeer>[] = [
  { label: '删除', icon: 'morph:trash-2', danger: true, onClick: peer => removePeer(peer) },
]

function save() {
  return model.save().then(() => {
    toast.success('站点身份已保存')
  }).catch((cause: unknown) => {
    toast.error(cause instanceof Error ? cause.message : '保存失败')
  })
}

function savePeer() {
  return model.savePeer().then(() => {
    toast.success('友站已保存')
  }).catch((cause: unknown) => {
    toast.error(cause instanceof Error ? cause.message : '保存失败')
  })
}

function removePeer(peer: McInteropPeer) {
  confirm.confirm({
    title: '删除友站声明',
    content: `确认删除友站「${peer.name || peer.siteId}」吗？出口的 peers 列表将不再包含它。`,
    onConfirm: () => model.removePeer(peer.siteId),
  })
}
</script>

<template>
  <div class="settings-page-shell">
    <YdPageHeader title="站点与友站" class="mb-0">
      <YdButton v-if="activeSection === 'site'" :loading="model.loading" @click="save">
        保存
      </YdButton>
      <YdButton v-else @click="model.peerOpen = true">
        声明友站
      </YdButton>
    </YdPageHeader>
    <YdAlert v-if="model.error" class="mb-3" variant="destructive" title="操作失败" :description="model.error" />
    <YdSettingsLayout
      v-model="activeSection"
      :list="sectionList"
      surface
      scroll
      width-class="max-w-[880px]"
    >
      <template #site>
        <YdSettingsSection
          title="站点身份"
          description="站点身份优先来自联合认证插件（站点身份页配置的 siteCode/站点名/origin，单一事实源）；未启用 union 时才在本页维护。"
        >
          <YdForm :model="model.settings" layout="vertical">
            <div class="grid max-w-3xl gap-4">
              <YdAlert
                v-if="model.settings.identitySource === 'union'"
                variant="default"
                title="身份来自联合认证插件"
                description="site.id / 站点名 / 根域名由 yudream-union 统一下发，此处只读；如需调整请到联合认证 → 站点身份页修改。"
              />
              <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
                <YdFormItem label="site.id" required>
                  <YdInput
                    v-model="model.settings.siteId"
                    maxlength="128"
                    placeholder="如 yda"
                    :disabled="model.settings.identitySource === 'union'"
                  />
                </YdFormItem>
                <YdFormItem label="站点名" required>
                  <YdInput
                    v-model="model.settings.siteName"
                    maxlength="128"
                    :disabled="model.settings.identitySource === 'union'"
                  />
                </YdFormItem>
              </div>
              <YdFormItem label="根域名" required>
                <YdInput
                  v-model="model.settings.domain"
                  maxlength="253"
                  placeholder="如 mc.yudream.app"
                  :disabled="model.settings.identitySource === 'union'"
                />
              </YdFormItem>
              <YdFormItem label="简介">
                <YdInput v-model="model.settings.description" maxlength="2000" />
              </YdFormItem>
            </div>
          </YdForm>
        </YdSettingsSection>
        <YdSettingsSection
          title="出口端点"
          description="协议入口挂在插件路径下（宿主聚合本站出口 + peers 互采，ETag/5 分钟缓存），零反代零网关配置。当前部署的完整可用地址："
        >
          <div class="flex flex-wrap items-center gap-2">
            <code class="break-all text-sm">{{ wellKnownUrl }}</code>
            <YdCopyButton :text="wellKnownUrl" label="复制" size="sm" variant="outline" />
          </div>
          <p v-if="model.settings.domain" class="mt-2 text-xs text-muted-foreground">
            站点对外域名（{{ model.settings.domain }}）上线后，友站同样以
            https://{{ model.settings.domain }}/api/plugins/mc-interop/.well-known/mc-interop-v1.json 发现本站。
          </p>
          <p class="mt-2 text-xs text-muted-foreground">
            等价入口：宿主根别名 /.well-known/mc-interop-v1.json（宿主直连时可用）· 早期单站路径
            /api/plugins/mc-interop/interop/v1/site
          </p>
        </YdSettingsSection>
      </template>

      <template #peers>
        <YdLoadingOverlay :loading="model.loading">
          <YdResponsiveEnhancedTable
            :columns="peerColumns"
            :data="model.peers"
            row-key="siteId"
            table-root-class="rounded-lg overflow-hidden"
            table-class="min-w-[640px]"
            border
            stripe
            :actions="peerRowActions"
            empty-text="尚未声明友站"
          />
        </YdLoadingOverlay>
        <p class="mt-2 text-sm text-muted-foreground">
          peers 供其他站点互相发现（启动器可忽略）；友站内容是否采入由聚合方决定。
        </p>
      </template>
    </YdSettingsLayout>

  <YdModal v-model="model.peerOpen" title="声明友站" show-cancel-button @confirm="savePeer">
    <YdForm :model="model.peerForm" layout="vertical">
      <div class="flex flex-col gap-4">
        <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <YdFormItem label="siteId" required>
            <YdInput v-model="model.peerForm.siteId" maxlength="128" placeholder="如 sitmc" />
          </YdFormItem>
          <YdFormItem label="名称">
            <YdInput v-model="model.peerForm.name" maxlength="128" />
          </YdFormItem>
        </div>
        <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <YdFormItem label="根域名">
            <YdInput v-model="model.peerForm.domain" maxlength="253" placeholder="skin.sitmc.club" />
          </YdFormItem>
          <YdFormItem label="清单地址" required>
            <YdInput v-model="model.peerForm.manifestUrl" placeholder="https://…/.well-known/mc-interop-v1.json" />
          </YdFormItem>
        </div>
      </div>
    </YdForm>
  </YdModal>
  </div>
</template>
