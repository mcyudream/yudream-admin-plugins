import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import StudentInfoPlugin from './StudentInfoPlugin.vue'

export const Profile = StudentInfoPlugin
export const AdminProfiles = StudentInfoPlugin

export const routes = {
  Profile,
  AdminProfiles,
  'yudream-student-info/Home': Profile,
  'yudream-student-info/Profile': Profile,
  'yudream-student-info/AdminProfiles': AdminProfiles,
}

export default defineYuDreamPlugin({
  routes,
  default: Profile,
})
