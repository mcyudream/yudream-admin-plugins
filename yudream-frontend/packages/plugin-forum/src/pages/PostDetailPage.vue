<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaAvatar, FaButton, FaIcon, FaPageHeader, FaPageMain, FaTextarea, FaTag, useFaToast } from '@yudream/components'
import { onMounted, ref } from 'vue'
import { createForumApi } from '../api/forum-api'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import type { Category, Comment, Post } from '../types'
const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const api = createForumApi(props.sdk)
const toast = useFaToast()
const post = ref<Post | null>(null)
const comments = ref<Comment[]>([])
const categories = ref<Category[]>([])
const comment = ref('')
const loading = ref(true)
const id = new URLSearchParams(window.location.search).get('id') || ''
const categoryName = () => categories.value.find(item => item.id === post.value?.categoryId)?.name || '未分类'
const authorLabel = (id: string) => id || '社区成员'
async function load() {
  try {
    const [postResult, commentResult, categoryResult] = await Promise.all([api.post(id), api.comments(id), api.categories()])
    post.value = postResult
    comments.value = commentResult.records
    categories.value = categoryResult.records
  }
  catch (error) { toast.error(error instanceof Error ? error.message : '帖子加载失败') }
  finally { loading.value = false }
}
function back() { window.history.back() }
function openProfile(userId: string) { window.history.pushState({}, '', `/platform/plugins/forum/profile?id=${encodeURIComponent(userId)}`); window.dispatchEvent(new PopStateEvent('popstate')) }
async function send() { if (!comment.value.trim()) return; try { await api.comment(id, comment.value); comment.value = ''; comments.value = (await api.comments(id)).records; toast.success('评论已提交') } catch (error) { toast.error(error instanceof Error ? error.message : '评论失败') } }
async function interact(type: 'like' | 'bookmark') { try { await api.interact(id, type); post.value = await api.post(id) } catch (error) { toast.error(error instanceof Error ? error.message : '操作失败') } }
onMounted(load)
</script>
<template>
  <div class="forum-page forum-detail-page">
    <FaPageHeader :title="post?.title || '帖子详情'">
      <FaButton variant="ghost" title="返回论坛" aria-label="返回论坛" @click="back"><FaIcon name="i-ri:arrow-left-line" />返回</FaButton>
    </FaPageHeader>
    <FaPageMain>
      <div v-if="loading" v-loading="true" class="forum-detail-loading" />
      <template v-else-if="post">
        <article class="forum-detail forum-detail-layout">
          <header class="forum-detail-author">
            <button type="button" class="forum-avatar-button" @click="openProfile(post.authorId)"><FaAvatar :alt="authorLabel(post.authorId)">{{ authorLabel(post.authorId).slice(0, 1).toUpperCase() }}</FaAvatar></button>
            <div><button type="button" class="forum-detail-author__name" @click="openProfile(post.authorId)">{{ authorLabel(post.authorId) }}</button><div class="forum-detail-author__meta">{{ new Date(Number(post.publishedAt)).toLocaleString() }} · {{ categoryName() }}</div></div>
            <div class="forum-detail-author__badges"><FaTag v-if="post.pinned" variant="default"><FaIcon name="i-ri:pushpin-2-fill" />置顶</FaTag><FaTag v-if="post.featured" variant="secondary"><FaIcon name="i-ri:star-fill" />精华</FaTag></div>
          </header>
          <div class="forum-detail__body"><MarkdownPreview :content="post.body" /></div>
          <footer class="forum-detail__footer"><div class="forum-post-card__context"><FaTag variant="outline"><FaIcon name="i-ri:folder-2-line" />{{ categoryName() }}</FaTag><FaTag v-for="tag in post.tags" :key="tag" variant="outline">#{{ tag }}</FaTag></div><div class="forum-detail-actions"><FaButton variant="ghost" title="点赞" aria-label="点赞" @click="interact('like')"><FaIcon name="i-ri:thumb-up-line" />{{ post.likes }}</FaButton><FaButton variant="ghost" title="收藏" aria-label="收藏" @click="interact('bookmark')"><FaIcon name="i-ri:bookmark-line" />{{ post.bookmarks }}</FaButton><span><FaIcon name="i-ri:eye-line" />{{ post.views }}</span></div></footer>
        </article>
        <section class="forum-comments"><div class="forum-section-heading"><h2><FaIcon name="i-ri:message-3-line" />评论</h2><span>{{ post.comments }} 条评论</span></div><div v-for="item in comments" :key="item.id" class="forum-comment"><button type="button" class="forum-avatar-button" @click="openProfile(item.authorId)"><FaAvatar :alt="authorLabel(item.authorId)">{{ authorLabel(item.authorId).slice(0, 1).toUpperCase() }}</FaAvatar></button><div class="forum-comment__body"><div class="forum-comment__meta"><button type="button" @click="openProfile(item.authorId)">{{ authorLabel(item.authorId) }}</button><span>{{ new Date(Number(item.createdAt)).toLocaleString() }}</span></div><MarkdownPreview :content="item.body" /></div></div><div class="forum-comment-editor"><FaTextarea v-model="comment" placeholder="写下你的评论，支持 Markdown"/><FaButton :disabled="!comment.trim()" @click="send"><FaIcon name="i-ri:send-plane-line" />发表评论</FaButton></div></section>
      </template>
      <div v-else class="forum-empty"><FaIcon name="i-ri:file-warning-line" /><strong>帖子不存在或暂不可见</strong></div>
    </FaPageMain>
  </div>
</template>
