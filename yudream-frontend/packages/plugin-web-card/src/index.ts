import { defineYuDreamPlugin } from '@yudream/plugin-sdk'
import SitesPage from './pages/SitesPage.vue'
import TemplateDesignerPage from './pages/TemplateDesignerPage.vue'
import AgentWorkspacePage from './pages/AgentWorkspacePage.vue'
import GroupBindingsPage from './pages/GroupBindingsPage.vue'
import CrawlJobsPage from './pages/CrawlJobsPage.vue'
import RunsPage from './pages/RunsPage.vue'
import StudioPage from './pages/StudioPage.vue'
export const Studio=StudioPage,Sites=SitesPage,TemplateDesigner=TemplateDesignerPage,AgentWorkspace=AgentWorkspacePage,GroupBindings=GroupBindingsPage,CrawlJobs=CrawlJobsPage,Runs=RunsPage
export const routes={Studio,Sites,TemplateDesigner,AgentWorkspace,GroupBindings,CrawlJobs,Runs,'web-card/Studio':Studio,'web-card/Sites':Sites,'web-card/TemplateDesigner':TemplateDesigner,'web-card/AgentWorkspace':AgentWorkspace,'web-card/GroupBindings':GroupBindings,'web-card/CrawlJobs':CrawlJobs,'web-card/Runs':Runs}
export default defineYuDreamPlugin({routes,default:Studio})
