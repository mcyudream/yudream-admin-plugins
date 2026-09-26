import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'

export type Moderation = 'inherit' | 'off' | 'manual' | 'ai'
export type PostStatus = 'draft' | 'pending' | 'published' | 'rejected' | 'archived'
export interface Category { id: string; slug: string; name: string; description: string; sort: number; enabled: boolean; viewPermission: string; postPermission: string; moderation: Moderation; aiTagging: boolean }
export interface Post { id: string; title: string; body: string; summary: string; categoryId: string; tags: string[]; authorId: string; status: PostStatus; rejectionReason: string; pinned: boolean; featured: boolean; publishedAt: string; createdAt: string; updatedAt: string; views: number; likes: number; comments: number; bookmarks: number }
export interface Comment { id: string; postId: string; authorId: string; body: string; parentId: string; status: PostStatus; createdAt: string }
export interface Page<T> { records: T[]; total: number }
export interface UserProfile { id: string; username: string; nickname: string; avatar: string }
export interface ForumApi {
  categories(): Promise<{ records: Category[] }>
  posts(params?: Record<string, string | number>): Promise<Page<Post>>
  post(id: string): Promise<Post>
  comments(id: string, page?: number): Promise<Page<Comment>>
  tags(): Promise<{ records: string[] }>
  profile(id: string): Promise<UserProfile>
  savePost(id: string | undefined, payload: Record<string, unknown>): Promise<Post>
  comment(id: string, body: string, parentId?: string): Promise<Comment>
  interact(id: string, type: 'like' | 'bookmark'): Promise<{ active: boolean }>
  adminCategories(): Promise<{ records: Category[] }>
  settings(): Promise<Record<string, unknown>>
  saveSettings(payload: Record<string, unknown>): Promise<Record<string, unknown>>
  audit(page?: number): Promise<Page<Record<string, string | number>>>
  createCategory(payload: Record<string, unknown>): Promise<Category>
  updateCategory(id: string, payload: Record<string, unknown>): Promise<Category>
  deleteCategory(id: string): Promise<{ deleted: boolean }>
  adminPosts(params?: Record<string, string | number>): Promise<Page<Post>>
  moderate(id: string, status: PostStatus, reason?: string): Promise<Post>
  flags(id: string, payload: { pinned?: boolean; featured?: boolean }): Promise<Post>
}
export function createForumApi(sdk: YuDreamPluginSdk): ForumApi {
  const query = (params: Record<string, string | number> = {}) => { const q = new URLSearchParams(); Object.entries(params).forEach(([k, v]) => { if (v !== '') q.set(k, String(v)) }); const text = q.toString(); return text ? `?${text}` : '' }
  const records = async <T>(promise: Promise<unknown>): Promise<{ records: T[] }> => { const body = await promise as { records?: T[] } | T[]; return { records: Array.isArray(body) ? body : body.records || [] } }
  return {
    categories: () => sdk.http.get('/public/categories'),
    posts: (params = {}) => sdk.http.get(`/public/posts${query(params)}`),
    post: id => sdk.http.get(`/public/posts/${encodeURIComponent(id)}`),
    comments: (id, page = 1) => sdk.http.get(`/public/posts/${encodeURIComponent(id)}/comments${query({ page, size: 20 })}`),
    tags: () => records<string>(sdk.http.get('/public/tags')),
    profile: id => sdk.http.get(`/public/users/${encodeURIComponent(id)}`),
    savePost: (id, payload) => id ? sdk.http.request(`/me/posts/${encodeURIComponent(id)}`, { method: 'PUT', data: payload }) : sdk.http.post('/me/posts', payload),
    comment: (id, body, parentId = '') => sdk.http.post(`/me/posts/${encodeURIComponent(id)}/comments`, { body, parentId }),
    interact: (id, type) => sdk.http.post(`/me/posts/${encodeURIComponent(id)}/${type}`, {}),
    settings: () => sdk.http.get('/admin/settings'),
    saveSettings: payload => sdk.http.request('/admin/settings', { method: 'PUT', data: payload }),
    audit: (page = 1) => sdk.http.get(`/admin/audit${query({ page, size: 20 })}`),
    adminCategories: () => sdk.http.get('/admin/categories'),
    createCategory: payload => sdk.http.post('/admin/categories', payload),
    updateCategory: (id, payload) => sdk.http.request(`/admin/categories/${encodeURIComponent(id)}`, { method: 'PUT', data: payload }),
    deleteCategory: id => sdk.http.request(`/admin/categories/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    adminPosts: (params = {}) => sdk.http.get(`/admin/posts${query(params)}`),
    moderate: (id, status, reason = '') => sdk.http.post(`/admin/posts/${encodeURIComponent(id)}/moderate`, { status, reason }),
    flags: (id, payload) => sdk.http.post(`/admin/posts/${encodeURIComponent(id)}/flags`, payload),
  }
}
