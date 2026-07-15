import type { AiAgent } from '../types'

export const BUILTIN_GROUP_AGENT_CODE = 'builtin-group-chatbot'

export interface GroupAgentOption {
  label: string
  value: string
}

export function toGroupAgentOptions(agents: AiAgent[]): GroupAgentOption[] {
  return agents
    .map(agent => ({ label: agent.name.trim() || agent.code.trim(), value: agent.code.trim() }))
    .filter(option => Boolean(option.label && option.value))
}

export function resolveGroupAgentCode(options: GroupAgentOption[], current: string): string {
  if (options.some(option => option.value === current)) {
    return current
  }
  return options.find(option => option.value === BUILTIN_GROUP_AGENT_CODE)?.value || options[0]?.value || BUILTIN_GROUP_AGENT_CODE
}
