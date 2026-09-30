/** 更新发布的接口视图类型。 */
export interface AppReleaseView {
  id: string
  platform: string
  versionCode: number
  versionName: string
  changelog: string
  fileName: string
  fileSize: number
  sha256?: string
  forceUpdate: boolean
  published: boolean
  createdAt: number
  publishedAt: number
}

export interface AppReleaseSettingsView {
  minVersionCode: number
  updatedAt: number
}

export interface AppReleaseUploadPayload {
  platform: string
  versionCode: number
  versionName: string
  changelog: string
  forceUpdate: boolean
  file: File
}
