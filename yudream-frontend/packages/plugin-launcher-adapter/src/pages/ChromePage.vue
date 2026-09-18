<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import {
  FaButton,
  FaIcon,
  FaImageUpload,
  FaInput,
  FaPageHeader,
  FaPageMain,
  FaSelect,
  FaSwitch,
  FaTag,
  FaTooltip,
} from '@yudream/components'
import { computed, onMounted, ref, watch } from 'vue'
import ChromeNavTree from '../components/ChromeNavTree.vue'
import type { ChromeTreeNode } from '../components/ChromeNavTree.vue'
import NavIconPicker from '../components/NavIconPicker.vue'
import { useLauncherPlugin } from '../composables/useLauncherPlugin'
import type { YmclChromePage, YmclNavKind, YmclNavNode } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useLauncherPlugin(props.sdk)

const logoList = ref<string[]>([])
const backgroundList = ref<string[]>([])
const selectedKey = ref('')

watch(() => model.chromeForm.logoUrl, (value) => {
  logoList.value = value ? [value] : []
})

watch(() => model.chromeForm.backgroundUrl, (value) => {
  backgroundList.value = value ? [value] : []
})

watch(logoList, (list) => {
  model.chromeForm.logoUrl = list.at(-1) || ''
}, { deep: true })

watch(backgroundList, (list) => {
  model.chromeForm.backgroundUrl = list.at(-1) || ''
}, { deep: true })

onMounted(() => {
  void model.loadChrome()
})

async function uploadImage(options: { file: File }) {
  return await model.uploadChromeImage(options.file)
}

function afterUpload(response: unknown) {
  return typeof response === 'string' ? response : ''
}

const pageByCode = computed(() => {
  const map = new Map<string, YmclChromePage>()
  for (const page of model.chromePages) {
    map.set(page.code, page)
  }
  return map
})

function nodeTitle(node: YmclNavNode) {
  if (node.title.trim()) {
    return node.title
  }
  const page = pageByCode.value.get(node.pageCode)
  if (page) {
    return page.title
  }
  return node.kind === 'tab' ? '未命名 Tab' : '未命名菜单'
}

function nodeIcon(node: YmclNavNode) {
  if (node.icon.trim()) {
    return node.icon
  }
  const page = pageByCode.value.get(node.pageCode)
  if (page?.icon) {
    return page.icon
  }
  return node.kind === 'tab' ? 'i-ri:layout-top-line' : 'i-ri:menu-line'
}

function buildTree(nodes: YmclNavNode[], parent?: YmclNavNode, parentKey = ''): ChromeTreeNode[] {
  return nodes.map((node, index) => {
    const key = parentKey ? `${parentKey}/${node.code || index}` : `tab:${node.code || index}`
    return {
      key,
      title: nodeTitle(node),
      icon: nodeIcon(node),
      kind: node.kind,
      node,
      parent,
      parentKey: parentKey || undefined,
      children: buildTree(node.children || [], node, key),
    }
  })
}

const treeData = computed<ChromeTreeNode[]>(() => buildTree(model.chromeForm.navTree))

const selectedNode = computed(() => findNode(treeData.value, selectedKey.value))
const selectedNav = computed(() => selectedNode.value?.node)
const selectedPage = computed(() => {
  const code = selectedNav.value?.pageCode
  return code ? pageByCode.value.get(code) : undefined
})

watch(treeData, (nodes) => {
  if (selectedKey.value && findNode(nodes, selectedKey.value)) {
    return
  }
  selectedKey.value = nodes[0]?.key || ''
}, { immediate: true })

function findNode(nodes: ChromeTreeNode[], key: string): ChromeTreeNode | undefined {
  for (const node of nodes) {
    if (node.key === key) {
      return node
    }
    const child = findNode(node.children || [], key)
    if (child) {
      return child
    }
  }
  return undefined
}

function selectNode(key: string) {
  selectedKey.value = key
}

function createNode(kind: YmclNavKind, title: string, icon: string): YmclNavNode {
  return {
    code: `${kind}-${Date.now().toString(36)}`,
    kind,
    title,
    icon,
    pageCode: '',
    visible: true,
    children: [],
  }
}

function addTab() {
  const node = createNode('tab', `Tab ${model.chromeForm.navTree.length + 1}`, 'i-ri:layout-top-line')
  model.chromeForm.navTree = [...model.chromeForm.navTree, node]
  selectedKey.value = `tab:${node.code}`
}

