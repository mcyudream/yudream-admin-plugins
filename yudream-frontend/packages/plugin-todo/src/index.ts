import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import DashboardTodoCard from './components/DashboardTodoCard.vue'
import AdminTasksPage from './pages/AdminTasksPage.vue'
import TasksPage from './pages/TasksPage.vue'

export const Tasks = TasksPage
export const AdminTasks = AdminTasksPage

export const routes = {
  Tasks,
  AdminTasks,
  DashboardTodoCard,
  'todo/Tasks': Tasks,
  'todo/AdminTasks': AdminTasks,
  'todo/DashboardTodoCard': DashboardTodoCard,
}

export default defineYuDreamPlugin({
  routes,
  default: Tasks,
})
