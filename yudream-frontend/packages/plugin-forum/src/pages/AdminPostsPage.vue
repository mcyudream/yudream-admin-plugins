<script setup lang="ts">
import { YdLoadingOverlay, YdTablePageLayout } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdButton, YdPagination, YdResponsiveEnhancedTable, YdSelect, useYdToast, type ActionMenuItem, type StatusColumn } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createForumApi } from '../api/forum-api'
import type { Post } from '../api/forum-api'
const props=defineProps<{sdk:YuDreamPluginSdk}>(); const api=createForumApi(props.sdk); const toast=useYdToast(); const rows=ref<Post[]>([]); const total=ref(0); const page=ref(1); const size=ref(10); const status=ref('pending'); const loading=ref(false)
const columns=[{id:'title',header:'标题',accessorKey:'title'},{id:'authorId',header:'作者',accessorKey:'authorId'},{id:'createdAt',header:'创建时间',accessorKey:'createdAt'}]

/** 帖子状态：语义色胶囊 */
const postStatusColumn: StatusColumn<Post> = {
  statusKey: 'status',
  header: '状态',
  width: 110,
  statusMap: {
    pending: { label: '待审核', variant: 'default', tone: 'warning' },
    published: { label: '已发布', variant: 'default', tone: 'success' },
    rejected: { label: '已拒绝', variant: 'secondary', tone: 'danger' },
    archived: { label: '已归档', variant: 'secondary', tone: 'neutral' },
  },
}

/** 行操作统一收进操作菜单 */
const rowActions: ActionMenuItem<Post>[] = [
  { label: '通过', icon: 'morph:check', visible: row => row.status === 'pending', onClick: row => moderate(row.id, 'published') },
  { label: '拒绝', icon: 'morph:ban', danger: true, visible: row => row.status === 'pending', onClick: row => moderate(row.id, 'rejected') },
  { label: row => (row.pinned ? '取消置顶' : '置顶'), icon: 'morph:pin', onClick: row => flag(row.id, 'pinned') },
  { label: row => (row.featured ? '取消精华' : '设为精华'), icon: 'morph:sparkles', onClick: row => flag(row.id, 'featured') },
]
async function load(){loading.value=true;try{const result=await api.adminPosts({page:page.value,size:size.value,status:status.value});rows.value=result.records;total.value=result.total}catch(e){toast.error(e instanceof Error?e.message:'加载失败')}finally{loading.value=false}}
async function moderate(id:string,state:'published'|'rejected'|'archived'){try{await api.moderate(id,state);await load();toast.success('状态已更新')}catch(e){toast.error(e instanceof Error?e.message:'操作失败')}}
async function flag(id:string,key:'pinned'|'featured'){try{const post=rows.value.find(item=>item.id===id);await api.flags(id,{[key]:!post?.[key]});await load()}catch(e){toast.error(e instanceof Error?e.message:'操作失败')}}
onMounted(load)
</script>
<template><YdTablePageLayout title="帖子审核">
            <template #search>
              <div class="yd-filter-grid" style="--yd-filter-columns: 1">
                <YdSelect v-model="status" class="w-full" :options="[{label:'待审核',value:'pending'},{label:'已发布',value:'published'},{label:'已拒绝',value:'rejected'},{label:'全部',value:''}]" @update:model-value="page=1;load()"/>
              </div>
            </template>
            <template #table>
<YdLoadingOverlay :loading="loading"><YdResponsiveEnhancedTable row-key="id" :columns="columns" :data="rows" border stripe column-visibility :status-column="postStatusColumn" :actions="rowActions" table-root-class="rounded-lg overflow-hidden" table-class="min-w-[760px]" empty-text="暂无帖子"></YdResponsiveEnhancedTable></YdLoadingOverlay>
            </template>
            <template #footer>
      <YdPagination v-model:page="page" v-model:size="size" :total="total" @page-change="load" @size-change="page = 1; load()"/>
            </template>
          </YdTablePageLayout>
</template>
