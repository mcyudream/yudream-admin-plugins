<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaPageHeader, FaPageMain, FaPagination, useFaToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createForumApi } from '../api/forum-api'
import PostCard from '../components/PostCard.vue'
import type { Post } from '../api/forum-api'
const props=defineProps<{sdk:YuDreamPluginSdk}>(); const api=createForumApi(props.sdk); const toast=useFaToast(); const rows=ref<Post[]>([]); const page=ref(1); const size=ref(10); const total=ref(0)
async function load(){try{const result=await api.posts({page:page.value,size:size.value});rows.value=result.records;total.value=result.total}catch(e){toast.error(e instanceof Error?e.message:'加载失败')}}
onMounted(load)
</script>
<template><div class="forum-page"><FaPageHeader title="我的帖子" description="管理你发布的帖子和审核状态"/><FaPageMain><div class="forum-list"><PostCard v-for="post in rows" :key="post.id" :post="post" compact/><div v-if="!rows.length" class="forum-empty">暂无帖子</div></div><FaPagination v-model:page="page" v-model:size="size" :total="total" class="mt-3" @page-change="load" @size-change="page = 1; load()"/></FaPageMain></div></template>
