<script setup lang="ts">
import { YdLoadingOverlay } from '@yudream/components'

import type { TableColumn, YdFilePickerInstance, YdSortableTreeDropEvent, YdSortableTreeNode, ActionMenuItem } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { NavigationConfigItem, NavigationPageInfo } from '../types'
import { YdButton, YdIcon, YdFilePicker, YdInput, YdModal, YdPageHeader, YdPageMain, YdSortableTree, YdSelect, YdSwitch, YdTag, YdTextarea, useYdModal, useYdToast, YdResponsiveEnhancedTable } from '@yudream/components'
import { computed, onMounted, reactive, ref } from 'vue'
import NavIconPicker from '../components/NavIconPicker.vue'
import {
  isNativeIconValue,
  preferredIconValue,
  remixIconName as remixIcon,
} from '../composables/nav-icons'
import { useYmclAdapter } from '../composables/useYmclAdapter'
import { formatTime } from '../composables/ymcl-protocol'

/** 与后端 YmclChromeHomeController.MAX_NAVIGATION_DEPTH 一致 */
const MAX_DEPTH = 8

const MODULE_PERMISSIONS: { value: string, label: string, hint: string }[] = [
  { value: 'data.fetch', label: 'data.fetch', hint: '读取域数据源' },
  { value: 'action.execute', label: 'action.execute', hint: '执行页面动作' },
  { value: 'open-url', label: 'open-url', hint: '打开外部链接（限 https）' },
  { value: 'theme.read', label: 'theme.read', hint: '读取域主题' },
]

interface NavTreeNode extends YdSortableTreeNode {
  key: string
  title: string
  label: string
  navIcon?: string | null
  enabled: boolean
  isDirectory: boolean
  /** page 节点引用的 pageId 已不在注册表（页面被删/插件卸载） */
  stale: boolean
  depth: number
  hasOverride: boolean
  source?: 'native' | 'provider' | 'custom'
  children?: NavTreeNode[]
}

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const model = useYmclAdapter(props.sdk)
const toast = useYdToast()
const modal = useYdModal()

const searchKeyword = ref('')
const selectedKeys = ref<string[]>([])

const addModal = ref(false)
const addChildTarget = ref<string | null>(null)
const addMode = ref<'page' | 'directory'>('page')
const addSelectedPageId = ref('')
const addDirectoryTitle = ref('')
const addDirectoryIcon = ref('')

const editModal = ref(false)
const editKey = ref('')
const editTitle = ref('')
const editIcon = ref('')
const editIsDirectory = ref(false)
const editDefaultTitle = ref('')
const editDefaultIcon = ref('')

const customModal = ref(false)
const customEditingId = ref<string | null>(null)
const customForm = reactive({
  title: '',
  renderer: 'extension' as 'extension' | 'module',
  bundleKey: '',
  entry: '',
  icon: '',
  requiredPermission: '',
  paramsText: '',
  permissions: [] as string[],
})
const bundleUpload = reactive({ bundleId: '', version: '' })
const bundleFile = ref<File | null>(null)
const bundleFileInput = ref<YdFilePickerInstance | null>(null)

const registryColumns: TableColumn<NavigationPageInfo>[] = [
  { id: 'title', header: '页面', minWidth: 150 },
  { accessorKey: 'source', header: '来源', width: 90 },
  { id: 'route', header: '路由 / 标识', minWidth: 160 },
  { id: 'requiredPermission', header: '所需权限（随页面固定）', minWidth: 200 },
  { id: 'usage', header: '状态', width: 100 },
]

function pageInfo(pageId: string) {
  return model.navPageInfo(pageId)
}

function isUsed(pageId: string) {
  return model.usedPageIds().has(pageId)
}

const unusedPages = computed(() =>
  model.navForm.pages.filter(page => !isUsed(page.pageId)))

const addOptions = computed(() => unusedPages.value.map(page => ({
  label: `${page.title}（${page.pageId}）`,
  value: page.pageId,
})))

function nodeTitle(item: NavigationConfigItem) {
  if (item.kind === 'directory') {
    return item.title || '目录'
  }
  if (item.title?.trim()) {
    return item.title.trim()
  }
  return item.pageId ? (pageInfo(item.pageId)?.title || item.pageId) : '未命名'
}

/** 原生 token（home/discover…）映射为 Remix 名，保证管理台 YdIcon 可渲染。 */
function nodeIcon(item: NavigationConfigItem) {
  if (item.icon?.trim()) {
    return remixIcon(preferredIconValue(item.icon))
  }
  if (item.kind === 'directory') {
    return 'i-ri:folder-line'
  }
  return remixIcon(preferredIconValue(pageInfo(item.pageId ?? '')?.icon))
}

function toTreeNode(item: NavigationConfigItem, depth: number): NavTreeNode {
  const info = item.pageId ? pageInfo(item.pageId) : undefined
  return {
    key: model.navNodeKey(item),
    title: nodeTitle(item),
    label: nodeTitle(item),
    navIcon: nodeIcon(item),
    enabled: item.enabled !== false,
    isDirectory: item.kind === 'directory',
    stale: item.kind !== 'directory' && Boolean(item.pageId) && !info,
    depth,
    hasOverride: Boolean(item.title?.trim() || item.icon?.trim()),
    source: info?.source,
    children: (item.children || []).map(child => toTreeNode(child, depth + 1)),
  }
}

