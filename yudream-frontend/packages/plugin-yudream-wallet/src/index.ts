import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import DashboardBalanceCard from './components/DashboardBalanceCard.vue'
import WalletPlugin from './WalletPlugin.vue'

export const Home = WalletPlugin
export const Recharge = WalletPlugin
export const Settings = WalletPlugin
export const RechargeSettings = WalletPlugin
export const Balances = WalletPlugin
export const Transactions = WalletPlugin
export const YUDREAM_WALLET_BALANCE_CARD = DashboardBalanceCard

export const routes = {
  Home,
  Recharge,
  Settings,
  RechargeSettings,
  Balances,
  Transactions,
  DashboardBalanceCard,
  YUDREAM_WALLET_BALANCE_CARD,
  'yudream-wallet/DashboardBalanceCard': DashboardBalanceCard,
  'yudream-wallet/Home': Home,
  'yudream-wallet/Recharge': Recharge,
  'yudream-wallet/Settings': Settings,
  'yudream-wallet/RechargeSettings': RechargeSettings,
  'yudream-wallet/Balances': Balances,
  'yudream-wallet/Transactions': Transactions,
  'yudream-wallet/System': Settings,
}

export default defineYuDreamPlugin({
  routes,
  default: Home,
})
