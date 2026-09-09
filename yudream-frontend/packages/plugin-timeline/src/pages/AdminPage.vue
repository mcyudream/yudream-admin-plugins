<script setup lang="ts">
import type { TableColumn } from '@yudream/components'
import type { TimelinePluginModel } from '../composables/useTimelinePlugin'
import type { TimelineEventSummary } from '../types'
import { FaButton, FaCard, FaIcon, FaInput, FaPageHeader, FaPageMain, FaPagination, FaSearchBar, FaSelect, FaTable, FaTag, useFaModal } from '@yudream/components'
import { onMounted, ref } from 'vue'
import CoverThumb from '../components/CoverThumb.vue'
import EventEditorModal from '../components/EventEditorModal.vue'
import { EVENT_TYPE_OPTIONS, eventTypeMeta, formatEventDate, formatTime, resolveImageUrl, resolveThumbUrl } from '../composables/utils'

const props = defineProps<{ model: TimelinePluginModel }>()
const model = props.model
const confirm = useFaModal()

const editorOpen = ref(false)
const editingId = ref<string | null>(null)

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '已发布', value: 'published' },
  { label: '草稿', value: 'draft' },
]

const typeOptions = [
  { label: '全部类型', value: '' },
  ...EVENT_TYPE_OPTIONS.map(item => ({ label: item.label, value: item.value })),
]

const columns: TableColumn<TimelineEventSummary>[] = [
  { accessorKey: 'eventDate', header: '事件时间', width: 130 },
  { id: 'title', header: '事件', minWidth: 260 },
  { accessorKey: 'imageCount', header: '图集', width: 70 },
  { accessorKey: 'sort', header: '排序', width: 70 },
  { id: 'published', header: '状态', width: 90 },
  { accessorKey: 'updatedAt', header: '更新时间', width: 170 },
  { id: 'operation', header: '操作', width: 230 },
]

function applyFilters() {
  model.adminPager.page = 1
  void model.loadAdminEvents(1)
}

function coverUrl(row: TimelineEventSummary) {
  return resolveImageUrl(model.sdk, row.coverImage)
}

function coverThumb(row: TimelineEventSummary) {
  return resolveThumbUrl(model.sdk, row.coverImage)
}

function openCreate() {
  editingId.value = null
  editorOpen.value = true
}

function openEdit(row: TimelineEventSummary) {
  editingId.value = row.id
  editorOpen.value = true
}

async function onSaved() {
  editorOpen.value = false
  await model.loadAdminEvents()
}

function confirmDelete(row: TimelineEventSummary) {
  confirm.confirm({
    title: '删除事件',
    content: `确认删除「${row.title}」吗？删除后公开时间轴将不再展示该事件，且不可恢复。`,
    onConfirm: () => model.removeEvent(row),
  })
}

function togglePublish(row: TimelineEventSummary) {
  void model.setPublished(row.id, !row.published)
}

onMounted(() => {
  void model.loadAdminEvents()
})
</script>