const treeData = computed<NavTreeNode[]>(() =>
  model.navForm.items.map(item => toTreeNode(item, 1)))

/** 搜索过滤：命中节点保留其祖先链，便于定位嵌套项。 */
const filteredTree = computed<NavTreeNode[]>(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  if (!keyword) {
    return treeData.value
  }
  const walk = (nodes: NavTreeNode[]): NavTreeNode[] => {
    const result: NavTreeNode[] = []
    for (const node of nodes) {
      const children = walk(node.children || [])
      if (node.label.toLowerCase().includes(keyword) || node.key.toLowerCase().includes(keyword) || children.length) {
        result.push({ ...node, children })
      }
    }
    return result
  }
  return walk(treeData.value)
})

/** 搜索时强制全展开；清空后交还树自身（default-expand-all）。 */
const expandedKeys = ref<string[]>([])
const treeExpandedKeys = computed<string[]>({
  get() {
    if (!searchKeyword.value.trim()) {
      return expandedKeys.value
    }
    const keys: string[] = []
    const walk = (nodes: NavTreeNode[]) => {
      for (const node of nodes) {
        keys.push(node.key)
        walk(node.children || [])
      }
    }
    walk(filteredTree.value)
    return keys
  },
  set(keys) {
    if (!searchKeyword.value.trim()) {
      expandedKeys.value = keys
    }
  },
})

// ── 选中节点详情 ────────────────────────────────────────────────────────

const selectedKey = computed<string | undefined>({
  get: () => selectedKeys.value[0],
  set: key => { selectedKeys.value = key ? [key] : [] },
})
const selectedItem = computed(() => (selectedKey.value ? model.findNavNode(selectedKey.value) : null))
function findTreeNode(key: string, nodes: NavTreeNode[] = treeData.value): NavTreeNode | null {
  for (const node of nodes) {
    if (node.key === key) {
      return node
    }
    const found = findTreeNode(key, node.children || [])
    if (found) {
      return found
    }
  }
  return null
}

function treeNodeView(key: string): NavTreeNode {
  return findTreeNode(key) ?? { key, title: key, label: key, enabled: false, isDirectory: false, stale: false, depth: 1, hasOverride: false }
}

const selectedNode = computed<NavTreeNode | null>(() => selectedKey.value ? findTreeNode(selectedKey.value) : null)
const selectedDepth = computed(() => (selectedKey.value ? model.navDepthOf(selectedKey.value) : 0))

const selectedParentTitle = computed(() => {
  if (!selectedKey.value) {
    return '—'
  }
  const walk = (nodes: NavigationConfigItem[], parent: NavigationConfigItem | null): string | null => {
    for (const item of nodes) {
      if (model.navNodeKey(item) === selectedKey.value) {
        return parent ? nodeTitle(parent) : '顶层'
      }
      const found = walk(item.children || [], item)
      if (found) {
        return found
      }
    }
    return null
  }
  return walk(model.navForm.items, null) || '—'
})

const canAddChildToSelected = computed(() =>
  model.canDesign && Boolean(selectedItem.value) && selectedDepth.value > 0 && selectedDepth.value < MAX_DEPTH)

function sourceText(source?: string) {
  if (source === 'native') {
    return '内置'
  }
  if (source === 'provider') {
    return '插件'
  }
  if (source === 'custom') {
    return '自定义'
  }
  return '—'
}

function sourceVariant(source?: string) {
  if (source === 'native') {
    return 'secondary' as const
  }
  if (source === 'custom') {
    return 'default' as const
  }
  return 'outline' as const
}

// ── 新增 / 编辑 / 删除导航节点 ─────────────────────────────────────────

function openAdd(mode: 'page' | 'directory', parentKey: string | null = null) {
  if (parentKey && model.navDepthOf(parentKey) >= MAX_DEPTH) {
    toast.error(`导航最多嵌套 ${MAX_DEPTH} 层`)
    return
  }
  if (mode === 'page' && !unusedPages.value.length) {
    toast.info('所有页面都已加入导航；可添加目录后再挂子菜单')
    return
  }
  addChildTarget.value = parentKey
  addMode.value = mode
  addSelectedPageId.value = ''
  addDirectoryTitle.value = ''
  addDirectoryIcon.value = ''
  addModal.value = true
}

function openAddChild(parentKey: string) {
  if (!unusedPages.value.length) {
    openAdd('directory', parentKey)
    toast.info('暂无未使用页面，已切换为添加目录')
    return
  }
  openAdd('page', parentKey)
}

function submitAdd() {
  if (addMode.value === 'directory') {
    const title = addDirectoryTitle.value.trim()
    if (!title) {
      toast.error('请填写目录名称')
      return
    }
    model.addDirectoryItem(title, addChildTarget.value, preferredIconValue(addDirectoryIcon.value) || undefined)
    toast.success(addChildTarget.value ? '已添加子目录，记得保存' : '已添加目录，记得保存')
  }
  else {
    if (!addSelectedPageId.value) {
      toast.error('请选择页面')
      return
    }
    model.addPageNavItem(addSelectedPageId.value, addChildTarget.value)
    toast.success(addChildTarget.value ? '已添加为子菜单，记得保存' : '已添加到导航，记得保存')
  }
  addModal.value = false
}

