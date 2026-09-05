export type TimeValue = string | number | number[] | null | undefined

export type TimelineEventType = 'ARTICLE' | 'TEXT' | 'ELECTION' | 'MILESTONE' | 'AWARD'

/** 公开列表条目：不含 Markdown 详情正文与换届名册。 */
export interface TimelineEventSummary {
  id: string
  title: string
  summary: string
  eventDate: string
  dateLabel: string
  eventType: TimelineEventType
  termLabel: string
  coverImage: string
  imageCount: number
  published: boolean
  sort: number
  createdAt: TimeValue
  updatedAt: TimeValue
}

export interface TimelineEventView extends TimelineEventSummary {
  outgoingMembers: string[]
  incomingMembers: string[]
  images: string[]
  detail: string
}

export interface TimelineEventPayload {
  title: string
  summary: string
  eventDate: string
  dateLabel: string
  eventType: TimelineEventType
  termLabel: string
  outgoingMembers: string[]
  incomingMembers: string[]
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

export type TimelineTypeFilter = '' | TimelineEventType
