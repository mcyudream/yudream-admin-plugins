/**
 * mcpanel 移动端（设计稿 panelOverview / panelInstance）：
 * 运维总览（摘要 + 实例 + 节点）、实例运维（运行状态卡 + 电源三键 + 四格指标 +
 * 黑底控制台 + 六宫格快捷入口）、备份与任务、文件管理与编辑、实例配置、域名解析、
 * 在线玩家。数据走 /api/plugins/mcpanel/admin/**；视觉经 sdk.theme 与
 * plugin-mobile-ui；控制台区域按终端语义固定暗色（与 UIX 设计稿一致）。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  KeyboardAvoidingView, Platform, Pressable, ScrollView, Text, TextInput, View,
} from 'react-native';

/** 内页渲染错误兜底：插件内无原生导航栈，崩溃会白屏；记录日志并给出返回入口。 */
class ViewBoundary extends React.Component<
  { children: React.ReactNode },
  { error: Error | null }
> {
  state = { error: null as Error | null };
  static getDerivedStateFromError(error: Error) {
    return { error };
  }
  componentDidCatch(error: Error) {
    console.warn('[mcpanel] view crash', error?.message);
  }
  render() {
    if (this.state.error) {
      return (
        <View style={{ padding: 24, gap: 8 }}>
          <Text style={{ color: '#dc2626', fontSize: 13 }}>页面渲染失败：{String(this.state.error.message)}</Text>
        </View>
      );
    }
    return this.props.children;
  }
}
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, Icon, Loading, PrimaryButton, ProgressBar, Screen, SectionTitle,
  SecondaryButton, StatTile, UiProvider, useResource,,
  uiAlert,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
/** 宿主注入的 manifest 条目：adminCards 已按当前用户权限过滤（空 = 无管理权限）。 */
let currentEntry: { adminCards?: unknown[] } | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}
function useManageGranted(): boolean {
  return (currentEntry?.adminCards?.length ?? 0) > 0;
}

const API = '/api/plugins/mcpanel';

interface InstanceDto {
  id: string;
  nodeId: string;
  name: string;
  mcVersion?: string;
  state: string;
  lastExitCode?: number | null;
  /** 总览聚合的容器实时指标（overview 专用；详情页走 metrics 接口） */
  cpuPercent?: number | null;
  memUsedMb?: number | null;
  memTotalMb?: number | null;
  /** 详情接口的规格内存上限（未配置为 0/缺省，展示时须判 0） */
  memoryMb?: number;
  diskMb?: number;
  cpuMillis?: number;
  autoRestart?: boolean;
  updatedAt?: number;
}
interface Overview {
  nodes?: { total?: number; online?: number; offline?: number };
  instances?: { total?: number; running?: number; exited?: number; other?: number };
  nodeDetails?: { id: string; name: string; status?: string; connected?: boolean; agentVersion?: string }[];
  recentInstances?: InstanceDto[];
}
interface BackupInfo {
  file?: string;
  size?: string;
  at?: number;
  status?: string;
  archiveName?: string;
}
interface ScheduleRow {
  id: string;
  name: string;
  cron?: string;
  enabled?: boolean;
  nextRunAt?: number;
}
interface FileEntry { name: string; isDir: boolean; size?: number; path?: string }
interface PlayersDto {
  reachable?: boolean;
  reason?: string;
  version?: string;
  online?: number;
  max?: number;
  players?: { name: string; id?: string }[];
  latencyMs?: number;
  probedAt?: number;
}
interface ProxyServer { name: string; address: string; boundInstanceId?: string; boundName?: string; external?: boolean }
interface ProxyGroup {
  exists?: boolean;
  proxyInstanceId?: string;
  kind?: string;
  servers?: ProxyServer[];
  defaultServer?: string;
  forwarding?: string;
}

const stateTone = (s: string): 'success' | 'danger' | 'warning' | 'default' =>
  s === 'running' ? 'success' : s === 'exited' ? 'danger' : 'default';