function openEdit(key?: string) {
  const target = key || selectedKey.value
  const item = target ? model.findNavNode(target) : null
  if (!item) {
    return
  }
  editKey.value = model.navNodeKey(item)
  editIsDirectory.value = item.kind === 'directory'
  editDefaultTitle.value = item.kind === 'directory'
    ? ''
    : (item.pageId ? (pageInfo(item.pageId)?.title || item.pageId) : '')
  const defaultRaw = item.kind === 'directory'
    ? 'i-ri:folder-line'
    : (item.pageId ? pageInfo(item.pageId)?.icon : '')
  // 默认值展示为内置 token（可写回），管理台预览仍走 Remix
  editDefaultIcon.value = preferredIconValue(defaultRaw) || (defaultRaw || '')
  editTitle.value = item.title || ''
  // 已存的 i-ri 与内置 token 等价时，规范成内置 token
  editIcon.value = preferredIconValue(item.icon)
  editModal.value = true
}

function submitEdit() {
  if (editIsDirectory.value && !editTitle.value.trim()) {
    toast.error('目录名称不能为空')
    return
  }
  model.updateNavItem(editKey.value, {
    title: editTitle.value,
    // 内置等价图标写入 token，保证启动器用内置图标
    icon: preferredIconValue(editIcon.value),
  })
  toast.success('已更新节点展示属性，记得保存')
  editModal.value = false
}

function confirmRemove(key?: string) {
  const target = key || selectedKey.value
  const item = target ? model.findNavNode(target) : null
  if (!item) {
    return
  }
  const isDirectory = item.kind === 'directory'
  modal.confirm({
    title: '确认信息',
    content: `确认从导航中移除「${nodeTitle(item)}」吗？${isDirectory || item.children?.length ? '其下子菜单会一并移除。' : '移除后可在页面注册表重新添加。'}需保存才会生效。`,
    onConfirm: () => {
      model.removeNavItem(model.navNodeKey(item))
      selectedKeys.value = []
    },
  })
}

function confirmReset() {
  modal.confirm({
    title: '确认信息',
    content: '确认放弃自定义导航、恢复内置默认树吗？成员下次拉取清单即生效。',
    onConfirm: async () => {
      selectedKeys.value = []
      await model.resetNavigation()
    },
  })
}

async function submitSave() {
  await model.saveNavigation()
}

// ── 拖拽：任意深度 detach + 按落点插入（上限 MAX_DEPTH，禁止拖入自身子树） ──

function onDrop(event: YdSortableTreeDropEvent) {
  const dragKey = event.dragKey
  const dropKey = event.dropKey
  if (dragKey === dropKey) {
    return
  }
  const draggedPreview = model.findNavNode(dragKey)
  if (!draggedPreview) {
    return
  }
  if (model.navSubtreeContains(draggedPreview, dropKey)) {
    toast.error('不能移动到自身或其子级内')
    return
  }
  const height = model.navSubtreeHeight(draggedPreview)
  const dropDepth = model.navDepthOf(dropKey)
  // dropPosition 0=成为子级（根深度+1），±1=同级（与落点同深）
  const baseDepth = event.dropPosition === 0 ? dropDepth : dropDepth - 1
  if (baseDepth + height > MAX_DEPTH) {
    toast.error(`导航最多嵌套 ${MAX_DEPTH} 层`)
    return
  }
  const dragged = model.detachNavItem(dragKey)
  if (!dragged) {
    return
  }
  if (event.dropPosition === 0) {
    const parent = model.findNavNode(dropKey)
    if (!parent) {
      return
    }
    parent.children = [...(parent.children || []), dragged]
  }
  else {
    const siblings = model.navSiblingsOf(dropKey)
    if (!siblings) {
      return
    }
    const index = siblings.findIndex(entry => model.navNodeKey(entry) === dropKey)
    siblings.splice(event.dropPosition === -1 ? index : index + 1, 0, dragged)
  }
  model.navDirty = true
}

// ── 自定义页面（remoteEntry 页面注册 + bundle 上传） ────────────────────

const bundleOptions = computed(() => model.bundles.map(bundle => ({
  label: `${bundle.bundleId}@${bundle.version}（${(bundle.size / 1024).toFixed(0)} KB）`,
  value: `${bundle.bundleId}@${bundle.version}`,
})))

function resetCustomForm() {
  customForm.title = ''
  customForm.renderer = 'extension'
  customForm.bundleKey = ''
  customForm.entry = ''
  customForm.icon = ''
  customForm.requiredPermission = ''
  customForm.paramsText = ''
  customForm.permissions = []
  bundleUpload.bundleId = ''
  bundleUpload.version = ''
  bundleFile.value = null
  if (bundleFileInput.value) {
    bundleFileInput.value.reset()
  }
}

function openCustomCreate() {
  customEditingId.value = null
  resetCustomForm()
  customModal.value = true
}

function openCustomEdit(page: NavigationPageInfo) {
  const doc = model.customPages.find(entry => entry.id === page.pageId)
  if (!doc) {
    toast.error('自定义页面数据尚未加载，请稍后再试')
    return
  }
  customEditingId.value = doc.id
  resetCustomForm()
  customForm.title = doc.title
  customForm.renderer = doc.renderer
  customForm.bundleKey = `${doc.bundle.id}@${doc.bundle.version}`
  customForm.entry = doc.bundle.entry
  customForm.icon = preferredIconValue(doc.icon)
  customForm.requiredPermission = doc.requiredPermission || ''
  customForm.paramsText = doc.params ? JSON.stringify(doc.params, null, 2) : ''
  customForm.permissions = [...(doc.bundle.permissions || [])]
  customModal.value = true
}

