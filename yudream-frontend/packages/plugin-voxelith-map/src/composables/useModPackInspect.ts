/**
 * 模组包「上传即可解析」的状态机。
 *
 * <p>以前上传和解析是两件事：zip 只在上传列表里等着，保存时才真正上传，所以「传上来看看里面有什么」
 * 要等到保存之后再点一次解析——包越大越别扭。现在选到 zip 就立刻上传并解析：列表马上出现，
 * 同时把平台文件 id 记下来（{@code pendingFileId}），保存时直接复用，不重复上传一次 GB 级包。</p>
 *
 * <p>状态按组件实例持有（列表页的新建弹窗与详情页各一份）：它描述的是「这次编辑里待保存的包」，
 * 不是地图上已绑定的包——已绑定的包由页面用 {@link inspect} 单独解析。</p>
 */
import { ref } from 'vue'
import { useYdToast } from '@yudream/components'
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import type { ModPackListing } from '../types'
import { createVoxelithMapAdminApi } from '../api/voxelith-map-admin-api'
import { clockTime } from './formDiff'

export function useModPackInspect(sdk: YuDreamPluginSdk) {
  const api = createVoxelithMapAdminApi(sdk)
  const toast = useYdToast()

  const listing = ref<ModPackListing | null>(null)
  const loading = ref(false)
  const error = ref('')
  /** 当前阶段：上传与解析分开显示，过程可视化（解析大包要读完整对象，不是瞬间完成） */
  const stage = ref<'idle' | 'uploading' | 'parsing'>('idle')
  /** 当前阶段已等待秒数（每秒刷新，避免长操作看起来像卡死） */
  const elapsedSeconds = ref(0)
  /** 最近一次解析完成的时刻（HH:mm:ss） */
  const finishedAt = ref('')
  let ticker: ReturnType<typeof setInterval> | null = null

  function startTicker(): void {
    stopTicker()
    const startedAt = Date.now()
    elapsedSeconds.value = 0
    ticker = setInterval(() => {
      elapsedSeconds.value = Math.round((Date.now() - startedAt) / 1000)
    }, 1000)
  }

  function stopTicker(): void {
    if (ticker !== null) {
      clearInterval(ticker)
      ticker = null
    }
  }
  /** 已上传但尚未保存的包（保存时复用，避免同一个 zip 上传两次） */
  const pendingFileId = ref('')
  const pendingName = ref('')

  function clear(): void {
    listing.value = null
    error.value = ''
    pendingFileId.value = ''
    pendingName.value = ''
    stage.value = 'idle'
    stopTicker()
  }

  /** 解析一个已在平台文件里的模组包（例如地图上已绑定的那个）。 */
  async function inspect(fileId: string, name = ''): Promise<void> {
    if (!fileId || loading.value) {
      return
    }
    loading.value = true
    error.value = ''
    stage.value = 'parsing'
    startTicker()
    try {
      listing.value = await api.inspectModPack({ fileId, name })
      finishedAt.value = clockTime()
    }
    catch (e) {
      listing.value = null
      error.value = e instanceof Error && e.message ? e.message : '模组包解析失败'
      toast.error(error.value, { duration: 6000 })
    }
    finally {
      loading.value = false
      stage.value = 'idle'
      stopTicker()
    }
  }

  /**
   * 选中 zip 后立刻上传并解析。
   *
   * @return 平台文件 id；上传失败返回空串（错误已提示，列表照旧可保存后重试）
   */
  async function uploadAndInspect(file: File): Promise<string> {
    loading.value = true
    error.value = ''
    listing.value = null
    stage.value = 'uploading'
    startTicker()
    try {
      const uploaded = await sdk.files.uploadImage(file, { module: 'voxelith-map', publicAccess: false })
      if (!uploaded.id) {
        throw new Error(`模组包 ${file.name} 上传后未返回 id`)
      }
      pendingFileId.value = uploaded.id
      pendingName.value = file.name
      stage.value = 'parsing'
      await inspect(uploaded.id, file.name)
      return uploaded.id
    }
    catch (e) {
      pendingFileId.value = ''
      pendingName.value = ''
      error.value = e instanceof Error && e.message ? e.message : '模组包上传失败'
      toast.error(error.value, { duration: 6000 })
      return ''
    }
    finally {
      loading.value = false
      stage.value = 'idle'
      stopTicker()
    }
  }

  /** 判断某个待上传文件是否就是已经传好的那个包（同名即视为同一个）。 */
  function matchesPending(file: File): boolean {
    return Boolean(pendingFileId.value) && pendingName.value === file.name
  }

  return {
    listing,
    loading,
    error,
    stage,
    elapsedSeconds,
    finishedAt,
    pendingFileId,
    pendingName,
    clear,
    inspect,
    uploadAndInspect,
    matchesPending,
  }
}
