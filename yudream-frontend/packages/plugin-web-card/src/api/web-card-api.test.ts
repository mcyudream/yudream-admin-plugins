import { describe, expect, it, vi } from 'vitest'
import { createWebCardApi } from './web-card-api'
import type { AgentProposal, WorkspacePlan } from '../types'

function sdk() {
  return {
    http: {
      post: vi.fn(async () => ({ streamId: 'stream-7' })),
      request: vi.fn(async () => ({})),
      url: vi.fn((path: string) => `/api/plugins/web-card${path}`),
    },
  } as never
}

describe('web card Agent API', () => {
  it('starts a turn through the SDK and resolves the SSE URL through the SDK', async () => {
    const client = sdk() as { http: { post: ReturnType<typeof vi.fn>; request: ReturnType<typeof vi.fn>; url: ReturnType<typeof vi.fn> } }
    const api = createWebCardApi(client as never)

    await expect(api.startAgentMessage('session/1', '生成卡片')).resolves.toEqual({ streamId: 'stream-7' })
    expect(client.http.post).toHaveBeenCalledWith('/admin/agent-sessions/session%2F1/messages/stream', { message: '生成卡片' })
    expect(api.agentMessageEventsUrl('stream/7')).toBe('/api/plugins/web-card/admin/agent-message-streams/stream%2F7/events')
  })

  it('updates the editable structured proposal through the SDK', async () => {
    const client = sdk() as { http: { post: ReturnType<typeof vi.fn>; request: ReturnType<typeof vi.fn>; url: ReturnType<typeof vi.fn> } }
    const api = createWebCardApi(client as never)
    const proposal = {
      id: 'proposal/1',
      sessionId: 'session-1',
      summary: '可编辑方案',
      operations: [{ target: 'workspace', operation: 'replace', value: { job: null } }],
      status: 'PENDING',
      createdAt: 0,
      updatedAt: 0,
    } satisfies AgentProposal

    await api.updateProposal(proposal.id, proposal.operations[0].value as WorkspacePlan)

    expect(client.http.request).toHaveBeenCalledWith('/admin/agent-proposals/proposal%2F1', {
      method: 'PUT',
      data: { plan: proposal.operations[0].value },
    })
  })

  it('previews the current unsaved template against a real URL', async () => {
    const client = sdk() as { http: { post: ReturnType<typeof vi.fn>; request: ReturnType<typeof vi.fn>; url: ReturnType<typeof vi.fn> } }
    const api = createWebCardApi(client as never)
    const request = {
      siteId: 'site-1',
      url: 'https://example.com/article/1',
      version: {
        templateId: 'template-1',
        version: 0,
        parseRules: { siteId: 'site-1', detailType: 'HTML' as const, fields: [], listExpression: '', listLinkAttribute: '', jsonItemsPath: '', canonicalField: '', contentKeyField: '', detailUrlPattern: '/article/{id}' },
        mode: 'ADVANCED' as const,
        structuredLayout: '{}',
        html: '<article id="web-card">{{title}}</article>',
        css: '#web-card { width: 640px; }',
        fixture: {},
        origin: 'MANUAL',
        summary: '手工编辑',
        previewPassed: false,
        createdAt: 0,
      },
    }

    await api.previewDraft(request)

    expect(client.http.request).toHaveBeenCalledWith('/admin/template-draft-preview', {
      method: 'POST',
      data: request,
    })
  })
})
