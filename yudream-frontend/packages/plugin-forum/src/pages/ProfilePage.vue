<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaAvatar, FaPageHeader, FaPageMain, FaPagination, useFaToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createForumApi } from '../api/forum-api'
import PostCard from '../components/PostCard.vue'
import type { Post, UserProfile } from '../types'
const props=defineProps<{sdk:YuDreamPluginSdk}>(); const api=createForumApi(props.sdk); const toast=useFaToast(); const profile=ref<UserProfile|null>(null); const rows=ref<Post[]>([]); const page=ref(1); const total=ref(0); const id=new URLSearchParams(window.location.search).get('id')||''
async function load(){try{profile.value=await api.profile(id);const result=await api.posts({authorId:id,page:page.value,size:10});rows.value=result.records;total.value=result.total}catch(e){toast.error(e instanceof Error?e.message:'资料加载失败')}}
onMounted(load)
</script>
<template><div class="forum-page"><FaPageHeader title="用户资料"/><FaPageMain><section v-if="profile" class="forum-profile"><FaAvatar :src="profile.avatar" :alt="profile.nickname||profile.username"/><div><h2>{{profile.nickname||profile.username}}</h2><p>@{{profile.username}}</p></div></section><section><h2>公开帖子</h2><div class="forum-list"><PostCard v-for="post in rows" :key="post.id" :post="post" compact/><div v-if="!rows.length" class="forum-empty">暂无公开帖子</div></div><FaPagination v-model:page="page" :total="total" class="mt-3" @page-change="load"/></section></FaPageMain></div></template>
