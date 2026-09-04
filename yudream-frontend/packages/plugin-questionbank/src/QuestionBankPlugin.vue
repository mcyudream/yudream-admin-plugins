<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed } from 'vue'
import { useQuestionBankPlugin } from './composables/useQuestionBankPlugin'
import AdminPage from './pages/AdminPage.vue'
import AdminComposePage from './pages/AdminComposePage.vue'
import AdminPaperEditPage from './pages/AdminPaperEditPage.vue'
import AdminPaperPrintPage from './pages/AdminPaperPrintPage.vue'
import AdminPapersPage from './pages/AdminPapersPage.vue'
import AdminRecordsPage from './pages/AdminRecordsPage.vue'
import AdminReviewPage from './pages/AdminReviewPage.vue'
import AdminSettingsPage from './pages/AdminSettingsPage.vue'
import BuzzScreenPage from './pages/BuzzScreenPage.vue'
import CategoriesPage from './pages/CategoriesPage.vue'
import PapersPage from './pages/PapersPage.vue'
import PracticePage from './pages/PracticePage.vue'
import QuestionEditPage from './pages/QuestionEditPage.vue'
import RecordsPage from './pages/RecordsPage.vue'
import SessionPage from './pages/SessionPage.vue'
import SharedComposePage from './pages/SharedComposePage.vue'

const props = defineProps<{ sdk: YuDreamPluginSdk, route?: { meta?: { plugin?: { component?: string } } } }>()
const model = useQuestionBankPlugin(props.sdk)
const page = computed(() => {
  switch (props.route?.meta?.plugin?.component?.split('/').pop()) {
    case 'Session':
      return SessionPage
    case 'Records':
      return RecordsPage
    case 'Papers':
      return PapersPage
    case 'Admin':
      return AdminPage
    case 'QuestionEdit':
      return QuestionEditPage
    case 'Categories':
      return CategoriesPage
    case 'AdminPapers':
      return AdminPapersPage
    case 'AdminPaperEdit':
      return AdminPaperEditPage
    case 'AdminPaperPrint':
      return AdminPaperPrintPage
    case 'AdminReview':
      return AdminReviewPage
    case 'AdminSettings':
      return AdminSettingsPage
    case 'AdminRecords':
      return AdminRecordsPage
    case 'AdminCompose':
      return AdminComposePage
    case 'BuzzScreen':
      return BuzzScreenPage
    case 'SharedCompose':
      return SharedComposePage
    default:
      return PracticePage
  }
})
</script>

<template>
  <div class="questionbank-plugin"><component :is="page" :model="model" :route="route" /></div>
</template>
