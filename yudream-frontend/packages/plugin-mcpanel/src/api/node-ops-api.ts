import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

/** 节点级能力 API（M2 第二批）：节点终端 / 节点文件 / 节点 SFTP。全部 admin 面。 */
export function createNodeOpsApi(sdk: YuDreamPluginSdk) {
  const nodePath = (id: string) => `admin/nodes/${encodeURIComponent(id)}`
  function nodeFiles(id: string, op: string) {
    return `${nodePath(id)}/files/${op}`
  }
  function nodeFtp(id: string, op: string) {
    return `${nodePath(id)}/ftp/${op}`
  }
  function nodeTerm(id: string, op: string) {
    return `${nodePath(id)}/terminal/${op}`
  }

  return {
    terminalOpen: (id: string, terminalId: string) =>
      sdk.http.post(nodeTerm(id, 'open'), { terminalId }),
    terminalInput: (id: string, terminalId: string, data: string) =>
      sdk.http.post(nodeTerm(id, 'input'), { terminalId, data }),
    terminalClose: (id: string, terminalId: string) =>
      sdk.http.post(nodeTerm(id, 'close'), { terminalId }),
    terminalEventsUrl: (id: string, terminalId: string) =>
      sdk.http.url(`admin/nodes/${encodeURIComponent(id)}/terminal/events${terminalId ? `?terminalId=${encodeURIComponent(terminalId)}` : ''}`),
    /** 真实系统命令补全（节点 0.4.0+）：word 为正在输入的命令词，返回前缀命中列表。 */
    terminalComplete: (id: string, word: string) =>
      sdk.http.post(nodeTerm(id, 'complete'), { prefix: word, limit: 10 }),
    /** page/size 缺省 = 整目录返回；keyword 按名称过滤（节点 0.3.0+）。 */
    nodeFileList: (id: string, root: string, path: string, page?: number, size?: number, keyword?: string) =>
      sdk.http.post(nodeFiles(id, 'list'), { root, path, page, size, keyword }),
    nodeFileRead: (id: string, root: string, path: string) =>
      sdk.http.post(nodeFiles(id, 'read'), { root, path }),
    /** 分块读取（node.file.download.chunk）：大文件只读预览用，length ≤ 96KiB。 */
    nodeFileReadChunk: (id: string, root: string, path: string, offset: number, length: number) =>
      sdk.http.post(nodeFiles(id, 'download'), { root, path, offset, length }),
    nodeFileWrite: (id: string, root: string, path: string, content: string, charset?: string) =>
      sdk.http.post(nodeFiles(id, 'write'), { root, path, content, encoding: 'base64', ...(charset ? { charset } : {}) }),
    nodeFileMkdir: (id: string, root: string, path: string) =>
      sdk.http.post(nodeFiles(id, 'mkdir'), { root, path }),
    nodeFileRename: (id: string, root: string, from: string, to: string) =>
      sdk.http.post(nodeFiles(id, 'rename'), { root, from, to }),
    nodeFileDelete: (id: string, root: string, path: string) =>
      sdk.http.post(nodeFiles(id, 'delete'), { root, path }),
    /** 打包目录为 root 内 zip（node.file.zip）：回收站下载用，dest 为 root 内相对路径。 */
    nodeFileZip: (id: string, root: string, path: string, dest: string) =>
      sdk.http.post(nodeFiles(id, 'zip'), { root, path, dest }),
    nodeFileDownloadUrl: (id: string, root: string, path: string) =>
      sdk.http.url(`/${nodeFiles(id, 'download')}?root=${encodeURIComponent(root)}&path=${encodeURIComponent(path)}`),
    nodeFtpOpen: (id: string, root: string, ttlMinutes?: number) =>
      sdk.http.post(nodeFtp(id, 'open'), { root, ttlMinutes }),
    nodeFtpClose: (id: string, root: string) =>
      sdk.http.post(nodeFtp(id, 'close'), { root }),
  }
}

export type NodeOpsApi = ReturnType<typeof createNodeOpsApi>
