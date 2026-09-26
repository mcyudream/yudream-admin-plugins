import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'md-editor-v3/lib/style.css'
import 'virtual:uno.css'
import './styles.css'
import AdminAuditPage from './pages/AdminAuditPage.vue'
import AdminCategoriesPage from './pages/AdminCategoriesPage.vue'
import AdminPostsPage from './pages/AdminPostsPage.vue'
import AdminSettingsPage from './pages/AdminSettingsPage.vue'
import BookmarksPage from './pages/BookmarksPage.vue'
import HomePage from './pages/HomePage.vue'
import MyPostsPage from './pages/MyPostsPage.vue'
import PostDetailPage from './pages/PostDetailPage.vue'
import PostEditPage from './pages/PostEditPage.vue'
import ProfilePage from './pages/ProfilePage.vue'

export const Home = HomePage
export const PostDetail = PostDetailPage
export const PostEdit = PostEditPage
export const Profile = ProfilePage
export const MyPosts = MyPostsPage
export const Bookmarks = BookmarksPage
export const AdminPosts = AdminPostsPage
export const AdminCategories = AdminCategoriesPage
export const AdminSettings = AdminSettingsPage
export const AdminAudit = AdminAuditPage
export const routes = { Home, PostDetail, PostEdit, Profile, MyPosts, Bookmarks, AdminPosts, AdminCategories, AdminSettings, AdminAudit,
  'forum/Home': HomePage, 'forum/PostDetail': PostDetailPage, 'forum/PostEdit': PostEditPage, 'forum/Profile': ProfilePage,
  'forum/MyPosts': MyPostsPage, 'forum/Bookmarks': BookmarksPage, 'forum/AdminPosts': AdminPostsPage,
  'forum/AdminCategories': AdminCategoriesPage, 'forum/AdminSettings': AdminSettingsPage, 'forum/AdminAudit': AdminAuditPage }
export default defineYuDreamPlugin({ routes, default: Home })
