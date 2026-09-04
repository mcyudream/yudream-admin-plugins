import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import markdownEditorStyles from 'md-editor-v3/lib/style.css?inline'
import QuestionBankPlugin from './QuestionBankPlugin.vue'
import questionBankStyles from './styles.css?inline'

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
}

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-questionbank-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = id
    document.head.appendChild(style)
  }
  style.textContent = `${markdownEditorStyles}\n${questionBankStyles}`
}

export function dispose() {
  document.getElementById('yudream-plugin-questionbank-style')?.remove()
}

export default defineYuDreamPlugin({
  routes,
  default: Practice,
  install,
  dispose,
})
