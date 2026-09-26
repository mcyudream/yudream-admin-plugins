<script setup lang="ts">
import { FaButton, FaIcon, FaInput } from '@yudream/components'
import { computed, onMounted, ref } from 'vue'
import { errorMessage, formatSize } from '../composables/utils'

/**
 * 侧栏迷你文件浏览器：面包屑（当前目录位置）+ 名称过滤 + 目录/文件列表。
 * 目录点击进入，文件点击回调 openFile（由父级决定打开编辑器/下载等）。
 * list 回调由父级注入（实例文件 / 节点文件两种通道复用），列表在节点侧
 * 按上限截断，避免大目录整目录拉取长时间占用控制信道。
 */
export interface BrowserEntry {
  name: string
  isDir: boolean
  size?: number
  path?: string
}

export interface BrowserListResult {
  entries: BrowserEntry[]
  /** 过滤后的目录总项数；大于 entries.length 表示已截断。 */
  total?: number
}

const props = withDefaults(defineProps<{
  list: (path: string, keyword?: string) => Promise<BrowserListResult>
  openFile: (path: string, entry: BrowserEntry) => void
  title?: string
  initialPath?: string
}>(), {
  title: '文件',
  initialPath: '',
})

const currentPath = ref(props.initialPath)
const entries = ref<BrowserEntry[]>([])
const total = ref(0)
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const truncated = computed(() => total.value > entries.value.length)

const crumbs = computed(() => {
  const parts = currentPath.value ? currentPath.value.split('/') : []
  const items: Array<{ label: string, path: string }> = [{ label: '/', path: '' }]
  let acc = ''
  for (const part of parts) {
    acc = acc ? `${acc}/${part}` : part
    items.push({ label: part, path: acc })
  }
  return items
})

const sortedEntries = computed(() => {
  return [...entries.value].sort((a, b) => {
    if (a.isDir !== b.isDir) {
      return a.isDir ? -1 : 1
    }
    return a.name.localeCompare(b.name)
  })
})

function entryPath(entry: BrowserEntry): string {
  return String(entry.path ?? (currentPath.value ? `${currentPath.value}/${entry.name}` : entry.name))
}

function parentPath(): string {
  const index = currentPath.value.lastIndexOf('/')
  return index > 0 ? currentPath.value.slice(0, index) : ''
}

async function load(path = currentPath.value) {
  loading.value = true
  error.value = ''
  try {
    const result = await props.list(path, keyword.value.trim() || undefined)
    const list = Array.isArray(result) ? result : (result?.entries ?? [])
    entries.value = list
    total.value = Number((!Array.isArray(result) && result?.total) ?? list.length) || 0
    currentPath.value = path
  }
  catch (e) {
    error.value = errorMessage(e, '加载目录失败')
    entries.value = []
    total.value = 0
  }
  finally {
    loading.value = false
  }
}

/** 名称过滤变化后回到当前目录重查（节点侧 keyword 过滤）。 */
function onKeywordEnter() {
  void load(currentPath.value)
}

function open(entry: BrowserEntry) {
  if (entry.isDir) {
    void load(entryPath(entry))
  }
  else {
    props.openFile(entryPath(entry), entry)
  }
}

defineExpose({ reload: () => load(currentPath.value) })

onMounted(() => void load(props.initialPath))
</script>

<template>
  <div class="mcp-fbp">
    <div class="mcp-fbp-head">
      <span class="mcp-fbp-title">{{ title }}</span>
      <span class="mcp-spacer" />
      <FaButton size="icon-sm" variant="ghost" :disabled="!currentPath || loading" title="上一级" @click="load(parentPath())">
        <FaIcon name="i-ri:arrow-up-line" />
      </FaButton>
      <FaButton size="icon-sm" variant="ghost" :disabled="loading" title="刷新" @click="load(currentPath)">
        <FaIcon name="i-ri:refresh-line" />
      </FaButton>
    </div>

    <div class="mcp-fbp-crumbs" :title="`/${currentPath}`">
      <template v-for="(crumb, index) in crumbs" :key="crumb.path || '/'">
        <a class="mcp-fbp-crumb" :class="{ 'is-active': index === crumbs.length - 1 }" @click.prevent="load(crumb.path)">{{ crumb.label }}</a>
        <span v-if="index < crumbs.length - 1" class="mcp-fbp-sep">/</span>
      </template>
    </div>

    <div class="mcp-fbp-filter">
      <FaInput v-model="keyword" placeholder="按名称过滤（回车）" clearable class="mcp-w-full" @keydown.enter="onKeywordEnter" />
    </div>

    <div v-if="error" class="mcp-fbp-msg is-error">
      {{ error }}
    </div>
    <div v-else-if="loading && !entries.length" class="mcp-fbp-msg">
      加载中…
    </div>
    <div v-else-if="!sortedEntries.length" class="mcp-fbp-msg">
      目录为空{{ keyword.trim() ? '或无匹配项' : '' }}
    </div>
    <template v-else>
      <div class="mcp-fbp-list" :class="{ 'is-loading': loading }">
        <button
          v-for="entry in sortedEntries"
          :key="entryPath(entry)"
          type="button"
          class="mcp-fbp-row"
          :title="entryPath(entry)"
          @click="open(entry)"
        >
          <FaIcon :name="entry.isDir ? 'i-ri:folder-line' : 'i-ri:file-line'" class="mcp-fbp-icon" :class="{ 'is-dir': entry.isDir }" />
          <span class="mcp-fbp-name">{{ entry.name }}</span>
          <span v-if="!entry.isDir" class="mcp-fbp-size">{{ formatSize(entry.size) }}</span>
        </button>
      </div>
      <div v-if="truncated" class="mcp-fbp-msg">
        仅显示前 {{ entries.length }} 项（共 {{ total }}）——可用上方名称过滤缩小范围。
      </div>
    </template>
  </div>
</template>
