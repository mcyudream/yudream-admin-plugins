export function mcpClientConfig(endpoint: string, apiKey: string): string {
  return JSON.stringify({ mcpServers: { 'yudream-mcpanel': { type: 'http', url: endpoint, headers: { Authorization: `Bearer ${apiKey}` } } } }, null, 2)
}

export function vscodeMcpInstallUri(endpoint: string, apiKey: string): string {
  return `vscode:mcp/install?${encodeURIComponent(JSON.stringify({ name: 'yudream-mcpanel', type: 'http', url: endpoint, headers: { Authorization: `Bearer ${apiKey}` } }))}`
}
