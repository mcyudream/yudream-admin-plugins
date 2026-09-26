<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaPageHeader, FaPageMain, FaPagination, FaTable, FaTag, useFaToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createForumApi } from '../api/forum-api'
const props=defineProps<{sdk:YuDreamPluginSdk}>(); const api=createForumApi(props.sdk); const toast=useFaToast(); const rows=ref<Record<string,string|number>[]>([]); const page=ref(1); const size=ref(20); const total=ref(0)
const columns=[{id:'createdAt',header:'时间',accessorKey:'createdAt'},{id:'actorId',header:'操作者',accessorKey:'actorId'},{id:'targetType',header:'对象',accessorKey:'targetType'},{id:'action',header:'动作',accessorKey:'action'},{id:'detail',header:'说明',accessorKey:'detail'}]
async function load(){try{const result=await api.audit(page.value,size.value);rows.value=result.records;total.value=result.total}catch(e){toast.error(e instanceof Error?e.message:'审计加载失败')}}
onMounted(load)
</script>
<template><div class="forum-page"><FaPageHeader title="操作审计" description="审核、置顶、精华和论坛设置变更记录"/><FaPageMain><FaTable row-key="id" :columns="columns" :data="rows" border stripe table-root-class="rounded-lg overflow-hidden" table-class="min-w-[900px]"><template #cell-action="{row}"><FaTag variant="outline">{{row.original.action}}</FaTag></template></FaTable><FaPagination v-model:page="page" v-model:size="size" :total="total" class="mt-3" @page-change="load" @size-change="page = 1; load()"/></FaPageMain></div></template>