const stateText = (s: string) => (s === 'running' ? '运行中' : s === 'exited' ? '已终止' : s === 'stopped' ? '已停止' : s === 'unknown' ? '状态未知' : s === 'starting' ? '启动中' : s);
/** 节点离线判定：插件错误体消息被宿主 httpClient 吞成「请求失败（HTTP 409/502）」，需按状态兜底。 */
const isOfflineError = (e: unknown) => {
  const msg = e instanceof Error ? e.message : String(e);
  const status = (e as { status?: number }).status;
  return /节点未在线|node\.offline/i.test(msg) || /HTTP 409|HTTP 502/.test(msg) || status === 409 || status === 502;
};
const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** 实例运行起始（本会话观测）：退出清零，重进重计——后端未暴露容器启动时间。 */
const runningSince = new Map<string, number>();
function formatUptime(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (v: number) => String(v).padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`;
}

/** 已运行时长：独立小组件自跳秒，避免整页每秒重渲染打断滚动手势。 */
function UptimeText({ instanceId, running, prefix = '已运行 ' }: { instanceId: string; running: boolean; prefix?: string }) {
  const [, tick] = useState(0);
  useEffect(() => {
    if (!running) return undefined;
    const timer = setInterval(() => tick((v) => v + 1), 1000);
    return () => clearInterval(timer);
  }, [running]);
  if (!running) return null;
  const at = runningSince.get(instanceId);
  if (!at) return null;
  return <Text>{` · ${prefix}${formatUptime(Date.now() - at)}`}</Text>;
}

/* ---------------- base64（Hermes 无 atob/btoa/TextDecoder，纯 JS 实现） ---------------- */

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function b64ToBytes(input: string): Uint8Array {
  const clean = input.replace(/[^A-Za-z0-9+/]/g, '');
  const len = clean.length;
  const out = new Uint8Array(Math.floor((len * 3) / 4));
  let o = 0;
  let buffer = 0;
  let bits = 0;
  for (let i = 0; i < len; i++) {
    buffer = (buffer << 6) | B64.indexOf(clean[i]);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[o++] = (buffer >> bits) & 0xff;
    }
  }
  return out.subarray(0, o);
}
function bytesToB64(bytes: Uint8Array): string {
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i];
    const b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
    const b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
    out += B64[b0 >> 2];
    out += B64[((b0 & 3) << 4) | (b1 >> 4)];
    out += i + 1 < bytes.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=';
    out += i + 2 < bytes.length ? B64[b2 & 63] : '=';
  }
  return out;
}
/** UTF-8 字节 → 字符串（替代 TextDecoder）。 */
function utf8Decode(bytes: Uint8Array): string {
  let out = '';
  let i = 0;
  while (i < bytes.length) {
    const b = bytes[i];
    if (b < 0x80) {
      out += String.fromCharCode(b);
      i += 1;
    } else if (b < 0xe0) {
      out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f));
      i += 2;
    } else if (b < 0xf0) {
      out += String.fromCharCode(((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f));
      i += 3;
    } else {
      const cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f);
      out += String.fromCharCode(0xd800 + ((cp - 0x10000) >> 10), 0xdc00 + ((cp - 0x10000) & 0x3ff));
      i += 4;
    }
  }
  return out;
}
/** 字符串 → UTF-8 字节（替代 TextEncoder；代理对拆回码点）。 */
function utf8Encode(text: string): Uint8Array {
  const bytes: number[] = [];
  for (let i = 0; i < text.length; i++) {
    let cp = text.charCodeAt(i);
    if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < text.length) {
      const lo = text.charCodeAt(i + 1);
      if (lo >= 0xdc00 && lo <= 0xdfff) {
        cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00);
        i += 1;
      }
    }
    if (cp < 0x80) bytes.push(cp);
    else if (cp < 0x800) bytes.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
    else if (cp < 0x10000) bytes.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    else bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
  }
  return new Uint8Array(bytes);
}
const decodeContent = (b64: unknown) => {
  const s = typeof b64 === 'string' ? b64 : '';
  return s ? utf8Decode(b64ToBytes(s)) : '';
};
const encodeContent = (text: string) => bytesToB64(utf8Encode(text));

/* ---------------- 总览 ---------------- */

function Overview({ onOpenInstance, onOpenOps }: { onOpenInstance: (id: string, name: string) => void; onOpenOps: (id: string, name: string) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const ov = useResource<Overview>(() => sdk.api.request(`${API}/admin/overview`), [sdk]);
  const d = ov.data;
  const abnormal = (d?.instances?.exited ?? 0) + (d?.instances?.other ?? 0);

  return (
    <Screen>
      {ov.loading ? <Loading /> : null}
      {ov.error ? <Card><Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeSm }}>{ov.error}</Text></Card> : null}
      {d ? (
        <>
          <View style={{ flexDirection: 'row', gap: 10 }}>
            <StatTile value={`${d.instances?.running ?? 0} / ${d.instances?.total ?? 0}`} label="运行实例" />
            <StatTile value={`${d.nodes?.online ?? 0} / ${d.nodes?.total ?? 0}`} label="在线节点" />
            <StatTile value={`${abnormal}`} label="告警" tone={abnormal > 0 ? 'danger' : 'default'} />
          </View>

          {abnormal > 0 ? (
            <Card style={{ borderColor: c.danger ?? '#dc2626', borderWidth: 1 }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <Text style={{ color: c.danger ?? '#dc2626', fontSize: 14 }}>⚠</Text>
                <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeSm, fontWeight: '500', flex: 1 }}>
                  {abnormal} 个实例状态异常
                </Text>
              </View>
            </Card>
          ) : null}

          <SectionTitle title="实例" />
          {(d.recentInstances ?? []).map((inst) => (
            <Card key={inst.id} onPress={() => onOpenInstance(inst.id, inst.name)}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <View style={{ width: 9, height: 9, borderRadius: 5, backgroundColor: inst.state === 'running' ? (c.success ?? '#16a34a') : c.textTertiary }} />
                <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
                  {inst.name}
                </Text>
                <Badge text={stateText(inst.state)} tone={stateTone(inst.state)} solid={inst.state === 'running'} />
                <Pressable
                  onPress={() => onOpenInstance(inst.id, inst.name)}
                  hitSlop={6}
                  style={{
                    width: 30, height: 30, borderRadius: 15, backgroundColor: c.fillHover,
                    alignItems: 'center', justifyContent: 'center',
                  }}
                >
                  <Icon name={inst.state === 'running' ? 'power' : 'play'} size={15} color={inst.state === 'running' ? (c.danger ?? '#dc2626') : (c.success ?? '#16a34a')} />
                </Pressable>
              </View>
              <View style={{ flexDirection: 'row', gap: 6, flexWrap: 'wrap' }}>
                {inst.mcVersion ? <Badge text={`MC ${inst.mcVersion}`} /> : null}
                {inst.cpuPercent != null ? <Badge text={`CPU ${Number(inst.cpuPercent).toFixed(1)}%`} /> : null}
                {/* 后端字段是 memUsedMb/memTotalMb（容器实时用量/规格上限）；两者皆缺时不渲染内存，禁止出现 0G */}
                {inst.memUsedMb != null ? (
                  <Badge
                    text={
                      inst.memTotalMb != null
                        ? `${(Number(inst.memUsedMb) / 1024).toFixed(1)}G/${Math.round(Number(inst.memTotalMb) / 1024)}G 内存`
                        : `${(Number(inst.memUsedMb) / 1024).toFixed(1)}G 内存`
                    }
                  />
                ) : null}
              </View>
            </Card>
          ))}

          <SectionTitle title="节点" actionText={abnormal > 0 ? undefined : '全部在线'} />
          {(d.nodeDetails ?? []).map((n) => (
            <Card key={n.id}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
                <View style={{ width: 30, height: 30, borderRadius: 8, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
                  <Icon name="server-outline" size={15} color={c.textSecondary} />
                </View>
                <View style={{ flex: 1, gap: 1 }}>
                  <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>{n.name}</Text>
                  <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                    agent {n.agentVersion || '—'}
                  </Text>
                </View>
                <Badge text={n.connected ? '在线' : '离线'} tone={n.connected ? 'success' : 'danger'} />
              </View>
            </Card>
          ))}

          <Card
            onPress={() => {
              const first = (d.recentInstances ?? [])[0];
              onOpenOps(first?.id ?? '', first?.name ?? '全部实例');
            }}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500', flex: 1 }}>
                备份与计划任务
              </Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeLg }}>{'›'}</Text>
            </View>
          </Card>
        </>
      ) : null}
    </Screen>
  );
}

/* ---------------- 实例运维（UIX panelInstance） ---------------- */

const TPS_LINE = /TPS from last 1m, 5m, 15m:\s*([0-9.]+),\s*([0-9.]+),\s*([0-9.]+)/;

/** 控制台终端语义配色：终端恒为暗色，不随主题浅色模式翻转（与 UIX 设计稿一致）。 */
const CONSOLE = {
  bg: '#0d1117',
  border: '#1f2733',
  text: '#c9d1d9',
  dim: '#7d8590',
  ok: '#3fb950',
  accent: '#58a6ff',
  cmd: '#79c0ff',
};

function MetricCell({ label, value, fraction, tone }: { label: string; value: string; fraction: number; tone?: 'accent' | 'success' | 'warning' | 'danger' }) {
  const t = useSdk().theme;
  const c = t.colors;
  return (
    <View style={{ flex: 1, gap: 5 }}>
      <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: 6 }}>
        <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd, fontWeight: '700' }}>{value}</Text>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{label}</Text>
      </View>
      <ProgressBar fraction={fraction} tone={tone ?? 'accent'} />
    </View>
  );
}

function ConsoleCard({ instanceId, running, onOfflineChange }: { instanceId: string; running: boolean; onOfflineChange?: (offline: boolean) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const [lines, setLines] = useState<string[]>([]);
  const [cursor, setCursor] = useState('');
  const [connected, setConnected] = useState(false);
  const [offline, setOffline] = useState(false);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const scanRef = useRef<(chunk: string) => void>(() => undefined);
  const logRef = useRef<ScrollView>(null);

  const poll = useCallback(async () => {
    try {
      const qs = cursor ? `since=${encodeURIComponent(cursor)}&tail=200` : 'tail=250';
      const res = await sdk.api.request<{ text?: string; data?: string; nextCursor?: string; truncated?: boolean }>(
        `${API}/admin/instances/${encodeURIComponent(instanceId)}/output?${qs}`,
      );
      const chunk = typeof res.data === 'string' && res.data ? decodeContent(res.data) : (res.text ?? '');
      setConnected(true);
      setOffline(false);
      onOfflineChange?.(false);
      if (chunk) {
        const incoming = chunk.replace(/\r/g, '').split('\n').filter((l) => l.trim().length > 0);
        if (incoming.length) {
          setLines((prev) => [...prev, ...incoming].slice(-300));
          scanRef.current(chunk);
          setTimeout(() => logRef.current?.scrollToEnd({ animated: false }), 60);
        }
      }
      if (res.nextCursor) setCursor(res.nextCursor);
    } catch (e) {
      setConnected(false);
      if (isOfflineError(e)) {
        setOffline(true);
        throw e;
      }
    }
  }, [sdk, instanceId, cursor]);

  useEffect(() => {
    setLines([]);
    setCursor('');
    setConnected(false);
    setOffline(false);
  }, [instanceId]);

  useEffect(() => {
    let stopped = false;
    const tick = () => {
      if (!stopped) void poll().catch(() => undefined);
    };
    tick();
    const timer = setInterval(tick, 2500);
    return () => { stopped = true; clearInterval(timer); };
    // cursor 进 change 后重建 interval 保证携带最新 since
  }, [poll]);

  // TPS 探测：Paper 系代发 tps 命令，输出经控制台轮询回流解析
  const tpsRef = useRef<number | null>(null);
  const [tps, setTps] = useState<number | null>(null);
  scanRef.current = (chunk: string) => {
    const m = TPS_LINE.exec(chunk.slice(-4000));
    if (m) setTps(Number(m[1]));
  };
  useEffect(() => {
    if (!running) { setTps(null); return undefined; }
    const probe = () => {
      void sdk.api
        .request(`${API}/admin/instances/${encodeURIComponent(instanceId)}/tps-probe`, { method: 'POST' })
        .catch(() => undefined);
    };
    probe();
    const timer = setInterval(probe, 12_000);
    return () => clearInterval(timer);
  }, [sdk, instanceId, running]);

  const send = () => {
    const command = input.trim();
    if (!command || sending) return;
    setSending(true);
    void sdk.api
      .request(`${API}/admin/instances/${encodeURIComponent(instanceId)}/command`, {
        method: 'POST',
        body: { command },
      })
      .then(() => {
        setLines((prev) => [...prev, `> ${command}`].slice(-300));
        setInput('');
        setTimeout(() => { void poll().catch(() => undefined); }, 500);
      })
      .catch((e) => uiAlert('命令发送失败', errText(e)))
      .finally(() => setSending(false));
  };

  return (
    <View style={{ borderRadius: t.radii.lg, backgroundColor: CONSOLE.bg, borderWidth: 1, borderColor: CONSOLE.border, overflow: 'hidden' }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', paddingHorizontal: 12, paddingVertical: 9, gap: 8, borderBottomWidth: 1, borderBottomColor: CONSOLE.border }}>
        <Icon name="terminal-outline" size={14} color={CONSOLE.dim} />
        <Text style={{ color: CONSOLE.text, fontSize: t.typography.sizeSm, fontWeight: '600', flex: 1 }}>控制台</Text>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 5 }}>
          <View style={{ width: 7, height: 7, borderRadius: 4, backgroundColor: connected ? CONSOLE.ok : offline ? '#f0883e' : CONSOLE.dim }} />
          <Text style={{ color: connected ? CONSOLE.ok : offline ? '#f0883e' : CONSOLE.dim, fontSize: t.typography.sizeXs }}>
            {connected ? '已连接' : offline ? '节点未在线' : '等待连接'}
          </Text>
        </View>
      </View>
      <ScrollView
        ref={logRef}
        style={{ height: 220 }}
        contentContainerStyle={{ padding: 12, gap: 2 }}
      >
        {lines.length === 0 ? (
          <Text style={{ color: CONSOLE.dim, fontSize: 11, fontFamily: Platform.select({ android: 'monospace' }) }}>
            {offline ? '节点未在线，暂无法获取日志' : running ? '等待日志输出…' : '实例未运行，启动后可查看日志'}
          </Text>
        ) : lines.map((line, i) => (
          <Text
            key={`${i}-${line.slice(0, 12)}`}
            selectable
            style={{ color: line.startsWith('> ') ? CONSOLE.cmd : CONSOLE.text, fontSize: 11, lineHeight: 16, fontFamily: Platform.select({ android: 'monospace' }) }}
          >
            {line}
          </Text>
        ))}
      </ScrollView>
      <KeyboardAvoidingView behavior={Platform.OS === 'android' ? undefined : 'padding'}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 10, paddingVertical: 8, borderTopWidth: 1, borderTopColor: CONSOLE.border }}>
          <TextInput
            value={input}
            onChangeText={setInput}
            onSubmitEditing={send}
            placeholder={running ? '输入命令，回车或点击发送' : '实例未运行'}
            placeholderTextColor={CONSOLE.dim}
            editable={running}
            autoCapitalize="none"
            autoCorrect={false}
            style={{ flex: 1, color: CONSOLE.text, fontSize: 12, fontFamily: Platform.select({ android: 'monospace' }), backgroundColor: '#161b22', borderRadius: 8, paddingHorizontal: 10, paddingVertical: 7 }}
          />
          <Pressable
            onPress={send}
            disabled={!running || sending || !input.trim()}
            hitSlop={6}
            style={{ width: 34, height: 34, borderRadius: 9, backgroundColor: input.trim() && running ? t.colors.accent : '#21262d', alignItems: 'center', justifyContent: 'center', opacity: !running || sending ? 0.6 : 1 }}
          >
            <Icon name="send" size={15} color={input.trim() && running ? '#ffffff' : CONSOLE.dim} />
          </Pressable>
        </View>
      </KeyboardAvoidingView>
      {/* TPS 解析结果经 tpsStore 桥接给父级四格指标 */}
      <TpsBridge value={tps} />
    </View>
  );
}

/** 子组件向父页传 TPS 的轻量桥：父级订阅 store。 */
const tpsStore: { current: number | null; listeners: Set<(v: number | null) => void> } = {
  current: null,
  listeners: new Set(),
};
function TpsBridge({ value }: { value: number | null }) {
  useEffect(() => {
    tpsStore.current = value;
    tpsStore.listeners.forEach((fn) => fn(value));
  }, [value]);
  return null;
}

function InstancePage({
  instanceId,
  name,
  onOpen,
}: {
  instanceId: string;
  name: string;
  onOpen: (sub: 'ops' | 'files' | 'config' | 'proxy' | 'players') => void;
}) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const inst = useResource<InstanceDto>(
    () => sdk.api.request(`${API}/admin/instances/${encodeURIComponent(instanceId)}`),
    [instanceId],
  );
  const d = inst.data;
  const [busy, setBusy] = useState('');
  // 节点离线：以控制台轮询的真实调用结果为准（注册表 connected 不可信）
  const [nodeOffline, setNodeOffline] = useState(false);
  // 挂载即探测一次输出通道：节点离线立刻在状态卡呈现，不等控制台轮询
  useEffect(() => {
    let alive = true;
    sdk.api
      .request(`${API}/admin/instances/${encodeURIComponent(instanceId)}/output?tail=1`)
      .then(() => { if (alive) setNodeOffline(false); })
      .catch((e) => {
        if (!alive) return;
        const msg = e instanceof Error ? e.message : String(e);
        const status = (e as { status?: number }).status;
        if (/节点未在线|node\.offline/i.test(msg) || status === 409 || status === 502) {
          setNodeOffline(true);
        }
      });
    return () => { alive = false; };
  }, [sdk, instanceId]);
  const state = d?.state ?? '';
  const running = state === 'running';

  useEffect(() => {
    if (running) {
      if (!runningSince.has(instanceId)) runningSince.set(instanceId, Date.now());
    } else {
      runningSince.delete(instanceId);
    }
  }, [running, instanceId]);

  // 四格指标：在线玩家 + CPU/内存（历史环最新点）
  const players = useResource<PlayersDto>(
    () => sdk.api.request(`${API}/admin/instances/${encodeURIComponent(instanceId)}/players`),
    [instanceId],
  );
  const [tpsValue, setTpsValue] = useState<number | null>(null);
  useEffect(() => {
    const fn = (v: number | null) => setTpsValue(v);
    tpsStore.listeners.add(fn);
    // 控制台子组件先于父级 effect 挂载，首帧先取存量值
    setTpsValue(tpsStore.current);
    return () => { tpsStore.listeners.delete(fn); };
  }, []);

  const metrics = useResource<{ points?: number[][] }>(
    () => (running
      ? sdk.api.request(`${API}/admin/instances/${encodeURIComponent(instanceId)}/metrics?windowMs=3600000`)
      : Promise.resolve({ points: [] })),
    [instanceId, running],
  );
  const lastPoint = (metrics.data?.points ?? []).slice(-1)[0];
  const cpuPercent = running && lastPoint ? Math.round(lastPoint[1] ?? 0) : null;
  const memUsedMb = running && lastPoint ? (lastPoint[2] ?? 0) : null;
  const memTotalMb = d?.memoryMb ?? 0;

  const power = (action: string, label: string) => {
    uiAlert(label, `对「${name}」执行${label}？`, [
      { text: '取消', style: 'cancel' },
      {
        text: label, style: action === 'kill' ? 'destructive' : 'default',
        onPress: () => {
          setBusy(action);
          void sdk.api
            .request(`${API}/admin/instances/${encodeURIComponent(instanceId)}/${action}`, { method: 'POST' })
            .then(() => { setBusy(''); runningSince.delete(instanceId); inst.reload(); players.reload(); })
            .catch((e) => { setBusy(''); uiAlert(`${label}失败`, errText(e)); });
        },
      },
    ]);
  };

  const playersData = players.data;
  const online = playersData?.reachable ? Number(playersData.online ?? 0) : null;
  const maxPlayers = playersData?.max != null ? Number(playersData.max) : null;

  const manageGranted = useManageGranted();
  const quickEntries: { key: 'players' | 'files' | 'ops' | 'config' | 'proxy'; icon: string; label: string; sub: string }[] = [
    { key: 'players', icon: 'people-outline', label: '玩家', sub: '在线列表' },
    { key: 'files', icon: 'folder-open-outline', label: '文件', sub: '管理与编辑' },
    { key: 'ops', icon: 'archive-outline', label: '备份', sub: '归档与任务' },
    { key: 'ops', icon: 'time-outline', label: '计划任务', sub: '定时运维' },
    ...(manageGranted
      ? [
          { key: 'config' as const, icon: 'options-outline', label: '实例配置', sub: 'server.properties' },
          { key: 'proxy' as const, icon: 'globe-outline', label: '域名解析', sub: '代理组纳管' },
        ]
      : []),
  ];

  return (
    <Screen>
      {inst.loading || !d ? <Loading /> : null}
      {d ? (
        <>
          {/* 运行状态卡 */}
          <Card>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
              <View style={{ width: 10, height: 10, borderRadius: 5, backgroundColor: running ? (c.success ?? '#16a34a') : c.textTertiary }} />
              <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeMd + 1, fontWeight: '700', flex: 1 }}>
                {name}
              </Text>
              {nodeOffline ? (
                <Badge text="节点离线" tone="danger" solid />
              ) : (
                <Badge text={state ? stateText(state) : '未知'} tone={stateTone(state)} solid={running} />
              )}
            </View>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
              {nodeOffline
                ? '节点离线 · 状态可能已过期'
                : `节点 ${d.nodeId?.slice(0, 8)} · ${d.mcVersion || '版本未知'}${(d.memoryMb ?? 0) > 0 ? ` · 规格 ${Math.round(Number(d.memoryMb) / 1024)}G 内存` : ''}`}
              <UptimeText instanceId={instanceId} running={running} />
              {d.autoRestart ? ' · 自动重启' : ''}
            </Text>
          </Card>

          {/* 电源操作 */}
          {nodeOffline ? (
            <Card style={{ borderColor: c.warning ?? '#d97706', borderWidth: 1 }}>
              <Text style={{ color: c.warning ?? '#d97706', fontSize: t.typography.sizeXs + 1 }}>
                节点离线，状态可能已过期；电源与命令操作暂不可用，待节点恢复后自动开放
              </Text>
            </Card>
          ) : null}
          <View style={{ flexDirection: 'row', gap: 8, opacity: nodeOffline ? 0.45 : 1 }} pointerEvents={nodeOffline ? 'none' : 'auto'}>
            {running ? (
              <>
                <View style={{ flex: 1 }}>
                  <SecondaryButton title="重启" onPress={() => power('restart', '重启')} busy={busy === 'restart'} />
                </View>
                <View style={{ flex: 1 }}>
                  <SecondaryButton title="停止" danger onPress={() => power('stop', '停止')} busy={busy === 'stop'} />
                </View>
                <View style={{ flex: 1 }}>
                  <SecondaryButton title="强制终止" danger onPress={() => power('kill', '强制终止')} busy={busy === 'kill'} />
                </View>
              </>
            ) : (
              <View style={{ flex: 1 }}>
                <PrimaryButton title="启动实例" onPress={() => power('start', '启动')} busy={busy === 'start'} />
              </View>
            )}
          </View>

          {/* 四格指标（2×2，带进度条） */}
          <Card>
            <View style={{ flexDirection: 'row', gap: 14 }}>
              <MetricCell
                label="TPS"
                value={running ? (tpsValue != null ? tpsValue.toFixed(1) : '—') : '—'}
                fraction={tpsValue != null ? tpsValue / 20 : 0}
                tone={tpsValue != null && tpsValue < 18 ? 'warning' : 'success'}
              />
              <MetricCell
                label={maxPlayers != null ? `在线 / ${maxPlayers}` : '在线'}
                value={running ? (online != null ? String(online) : '—') : '—'}
                fraction={online != null && maxPlayers ? online / maxPlayers : 0}
                tone="accent"
              />
            </View>
            <View style={{ height: 10 }} />
            <View style={{ flexDirection: 'row', gap: 14 }}>
              <MetricCell
                label="CPU"
                value={cpuPercent != null ? `${cpuPercent}%` : '—'}
                fraction={(cpuPercent ?? 0) / 100}
                tone={(cpuPercent ?? 0) > 85 ? 'danger' : (cpuPercent ?? 0) > 60 ? 'warning' : 'success'}
              />
              <MetricCell
                label={memTotalMb ? `内存 / ${Math.round(memTotalMb / 1024)}G` : '内存'}
                value={memUsedMb != null ? `${(memUsedMb / 1024).toFixed(1)}G` : '—'}
                fraction={memTotalMb ? (memUsedMb ?? 0) / memTotalMb : 0}
                tone={memTotalMb && (memUsedMb ?? 0) / memTotalMb > 0.9 ? 'danger' : 'accent'}
              />
            </View>
            {playersData?.reachable === false && running ? (
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                在线探测失败：{playersData.reason || '无法连接'}
              </Text>
            ) : null}
          </Card>

          {/* 黑底控制台 */}
          <ConsoleCard instanceId={instanceId} running={running} onOfflineChange={setNodeOffline} />

          {/* 快捷入口（六宫格） */}
          <SectionTitle title="快捷入口" />
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
            {quickEntries.map((entry, i) => (
              <Card
                key={`${entry.key}-${i}`}
                onPress={() => onOpen(entry.key)}
                style={{ width: '31%', alignItems: 'center', paddingVertical: 14, gap: 5 }}
              >
                <Icon name={entry.icon} size={22} color={c.accent} />
                <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>{entry.label}</Text>
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs - 1 }}>{entry.sub}</Text>
              </Card>
            ))}
          </View>
        </>
      ) : null}
    </Screen>
  );
}

/* ---------------- 在线玩家 ---------------- */

function PlayersPage({ instanceId }: { instanceId: string }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const res = useResource<PlayersDto>(
    () => sdk.api.request(`${API}/admin/instances/${encodeURIComponent(instanceId)}/players`),
    [instanceId],
  );
  const d = res.data;
  return (
    <Screen>
      {res.loading ? <Loading /> : null}
      {res.error && !d ? <Empty text={res.error} /> : null}
      {d ? (
        d.reachable === false ? (
          <Empty text={`无法连接到服务器：${d.reason || '探测失败'}`} />
        ) : (
          <>
            <View style={{ flexDirection: 'row', gap: 10 }}>
              <StatTile value={`${Number(d.online ?? 0)} / ${Number(d.max ?? 0)}`} label="在线玩家" />
              <StatTile value={d.latencyMs != null ? `${d.latencyMs}ms` : '—'} label="探测延迟" />
            </View>
            {d.version ? <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>服务端版本 {d.version}</Text> : null}
            <SectionTitle title="玩家列表" />
            {(d.players ?? []).length === 0 ? (
              <Empty text="当前没有玩家在线" />
            ) : (
              <Card>
                {(d.players ?? []).map((p, i) => (
                  <View key={p.id ?? `${p.name}-${i}`}>
                    {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10 }}>
                      <View style={{ width: 30, height: 30, borderRadius: 15, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
                        <Icon name="person" size={16} color={c.textSecondary} />
                      </View>
                      <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '500', flex: 1 }}>
                        {p.name || '未知玩家'}
                      </Text>
                    </View>
                  </View>
                ))}
              </Card>
            )}
          </>
        )
      ) : null}
    </Screen>
  );
}

/* ---------------- 文件管理与编辑 ---------------- */

function formatSize(size?: number): string {
  const v = Number(size ?? 0);
  if (v >= 1024 * 1024 * 1024) return `${(v / 1024 / 1024 / 1024).toFixed(1)}G`;
  if (v >= 1024 * 1024) return `${(v / 1024 / 1024).toFixed(1)}M`;
  if (v >= 1024) return `${(v / 1024).toFixed(1)}K`;
  return `${v}B`;
}

function FilesPage({ instanceId }: { instanceId: string }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const id = encodeURIComponent(instanceId);
  const [path, setPath] = useState('');
  const [entries, setEntries] = useState<FileEntry[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [offline, setOffline] = useState(false);

  // 编辑态：选中的文件 + 内容
  const [editing, setEditing] = useState<{ path: string; text: string; origin: string } | null>(null);
  const [content, setContent] = useState('');
  const [saving, setSaving] = useState(false);
  const [mkdirMode, setMkdirMode] = useState(false);
  const [mkdirName, setMkdirName] = useState('');

  const load = useCallback((target: string) => {
    setLoading(true);
    setError('');
    setOffline(false);
    sdk.api
      .request<{ entries?: FileEntry[] }>(`${API}/admin/instances/${id}/files?path=${encodeURIComponent(target)}`)
      .then((res) => {
        setEntries(res.entries ?? []);
        setPath(target);
        setLoading(false);
      })
      .catch((e) => {
        setError(errText(e));
        setOffline(isOfflineError(e));
        setEntries([]);
        setLoading(false);
      });
  }, [sdk, id]);

  useEffect(() => { load(''); }, [load]);

  const openFile = (entry: FileEntry) => {
    const full = entry.path ?? (path ? `${path}/${entry.name}` : entry.name);
    setLoading(true);
    sdk.api
      .request<{ content?: string }>(`${API}/admin/instances/${id}/files/content?path=${encodeURIComponent(full)}`)
      .then((res) => {
        const text = decodeContent(res.content);
        setEditing({ path: full, text, origin: text });
        setContent(text);
        setLoading(false);
      })
      .catch((e) => {
        setError(errText(e));
        setLoading(false);
      });
  };

  const save = () => {
    if (!editing || saving) return;
    setSaving(true);
    sdk.api
      .request(`${API}/admin/instances/${id}/files`, {
        method: 'POST',
        body: { path: editing.path, content: encodeContent(content), encoding: 'base64' },
      })
      .then(() => {
        setEditing({ ...editing, text: content, origin: content });
        setSaving(false);
      })
      .catch((e) => {
        setSaving(false);
        uiAlert('保存失败', errText(e));
      });
  };

  const removeFile = (entry: FileEntry) => {
    const full = entry.path ?? (path ? `${path}/${entry.name}` : entry.name);
    uiAlert('删除确认', `删除${entry.isDir ? '目录' : '文件'}「${entry.name}」？该操作不可恢复。`, [
      { text: '取消', style: 'cancel' },
      {
        text: '删除', style: 'destructive',
        onPress: () => {
          sdk.api
            .request(`${API}/admin/instances/${id}/files/delete`, { method: 'POST', body: { path: full } })
            .then(() => load(path))
            .catch((e) => uiAlert('删除失败', errText(e)));
        },
      },
    ]);
  };

  const mkdir = () => {
    const name = mkdirName.trim();
    if (!name) return;
    const full = path ? `${path}/${name}` : name;
    sdk.api
      .request(`${API}/admin/instances/${id}/files/mkdir`, { method: 'POST', body: { path: full } })
      .then(() => { setMkdirMode(false); setMkdirName(''); load(path); })
      .catch((e) => uiAlert('新建失败', errText(e)));
  };

  if (editing) {
    const dirty = content !== editing.origin;
    return (
      <Screen scroll={false} padded>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingBottom: t.spacing.sm }}>
          <Pressable onPress={() => { if (dirty) { uiAlert('放弃修改？', '未保存的修改将丢失', [{ text: '继续编辑', style: 'cancel' }, { text: '放弃', style: 'destructive', onPress: () => setEditing(null) }]); } else { setEditing(null); } }} hitSlop={8}>
            <Icon name="back" size={20} color={c.textPrimary} />
          </Pressable>
          <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600', flex: 1 }}>
            {editing.path}
          </Text>
          <Pressable onPress={save} disabled={!dirty || saving} hitSlop={6} style={{ flexDirection: 'row', alignItems: 'center', gap: 4, opacity: dirty ? 1 : 0.4 }}>
            <Icon name="checkmark" size={16} color={c.accent} />
            <Text style={{ color: c.accent, fontSize: t.typography.sizeSm, fontWeight: '600' }}>{saving ? '保存中' : '保存'}</Text>
          </Pressable>
        </View>
        <View style={{ flex: 1, borderRadius: t.radii.md, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface, overflow: 'hidden' }}>
          <TextInput
            value={content}
            onChangeText={setContent}
            multiline
            autoCapitalize="none"
            autoCorrect={false}
            textAlignVertical="top"
            style={{ flex: 1, color: c.textPrimary, fontSize: 12, fontFamily: Platform.select({ android: 'monospace' }), padding: 12 }}
          />
        </View>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingTop: t.spacing.sm }}>
          <Pressable
            onPress={() => uiAlert('删除文件', `删除「${editing.path}」？该操作不可恢复。`, [
              { text: '取消', style: 'cancel' },
              {
                text: '删除', style: 'destructive',
                onPress: () => {
                  sdk.api
                    .request(`${API}/admin/instances/${id}/files/delete`, { method: 'POST', body: { path: editing.path } })
                    .then(() => { setEditing(null); load(''); })
                    .catch((e) => uiAlert('删除失败', errText(e)));
                },
              },
            ])}
            style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}
          >
            <Icon name="trash" size={15} color={c.danger ?? '#dc2626'} />
            <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeSm }}>删除文件</Text>
          </Pressable>
          <View style={{ flex: 1 }} />
          {dirty ? <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>有未保存修改</Text> : null}
        </View>
      </Screen>
    );
  }

  const crumbs = path ? path.split('/') : [];
  const sorted = [...(entries ?? [])].sort((a, b) => (a.isDir === b.isDir ? a.name.localeCompare(b.name) : a.isDir ? -1 : 1));

  return (
    <Screen>
      {/* 目录导航：面包屑 + 新建文件夹 */}
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <Pressable onPress={() => load('')} hitSlop={4}>
          <Icon name="folder" size={15} color={c.accent} />
        </Pressable>
        {crumbs.map((part, i) => {
          const target = crumbs.slice(0, i + 1).join('/');
          return (
            <View key={target} style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{'/'}</Text>
              <Pressable onPress={() => load(target)} hitSlop={4}>
                <Text numberOfLines={1} style={{ color: i === crumbs.length - 1 ? c.textPrimary : c.accent, fontSize: t.typography.sizeXs + 1, fontWeight: i === crumbs.length - 1 ? '600' : '400', maxWidth: 110 }}>
                  {part}
                </Text>
              </Pressable>
            </View>
          );
        })}
        <View style={{ flex: 1 }} />
        <Pressable onPress={() => setMkdirMode((v) => !v)} hitSlop={6} style={{ flexDirection: 'row', alignItems: 'center', gap: 3 }}>
          <Icon name="add" size={15} color={c.accent} />
          <Text style={{ color: c.accent, fontSize: t.typography.sizeXs }}>新建文件夹</Text>
        </Pressable>
      </View>
      {mkdirMode ? (
        <Card>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <TextInput
              value={mkdirName}
              onChangeText={setMkdirName}
              onSubmitEditing={mkdir}
              placeholder="文件夹名称"
              placeholderTextColor={c.textTertiary}
              autoCapitalize="none"
              autoCorrect={false}
              style={{ flex: 1, height: 40, borderRadius: t.radii.md, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgPage, color: c.textPrimary, fontSize: t.typography.sizeSm, paddingHorizontal: 10 }}
            />
            <SecondaryButton title="创建" onPress={mkdir} />
          </View>
        </Card>
      ) : null}

      {loading ? <Loading /> : null}
      {offline ? <Empty text="节点未在线，无法浏览文件" /> : null}
      {!loading && !offline && error ? <Empty text={error} /> : null}
      {!loading && !error && sorted.length === 0 ? <Empty text="目录为空" /> : null}
      {sorted.length > 0 ? (
        <Card>
          {sorted.map((entry, i) => {
          const full = entry.path ?? (path ? `${path}/${entry.name}` : entry.name);
          return (
            <View key={full}>
              {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10 }}>
                <Pressable
                  onPress={() => (entry.isDir ? load(full) : openFile(entry))}
                  style={{ flexDirection: 'row', alignItems: 'center', gap: 10, flex: 1 }}
                >
                  <Icon name={entry.isDir ? 'folder-open-outline' : 'document-text-outline'} size={18} color={entry.isDir ? c.accent : c.textTertiary} />
                  <View style={{ flex: 1, gap: 1 }}>
                    <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '500' }}>
                      {entry.name}
                    </Text>
                    {!entry.isDir ? (
                      <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{formatSize(entry.size)}</Text>
                    ) : null}
                  </View>
                </Pressable>
                <Pressable onPress={() => removeFile(entry)} hitSlop={8}>
                  <Icon name="trash" size={16} color={c.textTertiary} />
                </Pressable>
              </View>
            </View>
          );
          })}
        </Card>
      ) : null}
      <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
        点击文本类文件可直接编辑保存；目录与二进制文件建议在网页端处理
      </Text>
    </Screen>
  );
}

/* ---------------- 实例配置（server.properties 等结构化配置原文编辑） ---------------- */

const CONFIG_PRESETS = ['server.properties', 'bukkit.yml', 'spigot.yml', 'paper-global.yml', 'paper-world-defaults.yml'];

function ConfigPage({ instanceId }: { instanceId: string }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const id = encodeURIComponent(instanceId);
  const [path, setPath] = useState('server.properties');
  const [meta, setMeta] = useState<{ exists: boolean; format?: string; parseError?: string } | null>(null);
  const [origin, setOrigin] = useState('');
  const [draft, setDraft] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [offline, setOffline] = useState(false);

  const load = useCallback((target: string) => {
    setLoading(true);
    setError('');
    setDraft(null);
    setOffline(false);
    sdk.api
      .request<{ content?: string; exists?: boolean; format?: string; parseError?: string }>(
        `${API}/admin/instances/${id}/server-config?path=${encodeURIComponent(target)}`,
      )
      .then((res) => {
        setMeta({ exists: res.exists === true, format: res.format, parseError: res.parseError });
        setOrigin(res.content ?? '');
        setLoading(false);
      })
      .catch((e) => {
        setError(errText(e));
        setOffline(isOfflineError(e));
        setMeta(null);
        setOrigin('');
        setLoading(false);
      });
  }, [sdk, id]);

  useEffect(() => { load(path); }, [load, path]);

  const value = draft ?? origin;
  const modified = draft != null && draft !== origin;

  const save = () => {
    if (saving || !modified) return;
    setSaving(true);
    sdk.api
      .request(`${API}/admin/instances/${id}/server-config`, {
        method: 'PUT',
        body: { path, properties: {}, content: value, preferRaw: true },
      })
      .then(() => {
        setOrigin(value);
        setDraft(null);
        setSaving(false);
      })
      .catch((e) => {
        setSaving(false);
        uiAlert('保存失败', errText(e));
      });
  };

  return (
    <Screen scroll={false} padded>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, paddingBottom: t.spacing.sm }}>
        {CONFIG_PRESETS.map((preset) => (
          <Pressable
            key={preset}
            onPress={() => { setDraft(null); setPath(preset); }}
            style={{
              paddingHorizontal: 10, paddingVertical: 6, borderRadius: 999,
              backgroundColor: path === preset ? c.accent : c.bgSurface,
              borderWidth: 1, borderColor: path === preset ? c.accent : c.borderSubtle,
            }}
          >
            <Text style={{ color: path === preset ? c.onAccent : c.textSecondary, fontSize: t.typography.sizeXs }}>{preset}</Text>
          </Pressable>
        ))}
      </View>
      {loading ? <Loading /> : null}
      {!loading && offline ? <Empty text="节点未在线，无法读取配置" /> : null}
      {!loading && !offline && error ? <Empty text={error} /> : null}
      {!loading && !offline && !error && meta ? (
        <>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs, flex: 1 }}>
              {meta.exists ? `${path} · ${meta.format ?? 'raw'}` : `${path} 尚未生成（启动一次实例后可生成默认配置）`}
            </Text>
            <Pressable onPress={save} disabled={!modified || saving} hitSlop={6} style={{ flexDirection: 'row', alignItems: 'center', gap: 4, opacity: modified ? 1 : 0.4 }}>
              <Icon name="checkmark" size={16} color={c.accent} />
              <Text style={{ color: c.accent, fontSize: t.typography.sizeSm, fontWeight: '600' }}>{saving ? '保存中' : '保存'}</Text>
            </Pressable>
          </View>
          {meta.parseError ? (
            <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeXs }}>解析失败，按原文保存：{meta.parseError}</Text>
          ) : null}
          <View style={{ flex: 1, borderRadius: t.radii.md, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface, overflow: 'hidden' }}>
            <TextInput
              value={value}
              onChangeText={setDraft}
              multiline
              autoCapitalize="none"
              autoCorrect={false}
              textAlignVertical="top"
              style={{ flex: 1, color: c.textPrimary, fontSize: 12, fontFamily: Platform.select({ android: 'monospace' }), padding: 12 }}
            />
          </View>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>按原文（raw）保存；修改 server.properties 后需重启实例生效</Text>
        </>
      ) : null}
    </Screen>
  );
}

/* ---------------- 域名解析（代理组纳管） ---------------- */

function ProxyPage({ instanceId, instanceName }: { instanceId: string; instanceName: string }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const id = encodeURIComponent(instanceId);
  const res = useResource<ProxyGroup>(() => sdk.api.request(`${API}/admin/instances/${id}/proxy-group`), [id]);
  const loaded = res.data;
  const exists = loaded?.exists !== false && loaded?.proxyInstanceId != null;

  const [kind, setKind] = useState<'velocity' | 'bungee'>('velocity');
  const [servers, setServers] = useState<ProxyServer[]>([]);
  const [defaultServer, setDefaultServer] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!loaded) return;
    if (exists) {
      setKind((loaded.kind === 'bungee' ? 'bungee' : 'velocity') as 'velocity' | 'bungee');
      setServers((loaded.servers ?? []).map((s) => ({ name: s.name, address: s.address, boundInstanceId: s.boundInstanceId, boundName: s.boundName, external: s.external })));
      setDefaultServer(loaded.defaultServer ?? '');
    }
  }, [loaded, exists]);

  const save = () => {
    if (saving) return;
    setSaving(true);
    sdk.api
      .request(`${API}/admin/instances/${id}/proxy-group`, {
        method: 'PUT',
        body: { kind, servers: servers.map((s) => ({ name: s.name.trim(), address: s.address.trim(), ...(s.boundInstanceId ? { boundInstanceId: s.boundInstanceId } : {}) })), defaultServer, forwarding: loaded?.forwarding ?? '' },
      })
      .then(() => { setSaving(false); res.reload(); })
      .catch((e) => { setSaving(false); uiAlert('保存失败', errText(e)); });
  };

  const detect = () => {
    sdk.api
      .request(`${API}/admin/instances/${id}/proxy-group/detect`, { method: 'POST' })
      .then((r) => {
        const d = r as { detected?: boolean; reason?: string; kind?: string; servers?: ProxyServer[]; defaultServer?: string };
        if (d.detected) {
          setKind((d.kind === 'bungee' ? 'bungee' : 'velocity') as 'velocity' | 'bungee');
          setServers((d.servers ?? []).map((s) => ({
            name: s.name,
            address: s.address,
            boundInstanceId: (s as { matchedInstanceId?: string }).matchedInstanceId ?? s.boundInstanceId,
            boundName: (s as { matchedName?: string }).matchedName ?? s.boundName,
          })));
          setDefaultServer(d.defaultServer ?? '');
        } else {
          uiAlert('未识别到代理配置', d.reason ?? '实例根目录没有 velocity.toml / config.yml 特征');
        }
      })
      .catch((e) => uiAlert('识别失败', errText(e)));
  };

  const unbind = () => {
    uiAlert('解除纳管', `解除「${instanceName}」的代理组纳管？只删除面板记录，不改实例文件。`, [
      { text: '取消', style: 'cancel' },
      {
        text: '解除', style: 'destructive',
        onPress: () => {
          sdk.api
            .request(`${API}/admin/instances/${id}/proxy-group`, { method: 'DELETE' })
            .then(() => res.reload())
            .catch((e) => uiAlert('解除失败', errText(e)));
        },
      },
    ]);
  };

  const updateServer = (index: number, patch: Partial<ProxyServer>) => {
    setServers((prev) => prev.map((s, i) => (i === index ? { ...s, ...patch } : s)));
  };

  return (
    <Screen>
      {res.loading ? <Loading /> : null}
      {res.error && !loaded ? <Empty text={res.error} /> : null}
      {loaded ? (
        <>
          <Card>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              <Icon name="globe-outline" size={18} color={c.accent} />
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
                {exists ? `代理组 · ${loaded.kind === 'bungee' ? 'BungeeCord' : 'Velocity'}` : '尚未纳管代理组'}
              </Text>
              {exists ? <Badge text="已纳管" tone="success" solid /> : <Badge text="未纳管" />}
            </View>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
              {exists
                ? `入口 ${instanceName} · 子服 ${servers.length} 个${defaultServer ? ` · 默认进 ${defaultServer}` : ''}`
                : '识别实例根目录的 velocity.toml / config.yml，把子服地址录入面板统一管理'}
            </Text>
            {!exists ? <PrimaryButton title="识别配置" onPress={detect} /> : null}
          </Card>

          {exists || servers.length > 0 ? (
            <>
              <SectionTitle
                title="子服列表"
                actionText="加一行"
                onAction={() => setServers((prev) => [...prev, { name: '', address: '' }])}
              />
              <Card>
                {servers.length === 0 ? <Empty text="还没有子服，点右上「加一行」" /> : null}
                {servers.map((s, i) => (
                  <View key={i} style={{ gap: 6, paddingVertical: 8 }}>
                    {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                      <TextInput
                        value={s.name}
                        onChangeText={(v) => updateServer(i, { name: v })}
                        placeholder="名称"
                        placeholderTextColor={c.textTertiary}
                        autoCapitalize="none"
                        autoCorrect={false}
                        style={{ width: 96, height: 38, borderRadius: t.radii.md, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgPage, color: c.textPrimary, fontSize: t.typography.sizeSm, paddingHorizontal: 10 }}
                      />
                      <TextInput
                        value={s.address}
                        onChangeText={(v) => updateServer(i, { address: v })}
                        placeholder="地址 host:port"
                        placeholderTextColor={c.textTertiary}
                        autoCapitalize="none"
                        autoCorrect={false}
                        style={{ flex: 1, height: 38, borderRadius: t.radii.md, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgPage, color: c.textPrimary, fontSize: t.typography.sizeSm, paddingHorizontal: 10 }}
                      />
                      <Pressable onPress={() => setServers((prev) => prev.filter((_, idx) => idx !== i))} hitSlop={8}>
                        <Icon name="trash" size={16} color={c.danger ?? '#dc2626'} />
                      </Pressable>
                    </View>
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                      {s.boundInstanceId ? (
                        <Badge text={`绑定实例 ${s.boundName ?? s.boundInstanceId}`} tone="success" />
                      ) : (
                        <Badge text="外部子服" />
                      )}
                      <Pressable onPress={() => setDefaultServer(s.name)} hitSlop={4}>
                        <Badge text={defaultServer === s.name ? '默认进入 ✓' : '设为默认'} tone={defaultServer === s.name ? 'accent' : 'default'} solid={defaultServer === s.name} />
                      </Pressable>
                    </View>
                  </View>
                ))}
              </Card>

              <View style={{ flexDirection: 'row', gap: 8 }}>
                <View style={{ flex: 1 }}>
                  <PrimaryButton title="保存代理组" onPress={save} busy={saving} />
                </View>
                <View style={{ flex: 1 }}>
                  <SecondaryButton title="解除纳管" danger onPress={unbind} />
                </View>
              </View>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                保存只更新面板纳管记录与绑定关系；要改写 velocity.toml / config.yml 请在实例配置或文件管理中编辑
              </Text>
            </>
          ) : null}
        </>
      ) : null}
    </Screen>
  );
}

/* ---------------- 备份与任务 ---------------- */

function OpsPage({ instanceId, instanceName, onBack }: { instanceId: string; instanceName: string; onBack: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const backups = useResource<{ backups?: BackupInfo[] }>(
    () => sdk.api.request(`${API}/admin/instances/${encodeURIComponent(instanceId)}/backups`),
    [instanceId],
  );
  const schedules = useResource<{ records?: ScheduleRow[] }>(
    () => sdk.api.request(`${API}/admin/schedules?instanceId=${encodeURIComponent(instanceId)}&page=1&size=10`),
    [instanceId],
  );
  const bks = backups.data?.backups ?? [];

  const createBackup = () => {
    void sdk.api
      .request(`${API}/admin/instances/${encodeURIComponent(instanceId)}/backups`, { method: 'POST' })
      .then(() => {
        uiAlert('已触发', '备份任务已创建，完成后可在列表查看');
        backups.reload();
      })
      .catch((e) => uiAlert('备份失败', errText(e)));
  };

  return (
    <Screen>
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }} numberOfLines={1}>
            实例备份 · {instanceName || '全部实例'}
          </Text>
          <Badge text={`${bks.length} 份`} tone={bks.length > 0 ? 'success' : 'default'} />
        </View>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
          {bks[0]
            ? `最近 ${bks[0].at ? new Date(Number(bks[0].at)).toLocaleString() : ''}${bks[0].size ? ` · ${bks[0].size}` : ''}`
            : '暂无备份，点击立即备份'}
        </Text>
        <Pressable
          onPress={createBackup}
          style={{ height: 44, borderRadius: t.radii.md, backgroundColor: c.accent, alignItems: 'center', justifyContent: 'center', marginTop: 10 }}
        >
          <Text style={{ color: c.onAccent, fontSize: t.typography.sizeSm, fontWeight: '500' }}>立即备份</Text>
        </Pressable>
      </Card>
      {bks.length > 1 ? <SectionTitle title="历史备份" /> : null}
      {bks.slice(1).map((b, i) => (
        <Card key={i}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500', flex: 1 }} numberOfLines={1}>
              {b.archiveName || b.file || '备份'}
            </Text>
            {b.status ? <Badge text={b.status} tone={b.status === '成功' || b.status === 'OK' ? 'success' : 'default'} /> : null}
          </View>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
            {[b.size, b.at ? new Date(Number(b.at)).toLocaleString() : ''].filter(Boolean).join(' · ')}
          </Text>
        </Card>
      ))}

      <SectionTitle title="计划任务" />
      {schedules.loading ? <Loading /> : null}
      {!schedules.loading && (schedules.data?.records ?? []).length === 0 ? <Empty text="暂无计划任务" /> : null}
      {(schedules.data?.records ?? []).length > 0 ? (
      <Card>
        {(schedules.data?.records ?? []).map((sc, i) => (
          <View key={sc.id}>
            {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 10 }}>
              <View style={{ flex: 1, gap: 1 }}>
                <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>{sc.name}</Text>
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                  {sc.cron || ''}{sc.nextRunAt ? ` · 下次 ${new Date(Number(sc.nextRunAt)).toLocaleString()}` : ''}
                </Text>
              </View>
              {sc.enabled ? <Badge text="启用" tone="success" /> : <Badge text="停用" />}
            </View>
          </View>
        ))}
      </Card>
      ) : null}
      <View style={{ height: 8 }} />
      <SecondaryButton title="返回实例" onPress={onBack} />
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ =
  | { name: 'overview' }
  | { name: 'instance'; id: string; title: string }
  | { name: 'ops'; id: string; title: string }
  | { name: 'files'; id: string; title: string }
  | { name: 'config'; id: string; title: string }
  | { name: 'proxy'; id: string; title: string }
  | { name: 'players'; id: string; title: string };

const SUB_NAMES: Partial<Record<View_['name'], string>> = {
  instance: '实例运维',
  ops: '备份与任务',
  files: '文件管理',
  config: '实例配置',
  proxy: '域名解析',
  players: '在线玩家',
};

/** 深链 → 栈：instance/{id}/{name}、ops|files|config|proxy|players/{id}/{name}。 */
function seedStack(route?: string): View_[] {
  if (!route) return [{ name: 'overview' }];
  const [head, ...rest] = route.split('/');
  const dec = (s?: string) => {
    try { return decodeURIComponent(s ?? ''); } catch { return s ?? ''; }
  };
  const id = dec(rest[0]);
  const title = dec(rest[1]);
  if (head === 'instance' && id) {
    return [{ name: 'overview' }, { name: 'instance', id, title }];
  }
  if ((head === 'ops' || head === 'files' || head === 'config' || head === 'proxy' || head === 'players') && id) {
    return [{ name: 'overview' }, { name: 'instance', id, title }, { name: head, id, title } as View_];
  }
  return [{ name: 'overview' }];
}

function McpanelApp({ initialRoute }: { initialRoute?: string }) {
  const [stack, setStack] = useState<View_[]>(() => seedStack(initialRoute));
  const view = stack[stack.length - 1];

  useEffect(() => {
    setStack(seedStack(initialRoute));
  }, [initialRoute]);

  useEffect(() => {
    const title = view.name === 'overview'
      ? '运维面板'
      : view.name === 'instance' && view.title ? `${view.title} · 实例运维` : SUB_NAMES[view.name] ?? '运维面板';
    currentSdk?.navigation?.setTitle(title);
    // 子页接管宿主返回键为应用内返回，总览恢复默认退出
    currentSdk?.navigation?.setBackAction?.(view.name === 'overview' ? null : () => setStack((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev)));
  }, [view]);

  const push = (next: View_) => setStack((prev) => [...prev, next]);

  const themed = (node: React.ReactNode, scrollable = true) => (
    <UiProvider sdk={currentSdk!}>
      <ViewBoundary>
        {scrollable ? node : <View style={{ flex: 1, backgroundColor: currentSdk!.theme.colors.bgPage }}>{node}</View>}
      </ViewBoundary>
    </UiProvider>
  );

  if (view.name === 'instance') {
    return themed(
      <InstancePage
        instanceId={view.id}
        name={view.title}
        onOpen={(sub) => {
          if (sub === 'ops') push({ name: 'ops', id: view.id, title: view.title });
          else push({ name: sub, id: view.id, title: view.title });
        }}
      />,
    );
  }
  if (view.name === 'ops') {
    return themed(<OpsPage instanceId={view.id} instanceName={view.title} onBack={() => setStack((prev) => prev.slice(0, -1))} />);
  }
  if (view.name === 'files') {
    return themed(<FilesPage instanceId={view.id} />);
  }
  if (view.name === 'config') {
    return themed(<ConfigPage instanceId={view.id} />, false);
  }
  if (view.name === 'proxy') {
    return themed(<ProxyPage instanceId={view.id} instanceName={view.title} />);
  }
  if (view.name === 'players') {
    return themed(<PlayersPage instanceId={view.id} />);
  }
  return (
    <UiProvider sdk={currentSdk!}>
      <ViewBoundary>
        <Overview
          onOpenInstance={(id, name) => push({ name: 'instance', id, title: name })}
          onOpenOps={(id, name) => push({ name: 'ops', id, title: name })}
        />
      </ViewBoundary>
    </UiProvider>
  );
}

const McpanelModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('mcpanel: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    currentEntry = (props as { entry?: { adminCards?: unknown[] } }).entry ?? null;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <McpanelApp initialRoute={route} />
      </UiProvider>
    );
  },
};

export default McpanelModule;
