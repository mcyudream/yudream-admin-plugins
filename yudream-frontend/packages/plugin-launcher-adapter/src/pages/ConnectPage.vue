<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaCard, FaIcon, FaPageHeader, FaPageMain } from '@yudream/components'
import { useLauncherPlugin } from '../composables/useLauncherPlugin'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useLauncherPlugin(props.sdk)
</script>

<template>
  <section class="launcher-connect">
    <FaPageHeader title="连接 YMCL" description="把当前站点授权给 YMCL：点击拉起启动器，或拖动基址到启动器的站点输入框。" class="mb-0">
      <FaButton @click="model.openYmcl">
        <FaIcon name="i-ri:external-link-line" />
        打开 YMCL
      </FaButton>
    </FaPageHeader>

    <FaPageMain>
      <div class="launcher-connect-grid">
        <FaCard title="域站基址" description="填写站点前端地址，不要填后端端口" content-class="launcher-card-content">
          <div class="launcher-address-row">
            <code>{{ model.origin }}</code>
            <FaButton size="sm" variant="outline" @click="model.copy(model.origin, '域站基址已复制')">
              <FaIcon name="i-ri:file-copy-line" />
              复制
            </FaButton>
          </div>
          <button
            type="button"
            class="launcher-drag-chip"
            draggable="true"
            title="拖到 YMCL 自动填充域站基址"
            @dragstart="model.applyDragPayload"
          >
            <FaIcon name="i-ri:drag-move-2-line" />
            <span>拖到 YMCL</span>
            <code>{{ model.siteToken }}</code>
          </button>
          <p class="launcher-muted">
            拖放载荷为编码后的 <code>ymcl:site:</code> 邀请文件（<code>.ymclsite</code>）。YMCL 发现 API 时会依次探测 <code>/api</code> 与 <code>/proxy/api</code>。
          </p>
        </FaCard>

        <FaCard title="一键授权" description="通过 ymcl:// 私有协议拉起启动器" content-class="launcher-card-content">
          <div class="launcher-address-row">
            <code>{{ model.addSiteUri }}</code>
            <FaButton size="sm" variant="outline" @click="model.copy(model.addSiteUri, '启动协议已复制')">
              <FaIcon name="i-ri:file-copy-line" />
              复制协议
            </FaButton>
          </div>
          <FaButton @click="model.openYmcl">
            <FaIcon name="i-ri:rocket-2-line" />
            添加到 YMCL
          </FaButton>
          <p class="launcher-muted">
            若浏览器询问是否打开 YMCL，请允许。未安装启动器时协议无法拉起，请先安装后再试。
          </p>
        </FaCard>
      </div>
    </FaPageMain>
  </section>
</template>
