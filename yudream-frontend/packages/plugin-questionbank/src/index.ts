import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'md-editor-v3/lib/style.css'
import QuestionBankPlugin from './QuestionBankPlugin.vue'
import 'virtual:uno.css'
import './styles.css'

export const Practice = QuestionBankPlugin
export const Session = QuestionBankPlugin
export const Records = QuestionBankPlugin
export const Papers = QuestionBankPlugin
export const Admin = QuestionBankPlugin
export const QuestionEdit = QuestionBankPlugin
export const Categories = QuestionBankPlugin
export const AdminPapers = QuestionBankPlugin
export const AdminPaperEdit = QuestionBankPlugin
export const AdminPaperPrint = QuestionBankPlugin
export const AdminReview = QuestionBankPlugin
export const AdminSettings = QuestionBankPlugin
export const AdminRecords = QuestionBankPlugin
export const AdminCompose = QuestionBankPlugin
export const BuzzScreen = QuestionBankPlugin
export const SharedCompose = QuestionBankPlugin
export const QuizRank = QuestionBankPlugin

export const routes = {
  Practice,
  Session,
  Records,
  Papers,
  Admin,
  QuestionEdit,
  Categories,
  AdminPapers,
  AdminPaperEdit,
  AdminPaperPrint,
  AdminReview,
  AdminSettings,
  AdminRecords,
  'questionbank/Practice': Practice,
  'questionbank/Session': Session,
  'questionbank/Records': Records,
  'questionbank/Papers': Papers,
  'questionbank/Admin': Admin,
  'questionbank/QuestionEdit': QuestionEdit,
  'questionbank/Categories': Categories,
  'questionbank/AdminPapers': AdminPapers,
  'questionbank/AdminPaperEdit': AdminPaperEdit,
  'questionbank/AdminPaperPrint': AdminPaperPrint,
  'questionbank/AdminReview': AdminReview,
  'questionbank/AdminSettings': AdminSettings,
  'questionbank/AdminRecords': AdminRecords,
  'questionbank/AdminCompose': AdminCompose,
  'questionbank/BuzzScreen': BuzzScreen,
  'questionbank/SharedCompose': SharedCompose,
  'questionbank/QuizRank': QuizRank,
}

export default defineYuDreamPlugin({
  routes,
  default: Practice,
})
