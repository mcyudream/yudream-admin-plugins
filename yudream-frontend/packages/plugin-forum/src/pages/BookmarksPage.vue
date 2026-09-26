<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaPageHeader, FaPageMain } from '@yudream/components'
defineProps<{sdk:YuDreamPluginSdk}>()
</script>
<template><div class="forum-page"><FaPageHeader title="我的收藏" description="收藏的帖子会在此集中展示"/><FaPageMain><div class="forum-empty">收藏列表接口将在下一步接入，当前可在帖子详情和论坛首页使用收藏操作。</div></FaPageMain></div></template>
