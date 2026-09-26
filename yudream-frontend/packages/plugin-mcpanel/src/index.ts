import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import 'virtual:uno.css'
import './styles.css'
import AuditPage from './pages/admin/AuditPage.vue'
import CreateInstancePage from './pages/admin/CreateInstancePage.vue'
import DockerImagesPage from './pages/admin/DockerImagesPage.vue'
import InstanceDetailPage from './pages/admin/InstanceDetailPage.vue'
import InstanceDomainPage from './pages/admin/InstanceDomainPage.vue'
import InstanceFilesPage from './pages/admin/InstanceFilesPage.vue'
import InstanceModsPage from './pages/admin/InstanceModsPage.vue'
import InstanceProxyPage from './pages/admin/InstanceProxyPage.vue'
import InstanceSchedulesPage from './pages/admin/InstanceSchedulesPage.vue'
import InstanceSettingsPage from './pages/admin/InstanceSettingsPage.vue'
import InstancesPage from './pages/admin/InstancesPage.vue'
import MarketPage from './pages/admin/MarketPage.vue'
import NodeDetailPage from './pages/admin/NodeDetailPage.vue'
import NodeDeployGuidePage from './pages/admin/NodeDeployGuidePage.vue'
import NodeFilesPage from './pages/admin/NodeFilesPage.vue'
import NodeTerminalPage from './pages/admin/NodeTerminalPage.vue'
import NodesPage from './pages/admin/NodesPage.vue'
import OverviewPage from './pages/admin/OverviewPage.vue'
import QuickStartPage from './pages/admin/QuickStartPage.vue'
import ServerConfigPage from './pages/admin/ServerConfigPage.vue'
import SettingsPage from './pages/admin/SettingsPage.vue'
import TemplatesPage from './pages/admin/TemplatesPage.vue'
import TrashPage from './pages/admin/TrashPage.vue'
import DashboardOverviewCard from './components/DashboardOverviewCard.vue'
import DashboardQuickLinkCard from './components/DashboardQuickLinkCard.vue'

/** MC 面板远程模块契约。样式走声明式 style.css。 */
export const Nodes = NodesPage
export const NodeDetail = NodeDetailPage
export const NodeDeploy = NodeDeployGuidePage
export const NodeTerminal = NodeTerminalPage
export const NodeFiles = NodeFilesPage
export const Overview = OverviewPage
export const Instances = InstancesPage
export const CreateInstance = CreateInstancePage
export const InstanceDetail = InstanceDetailPage
export const InstanceDomain = InstanceDomainPage
export const InstanceFiles = InstanceFilesPage
export const InstanceSchedules = InstanceSchedulesPage
export const InstanceMods = InstanceModsPage
export const InstanceProxy = InstanceProxyPage
export const InstanceSettings = InstanceSettingsPage
export const Market = MarketPage
export const QuickStart = QuickStartPage
export const Audit = AuditPage
export const ServerConfig = ServerConfigPage
export const Templates = TemplatesPage
export const DockerImages = DockerImagesPage
export const Settings = SettingsPage
export const Trash = TrashPage

export const routes = {
  Nodes,
  NodeDetail,
  NodeDeploy,
  NodeTerminal,
  NodeFiles,
  Overview,
  Instances,
  CreateInstance,
  InstanceDetail,
  InstanceDomain,
  InstanceFiles,
  InstanceSchedules,
  InstanceMods,
  InstanceProxy,
  InstanceSettings,
  Market,
  QuickStart,
  Audit,
  ServerConfig,
  Templates,
  DockerImages,
  Settings,
  Trash,
  DashboardOverviewCard,
  DashboardQuickLinkCard,
  'mcpanel/Nodes': NodesPage,  'mcpanel/NodeDetail': NodeDetailPage,
  'mcpanel/NodeDeploy': NodeDeployGuidePage,
  'mcpanel/NodeTerminal': NodeTerminalPage,
  'mcpanel/NodeFiles': NodeFilesPage,
  'mcpanel/Overview': OverviewPage,
  'mcpanel/Instances': InstancesPage,
  'mcpanel/CreateInstance': CreateInstancePage,
  'mcpanel/InstanceDetail': InstanceDetailPage,
  'mcpanel/InstanceDomain': InstanceDomainPage,
  'mcpanel/InstanceFiles': InstanceFilesPage,
  'mcpanel/InstanceSchedules': InstanceSchedulesPage,
  'mcpanel/InstanceMods': InstanceModsPage,
  'mcpanel/InstanceProxy': InstanceProxyPage,
  'mcpanel/InstanceSettings': InstanceSettingsPage,
  'mcpanel/Market': MarketPage,
  'mcpanel/QuickStart': QuickStartPage,
  'mcpanel/Audit': AuditPage,
  'mcpanel/ServerConfig': ServerConfigPage,
  'mcpanel/Templates': TemplatesPage,
  'mcpanel/DockerImages': DockerImagesPage,
  'mcpanel/Settings': SettingsPage,
  'mcpanel/Trash': TrashPage,
  'mcpanel/DashboardOverviewCard': DashboardOverviewCard,
  'mcpanel/DashboardQuickLinkCard': DashboardQuickLinkCard,
}

export {
  NodesPage as 'mcpanel/Nodes',
  NodeDetailPage as 'mcpanel/NodeDetail',
  NodeDeployGuidePage as 'mcpanel/NodeDeploy',
  NodeTerminalPage as 'mcpanel/NodeTerminal',
  NodeFilesPage as 'mcpanel/NodeFiles',
  OverviewPage as 'mcpanel/Overview',
  InstancesPage as 'mcpanel/Instances',
  CreateInstancePage as 'mcpanel/CreateInstance',
  InstanceDetailPage as 'mcpanel/InstanceDetail',
  InstanceDomainPage as 'mcpanel/InstanceDomain',
  InstanceFilesPage as 'mcpanel/InstanceFiles',
  InstanceSchedulesPage as 'mcpanel/InstanceSchedules',
  InstanceModsPage as 'mcpanel/InstanceMods',
  InstanceProxyPage as 'mcpanel/InstanceProxy',
  InstanceSettingsPage as 'mcpanel/InstanceSettings',
  MarketPage as 'mcpanel/Market',
  QuickStartPage as 'mcpanel/QuickStart',
  AuditPage as 'mcpanel/Audit',
  ServerConfigPage as 'mcpanel/ServerConfig',
  TemplatesPage as 'mcpanel/Templates',
  DockerImagesPage as 'mcpanel/DockerImages',
  SettingsPage as 'mcpanel/Settings',
  TrashPage as 'mcpanel/Trash',
  DashboardOverviewCard,
  DashboardQuickLinkCard,
  DashboardOverviewCard as 'mcpanel/DashboardOverviewCard',
  DashboardQuickLinkCard as 'mcpanel/DashboardQuickLinkCard',
}

export default defineYuDreamPlugin({
  routes,
  default: Overview,
})