<template>
  <FaPageHeader title="事件管理" description="维护大事记时间轴：新建与编辑事件、Markdown 图文详情、发布到公开站点">
    <FaButton @click="openCreate">
      <FaIcon name="i-ri:add-line" />新建事件
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <FaSearchBar class="w-full">
      <div class="tl-toolbar">
        <FaInput
          v-model="model.adminFilters.keyword"
          placeholder="搜索标题、简述或时间文案"
          clearable
          @keydown.enter="applyFilters"
          @clear="applyFilters"
        />
        <FaSelect v-model="model.adminFilters.status" :options="statusOptions" @change="applyFilters" />
        <FaSelect v-model="model.adminFilters.type" :options="typeOptions" @change="applyFilters" />
        <FaButton variant="outline" @click="applyFilters">
          <FaIcon name="i-ri:search-line" />查询
        </FaButton>
      </div>
    </FaSearchBar>

    <div class="tl-desktop-only">
      <FaTable
        v-loading="model.adminLoading"
        :columns="columns"
        :data="model.adminEvents"
        row-key="id"
        table-root-class="tl-table-scroll"
        table-class="tl-table-w1000"
        border
        stripe
        column-visibility
        empty-text="暂无事件，点击右上角新建第一条大事记"
      >
        <template #cell-eventDate="{ row }">
          <span>{{ formatEventDate(row.original.eventDate, row.original.dateLabel) }}</span>
        </template>
        <template #cell-title="{ row }">
          <div class="tl-event-cell">
            <CoverThumb
              v-if="row.original.coverImage"
              :src="coverThumb(row.original)"
              :fallback-src="coverUrl(row.original)"
              :alt="row.original.title"
              img-class="tl-event-thumb"
            />
            <div class="tl-event-cell-text">
              <span class="tl-event-cell-title" :title="row.original.title">
                <FaTag :variant="eventTypeMeta(row.original.eventType).tagVariant" class="tl-event-type-tag">
                  {{ eventTypeMeta(row.original.eventType).label }}
                </FaTag>
                {{ row.original.title }}
              </span>
              <span v-if="row.original.summary" class="tl-event-cell-summary" :title="row.original.summary">{{ row.original.summary }}</span>
            </div>
          </div>
        </template>
        <template #cell-published="{ row }">
          <FaTag :variant="row.original.published ? 'default' : 'outline'">
            {{ row.original.published ? '已发布' : '草稿' }}
          </FaTag>
        </template>
        <template #cell-updatedAt="{ row }">
          {{ formatTime(row.original.updatedAt) }}
        </template>
        <template #cell-operation="{ row }">
          <div class="flex-center gap-2">
            <FaButton size="sm" variant="outline" @click="openEdit(row.original)">
              编辑
            </FaButton>
            <FaButton size="sm" variant="outline" :disabled="model.toggling" @click="togglePublish(row.original)">
              {{ row.original.published ? '下架' : '发布' }}
            </FaButton>
            <FaButton size="sm" variant="destructive" @click="confirmDelete(row.original)">
              删除
            </FaButton>
          </div>
        </template>
      </FaTable>
    </div>

    <div class="tl-mobile-only">
      <div v-loading="model.adminLoading" class="tl-mobile-list">
        <FaCard v-for="row in model.adminEvents" :key="row.id">
          <div class="tl-mobile-card">
            <div class="tl-mobile-card-head">
              <CoverThumb
                v-if="row.coverImage"
                :src="coverThumb(row)"
                :fallback-src="coverUrl(row)"
                :alt="row.title"
                img-class="tl-event-thumb"
              />
              <div class="tl-event-cell-text">
                <span class="tl-event-cell-title">
                  <FaTag :variant="eventTypeMeta(row.eventType).tagVariant" class="tl-event-type-tag">
                    {{ eventTypeMeta(row.eventType).label }}
                  </FaTag>
                  {{ row.title }}
                </span>
                <span class="tl-event-cell-summary">{{ formatEventDate(row.eventDate, row.dateLabel) }}</span>
              </div>
              <FaTag :variant="row.published ? 'default' : 'outline'">
                {{ row.published ? '已发布' : '草稿' }}
              </FaTag>
            </div>
            <div v-if="row.summary" class="tl-mobile-card-summary">
              {{ row.summary }}
            </div>
            <div class="tl-mobile-card-actions">
              <FaButton size="sm" variant="outline" @click="openEdit(row)">
                编辑
              </FaButton>
              <FaButton size="sm" variant="outline" :disabled="model.toggling" @click="togglePublish(row)">
                {{ row.published ? '下架' : '发布' }}
              </FaButton>
              <FaButton size="sm" variant="destructive" @click="confirmDelete(row)">
                删除
              </FaButton>
            </div>
          </div>
        </FaCard>
        <div v-if="!model.adminLoading && model.adminEvents.length === 0" class="tl-mobile-empty">
          暂无事件，点击右上角新建第一条大事记
        </div>
      </div>
    </div>

    <FaPagination
      v-model:page="model.adminPager.page"
      v-model:size="model.adminPager.size"
      :total="model.adminPager.total"
      class="mt-3"
      @page-change="model.loadAdminEvents()"
      @size-change="applyFilters"
    />

    <EventEditorModal v-model:open="editorOpen" :event-id="editingId" :model="model" @saved="onSaved" />
  </FaPageMain>
</template>
