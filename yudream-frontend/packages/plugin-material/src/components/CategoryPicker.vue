<script setup lang="ts">
import type { TableColumn, YdTablePickerQuery, YdTablePickerResult } from '@yudream/components'
import type { CategoryView } from '../types'
import { FaButton, FaIcon, FaInput, YdTablePicker } from '@yudream/components'
import { computed, nextTick, ref } from 'vue'

/**
 * 弹出式分类选择：与标签选择一致走 YdTablePicker 表格弹窗，单选。
 * 空值用空字符串表示（未分类 / 全部分类），在弹窗内取消勾选即回到空值。
 *
 * 传入 create 后，弹窗搜索栏里会多出「新建分类」，可在选择分类的同时就地补一个分类，
 * 不必退出去找管理页。新建（或同名复用）成功后自动选中它并保持弹窗打开。
 */
const props = withDefaults(defineProps<{
  categories: CategoryView[]
  placeholder?: string
  /** 新建分类回调：入参为名称，返回新建或同名复用的分类；返回 null 表示失败（提示由调用方负责）。 */
  create?: (name: string) => Promise<CategoryView | null>
}>(), { placeholder: '选择分类（不选为未分类）' })

const model = defineModel<string>({ default: '' })

/** YdTablePicker 是数组模型，这里桥接成单个分类 id */
const keys = computed<string[]>({
  get: () => (model.value ? [model.value] : []),
  set: (value) => {
    model.value = value[0] ?? ''
  },
})

/** 弹窗未打开时 labelCache 为空，触发器靠 initialLabels 显示分类名而不是原始 id */
const initialLabels = computed(() => Object.fromEntries(props.categories.map(category => [category.id, category.name])))

const columns: TableColumn<CategoryView>[] = [
  { accessorKey: 'name', header: '分类', minWidth: 180 },
  { accessorKey: 'materials', header: '物料数', width: 90 },
]

/** 分类全量由后端一次给出，弹窗内按关键字过滤 + 前端分页 */
async function fetcher(query: YdTablePickerQuery): Promise<YdTablePickerResult<CategoryView>> {
  const keyword = query.keyword.trim().toLowerCase()
  const filtered = keyword
    ? props.categories.filter(category => category.name.toLowerCase().includes(keyword))
    : props.categories
  const from = (query.page - 1) * query.size
  return { list: filtered.slice(from, from + query.size), total: filtered.length }
}

// ---------- 快捷新增分类 ----------

/** 拿来重新调 open()，让弹窗按新的 modelValue 重建选中态 */
const picker = ref<{ open: () => void } | null>(null)
const creating = ref(false)
const busy = ref(false)
const draftName = ref('')

function startCreate() {
  draftName.value = ''
  creating.value = true
}

function cancelCreate() {
  creating.value = false
  draftName.value = ''
}

async function submitCreate() {
  if (!props.create || busy.value) {
    return
  }
  const name = draftName.value.trim()
  if (!name) {
    return
  }
  busy.value = true
  try {
    const created = await props.create(name)
    if (!created) {
      // 失败提示已由调用方给出；保留输入让用户改名重试
      return
    }
    creating.value = false
    draftName.value = ''
    model.value = created.id
    // 弹窗内的 selectedKeys 是「打开那一刻」拍下的快照，只有再次 open() 才会按新 modelValue 重置。
    // 不等 nextTick 就调的话，子组件拿到的还是旧的 modelValue，新分类不会被勾上，
    // 随后点「确定」又会用空快照把刚选中的分类覆盖掉。
    await nextTick()
    picker.value?.open()
  }
  finally {
    busy.value = false
  }
}
</script>

<template>
  <YdTablePicker
    ref="picker"
    v-model="keys"
    :columns="columns"
    :fetcher="fetcher"
    row-key="id"
    label-key="name"
    :multiple="false"
    :initial-labels="initialLabels"
    title="选择分类"
    :placeholder="placeholder"
    search-placeholder="搜索分类名称"
    :empty-text="props.create ? '暂无分类，可点右侧「新建分类」' : '暂无分类，可在管理页创建'"
    :page-size="10"
    :page-sizes="[10, 20, 50]"
  >
    <template #filters>
      <template v-if="props.create">
        <template v-if="creating">
          <FaInput
            v-model="draftName"
            :maxlength="30"
            class="w-44"
            placeholder="新分类名称（最多 30 字）"
            @keyup.enter="submitCreate"
          />
          <FaButton size="sm" :loading="busy" :disabled="!draftName.trim()" @click="submitCreate">
            保存
          </FaButton>
          <FaButton size="sm" variant="outline" :disabled="busy" @click="cancelCreate">
            取消
          </FaButton>
        </template>
        <FaButton v-else size="sm" variant="outline" @click="startCreate">
          <FaIcon name="i-ri:add-line" />新建分类
        </FaButton>
      </template>
    </template>
  </YdTablePicker>
</template>
