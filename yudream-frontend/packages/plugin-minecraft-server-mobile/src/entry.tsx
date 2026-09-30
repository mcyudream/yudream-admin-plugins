/**
 * minecraft-server 移动端（设计稿 serverList / serverDetail）：
 * 服务器列表（状态点/人数进度/版本徽章/周目）与服务器详情（状态卡 + 在线趋势 +
 * 信息行）。数据走 /api/plugins/minecraft-server/servers**；
 * 视觉经 sdk.theme 与 plugin-mobile-ui，不写死色值。
 */
import React, { useEffect, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, InfoRows, Loading, Screen, SectionTitle,
  UiProvider, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/minecraft-server';

interface ServerEndpoint {
  id: string;
  name?: string;
  host?: string;
  port?: number;
  status?: string;
  onlinePlayers?: number;
  maxPlayers?: number;
  versionName?: string;
}
interface ServerRow {
  id: string;
  name: string;
  descriptionMarkdown?: string;
  enabled?: boolean;
  endpoints?: ServerEndpoint[];
  seasons?: { id: string; name: string; current?: boolean }[];
  currentSeason?: { name?: string };
  status?: {
    status?: string;
    onlinePlayers?: number;
    maxPlayers?: number;
    checkedAt?: number;
  } | null;
  map?: { publicAccess?: boolean } | null;
}

function online(s?: ServerRow): boolean {
  return s?.status?.status === 'ONLINE';
}
function primaryEndpoint(s?: ServerRow): ServerEndpoint | undefined {
  const eps = s?.endpoints ?? [];
  return eps.find((e) => e.primaryLine) ?? eps[0];
}

/* ---------------- 服务器列表 ---------------- */

function ServerList({ onOpen }: { onOpen: (s: ServerRow) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const servers = useResource<{ records?: ServerRow[]; total?: number }>(
    () => sdk.api.request(`${API}/servers?page=1&size=20`),
    [sdk],
  );
  const list = servers.data?.records ?? [];
  const totalOnline = list.reduce((n, s) => n + (s.status?.onlinePlayers ?? 0), 0);

  return (
    <Screen>
      <View style={{ flexDirection: 'row', alignItems: 'center' }}>
        <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeXl, fontWeight: '700', flex: 1 }}>
          MC 服务器
        </Text>
      </View>
      <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1, marginTop: -t.spacing.md + 2 }}>
        {list.length} 台服务器 · 共 {totalOnline} 人在线
      </Text>

      {servers.loading ? <Loading /> : null}
      {!servers.loading && list.length === 0 ? <Empty text="暂无服务器" /> : null}

      {list.map((s) => {
        const on = online(s);
        const ep = primaryEndpoint(s);
        const op = s.status?.onlinePlayers ?? 0;
        const mp = s.status?.maxPlayers ?? 0;
        const season = s.seasons?.find((x) => x.current) ?? s.seasons?.[0];
        const version = ep?.versionName ?? '';
        return (
          <Card key={s.id} onPress={() => onOpen(s)}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              <View style={{ width: 9, height: 9, borderRadius: 5, backgroundColor: on ? (c.success ?? '#16a34a') : c.textTertiary }} />
              <View style={{ flex: 1, gap: 1 }}>
                <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeMd, fontWeight: '700' }}>
                  {s.name}
                </Text>
                <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
                  {ep?.host ?? ''}
                </Text>
              </View>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeLg }}>{'›'}</Text>
            </View>
            {on ? (
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <View style={{ flex: 1, height: 5, borderRadius: 3, backgroundColor: c.fillHover, overflow: 'hidden' }}>
                  <View
                    style={{
                      width: `${mp > 0 ? Math.min(100, Math.round((op / mp) * 100)) : 0}%`,
                      height: 5, backgroundColor: c.success ?? '#16a34a',
                    }}
                  />
                </View>
                <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, fontWeight: '500' }}>
                  {op} / {mp || '?'}
                </Text>
              </View>
            ) : (
              <Badge text="离线 · 等待开启" />
            )}
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
              {ep?.versionName ? <Badge text={ep.versionName} /> : null}
              {on && s.status?.ping != null ? <Badge text={`${s.status.ping}ms`} /> : null}
              <View style={{ flex: 1 }} />
              {season?.name ? <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{season.name}</Text> : null}
            </View>
          </Card>
        );
      })}
    </Screen>
  );
}