function addChild(parent: YmclNavNode) {
  const node = createNode('menu', `菜单 ${(parent.children?.length || 0) + 1}`, 'i-ri:menu-line')
  parent.children = [...(parent.children || []), node]
  const parentKey = selectedNav.value === parent
    ? selectedKey.value
    : (selectedNode.value?.parent === parent ? selectedNode.value.parentKey : '')
  selectedKey.value = parentKey ? `${parentKey}/${node.code}` : `tab:${parent.code}/${node.code}`
}

function addSibling() {
  const current = selectedNav.value
  if (!current) {
    addTab()
    return
  }
  if (!selectedNode.value?.parent) {
    addTab()
    return
  }
  addChild(selectedNode.value.parent)
}

function removeSelected() {
  const current = selectedNav.value
  if (!current) {
    return
  }
  const parent = selectedNode.value?.parent
  if (!parent) {
    model.chromeForm.navTree = model.chromeForm.navTree.filter(item => item !== current)
    selectedKey.value = model.chromeForm.navTree[0]?.code ? `tab:${model.chromeForm.navTree[0].code}` : ''
    return
  }
  parent.children = parent.children.filter(item => item !== current)
  selectedKey.value = selectedNode.value?.parentKey || ''
}

const boundPageCode = computed({
  get: () => selectedNav.value?.pageCode || '',
  set: (value: string) => {
    if (selectedNav.value) {
      selectedNav.value.pageCode = value
      if (value && !selectedNav.value.title.trim()) {
        selectedNav.value.title = pageByCode.value.get(value)?.title || ''
      }
      if (value && !selectedNav.value.icon.trim()) {
        selectedNav.value.icon = pageByCode.value.get(value)?.icon || ''
      }
    }
  },
})

const boundVisible = computed({
  get: () => selectedNav.value?.visible !== false,
  set: (value: boolean) => {
    if (selectedNav.value) {
      selectedNav.value.visible = value
    }
  },
})

const boundIcon = computed({
  get: () => selectedNav.value?.icon || '',
  set: (value: string) => {
    if (selectedNav.value) {
      selectedNav.value.icon = value
    }
  },
})

const selectedIconFallback = computed(() => {
  if (!selectedNav.value) {
    return ''
  }
  return nodeIcon(selectedNav.value)
})

const pageOptions = computed(() => [
  { label: '不绑定注册页（仅作分组）', value: '' },
  ...model.chromePages.map(page => ({
    label: `${page.title}（${page.code}）`,
    value: page.code,
  })),
])

function kindLabel(kind: YmclNavKind) {
  return kind === 'tab' ? 'Tab' : '菜单'
}

function depthLabel(node: ChromeTreeNode) {
  if (node.kind === 'tab') {
    return '顶级 Tab'
  }
  return node.parent?.kind === 'tab' ? 'Tab 内菜单' : '下级菜单'
}
</script>