function onBundleFileSelect(files: File[]) {
  bundleFile.value = files[0] || null
}

async function submitBundleUpload() {
  if (!bundleFile.value) {
    toast.error('请选择页面 zip 包')
    return
  }
  const bundleId = bundleUpload.bundleId.trim()
  const version = bundleUpload.version.trim()
  const result = await model.uploadBundle(bundleId, version, bundleFile.value)
  if (result) {
    customForm.bundleKey = `${bundleId}@${version}`
    bundleFile.value = null
    if (bundleFileInput.value) {
      bundleFileInput.value.reset()
    }
  }
}

function toggleModulePermission(value: string) {
  const index = customForm.permissions.indexOf(value)
  if (index >= 0) {
    customForm.permissions.splice(index, 1)
  }
  else {
    customForm.permissions.push(value)
  }
}

async function submitCustomPage() {
  const title = customForm.title.trim()
  if (!title) {
    toast.error('请填写页面标题')
    return
  }
  const bundle = model.bundles.find(entry => `${entry.bundleId}@${entry.version}` === customForm.bundleKey)
  if (!bundle) {
    toast.error('请选择已上传的页面包（或先上传新包）')
    return
  }
  const entry = customForm.entry.trim()
  if (!entry) {
    toast.error('请填写入口文件（zip 包内路径）')
    return
  }
  let params: Record<string, unknown> | undefined
  if (customForm.paramsText.trim()) {
    try {
      const parsed = JSON.parse(customForm.paramsText)
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('not an object')
      }
      params = parsed as Record<string, unknown>
    }
    catch {
      toast.error('页面参数需为合法 JSON 对象')
      return
    }
  }
  const id = await model.saveCustomPage({
    id: customEditingId.value || undefined,
    title,
    renderer: customForm.renderer,
    icon: preferredIconValue(customForm.icon) || undefined,
    requiredPermission: customForm.requiredPermission.trim() || undefined,
    params,
    bundle: {
      id: bundle.bundleId,
      version: bundle.version,
      entry,
      sha256: bundle.sha256,
      permissions: customForm.renderer === 'module' && customForm.permissions.length
        ? [...customForm.permissions]
        : undefined,
    },
  })
  if (id) {
    customModal.value = false
  }
}

function confirmDeleteCustomPage(page: NavigationPageInfo) {
  modal.confirm({
    title: '确认信息',
    content: `确认删除自定义页面「${page.title}」吗？导航树中残留的引用会被标记为失效，保存导航后才会真正移除。`,
    onConfirm: async () => {
      await model.deleteCustomPage(page.pageId)
    },
  })
}

onMounted(() => {
  void model.loadNavigation()
  void model.loadCustomPages()
  void model.loadBundles()
})
/** 行操作：仅自定义页可编辑/删除 */
const rowActions: ActionMenuItem<unknown>[] = [
  { label: '编辑', icon: 'morph:pencil', visible: row => (row as { source: string }).source === 'custom', disabled: () => !model.canDesign, onClick: row => openCustomEdit(row as never) },
  { label: '删除', icon: 'morph:trash-2', danger: true, visible: row => (row as { source: string }).source === 'custom', disabled: () => !model.canDesign, onClick: row => confirmDeleteCustomPage(row as never) },
]
</script>

