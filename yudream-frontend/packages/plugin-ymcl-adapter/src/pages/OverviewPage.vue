<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import {
  FaButton,
  FaCard,
  FaIcon,
  FaImageUpload,
  FaInput,
  FaPageHeader,
  FaPageMain,
  useFaToast,
} from '@yudream/components'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import JsonViewDrawer from '../components/JsonViewDrawer.vue'
import { useYmclAdapter } from '../composables/useYmclAdapter'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useYmclAdapter(props.sdk)
const toast = useFaToast()
const router = useRouter()

const manifestDrawer = ref(false)
const domainEditing = ref(false)
const logoList = ref<string[]>([])

const domainDisplayName = computed(() =>
  model.domainForm.name?.trim() || model.origin)

const domainLogo = computed(() => model.domainForm.logo_url?.trim() || '')

watch(() => model.domainForm.logo_url, (value) => {
  logoList.value = value ? [value] : []
}, { immediate: true })

watch(logoList, (list) => {
  model.domainForm.logo_url = list.at(-1) || ''
}, { deep: true })

async function uploadDomainImage(options: { file: File }) {
  return await model.uploadImage(options.file)
}

function afterUpload(response: unknown) {
  return typeof response === 'string' ? response : ''
}

const playerPerks = [
  {
    icon: 'i-ri:server-2-line',
    title: '服务器与整合包',
    description: '一键进入服务器、安装推荐整合包',
  },
  {
    icon: 'i-ri:shirt-line',
    title: '皮肤与披风',
    description: '在启动器里管理角色外观',
  },
  {
    icon: 'i-ri:task-line',
    title: '活动与任务',
    description: '查看社区活动、打卡与任务进度',
  },
]

const shortcuts = [
  {
    title: '域导航',
    description: '组织启动器侧边栏：排序、目录分组、启停',
    icon: 'i-ri:menu-line',
    path: '/platform/plugins/ymcl-adapter/admin/navigation',
    need: 'design' as const,
  },
  {
    title: '首页布局',
    description: '下发给成员的首页卡片布局',
    icon: 'i-ri:layout-masonry-line',
    path: '/platform/plugins/ymcl-adapter/admin/chrome-home',
    need: 'design' as const,
  },
  {
    title: '域主题',
    description: '托管启动器外观（模式、强调色、背景）',
    icon: 'i-ri:palette-line',
    path: '/platform/plugins/ymcl-adapter/admin/chrome-theme',
    need: 'design' as const,
  },
  {
    title: '整合包分发',
    description: '管理 pack 元数据、版本与预览',
    icon: 'i-ri:box-3-line',
    path: '/platform/plugins/ymcl-adapter/admin/packs',
    need: 'publish' as const,
  },
  {
    title: '服务器绑定',
    description: '服务器与整合包版本绑定',
    icon: 'i-ri:server-line',
    path: '/platform/plugins/ymcl-adapter/admin/servers',
    need: 'publish' as const,
  },
]

const visibleShortcuts = computed(() =>
  shortcuts.filter(item =>
    item.need === 'design' ? model.canDesign : model.canPublish))

function openShortcut(path: string) {
  void router.push(path)
}

onMounted(() => {
  void model.loadOverview()
  void model.loadDomainConfig()
})

function saveDomain() {
  if (!domainFormValid()) {
    return
  }
  void model.saveDomainConfig().then((saved) => {
    if (saved) {
      domainEditing.value = false
      void model.loadOverview()
    }
  })
}

function domainFormValid() {
  if (model.domainForm.logo_url && !/^https?:\/\//i.test(model.domainForm.logo_url.trim())) {
    toast.error('Logo 需通过上传获得公开图片地址')
    return false
  }
  return true
}
</script>

