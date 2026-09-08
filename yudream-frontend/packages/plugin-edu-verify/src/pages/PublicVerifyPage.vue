<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { FaButton, FaIcon, FaPageHeader, FaPageMain } from '@yudream/components'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PublicVerifyForm from '../components/PublicVerifyForm.vue'
import { queryText } from '../types'

const props = defineProps<{ sdk: YuDreamPluginSdk }>()
const route = useRoute()
const router = useRouter()
const email = ref(queryText(route.query.email))

function backToRegister() {
  const query: Record<string, string> = { form: 'register' }
  const value = email.value.trim()
  if (value) {
    query.email = value
  }
  router.push({ path: '/login', query })
}
</script>

<template>
  <section class="ev-page">
    <FaPageHeader title="高校学历认证" description="注册前请先完成高校身份核验。白名单内的教育邮箱填写后即视为已核验；学信网或人工审核须填写姓名与学校。">
      <FaButton variant="outline" @click="backToRegister">
        <FaIcon name="i-ri:arrow-go-back-line" />
        返回注册
      </FaButton>
    </FaPageHeader>
    <FaPageMain>
      <PublicVerifyForm
        :sdk="props.sdk"
        :initial-email="email"
        :initial-channel="queryText(route.query.channel)"
        @update:email="value => email = value"
        @done="payload => email = payload.email"
      />
    </FaPageMain>
  </section>
</template>