<template>
  <div class="ymcl-page nav-designer">
    <YdPageHeader title="域导航设计器" description="把页面组织成下发给启动器的导航树：顶层是启动器侧边栏 tab，次级及以下在页面内以图标+名称菜单嵌套展示。路由与权限随页面固定，可覆盖展示标题与图标。">
      <YdButton variant="outline" :disabled="!model.canDesign || !model.navForm.configured" @click="confirmReset">
        <YdIcon name="i-ri:arrow-go-back-line" />
        恢复默认
      </YdButton>
      <YdButton :disabled="!model.canDesign" :loading="model.saving" @click="submitSave">
        保存导航
      </YdButton>
    </YdPageHeader>

    <YdPageMain>
      <div class="menu-workbench">
        <aside class="menu-sidebar">
          <div class="menu-sidebar__header">
            <h2>导航树</h2>
            <div class="menu-toolbar">
              <YdButton variant="ghost" size="sm" title="添加目录" :disabled="!model.canDesign" @click="openAdd('directory')">
                <YdIcon name="i-ri:folder-add-line" />
              </YdButton>
              <YdButton variant="ghost" size="sm" title="添加页面" :disabled="!model.canDesign" @click="openAdd('page')">
                <YdIcon name="i-ri:add-line" />
              </YdButton>
            </div>
          </div>

          <div class="ymcl-action-row">
            <YdTag :variant="model.navForm.configured ? 'default' : 'outline'">
              {{ model.navForm.configured ? '自定义导航' : '内置默认导航（未修改）' }}
            </YdTag>
            <YdTag v-if="model.navDirty" variant="secondary">有未保存改动</YdTag>
          </div>

          <YdInput
            v-model="searchKeyword"
            clearable
            placeholder="搜索标题 / 页面 ID"
            class="w-full"
          />

          <YdLoadingOverlay :loading="model.loading"><div  class="menu-tree">
            <YdSortableTree
              v-if="treeData.length"
              v-model:selected-key="selectedKey"
              v-model:expanded-keys="treeExpandedKeys"
              :data="filteredTree"
              :draggable="model.canDesign"
              :max-depth="MAX_DEPTH"
              default-expand-all
              aria-label="导航树"
              @drop="onDrop"
            >
              <template #title="{ node }">
                <div v-for="navNode in [treeNodeView(node.key)]" :key="node.key" class="menu-tree-node" :class="{ 'menu-tree-node--off': !navNode.enabled }">
                  <span class="menu-tree-node__main">
                    <span class="menu-tree-node__icon">
                      <YdIcon v-if="navNode.navIcon" :name="navNode.navIcon" />
                    </span>
                    <span>{{ navNode.title }}</span>
                    <span v-if="navNode.source === 'provider' || navNode.source === 'custom'" class="plugin-source-marker" :title="navNode.source === 'provider' ? '插件页面' : '自定义页面'" />
                    <YdTag v-if="navNode.isDirectory" variant="secondary">目录</YdTag>
                    <YdTag v-if="navNode.stale" variant="destructive">失效</YdTag>
                    <YdTag v-if="!navNode.enabled" variant="outline">停用</YdTag>
                  </span>
                  <YdButton v-if="model.canDesign && navNode.depth < MAX_DEPTH" variant="ghost" size="sm" title="新增子菜单" @click.stop="openAddChild(node.key)">
                    <YdIcon name="i-ri:add-line" />
                  </YdButton>
                </div>
              </template>
            </YdSortableTree>
            <p v-else class="ymcl-muted">
              导航为空——保存后启动器将不显示任何域导航项。点右上角「添加页面」或「添加目录」开始。
            </p>
          </div></YdLoadingOverlay>
          <p class="ymcl-muted">
            拖拽调整顺序与层级（最多 {{ MAX_DEPTH }} 层）；改动保存后广播 manifest.updated，成员下次拉取清单生效。最后更新：{{ formatTime(model.navForm.updatedAt) }}
          </p>
        </aside>

        <main class="menu-content">
          <section class="menu-section">
            <div class="menu-section__header">
              <h2>节点详情</h2>
              <div class="menu-actions">
                <YdButton variant="outline" size="sm" :disabled="!canAddChildToSelected" @click="openAddChild(selectedKey ?? '')">
                  <YdIcon name="i-ri:add-line" />
                  新增子菜单
                </YdButton>
                <YdButton variant="outline" size="sm" :disabled="!model.canDesign || !selectedItem" @click="openEdit()">
                  <YdIcon name="i-ri:edit-2-line" />
                  编辑
                </YdButton>
                <YdButton
                  v-if="selectedItem && selectedItem.enabled === false"
                  variant="outline"
                  size="sm"
                  :disabled="!model.canDesign"
                  @click="model.toggleNavItem(selectedKey ?? '')"
                >
                  <YdIcon name="i-ri:play-circle-line" />
                  启用
                </YdButton>
                <YdButton
                  v-if="selectedItem && selectedItem.enabled !== false"
                  variant="outline"
                  size="sm"
                  :disabled="!model.canDesign"
                  @click="model.toggleNavItem(selectedKey ?? '')"
                >
                  <YdIcon name="i-ri:pause-circle-line" />
                  停用
                </YdButton>
                <YdButton
                  v-if="selectedItem"
                  variant="destructive"
                  size="sm"
                  :disabled="!model.canDesign"
                  @click="confirmRemove()"
                >
                  <YdIcon name="i-ri:delete-bin-line" />
                  移除
                </YdButton>
              </div>
            </div>

            <div v-if="selectedItem && selectedNode" class="detail-grid">
              <div class="detail-cell detail-cell--label">节点类型</div>
              <div class="detail-cell">
                <YdTag :variant="selectedNode.isDirectory ? 'secondary' : 'default'">
                  {{ selectedNode.isDirectory ? '目录' : '页面' }}
                </YdTag>
              </div>
              <div class="detail-cell detail-cell--label">状态</div>
              <div class="detail-cell">
                <YdTag :variant="selectedNode.enabled ? 'default' : 'secondary'">
                  {{ selectedNode.enabled ? '启用' : '已停用' }}
                </YdTag>
              </div>
              <div class="detail-cell detail-cell--label">展示标题</div>
              <div class="detail-cell detail-cell__name">
                <YdIcon v-if="selectedNode.navIcon" :name="selectedNode.navIcon" />
                <span>{{ selectedNode.title }}</span>
              </div>
              <div class="detail-cell detail-cell--label">页面 ID</div>
              <div class="detail-cell">
                <code v-if="selectedItem.pageId" class="ymcl-hash">{{ selectedItem.pageId }}</code>
                <span v-else>—</span>
              </div>
              <template v-if="!selectedNode.isDirectory">
                <div class="detail-cell detail-cell--label">来源</div>
                <div class="detail-cell">
                  <YdTag :variant="sourceVariant(selectedNode.source)">
                    {{ sourceText(selectedNode.source) }}
                  </YdTag>
                </div>
                <div class="detail-cell detail-cell--label">所需权限</div>
                <div class="detail-cell">
                  <span v-if="selectedItem.pageId && pageInfo(selectedItem.pageId)?.requiredPermission" class="ymcl-break">
                    {{ pageInfo(selectedItem.pageId)?.requiredPermission }}
                  </span>
                  <span v-else class="ymcl-muted">公开</span>
                </div>
                <div class="detail-cell detail-cell--label">路由</div>
                <div class="detail-cell">
                  <code v-if="selectedItem.pageId && pageInfo(selectedItem.pageId)?.route" class="ymcl-hash">
                    {{ pageInfo(selectedItem.pageId)?.route }}
                  </code>
                  <span v-else>—</span>
                </div>
                <div class="detail-cell detail-cell--label">数据有效性</div>
                <div class="detail-cell">
                  <YdTag v-if="selectedNode.stale" variant="destructive">失效</YdTag>
                  <YdTag v-else variant="outline">正常</YdTag>
                </div>
              </template>
              <div class="detail-cell detail-cell--label">标题覆盖</div>
              <div class="detail-cell">
                <span v-if="selectedItem.title?.trim()">{{ selectedItem.title }}</span>
                <span v-else class="ymcl-muted">—（默认）</span>
              </div>
              <div class="detail-cell detail-cell--label">图标覆盖</div>
              <div class="detail-cell">
                <code v-if="selectedItem.icon?.trim()" class="ymcl-hash">{{ selectedItem.icon }}</code>
                <span v-else class="ymcl-muted">—（默认）</span>
              </div>
              <div class="detail-cell detail-cell--label">父级</div>
              <div class="detail-cell">{{ selectedParentTitle }}</div>
              <div class="detail-cell detail-cell--label">层级</div>
              <div class="detail-cell">{{ selectedDepth }} / {{ MAX_DEPTH }}</div>
              <div v-if="selectedNode.stale" class="detail-cell detail-cell--wide detail-cell--warning">
                引用的页面已不在注册表（页面被删除或提供方插件已卸载）。启动器下发清单时会跳过该节点；请移除它或重新选择页面。
              </div>
            </div>
            <div v-else class="empty-state">
              请选择左侧导航节点
            </div>
          </section>

          <section class="menu-section">
            <div class="menu-section__header">
              <h2>页面注册表</h2>
              <div class="menu-actions">
                <YdButton size="sm" :disabled="!model.canDesign" @click="openCustomCreate">
                  <YdIcon name="i-ri:add-line" />
                  新建自定义页面
                </YdButton>
              </div>
            </div>
            <YdLoadingOverlay :loading="model.loading"><YdResponsiveEnhancedTable
              :actions="rowActions"

              
              table-root-class="max-w-full overflow-x-auto rounded-lg overflow-hidden"
              :columns="registryColumns"
              :data="model.navForm.pages"
              row-key="pageId"
            >
              <template #empty>
                <span class="ymcl-muted">暂无可用页面</span>
              </template>
              <template #cell-title="{ row }">
                <span class="ymcl-action-row">
                  <YdIcon
                    v-if="remixIcon(preferredIconValue(row.original.icon))"
                    :name="remixIcon(preferredIconValue(row.original.icon))!"
                  />
                  <strong>{{ row.original.title }}</strong>
                  <YdTag v-if="isNativeIconValue(row.original.icon)" variant="outline">内置图标</YdTag>
                </span>
              </template>
              <template #cell-source="{ row }">
                <YdTag :variant="sourceVariant(row.original.source)">
                  {{ sourceText(row.original.source) }}
                </YdTag>
              </template>
              <template #cell-route="{ row }">
                <code class="ymcl-hash">{{ row.original.route || row.original.pageId }}</code>
              </template>
              <template #cell-requiredPermission="{ row }">
                <span v-if="row.original.requiredPermission" class="ymcl-break">{{ row.original.requiredPermission }}</span>
                <span v-else class="ymcl-muted">公开</span>
              </template>
              <template #cell-usage="{ row }">
                <YdTag :variant="isUsed(row.original.pageId) ? 'default' : 'outline'">
                  {{ isUsed(row.original.pageId) ? '导航中' : '未使用' }}
                </YdTag>
              </template>
