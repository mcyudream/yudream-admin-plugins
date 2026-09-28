/**
 * mcpanel 移动端（设计稿 panelOverview / panelInstance / panelOps）：
 * 运维总览（摘要 + 告警 + 实例电源速操作 + 节点）、实例运维（电源四键 + 指标 +
 * 控制台日志 + 快捷入口）、备份与任务（备份/计划任务/审计）。
 * 数据走 /api/plugins/mcpanel/admin/**；视觉经 sdk.theme 与 plugin-mobile-ui。
 */
import React, { useEffect, useState } from 'react';
import { Alert, Pressable, ScrollView, Text, View } from 'react-native';

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
    console.warn('[mcpanel-debug] view crash', error?.message);
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
  BackRow, Badge, Card, Empty, Loading, Screen, SectionTitle, StatTile,
  UiProvider, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/mcpanel';

interface InstanceDto {
  id: string;
  nodeId: string;
  name: string;
  mcVersion?: string;
  state: string;
  lastExitCode?: number | null;
  memoryMb?: number;
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
  lastRunStatus?: string;
  nextRunAt?: number;
}

const stateTone = (s: string): 'success' | 'danger' | 'warning' | 'default' =>
  s === 'running' ? 'success' : s === 'exited' ? 'danger' : 'default';
const stateText = (s: string) => (s === 'running' ? '运行中' : s === 'exited' ? '已终止' : s === 'stopped' ? '已停止' : s);

/* ---------------- 总览 ---------------- */

