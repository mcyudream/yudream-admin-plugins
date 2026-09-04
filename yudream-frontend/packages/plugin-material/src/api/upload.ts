import type { YuDreamPluginFileObject, YuDreamPluginSdk } from '@yudream/plugin-sdk'

/** 目录选择返回的文件项：path 统一为「根文件夹名/…/文件名」相对路径，与 webkitRelativePath 形态一致 */
export interface PickedFile {
  file: File
  path: string
}

export interface PickDirectoryResult {
  files: PickedFile[]
  /** 所选根文件夹名，取作分类名 */
  rootName: string
  /** 遍历时无法读取被跳过的子文件夹/文件路径（占用、权限等），用于界面提示 */
  skipped: string[]
}

/** File System Access API 的最小结构类型：不依赖 lib dom.asynciterable 是否声明了 values() */
interface FsEntryHandle {
  kind: 'file' | 'directory'
  name: string
}
interface FsFileHandle extends FsEntryHandle {
  getFile: () => Promise<File>
}
interface FsDirectoryHandle extends FsEntryHandle {
  values: () => AsyncIterable<FsEntryHandle>
}
interface DirectoryPickerWindow {
  showDirectoryPicker?: (options?: { mode?: 'read' }) => Promise<FsDirectoryHandle>
}

/**
 * 目录选择。优先 File System Access API 并自行逐层递归——webkitdirectory 是让浏览器预扫描整棵
 * 目录树，部分环境对子文件夹会静默漏读（有层级就读不出来）；自己遍历则所见即所得，读不到的
 * 条目记入 skipped 明确提示。不支持 showDirectoryPicker 的浏览器回退动态创建的
 * input.webkitdirectory（FaFileUpload 不支持目录选择，隐藏原生 input 也会被规范检查拦截）。
 * 用户取消时两种路径都返回空结果，调用方保持原状。
 */
export async function pickDirectory(): Promise<PickDirectoryResult> {
  const picker = (window as unknown as DirectoryPickerWindow).showDirectoryPicker
  if (picker) {
    let root: FsDirectoryHandle
    try {
      root = await picker({ mode: 'read' })
    }
    catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        return { files: [], rootName: '', skipped: [] }
      }
      // 选择器被环境禁用（嵌入页、企业策略等）：回退动态 input
      return pickByDirectoryInput()
    }
    const files: PickedFile[] = []
    const skipped: string[] = []
    await walkDirectory(root, root.name, files, skipped)
    return { files, rootName: root.name, skipped }
  }
  return pickByDirectoryInput()
}

async function walkDirectory(dir: FsDirectoryHandle, prefix: string, files: PickedFile[], skipped: string[]): Promise<void> {
  try {
    for await (const entry of dir.values()) {
      if (entry.kind === 'directory') {
        await walkDirectory(entry as FsDirectoryHandle, `${prefix}/${entry.name}`, files, skipped)
      }
      else {
        try {
          files.push({ file: await (entry as FsFileHandle).getFile(), path: `${prefix}/${entry.name}` })
        }
        catch {
          skipped.push(`${prefix}/${entry.name}`)
        }
      }
    }
  }
  catch {
    skipped.push(prefix)
  }
}

function pickByDirectoryInput(): Promise<PickDirectoryResult> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.webkitdirectory = true
    input.multiple = true
    const done = (raw: File[]) => {
      resolve({
        files: raw.map(file => ({ file, path: file.webkitRelativePath || file.name })),
        rootName: raw.length ? (raw[0].webkitRelativePath.split('/')[0] || '') : '',
        skipped: [],
      })
    }
    input.onchange = () => done(Array.from(input.files || []))
    input.addEventListener('cancel', () => done([]))
    input.click()
  })
}

interface BackendEnvelope<T> {
  code: number
  message: string
  data: T
}

/**
 * 未完结 XHR 的兜底持有集合。Firefox 对每域名并发连接有上限，连接被占满时上传请求会排队；
 * 排队中的 XHR 若只被自身事件回调引用会被 GC 回收，请求以 NS_BINDING_ABORTED 静默中止且
 * 事件不再触发，外层 Promise 永不落定（表现为上传池卡死）。完结前显式持有即可避免。
 */
const inflightUploads = new Set<XMLHttpRequest>()

/**
 * sdk.files.uploadImage 走宿主 axios 实例：60s 超时且拿不到上传进度。
 * 大文件需要进度反馈且不能限时，这里改用 XHR 手动携带宿主 token 打同一个 /api/files/upload。
 */
export function uploadFileWithProgress(
  sdk: YuDreamPluginSdk,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<YuDreamPluginFileObject> {
  const form = new FormData()
  form.append('file', file)
  form.append('module', 'material')
  form.append('publicAccess', 'false')

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    inflightUploads.add(xhr)
    xhr.open('POST', sdk.files.assetUrl('/api/files/upload'))
    xhr.responseType = 'json'
    const token = localStorage.getItem('token')
    if (token) {
      xhr.setRequestHeader('Authorization', token)
    }
    xhr.upload.onprogress = (event) => {
      // 传输期间最多报 99，100 留给响应确认后，避免进度条先满后等
      if (event.lengthComputable && event.total > 0) {
        onProgress?.(Math.min(99, Math.round((event.loaded / event.total) * 100)))
      }
    }
    xhr.onload = () => {
      inflightUploads.delete(xhr)
      const body = xhr.response as BackendEnvelope<YuDreamPluginFileObject> | null
      if (xhr.status >= 200 && xhr.status < 300 && body?.code === 200 && body.data) {
        onProgress?.(100)
        resolve(body.data)
      }
      else {
        reject(new Error(body?.message || `上传失败（HTTP ${xhr.status}）`))
      }
    }
    xhr.onerror = () => {
      inflightUploads.delete(xhr)
      reject(new Error('网络错误，上传失败'))
    }
    xhr.onabort = () => {
      inflightUploads.delete(xhr)
      reject(new Error('上传已取消'))
    }
    xhr.send(form)
  })
}
