<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaIcon, FaInput, FaPageHeader, FaPageMain, FaSwitch, useFaToast } from '@yudream/components'
import { onMounted, reactive, ref } from 'vue'
import { createForumApi } from '../api/forum-api'
import CategorySelect from '../components/CategorySelect.vue'
import MarkdownEditor from '../components/MarkdownEditor.vue'
import TagPicker from '../components/TagPicker.vue'
import type { Category, Post } from '../types'
const props=defineProps<{sdk:YuDreamPluginSdk}>(); const api=createForumApi(props.sdk); const toast=useFaToast(); const categories=ref<Category[]>([]); const saving=ref(false); const form=reactive({ id:'', title:'', categoryId:'', tags:[] as string[], body:'', summary:'', draft:false }); const id=new URLSearchParams(window.location.search).get('id')||''
async function save(){ if(!form.title.trim()||!form.body.trim()||!form.categoryId){toast.error('请填写标题、分类和正文');return} saving.value=true; try { const result=await api.savePost(form.id||undefined,{ title:form.title,categoryId:form.categoryId,tags:form.tags,body:form.body,summary:form.summary,draft:form.draft }); toast.success(result.status==='published'?'帖子已发布':'帖子已提交，等待审核'); window.history.back() } catch(e){toast.error(e instanceof Error?e.message:'保存失败')} finally{saving.value=false} }
onMounted(async()=>{ categories.value=(await api.categories()).records; if(id){ const p=await api.post(id); Object.assign(form,{id:p.id,title:p.title,categoryId:p.categoryId,tags:p.tags,body:p.body,summary:p.summary}) } })
</script>
<template><div class="forum-page"><FaPageHeader :title="form.id?'编辑帖子':'发布帖子'"><template #default><FaButton variant="outline" @click="window.history.back()"><FaIcon name="i-ri:arrow-left-line"/>取消</FaButton><FaButton :loading="saving" @click="save"><FaIcon name="i-ri:send-plane-line"/>提交</FaButton></template></FaPageHeader><FaPageMain><form class="forum-editor" @submit.prevent="save"><label>标题<FaInput v-model="form.title" placeholder="请输入帖子标题"/></label><label>分类<CategorySelect v-model="form.categoryId" :categories="categories"/></label><label>标签<TagPicker v-model="form.tags"/></label><label>摘要<FaInput v-model="form.summary" placeholder="可选，留空自动生成"/></label><label>正文<MarkdownEditor v-model="form.body" :sdk="props.sdk" placeholder="支持 Markdown 与图片上传"/></label><label class="forum-switch"><FaSwitch v-model="form.draft"/>保存为草稿</label></form></FaPageMain></div></template>