<template>
  <div class="ymcl-page">
    <FaPageHeader
      :title="domainDisplayName"
      description="用 YMCL-Axolotl 启动器加入本站，进入服务器、皮肤与社区内容。"
    >
      <FaButton v-if="model.canDesign || model.canPublish" variant="outline" @click="manifestDrawer = true">
        <FaIcon name="i-ri:code-s-slash-line" />
        manifest 预览
      </FaButton>
      <FaButton @click="model.openYmcl">
        <FaIcon name="i-ri:rocket-2-line" />
        打开启动器
      </FaButton>
    </FaPageHeader>

    <FaPageMain>
      <div class="ymcl-overview-grid">
        <FaCard
          title="加入本站"
          description="把这台设备上的 YMCL 启动器连到本站"
          content-class="ymcl-card-content"
        >
          <div class="ymcl-join-hero">
            <img
              v-if="domainLogo"
              :src="domainLogo"
              alt=""
              class="ymcl-join-hero__logo"
            >
            <div class="ymcl-join-hero__body">
              <strong class="ymcl-join-hero__name">{{ domainDisplayName }}</strong>
              <span v-if="model.domainForm.description" class="ymcl-muted">
                {{ model.domainForm.description }}
              </span>
              <code class="ymcl-origin">{{ model.origin }}</code>
            </div>
          </div>

          <div class="ymcl-action-row">
            <FaButton @click="model.openYmcl">
              <FaIcon name="i-ri:rocket-2-line" />
              一键加入
            </FaButton>
            <FaButton variant="outline" @click="model.copy(model.addSiteUri, '加域链接已复制')">
              <FaIcon name="i-ri:links-line" />
              复制加域链接
            </FaButton>
            <FaButton variant="ghost" @click="model.copy(model.origin, '站点地址已复制')">
              <FaIcon name="i-ri:file-copy-line" />
              复制站点地址
            </FaButton>
          </div>

          <ol class="ymcl-join-steps">
            <li>点「一键加入」，系统会唤起 YMCL-Axolotl 启动器</li>
            <li>在启动器弹窗里确认添加本站</li>
            <li>登录后即可使用服务器、皮肤、活动等功能</li>
          </ol>
          <p class="ymcl-muted">
            一键加入依赖系统登记的 ymcl:// 协议。若拉起的是旧 SJMC/YMCL，请重新构建安装
            YMCL-Axolotl 后重试；也可复制加域链接粘贴到启动器的添加域入口。
          </p>
        </FaCard>

        <FaCard
          title="在启动器里能做什么"
          description="登录本站域后，成员可直接使用这些能力"
          content-class="ymcl-card-content"
        >
          <ul class="ymcl-perk-list">
            <li v-for="perk in playerPerks" :key="perk.title" class="ymcl-perk">
              <FaIcon :name="perk.icon" />
              <div>
                <strong>{{ perk.title }}</strong>
                <p class="ymcl-muted">{{ perk.description }}</p>
              </div>
            </li>
          </ul>
        </FaCard>

        <template v-if="model.canDesign">
          <FaCard
            title="域身份"
            description="启动器域列表里显示的名称与 Logo"
            content-class="ymcl-card-content"
          >
            <template v-if="domainEditing">
              <div class="ymcl-form-row">
                <label class="ymcl-label" for="ymcl-domain-name">显示名称</label>
                <FaInput id="ymcl-domain-name" v-model="model.domainForm.name" placeholder="留空则使用站点地址" />
              </div>
              <div class="ymcl-form-row">
                <label class="ymcl-label" for="ymcl-domain-desc">简介</label>
                <FaInput id="ymcl-domain-desc" v-model="model.domainForm.description" placeholder="域的一句话介绍（可选）" />
              </div>
              <div class="ymcl-form-row">
                <label class="ymcl-label" for="ymcl-domain-logo">Logo</label>
                <FaImageUpload
                  id="ymcl-domain-logo"
                  v-model="logoList"
                  :max="1"
                  :width="96"
                  :height="96"
                  :http-request="uploadDomainImage"
                  :after-upload="afterUpload"
                />
              </div>
              <div class="ymcl-action-row">
                <FaButton size="sm" :loading="model.saving" @click="saveDomain">
                  保存
                </FaButton>
                <FaButton size="sm" variant="outline" @click="domainEditing = false">
                  取消
                </FaButton>
              </div>
            </template>
            <template v-else>
              <dl class="ymcl-info-list">
                <div>
                  <dt>显示名称</dt>
                  <dd>{{ model.domainForm.name || '（未配置，使用站点地址）' }}</dd>
                </div>
                <div>
                  <dt>简介</dt>
                  <dd>{{ model.domainForm.description || '-' }}</dd>
                </div>
                <div>
                  <dt>Logo</dt>
                  <dd>
                    <img
                      v-if="model.domainForm.logo_url"
                      :src="model.domainForm.logo_url"
                      alt="域 Logo"
                      class="ymcl-domain-logo-preview"
                    >
                    <span v-else class="ymcl-break">-</span>
                  </dd>
                </div>
              </dl>
              <div class="ymcl-action-row">
                <FaButton size="sm" @click="domainEditing = true">
                  编辑身份
                </FaButton>
              </div>
            </template>
          </FaCard>
        </template>

        <FaCard
          v-if="visibleShortcuts.length"
          title="管理入口"
          description="仅对具备 design / publish 权限的管理员显示"
          content-class="ymcl-card-content"
        >
          <div class="ymcl-shortcut-grid">
            <button
              v-for="item in visibleShortcuts"
              :key="item.path"
              type="button"
              class="ymcl-shortcut"
              @click="openShortcut(item.path)"
            >
              <FaIcon :name="item.icon" />
              <span class="ymcl-shortcut__title">{{ item.title }}</span>
              <span class="ymcl-shortcut__desc">{{ item.description }}</span>
            </button>
          </div>
        </FaCard>
      </div>
    </FaPageMain>

    <JsonViewDrawer v-model="manifestDrawer" title="manifest 预览（启动器视角）" :payload="model.manifest" />
  </div>
</template>
