export type TimeValue = string | number | number[] | null | undefined

/** 公开列表条目：不含 Markdown 详情正文。 */
export interface TimelineEventSummary {
  id: string
  title: string
  summary: string
  eventDate: string
  dateLabel: string
  coverImage: string
  imageCount: number
  published: boolean
  sort: number
  createdAt: TimeValue
  updatedAt: TimeValue
}

export interface TimelineEventView extends TimelineEventSummary {
  images: string[]
  detail: string
}

export interface TimelineEventPayload {
  title: string
  summary: string
  eventDate: string
  dateLabel: string
  coverImage: string
  images: string[]
  detail: string
  published: boolean
  sort: number
}

export interface Page<T> {
  records: T[]
  total: number
}

export type TimelineStatusFilter = '' | 'published' | 'draft'
