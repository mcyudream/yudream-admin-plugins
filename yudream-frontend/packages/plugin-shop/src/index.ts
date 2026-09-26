import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'md-editor-v3/lib/style.css'
import 'virtual:uno.css'
import './styles.css'
import AdminOrdersPage from './pages/AdminOrdersPage.vue'
import AdminProductEditPage from './pages/AdminProductEditPage.vue'
import AdminProductsPage from './pages/AdminProductsPage.vue'
import AdminSettingsPage from './pages/AdminSettingsPage.vue'
import ExchangePage from './pages/ExchangePage.vue'
import MyOrdersPage from './pages/MyOrdersPage.vue'
import MyProductsPage from './pages/MyProductsPage.vue'
import MySalesPage from './pages/MySalesPage.vue'
import PlazaPage from './pages/PlazaPage.vue'
import ProductDetailPage from './pages/ProductDetailPage.vue'
import ProductEditPage from './pages/ProductEditPage.vue'

export const Plaza = PlazaPage
export const Exchange = ExchangePage
export const ProductDetail = ProductDetailPage
export const MyOrders = MyOrdersPage
export const MyProducts = MyProductsPage
export const MySales = MySalesPage
export const ProductEdit = ProductEditPage
export const AdminProducts = AdminProductsPage
export const AdminProductEdit = AdminProductEditPage
export const AdminOrders = AdminOrdersPage
export const AdminSettings = AdminSettingsPage

export const routes = {
  Plaza,
  Exchange,
  ProductDetail,
  MyOrders,
  MyProducts,
  MySales,
  ProductEdit,
  AdminProducts,
  AdminProductEdit,
  AdminOrders,
  AdminSettings,
  'shop/Plaza': Plaza,
  'shop/Exchange': Exchange,
  'shop/ProductDetail': ProductDetail,
  'shop/MyOrders': MyOrders,
  'shop/MyProducts': MyProducts,
  'shop/MySales': MySales,
  'shop/ProductEdit': ProductEdit,
  'shop/AdminProducts': AdminProducts,
  'shop/AdminProductEdit': AdminProductEditPage,
  'shop/AdminOrders': AdminOrders,
  'shop/AdminSettings': AdminSettings,
}

export default defineYuDreamPlugin({
  routes,
  default: Plaza,
})
