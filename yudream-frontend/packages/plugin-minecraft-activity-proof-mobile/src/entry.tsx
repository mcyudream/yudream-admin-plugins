/**
 * minecraft-activity-proof 移动端（设计稿 activitySquare / activityDetail）：
 * 活动广场（活动卡 + 报名）与活动详情（时间/名额 + 在线答题/参与证明任务）。
 * 数据走 /api/plugins/minecraft-activity-proof/me/activities**；视觉经 sdk.theme 与
 * plugin-mobile-ui，不写死色值。
 */
import React, { useEffect, useState } from 'react';
import { Alert, Image, Pressable, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, Loading, ProgressBar, Screen, SectionTitle,
  UiProvider, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/minecraft-activity-proof';

interface ActivityRow {
  id: string;
  title: string;
  summary?: string;
  description?: string;
  coverUrl?: string | null;
  signupStart?: number | string | null;
  signupEnd?: number | string | null;
  activityStart?: number | string | null;
  activityEnd?: number | string | null;
  status?: string;
  participantCount?: number;
  eligible?: boolean;
  participationStatus?: string;
}

function num(v: unknown): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}
function fmtRange(a?: number | string | null, b?: number | string | null): string {
  const fmt = (v: unknown) => {
    const n = num(v);
    if (!n) return '';
    const d = new Date(n);
    return `${d.getMonth() + 1}.${d.getDate()}`;
  };
  const s = fmt(a);
  const e = fmt(b);
  return s && e ? `${s} – ${e}` : s || e || '时间待定';
}

/* ---------------- 活动广场 ---------------- */