<template>
  <div class="chrome-page">
    <FaPageHeader title="启动器外观" description="各插件向适配器注册页面；本页只编排 Tab / 菜单树与站点主题。顶级是 Tab，次级是该 Tab 里的菜单，再往下继续分级，每一级都可以改名字和图标。">
      <FaButton :loading="model.saving" @click="model.saveChrome">
        <FaIcon name="i-ri:save-line" />
        保存
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div v-loading="model.loading" class="chrome-workbench">
        <aside class="chrome-sidebar">
          <div class="chrome-sidebar__header">
            <h2>导航结构</h2>
            <div class="chrome-actions">
              <FaTooltip text="新增顶级 Tab" side="bottom">
                <FaButton variant="ghost" size="sm" @click="addTab">
                  <FaIcon name="i-ri:layout-top-line" />
                </FaButton>
              </FaTooltip>
              <FaTooltip text="在当前节点下新增菜单" side="bottom">
                <FaButton variant="ghost" size="sm" :disabled="!selectedNav" @click="selectedNav && addChild(selectedNav)">
                  <FaIcon name="i-ri:add-line" />
                </FaButton>
              </FaTooltip>
            </div>
          </div>
          <div class="chrome-tree">
            <template v-if="treeData.length">
              <ChromeNavTree :nodes="treeData" :selected-key="selectedKey" @select="selectNode" />
            </template>
            <p v-else class="launcher-muted">
              还没有 Tab。先新增一个顶级 Tab，再往里面加菜单。
            </p>
          </div>
          <p class="launcher-muted">
            当前扩展点贡献了 {{ model.chromePages.length }} 个页面。导航树只改变启动器展示，不改插件注册内容。
          </p>
        </aside>

        <main class="chrome-content">
          <section class="chrome-section">
            <div class="chrome-section__header">
              <h2>详情</h2>
              <div v-if="selectedNav" class="chrome-actions">
                <FaButton size="sm" @click="addSibling">
                  <FaIcon name="i-ri:node-tree" />
                  同级
                </FaButton>
                <FaButton size="sm" @click="addChild(selectedNav)">
                  <FaIcon name="i-ri:add-line" />
                  子级
                </FaButton>
                <FaButton variant="destructive" size="sm" @click="removeSelected">
                  <FaIcon name="i-ri:delete-bin-line" />
                  删除
                </FaButton>
              </div>
            </div>

            <template v-if="selectedNav && selectedNode">
              <div class="detail-grid">
                <div class="detail-cell detail-cell--label">节点类型</div>
                <div class="detail-cell">
                  <FaTag :variant="selectedNav.kind === 'tab' ? 'default' : 'secondary'">
                    {{ kindLabel(selectedNav.kind) }}
                  </FaTag>
                </div>
                <div class="detail-cell detail-cell--label">层级</div>
                <div class="detail-cell">{{ depthLabel(selectedNode) }}</div>
                <div class="detail-cell detail-cell--label">显示名称</div>
                <div class="detail-cell">
                  <FaInput v-model="selectedNav.title" placeholder="显示在启动器里的名字" />
                </div>
                <div class="detail-cell detail-cell--label">图标</div>
                <div class="detail-cell detail-cell--wide">
                  <NavIconPicker
                    v-model="boundIcon"
                    :fallback="selectedIconFallback"
                  />
                </div>
                <div class="detail-cell detail-cell--label">绑定注册页</div>
                <div class="detail-cell detail-cell--wide">
                  <FaSelect
                    v-model="boundPageCode"
                    :options="pageOptions"
                    placeholder="可选：把该节点接到已注册页面"
                  />
                </div>
                <div class="detail-cell detail-cell--label">显示</div>
                <div class="detail-cell">
                  <FaSwitch v-model="boundVisible" />
                </div>
                <div class="detail-cell detail-cell--label">编码</div>
                <div class="detail-cell">
                  <FaInput v-model="selectedNav.code" placeholder="稳定编码，可自定义" />
                </div>
                <template v-if="selectedPage">
                  <div class="detail-cell detail-cell--label">贡献插件</div>
                  <div class="detail-cell">{{ selectedPage.providerCode || '-' }}</div>
                  <div class="detail-cell detail-cell--label">渲染类型</div>
                  <div class="detail-cell">{{ selectedPage.type }}</div>
                  <div class="detail-cell detail-cell--label">路径</div>
                  <div class="detail-cell">{{ selectedPage.path }}</div>
                  <div class="detail-cell detail-cell--label">数据源</div>
                  <div class="detail-cell">{{ selectedPage.dataSourceCode || '-' }}</div>
                </template>
              </div>
              <p class="launcher-muted">
                注册页的路径和渲染类型由贡献插件决定。这里改的是启动器里看到的名字、图标、层级和是否显示。
              </p>
            </template>
            <div v-else class="empty-state">
              请选择左侧导航节点，或先新增一个 Tab
            </div>
          </section>

          <section class="chrome-section">
            <div class="chrome-section__header">
              <h2>站点主题</h2>
            </div>
            <div class="launcher-form">
              <label>
                <span>站点显示名</span>
                <FaInput v-model="model.chromeForm.displayName" placeholder="例如 YuDream Minecraft" />
              </label>
              <div class="launcher-chrome-uploads">
                <div class="grid gap-2">
                  <span>Logo</span>
                  <FaImageUpload
                    :model-value="logoList"
                    :max="1"
                    :width="96"
                    :height="96"
                    :http-request="uploadImage"
                    :after-upload="afterUpload"
                  />
                </div>
                <div class="grid gap-2">
                  <span>背景图</span>
                  <FaImageUpload
                    :model-value="backgroundList"
                    :max="1"
                    :width="240"
                    :height="135"
                    :http-request="uploadImage"
                    :after-upload="afterUpload"
                  />
                </div>
              </div>
            </div>
          </section>
        </main>
      </div>
    </FaPageMain>
  </div>
</template>
