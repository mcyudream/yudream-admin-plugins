import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

export interface DeploymentJob {
  id: string
  title: string
  sourceId: string
  targetId: string
  state: 'running' | 'done' | 'failed' | 'rolled-back' | 'rolling-back' | 'recovery-required'
  error?: string
  rollbackAvailable?: boolean
  fileCount?: number
  stagedFiles?: number
  completedFiles?: number
  createdAt: number
}
export interface DeploymentPreview {
  planId?: string
  packageId?: string
  name?: string
  version?: string
  namespace?: string
  fileCount: number
  totalBytes: number
  replaceCount: number
  addCount: number
  files: Array<{ path: string, size: number, action: string }>
}
export function createDeploymentApi(sdk: YuDreamPluginSdk) {
  const base = (id: string) => `admin/instances/${encodeURIComponent(id)}`
  return {
    copy: (id: string, name: string) => sdk.http.post<DeploymentJob>(`${base(id)}/copy`, { name }),
    preview: (id: string, targetId: string, paths: string[]) => sdk.http.post<DeploymentPreview>(`${base(id)}/deployments/preview`, { targetId, paths }),
    deploy: (id: string, planId: string) => sdk.http.post<DeploymentJob>(`${base(id)}/deployments`, { planId }),
    list: (id: string, page = 1, size = 10) => sdk.http.get<{ records: DeploymentJob[], total: number }>(`${base(id)}/deployments?page=${page}&size=${size}`),
    status: (id: string, jobId: string) => sdk.http.get<DeploymentJob>(`${base(id)}/deployments/status?jobId=${encodeURIComponent(jobId)}`),
    rollback: (id: string, jobId: string) => sdk.http.post<DeploymentJob>(`${base(id)}/deployments/rollback`, { jobId }),
    inspectPackage: (id: string, file: File) => {
      const data = new FormData()
      data.append('file', file)
      return sdk.http.request<DeploymentPreview>(`${base(id)}/resource-packages/inspect`, { method: 'POST', data })
    },
    installPackage: (id: string, packageId: string) => sdk.http.post<DeploymentJob>(`${base(id)}/resource-packages/install`, { packageId }),
  }
}