</YdResponsiveEnhancedTable></YdLoadingOverlay>
            <p class="ymcl-muted">
              自定义页面由页面包驱动：extension=沙箱 iframe 页面，module=域内可信 ESM 模块（remoteEntry 热下发）。先上传 zip 包，再登记页面并加入导航。
            </p>
          </section>
        </main>
      </div>
    </YdPageMain>

    <YdModal v-model="addModal" :title="addMode === 'directory'
      ? (addChildTarget ? '添加子目录' : '添加目录')
      : (addChildTarget ? '添加子菜单' : '添加页面到导航')">
      <div class="ymcl-binding-form">
        <template v-if="addMode === 'directory'">
          <div class="ymcl-form-row">
            <label class="ymcl-label">目录名称</label>
            <YdInput v-model="addDirectoryTitle" placeholder="例如：社区、工具、活动" />
          </div>
          <div class="ymcl-form-row">
            <label class="ymcl-label">图标（可选）</label>
            <NavIconPicker
              v-model="addDirectoryIcon"
              fallback="i-ri:folder-line"
              :disabled="!model.canDesign"
            />
          </div>
          <p class="ymcl-muted">
            目录是纯分组节点，没有独立页面。带「内置」的图标会写入启动器原生 token。
            {{ addChildTarget ? '将作为当前节点的子目录。' : '可在目录下挂页面作为子菜单。' }}
          </p>
        </template>
        <template v-else>
          <div class="ymcl-form-row">
            <label class="ymcl-label">选择页面</label>
            <YdSelect v-model="addSelectedPageId" :options="addOptions" />
          </div>
          <p class="ymcl-muted">
            页面的路由与所需权限由页面本身决定；展示标题/图标可添加后编辑覆盖。
            {{ addChildTarget ? `子菜单挂在所选节点下，最多嵌套 ${MAX_DEPTH} 层。` : '顶层项在启动器侧边栏作为 tab 展示。' }}
          </p>
          <YdButton size="sm" variant="ghost" @click="openAdd('directory', addChildTarget)">
            改为添加目录
          </YdButton>
        </template>
      </div>
      <template #footer>
        <div class="ymcl-action-row ymcl-action-row--end">
          <YdButton variant="outline" @click="addModal = false">
            取消
          </YdButton>
          <YdButton @click="submitAdd">
            添加
          </YdButton>
        </div>
      </template>
    </YdModal>

    <YdModal v-model="editModal" title="编辑导航项">
      <div class="ymcl-binding-form">
        <div class="ymcl-form-row">
          <label class="ymcl-label">{{ editIsDirectory ? '目录名称' : '展示标题' }}</label>
          <YdInput
            v-model="editTitle"
            :placeholder="editIsDirectory ? '必填' : `留空使用页面默认标题${editDefaultTitle ? `（${editDefaultTitle}）` : ''}`"
          />
        </div>
        <div class="ymcl-form-row">
          <label class="ymcl-label">展示图标</label>
          <NavIconPicker
            v-model="editIcon"
            :fallback="editDefaultIcon"
            :disabled="!model.canDesign"
          />
        </div>
        <p class="ymcl-muted">
          仅覆盖启动器中的展示标题/图标；路由、权限与数据源仍随页面固定。
          与启动器内置页等价的图标会保存为内置 token（如 home / skins），优先于 i-ri 字符串。
        </p>
      </div>
      <template #footer>
        <div class="ymcl-action-row ymcl-action-row--end">
          <YdButton variant="outline" @click="editModal = false">
            取消
          </YdButton>
          <YdButton @click="submitEdit">
            保存节点
          </YdButton>
        </div>
      </template>
    </YdModal>

    <YdModal v-model="customModal" :title="customEditingId ? `编辑自定义页面（${customEditingId}）` : '新建自定义页面'">
      <div class="ymcl-binding-form">
        <div class="ymcl-form-row">
          <label class="ymcl-label">页面标题</label>
          <YdInput v-model="customForm.title" placeholder="例如：活动日历、积分商城" />
        </div>
        <div class="ymcl-form-row">
          <label class="ymcl-label">渲染器</label>
          <YdSelect
            v-model="customForm.renderer"
            :options="[
              { label: '沙箱页面（extension · iframe 隔离，经桥接通信）', value: 'extension' },
              { label: '可信模块（module · 域内 ESM remoteEntry，直接渲染 Vue 组件）', value: 'module' },
            ]"
          />
        </div>
        <div class="ymcl-form-row">
          <label class="ymcl-label">页面包</label>
          <YdSelect v-model="customForm.bundleKey" :options="bundleOptions" placeholder="选择已上传的 zip 包" />
        </div>
        <details class="bundle-upload">
          <summary>上传新页面包</summary>
          <div class="ymcl-form-row">
            <label class="ymcl-label">包 ID / 版本</label>
            <div class="ymcl-action-row">
              <YdInput v-model="bundleUpload.bundleId" placeholder="如 domain-pages" class="w-full" />
              <YdInput v-model="bundleUpload.version" placeholder="如 1.0.0" class="w-full" />
            </div>
          </div>
          <div class="ymcl-form-row">
            <label class="ymcl-label">zip 文件</label>
            <div class="ymcl-action-row">
              <YdFilePicker ref="bundleFileInput" accept=".zip,application/zip" aria-label="选择 zip 文件" @select="onBundleFileSelect"><template #default="{ open, triggerAttrs }"><YdButton v-bind="triggerAttrs" size="sm" variant="outline" @click="open"><YdIcon name="i-ri:file-zip-line" />选择 zip 文件</YdButton></template></YdFilePicker>
              <YdButton size="sm" variant="outline" :loading="model.saving" @click="submitBundleUpload">
                <YdIcon name="i-ri:upload-2-line" />
                上传
              </YdButton>
            </div>
          </div>
        </details>
        <div class="ymcl-form-row">
          <label class="ymcl-label">入口文件</label>
          <YdInput
            v-model="customForm.entry"
            :placeholder="customForm.renderer === 'module' ? 'index.js（ESM，默认导出工厂或组件）' : 'index.html'"
          />
        </div>
        <div class="ymcl-form-row">
          <label class="ymcl-label">图标（可选）</label>
          <NavIconPicker v-model="customForm.icon" fallback="i-ri:file-code-line" :disabled="!model.canDesign" />
        </div>
        <div class="ymcl-form-row">
          <label class="ymcl-label">所需权限（可选）</label>
          <YdInput v-model="customForm.requiredPermission" placeholder="留空 = 所有成员可见" />
        </div>
        <div v-if="customForm.renderer === 'module'" class="ymcl-form-row">
          <label class="ymcl-label">宿主能力授权（仅 module）</label>
          <div class="permission-list">
            <div v-for="permission in MODULE_PERMISSIONS" :key="permission.value" class="permission-item">
              <YdSwitch
                :model-value="customForm.permissions.includes(permission.value)"
                @update:model-value="toggleModulePermission(permission.value)"
              />
              <code class="ymcl-hash">{{ permission.label }}</code>
              <span class="ymcl-muted">{{ permission.hint }}</span>
            </div>
          </div>
        </div>
        <div class="ymcl-form-row">
          <label class="ymcl-label">页面参数（可选，JSON 对象，随 descriptor.params 下发）</label>
          <YdTextarea v-model="customForm.paramsText" :rows="3" placeholder='{"key": "value"}' />
        </div>
        <p class="ymcl-muted">
          module 页面在启动器进程内以 blob ESM 动态导入执行，享有域内信任；请只部署自己构建的包。保存后页面进入注册表，加入导航并保存导航即下发。
        </p>
      </div>
      <template #footer>
        <div class="ymcl-action-row ymcl-action-row--end">
          <YdButton variant="outline" @click="customModal = false">
            取消
          </YdButton>
          <YdButton :loading="model.saving" @click="submitCustomPage">
            保存页面
          </YdButton>
        </div>
      </template>
    </YdModal>
  </div>
