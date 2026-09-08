import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
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

export default defineYuDreamPlugin({
  routes,
  default: Home,
})