function Overview({ onOpenInstance, onOpenOps }: { onOpenInstance: (id: string, name: string) => void; onOpenOps: () => void }) {
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
                  {abnormal} 个实例异常终止
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
                  <Text style={{ color: inst.state === 'running' ? (c.danger ?? '#dc2626') : (c.success ?? '#16a34a'), fontSize: 12 }}>
                    {inst.state === 'running' ? '■' : '▶'}
                  </Text>
                </Pressable>
              </View>
              <View style={{ flexDirection: 'row', gap: 6 }}>
                <Badge text={inst.mcVersion || '—'} />
                <Badge text={`${Math.round((inst.memoryMb ?? 0) / 1024)}G 内存`} />
              </View>
            </Card>
          ))}

          <SectionTitle title="节点" actionText={abnormal > 0 ? undefined : '全部在线'} />
          {(d.nodeDetails ?? []).map((n) => (
            <Card key={n.id}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
                <View style={{ width: 30, height: 30, borderRadius: 8, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
                  <Text style={{ color: c.textPrimary, fontSize: 13 }}>▣</Text>
                </View>
                <View style={{ flex: 1, gap: 1 }}>
                  <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>{n.name}</Text>
                  <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                    agent {n.agentVersion || '—'} · {n.containers ?? 0} 容器
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

/* ---------------- 实例运维 ---------------- */

function InstancePage({
  instanceId,
  name,
  onBack,
  onOpenOps,
}: {
  instanceId: string;
  name: string;
  onBack: () => void;
  onOpenOps: () => void;
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

  const power = (action: string, label: string) => {
    Alert.alert(label, `对「${name}」执行${label}？`, [
      { text: '取消', style: 'cancel' },
      {
        text: label, style: action === 'kill' ? 'destructive' : 'default',
        onPress: () => {
          setBusy(action);
          void sdk.api
            .request(`${API}/admin/instances/${encodeURIComponent(instanceId)}/${action}`, { method: 'POST' })
            .then(() => { setBusy(''); inst.reload(); })
            .catch((e) => { setBusy(''); Alert.alert(`${label}失败`, e instanceof Error ? e.message : String(e)); });
        },
      },
    ]);
  };

  const state = d?.state ?? '';
  const running = state === 'running';

  return (
    <Screen>
      {inst.loading || !d ? <Loading /> : null}
      {d ? (
        <>
          <Card>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
              <View style={{ width: 10, height: 10, borderRadius: 5, backgroundColor: running ? (c.success ?? '#16a34a') : c.textTertiary }} />
              <View style={{ flex: 1, gap: 1 }}>
                <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 2, fontWeight: '700' }} numberOfLines={1}>
                  {name}{state ? ` · ${stateText(state)}` : ''}{d.lastExitCode != null ? ` · exit ${d.lastExitCode}` : ''}
                </Text>
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
                  节点 {d.nodeId?.slice(0, 8)} · {d.mcVersion || '—'} · {Math.round((d.memoryMb ?? 0) / 1024)}G
                </Text>
              </View>
            </View>
          </Card>

          <View style={{ flexDirection: 'row', gap: 8 }}>
            <Card style={{ flex: 1, alignItems: 'center' }}><Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd, fontWeight: '700' }}>{d.autoRestart ? '开' : '关'}</Text><Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>自动重启</Text></Card>
            <Card style={{ flex: 1, alignItems: 'center' }}><Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd, fontWeight: '700' }}>{Math.round((d.diskMb ?? 0) / 1024)}G</Text><Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>磁盘</Text></Card>
            <Card style={{ flex: 1, alignItems: 'center' }}><Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd, fontWeight: '700' }}>{d.cpuMillis ?? 0}</Text><Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>CPU 毫秒</Text></Card>
          </View>

          <SectionTitle title="电源操作" />
          <View style={{ flexDirection: 'row', gap: 8 }}>
            {running ? (
              <>
                <Pressable
                  onPress={() => power('restart', '重启')}
                  style={{ flex: 1, height: 46, borderRadius: t.radii.md, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface, alignItems: 'center', justifyContent: 'center' }}
                >
                  <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>重启</Text>
                </Pressable>
                <Pressable
                  onPress={() => power('stop', '停止')}
                  style={{ flex: 1, height: 46, borderRadius: t.radii.md, borderWidth: 1, borderColor: c.danger ?? '#dc2626', backgroundColor: c.bgSurface, alignItems: 'center', justifyContent: 'center' }}
                >
                  <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeSm, fontWeight: '500' }}>停止</Text>
                </Pressable>
                <Pressable
                  onPress={() => power('kill', '强制终止')}
                  style={{ flex: 1, height: 46, borderRadius: t.radii.md, borderWidth: 1, borderColor: c.danger ?? '#dc2626', backgroundColor: c.bgSurface, alignItems: 'center', justifyContent: 'center' }}
                >
                  <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeSm, fontWeight: '500' }}>强制终止</Text>
                </Pressable>
              </>
            ) : (
              <Pressable
                onPress={() => power('start', '启动')}
                style={{ flex: 1, height: 46, borderRadius: t.radii.md, backgroundColor: c.success ?? '#16a34a', alignItems: 'center', justifyContent: 'center' }}
              >
                <Text style={{ color: '#ffffff', fontSize: t.typography.sizeSm, fontWeight: '500' }}>启动</Text>
              </Pressable>
            )}
          </View>

          <SectionTitle title="快捷入口" />
          <View style={{ flexDirection: 'row', gap: 8 }}>
            <Card onPress={onOpenOps} style={{ flex: 1, alignItems: 'center', paddingVertical: 14 }}>
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>备份</Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>最近归档</Text>
            </Card>
            <Card onPress={onOpenOps} style={{ flex: 1, alignItems: 'center', paddingVertical: 14 }}>
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>计划任务</Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>定时运维</Text>
            </Card>
          </View>
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
  const audit = useResource<{ records?: { id?: string; action?: string; targetName?: string; at?: number | string }[] }>(
    () => sdk.api.request(`${API}/admin/audit?page=1&size=3`),
    [instanceId],
  );
  const bks = backups.data?.backups ?? [];

  const auditActionText: Record<string, string> = {
    'backup.create': '创建备份',
    'instance.start': '启动实例',
    'instance.stop': '停止实例',
    'instance.restart': '重启实例',
    'instance.kill': '强制终止',
    'ftp.close': '关闭 FTP',
    'ftp.open': '开启 FTP',
    'file.upload': '上传文件',
  };

  const createBackup = () => {
    void sdk.api
      .request(`${API}/admin/instances/${encodeURIComponent(instanceId)}/backups`, { method: 'POST' })
      .then(() => {
        Alert.alert('已触发', '备份任务已创建，完成后可在列表查看');
        backups.reload();
      })
      .catch((e) => Alert.alert('备份失败', e instanceof Error ? e.message : String(e)));
  };

  return (
    <Screen>
      {backups.loading ? <Loading /> : null}
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
        {(schedules.data?.records ?? []).map((sc, i, arr) => (
          <View key={sc.id}>
            {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 10 }}>
              <View style={{ flex: 1, gap: 1 }}>
                <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>{sc.name}</Text>
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                  {sc.cron || sc.dailyTime || ''}{sc.nextRunAt ? ` · 下次 ${new Date(Number(sc.nextRunAt)).toLocaleString()}` : ''}
                </Text>
              </View>
              {sc.enabled ? <Badge text="启用" tone="success" /> : <Badge text="停用" />}
            </View>
          </View>
        ))}
      </Card>
      ) : null}

      <SectionTitle title="最近审计" />
      {audit.loading ? <Loading /> : null}
      {(audit.data?.records ?? []).length === 0 && !audit.loading ? <Empty text="暂无审计记录" /> : null}
      {(audit.data?.records ?? []).length > 0 ? (
        <Card>
          {(audit.data?.records ?? []).map((a, i, arr) => (
            <View key={a.id ?? i}>
              {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
              <View style={{ gap: 1, paddingVertical: 10 }}>
                <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>
                  {auditActionText[a.action ?? ''] ?? a.action}{a.targetName ? ` · ${a.targetName}` : ''}
                </Text>
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                  {a.at ? new Date(Number(a.at)).toLocaleString() : ''}
                </Text>
              </View>
            </View>
          ))}
        </Card>
      ) : null}
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ =
  | { name: 'overview' }
  // 判别字段与载荷字段刻意错开（title）：shorthand 的 name 会覆盖判别键 name
  | { name: 'instance'; id: string; title: string }
  | { name: 'ops'; id: string; title: string };

/**
 * 应用内路由（宿主 route 参数 / 深链）：`instance/{id}/{name}`、`ops/{id}/{name}`，
 * 缺省回落总览。id/name 先解码再截取，name 可省略。
 */
function parseRoute(route?: string): View_ {
  if (!route) return { name: 'overview' };
  const [head, ...rest] = route.split('/');
  const dec = (s?: string) => {
    try { return decodeURIComponent(s ?? ''); } catch { return s ?? ''; }
  };
  if (head === 'instance' && rest[0]) return { name: 'instance', id: dec(rest[0]), title: dec(rest[1]) };
  if (head === 'ops') return { name: 'ops', id: dec(rest[0]), title: dec(rest[1]) || '全部实例' };
  return { name: 'overview' };
}

function McpanelApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<View_>(() => parseRoute(initialRoute));
  const [opsCtx, setOpsCtx] = useState(() => {
    const r = parseRoute(initialRoute);
    return r.name === 'overview' ? { id: '', name: '' } : { id: r.id, name: r.title };
  });
  const theme = currentSdk!.theme;

  useEffect(() => {
    console.log('[mcpanel-debug] render view', view.name);
    const titles = { overview: '运维面板', instance: '实例运维', ops: '备份与任务' };
    const title = view.name === 'instance' ? (view.title || titles.instance) : titles[view.name];
    currentSdk?.navigation?.setTitle(title);
    // 子页接管宿主返回键为应用内返回，总览恢复默认退出
    currentSdk?.navigation?.setBackAction?.(view.name === 'overview' ? null : () => setView({ name: 'overview' }));
  }, [view]);

  const openInstance = (id: string, name: string) => {
    console.log('[mcpanel-debug] openInstance', id, name);
    setOpsCtx({ id, name });
    setView({ name: 'instance', id, title: name });
  };
  const openOps = (id: string, name: string) => {
    setOpsCtx({ id, name });
    setView({ name: 'ops', id, title: name });
  };

  const themed = (node: React.ReactNode) => (
    <UiProvider sdk={currentSdk!}>
      <ViewBoundary>
        <ScrollView style={{ flex: 1, backgroundColor: theme.colors.bgPage }}>
          <View style={{ padding: 16 }}>{node}</View>
        </ScrollView>
      </ViewBoundary>
    </UiProvider>
  );
  if (view.name === 'instance') {
    return themed(
      <InstancePage
        instanceId={view.id}
        name={view.title}
        onBack={() => setView({ name: 'overview' })}
        onOpenOps={() => openOps(view.id, view.title)}
      />,
    );
  }
  if (view.name === 'ops') {
    return themed(<OpsPage instanceId={opsCtx.id} instanceName={opsCtx.name} onBack={() => setView({ name: 'overview' })} />);
  }
  return (
    <UiProvider sdk={currentSdk!}>
      <Overview onOpenInstance={openInstance} onOpenOps={() => openOps(opsCtx.id, opsCtx.name || '全部实例')} />
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
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <McpanelApp initialRoute={route} />
      </UiProvider>
    );
  },
};

export default McpanelModule;
