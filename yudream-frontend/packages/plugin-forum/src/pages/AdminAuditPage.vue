<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { YdPagination, YdResponsiveEnhancedTable, YdTag, useYdToast, YdTablePageLayout, type ActionMenuItem } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createForumApi } from '../api/forum-api'
const props=defineProps<{sdk:YuDreamPluginSdk}>(); const api=createForumApi(props.sdk); const toast=useYdToast(); const rows=ref<Record<string,string|number>[]>([]); const page=ref(1); const size=ref(20); const total=ref(0)
const columns=[{id:'createdAt',header:'时间',accessorKey:'createdAt'},{id:'actorId',header:'操作者',accessorKey:'actorId'},{id:'targetType',header:'对象',accessorKey:'targetType'},{id:'action',header:'动作',accessorKey:'action'},{id:'detail',header:'说明',accessorKey:'detail'}]
async function load(){try{const result=await api.audit(page.value,size.value);rows.value=result.records;total.value=result.total}catch(e){toast.error(e instanceof Error?e.message:'审计加载失败')}}
onMounted(load)
</script>
<template><YdTablePageLayout title="操作审计" :show-search="false">
            <template #table>
<YdResponsiveEnhancedTable row-key="id" :columns="columns" :data="rows" border stripe column-visibility table-root-class="rounded-lg overflow-hidden" table-class="min-w-[820px]" empty-text="暂无审计记录"><template #cell-action="{row}"><YdTag variant="outline">{{row.original.action}}</YdTag></template></YdResponsiveEnhancedTable>
            </template>
            <template #footer>
      <YdPagination v-model:page="page" v-model:size="size" :total="total" @page-change="load" @size-change="page = 1; load()"/>
            </template>
          </YdTablePageLayout>
</template>
