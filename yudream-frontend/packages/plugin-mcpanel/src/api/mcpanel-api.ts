import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { McpDeleteResult, McpEnrollCredential, McpNode, McpNodeSaveRequest, McpPage } from '../types'

/**
 * M1 节点管理 API（全部 /admin/** 管理面）。路径为插件相对路径，运行时由宿主
 * 挂载到 /api/plugins/mcpanel/**；仅使用 sdk.http，事件流走 nodeEventsStream
 * 的 fetch-stream（EventSource 无法携带 Authorization）。
 *
 * 约束：节点响应不含 secret；POST /admin/nodes 仅返回 NodeRes 不带 token，
 * 注册凭据必须经 /enrollment 显式签发且仅 hasSecret=false 时可用；不存在
 * 密钥轮换（rekey 后端未实现），不得混淆。
 */
export function createMcPanelApi(sdk: YuDreamPluginSdk) {
  function query(params: Record<string, string | number | boolean | undefined>) {
    const search = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== '') {
        search.set(key, String(value))
      }
    })
    const value = search.toString()
    return value ? `?${value}` : ''
  }

  const nodePath = (id: string) => `admin/nodes/${encodeURIComponent(id)}`

  return {
    /** 后端分页：GET admin/nodes?page&size&keyword&status → { records, total, page, size }。 */
    pageNodes: (page: number, size: number, keyword?: string, status?: string) =>
      sdk.http.get<McpPage<McpNode>>(`admin/nodes${query({ page, size, keyword, status })}`),
    /** 创建节点：仅返回 NodeRes，不携带注册凭据。 */
    createNode: (data: McpNodeSaveRequest) =>
      sdk.http.post<McpNode>('admin/nodes', data),
    nodeDetail: (id: string) =>
      sdk.http.get<McpNode>(nodePath(id)),
    updateNode: (id: string, data: McpNodeSaveRequest) =>
      sdk.http.request<McpNode>(nodePath(id), { method: 'PUT', data }),
    removeNode: (id: string) =>
      sdk.http.request<McpDeleteResult>(nodePath(id), { method: 'DELETE' }),
    /** 触发面板主动重连该节点，返回刷新后的 NodeRes。 */
    reconnectNode: (id: string) =>
      sdk.http.post<McpNode>(`${nodePath(id)}/reconnect`),
    /** 重签一次性注册凭据：仅 hasSecret=false（未注册）可签；响应 { token, expiresAt }。 */
    issueEnrollment: (id: string) =>
      sdk.http.post<McpEnrollCredential>(`${nodePath(id)}/enrollment`),
    /** 节点事件流端点（SSE，event=node.stats / node.state，data 为含 nodeId 的 envelope）。 */
    nodeEventsUrl: (id: string) =>
      sdk.http.url(`/${nodePath(id)}/events`),
  }
}

export type McPanelApi = ReturnType<typeof createMcPanelApi>