</template>

<style scoped>
/* 布局照抄主仓库菜单管理（system/menu）：左树 + 右详情/列表工作台 */
.menu-workbench {
  display: grid;
  grid-template-columns: minmax(300px, 340px) minmax(0, 1fr);
  gap: 12px;
}

.menu-sidebar,
.menu-section {
  border: 1px solid var(--color-border-2);
  border-radius: 6px;
  background: var(--color-bg-2);
}

.menu-sidebar {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  min-height: 0;
  max-height: calc(100vh - 136px);
}

.menu-sidebar__header,
.menu-section__header,
.menu-actions,
.menu-toolbar,
.menu-tree-node,
.menu-tree-node__main {
  display: flex;
  align-items: center;
}

.menu-sidebar__header,
.menu-section__header {
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.menu-sidebar h2,
.menu-section h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.menu-toolbar,
.menu-actions {
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.menu-tree {
  overflow: auto;
  min-height: 0;
  padding-right: 2px;
}

.menu-tree-node {
  justify-content: space-between;
  width: 100%;
  min-height: 32px;
  gap: 8px;
}

.menu-tree-node__main {
  min-width: 0;
  gap: 8px;
}

.menu-tree-node__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  color: var(--color-text-2);
}

.menu-tree-node__main > span:nth-child(2) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-tree-node--off .menu-tree-node__main {
  opacity: 0.55;
}

.plugin-source-marker {
  width: 6px;
  height: 6px;
  margin-left: 2px;
  border-radius: 50%;
  background: var(--color-text-4);
  flex: 0 0 auto;
}

.menu-content {
  display: grid;
  align-content: start;
  gap: 12px;
  min-width: 0;
}

.menu-section {
  padding: 14px;
  min-width: 0;
  overflow-x: auto;
}

.detail-grid {
  display: grid;
  grid-template-columns: 130px minmax(160px, 1fr) 130px minmax(160px, 1fr);
  min-width: 720px;
  margin-top: 12px;
  overflow: hidden;
  border: 1px solid var(--color-border-2);
  /* 底边由末行单元格的 border-bottom 提供，避免与容器边框叠加成双线 */
  border-bottom: 0;
  border-radius: 4px;
}

.detail-cell {
  min-height: 40px;
  padding: 9px 12px;
  border-right: 1px solid var(--color-border-2);
  border-bottom: 1px solid var(--color-border-2);
  word-break: break-all;
  font-size: 14px;
}

.detail-cell:nth-child(4n) {
  border-right: 0;
}

.detail-cell--label {
  font-weight: 600;
  background: var(--color-fill-1);
}

.detail-cell__name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-cell--wide {
  grid-column: span 4;
  border-right: 0;
}

.detail-cell--warning {
  color: rgb(var(--red-6));
  background: rgb(var(--red-1));
}

.permission-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.permission-item {
  display: flex;
  align-items: center;
  gap: 10px;
}

.bundle-upload {
  border: 1px dashed var(--color-border-2);
  border-radius: 6px;
  padding: 10px 12px;
}

.bundle-upload > summary {
  cursor: pointer;
  color: var(--color-text-2);
  font-size: 13px;
  user-select: none;
}

.bundle-upload[open] > summary {
  margin-bottom: 10px;
}

:deep(.arco-tree-node-title) {
  width: 100%;
}

:deep(.arco-tree-node-title:hover) {
  background: var(--color-fill-1);
}

:deep(.arco-tree-node-selected .arco-tree-node-title) {
  color: var(--color-text-1);
  background: var(--color-fill-1);
}

.empty-state {
  display: grid;
  place-items: center;
  min-height: 140px;
  color: var(--color-text-3);
}

@media (max-width: 1280px) {
  .menu-workbench {
    grid-template-columns: 1fr;
  }

  .menu-sidebar {
    min-height: 360px;
    max-height: 460px;
  }
}

@media (max-width: 768px) {
  .menu-actions {
    justify-content: flex-start;
  }

  .detail-grid {
    grid-template-columns: 120px minmax(0, 1fr);
    min-width: 0;
  }

  .detail-cell:nth-child(4n) {
    border-right: 1px solid var(--color-border-2);
  }

  .detail-cell:nth-child(2n) {
    border-right: 0;
  }

  .detail-cell--wide {
    grid-column: span 2;
  }
}
</style>