/* ---------------- 服务器详情 ---------------- */

function ServerDetail({ server }: { server: ServerRow }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const detail = useResource<ServerRow>(
    () => sdk.api.request(`${API}/servers/${encodeURIComponent(server.id)}`),
    [server.id],
  );
  const history = useResource<{ onlinePlayers?: number; checkedAt?: number }[]>(
    () => sdk.api.request(`${API}/servers/${encodeURIComponent(server.id)}/status/history?limit=12`),
    [server.id],
  );
  const s = detail.data ?? server;
  const on = online(s);
  const ep = s.endpoints?.find((e) => e.primaryLine) ?? s.endpoints?.[0];
  const season = s.seasons?.find((x) => x.current) ?? s.seasons?.[0];
  const hist = (history.data ?? []).slice(-12);
  const maxPlayers = s.status?.maxPlayers ?? 0;

  return (
    <Screen>
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <View style={{ width: 10, height: 10, borderRadius: 5, backgroundColor: on ? (c.success ?? '#16a34a') : c.textTertiary }} />
          <View style={{ flex: 1, gap: 1 }}>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd, fontWeight: '700' }}>
              {on ? `在线 · ${s.status?.onlinePlayers ?? 0} 人` : '离线'}
            </Text>
            <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
              {ep?.versionName || ''} · {ep?.host ?? ''}
            </Text>
          </View>
        </View>
      </Card>

      <SectionTitle title="在线玩家" actionText={`${s.status?.onlinePlayers ?? 0} / ${s.status?.maxPlayers ?? 0}`} />
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <View style={{ width: 34, height: 34, borderRadius: 17, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ color: c.textPrimary, fontSize: 14 }}>👤</Text>
          </View>
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, flex: 1 }}>
            当前 {s.status?.onlinePlayers ?? 0} 人在线{maxPlayers > 0 ? `，容量 ${maxPlayers}` : ''}
          </Text>
        </View>
      </Card>

      <SectionTitle title="在线趋势 · 近 12 次探测" />
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'flex-end', gap: 3, height: 64 }}>
          {hist.length === 0 ? (
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>暂无历史数据</Text>
          ) : (
            hist.map((h, i) => {
              const maxOp = Math.max(...hist.map((x) => x.onlinePlayers ?? 0), 1);
              const hRatio = (h.onlinePlayers ?? 0) / maxOp;
              return (
                <View key={i} style={{ flex: 1, height: '100%', justifyContent: 'flex-end' }}>
                  <View
                    style={{
                      height: `${Math.max(8, Math.round(hRatio * 100))}%`,
                      backgroundColor: c.textPrimary ?? '#0a0a0a', borderRadius: 2,
                    }}
                  />
                </View>
              );
            })
          )}
        </View>
      </Card>

      <InfoRows rows={[
        ['周目', season?.name || '—'],
        ['线路', ep ? `${ep.host}:${ep.port ?? 25565}` : '—'],
        ['版本', ep?.versionName || '—'],
        ['地图', s.map?.publicAccess ? '已公开' : '未公开'],
      ]} />
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ = { name: 'list' } | { name: 'detail'; server: ServerRow };

function ServerApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<View_>({ name: 'list' });
  useEffect(() => {
    currentSdk?.navigation?.setTitle(view.name === 'list' ? 'MC 服务器' : '服务器详情');
    currentSdk?.navigation?.setBackAction?.(view.name === 'list' ? null : () => setView({ name: 'list' }));
  }, [view, initialRoute]);
  return view.name === 'list' ? (
    <ServerList onOpen={(s) => setView({ name: 'detail', server: s })} />
  ) : (
    <ServerDetail server={view.server} />
  );
}

const ServerModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('minecraft-server: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <ServerApp initialRoute={route} />
      </UiProvider>
    );
  },
};

export default ServerModule;