function ActivitySquare({ onOpen }: { onOpen: (a: ActivityRow) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const acts = useResource<{ records?: ActivityRow[]; total?: number }>(
    () => sdk.api.request(`${API}/me/activities?page=1&size=20`),
    [sdk],
  );
  const list = acts.data?.records ?? [];

  const signup = (a: ActivityRow) => {
    void sdk.api
      .request(`${API}/me/activities/${encodeURIComponent(a.id)}/join`, { method: 'POST' })
      .then(() => acts.reload())
      .catch((e) => Alert.alert('报名失败', e instanceof Error ? e.message : String(e)));
  };
  const cancelSignup = (a: ActivityRow) => {
    Alert.alert('取消报名', `取消「${a.title}」的报名？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '确认取消', style: 'destructive',
        onPress: () => {
          void sdk.api
            .request(`${API}/me/activities/${encodeURIComponent(a.id)}/cancel`, { method: 'POST' })
            .then(() => acts.reload())
            .catch((e) => Alert.alert('取消失败', e instanceof Error ? e.message : String(e)));
        },
      },
    ]);
  };

  return (
    <Screen>
      {acts.loading ? <Loading /> : null}
      {!acts.loading && list.length === 0 ? <Empty text="暂无活动" /> : null}
      {list.map((a) => {
        const open = a.participationStatus === 'JOINED' || a.participationStatus === 'ATTENDED';
        const signupOpen = a.status === 'SIGNUP' || a.status === 'ONGOING';
        return (
          <Card key={a.id} onPress={() => onOpen(a)}>
            <View style={{ flexDirection: 'row', gap: 10 }}>
              {a.coverUrl ? (
                <Image
                  source={{ uri: `${sdk.baseUrl}${a.coverUrl}` }}
                  style={{ width: 74, height: 64, borderRadius: 10, backgroundColor: c.fillHover }}
                  resizeMode="cover"
                />
              ) : (
                <View style={{ width: 74, height: 64, borderRadius: 10, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
                  <Text style={{ color: c.textTertiary, fontSize: 18 }}>🎮</Text>
                </View>
              )}
              <View style={{ flex: 1, gap: 4 }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
                    {a.title}
                  </Text>
                  <Badge text={signupOpen ? '报名中' : '已结束'} tone={signupOpen ? 'success' : 'default'} />
                </View>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
                  <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>📅</Text>
                  <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs, flex: 1 }}>
                    {fmtRange(a.activityStart, a.activityEnd)}
                  </Text>
                </View>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  <ProgressBar fraction={(a.participantCount ?? 0) / Math.max(a.participantCount ?? 0, 1) * (a.participantCount ? 1 : 0)} tone={signupOpen ? 'success' : 'default'} height={4} />
                  <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs, fontWeight: '500' }}>
                    {a.participantCount ?? 0} 人
                  </Text>
                </View>
              </View>
            </View>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              <Badge text="在线答题" />
              <Badge text="盖章证明" />
              <View style={{ flex: 1 }} />
              {open ? (
                <Pressable
                  onPress={() => cancelSignup(a)}
                  style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 9, backgroundColor: c.fillHover }}
                >
                  <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs, fontWeight: '500' }}>已报名</Text>
                </Pressable>
              ) : signupOpen ? (
                <Pressable
                  onPress={() => signup(a)}
                  style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 9, backgroundColor: c.accent }}
                >
                  <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>立即报名</Text>
                </Pressable>
              ) : (
                <Badge text="已结束" />
              )}
            </View>
          </Card>
        );
      })}
    </Screen>
  );
}

/* ---------------- 活动详情 ---------------- */

function ActivityDetail({ activity }: { activity: ActivityRow }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const detail = useResource<ActivityRow>(
    () => sdk.api.request(`${API}/me/activities/${encodeURIComponent(activity.id)}`),
    [activity.id],
  );
  const a = detail.data ?? activity;
  const [busy, setBusy] = useState(false);
  const joined = a.participationStatus === 'JOINED' || a.participationStatus === 'ATTENDED';

  const signup = () => {
    setBusy(true);
    void sdk.api
      .request(`${API}/me/activities/${encodeURIComponent(a.id)}/join`, { method: 'POST' })
      .then(() => { setBusy(false); detail.reload(); })
      .catch((e) => { setBusy(false); Alert.alert('报名失败', e instanceof Error ? e.message : String(e)); });
  };

  return (
    <Screen>
      {a.coverUrl ? (
        <Image
          source={{ uri: `${sdk.baseUrl}${a.coverUrl}` }}
          style={{ width: '100%', height: 120, borderRadius: t.radii.lg, backgroundColor: t.colors.fillHover }}
          resizeMode="cover"
        />
      ) : null}
      <View style={{ gap: 5 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: t.colors.textPrimary, fontSize: t.typography.sizeXl, fontWeight: '700', flexShrink: 1 }}>
            {a.title}
          </Text>
          <Badge text={a.status === 'SIGNUP' || a.status === 'ONGOING' ? '报名中' : '已结束'} tone={a.status === 'SIGNUP' || a.status === 'ONGOING' ? 'success' : 'default'} />
        </View>
        <View style={{ flexDirection: 'row', gap: 12 }}>
          <Text style={{ color: t.colors.textSecondary, fontSize: t.typography.sizeXs + 1 }}>📅 {fmtRange(a.activityStart, a.activityEnd)}</Text>
          <Text style={{ color: t.colors.textSecondary, fontSize: t.typography.sizeXs + 1 }}>👥 已报名 {a.participantCount ?? 0}</Text>
        </View>
      </View>

      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <View style={{ flex: 1, gap: 2 }}>
            <Text style={{ color: t.colors.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700' }}>
              {joined ? '已报名' : `已报名 ${a.participantCount ?? 0} 人`}
            </Text>
            <Text style={{ color: t.colors.textTertiary, fontSize: t.typography.sizeXs }}>
              {joined ? '可在下方完成任务领取证明' : '报名后可参与在线答题'}
            </Text>
          </View>
          {!joined && (a.status === 'SIGNUP' || a.status === 'ONGOING') ? (
            <Pressable
              onPress={signup}
              disabled={busy}
              style={{ paddingHorizontal: 16, paddingVertical: 9, borderRadius: 10, backgroundColor: busy ? t.colors.fillHover : t.colors.accent }}
            >
              <Text style={{ color: t.colors.onAccent, fontSize: t.typography.sizeSm, fontWeight: '500' }}>立即报名</Text>
            </Pressable>
          ) : null}
        </View>
      </Card>

      <SectionTitle title="活动任务" actionText="完成可领证明" />
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 6 }}>
          <View style={{ width: 34, height: 34, borderRadius: t.radii.sm, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ color: c.textPrimary, fontSize: 14 }}>✍</Text>
          </View>
          <View style={{ flex: 1, gap: 1 }}>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>在线答题</Text>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>报名后在活动期内作答</Text>
          </View>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeLg }}>{'›'}</Text>
        </View>
        <View style={{ height: 1, backgroundColor: c.borderSubtle }} />
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 6 }}>
          <View style={{ width: 34, height: 34, borderRadius: t.radii.sm, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
            <Text style={{ color: c.textPrimary, fontSize: 14 }}>📄</Text>
          </View>
          <View style={{ flex: 1, gap: 1 }}>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>参与证明</Text>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>活动结束后可下载盖章 PDF</Text>
          </View>
        </View>
      </Card>

      {a.description ? (
        <Text style={{ color: t.colors.textSecondary, fontSize: t.typography.sizeSm, lineHeight: 21 }}>
          {a.description}
        </Text>
      ) : null}
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ = { name: 'square' } | { name: 'detail'; activity: ActivityRow };

function ActivityApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<View_>({ name: 'square' });
  useEffect(() => {
    currentSdk?.navigation?.setTitle(view.name === 'square' ? '活动广场' : '活动详情');
    currentSdk?.navigation?.setBackAction?.(view.name === 'square' ? null : () => setView({ name: 'square' }));
  }, [view, initialRoute]);
  return view.name === 'square' ? (
    <ActivitySquare onOpen={(a) => setView({ name: 'detail', activity: a })} />
  ) : (
    <ActivityDetail activity={view.activity} />
  );
}

const ActivityModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('minecraft-activity-proof: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <ActivityApp initialRoute={route} />
      </UiProvider>
    );
  },
};

function UiProvider({ sdk, children }: { sdk: PluginMobileSdk; children: React.ReactNode }) {
  const { UiProvider: Provider } = require('@yudream/plugin-mobile-ui');
  return <Provider sdk={sdk}>{children}</Provider>;
}

export default ActivityModule;
