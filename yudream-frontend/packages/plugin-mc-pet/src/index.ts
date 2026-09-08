import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import mcPetStyles from './styles.css?inline'
import GlobalPet from './components/GlobalPet.vue'
import AdminDefaultsPage from './pages/AdminDefaultsPage.vue'
import AdminPetsPage from './pages/AdminPetsPage.vue'
import MyPetPage from './pages/MyPetPage.vue'

export const Home = MyPetPage
export const AdminDefaults = AdminDefaultsPage
export const AdminPets = AdminPetsPage

export const routes = {
  Home,
  AdminDefaults,
  AdminPets,
  GlobalPet,
  'mc-pet/Home': Home,
  'mc-pet/AdminDefaults': AdminDefaults,
  'mc-pet/AdminPets': AdminPets,
  'mc-pet/GlobalPet': GlobalPet,
}

export function install() {
  if (typeof document === 'undefined') {
    return
  }
  const id = 'yudream-plugin-mc-pet-style'
  let style = document.getElementById(id) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    document.head.appendChild(style)
  }
  style.id = id
  style.textContent = mcPetStyles
}

export default defineYuDreamPlugin({
  routes,
  default: Home,
  install,
})
