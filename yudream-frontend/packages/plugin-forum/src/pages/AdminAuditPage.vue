<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaCard, FaPageHeader, FaPageMain, FaPagination, FaResponsiveTable, FaTag, useFaToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createForumApi } from '../api/forum-api'
const props=defineProps<{sdk:YuDreamPluginSdk}>(); const api=createForumApi(props.sdk); const toast=useFaToast(); const rows=ref<Record<string,string|number>[]>([]); const page=ref(1); const size=ref(20); const total=ref(0)
const columns=[{id:'createdAt',header:'时间',accessorKey:'createdAt'},{id:'actorId',header:'操作者',accessorKey:'actorId'},{id:'targetType',header:'对象',accessorKey:'targetType'},{id:'action',header:'动作',accessorKey:'action'},{id:'detail',header:'说明',accessorKey:'detail'}]
async function load(){try{const result=await api.audit(page.value,size.value);rows.value=result.records;total.value=result.total}catch(e){toast.error(e instanceof Error?e.message:'审计加载失败')}}
onMounted(load)
</script>
<template><div class="forum-page"><FaPageHeader title="操作审计" description="审核、置顶、精华和论坛设置变更记录"/><FaPageMain><FaResponsiveTable row-key="id" :columns="columns" :data="rows" border stripe table-root-class="rounded-lg overflow-hidden" table-class="min-w-[900px]"><template #cell-action="{row}"><FaTag variant="outline">{{row.original.action}}</FaTag></template><template #card="{ row }">
  <FaCard class="w-full">
    <div class="flex flex-col gap-3">
      <div class="flex items-center justify-between gap-2">
        <span class="min-w-0 break-words text-base font-semibold">{{ row.action }}</span>
        <span class="break-all text-sm text-secondary-foreground/60">{{ row.createdAt }}</span>
      </div>
      <div class="flex flex-col gap-1 text-sm">
        <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">操作者</span><span class="break-all">{{ row.actorId }}</span></div>
        <div class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">对象</span><span class="break-all">{{ row.targetType }}</span></div>
        <div v-if="row.detail" class="flex gap-2"><span class="shrink-0 text-secondary-foreground/60">说明</span><span class="break-all">{{ row.detail }}</span></div>
      </div>
    </div>
  </FaCard>
</template></FaResponsiveTable><FaPagination v-model:page="page" v-model:size="size" :total="total" class="mt-3" @page-change="load" @size-change="page = 1; load()"/></FaPageMain></div></template>
