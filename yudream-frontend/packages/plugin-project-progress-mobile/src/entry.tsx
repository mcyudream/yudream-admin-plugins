/**
 * project-progress 移动端（设计稿 progressMine / progressAdmin）：
 * 我的任务（统计 + 打卡 + 任务列表）与验收审批（待验收列表 + 通过/驳回）。
 * 数据走 /api/plugins/project-progress/me/** 与 /acceptance/pending、/details/{id}/accept|reject；
 * 视觉经 sdk.theme 与 plugin-mobile-ui，不写死色值。
 */
import React, { useEffect, useState } from 'react';
import { Alert, Image, Pressable, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, Loading, Screen, SectionTitle, StatTile,
  UiProvider, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/project-progress';

interface TaskRow {
  id: string;
  projectId: string;
  title: string;
  description?: string;
  statusCode?: string;
  pendingAcceptance?: boolean;
  acceptanceSummary?: string;
  acceptanceFiles?: { objectKey?: string; filename?: string; image?: boolean }[];
  dueAt?: number | string | null;
}
interface Stats {
  assignedDetails?: number;
  completedDetails?: number;
  pendingAcceptanceDetails?: number;
  checkIns?: number;
}
interface CheckInRow {
  id: string;
  summary?: string;
  type?: string;
  createdAt?: number | string;
}

function fmtDate(v?: number | string | null): string {
  const n = Number(v);
  if (!v || !Number.isFinite(n) || n <= 0) return '';
  const d = new Date(n);
  return `${d.getMonth() + 1}.${d.getDate()}`;
}

/* ---------------- 我的任务 ---------------- */

function ProgressMine({ onOpenAcceptance }: { onOpenAcceptance: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const stats = useResource<Stats>(() => sdk.api.request(`${API}/me/statistics`), [sdk]);
  const tasks = useResource<TaskRow[]>(
    () => sdk.api
      .request<TaskRow[] | { records?: TaskRow[] }>(`${API}/me/tasks?page=1&size=20`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const checkIns = useResource<CheckInRow[]>(
    () => sdk.api
      .request<CheckInRow[] | { records?: CheckInRow[] }>(`${API}/me/check-ins?page=1&size=5`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const list = tasks.data ?? [];
  const st = stats.data ?? {};

  const doCheckIn = (task: TaskRow) => {
    void sdk.api
      .request(`${API}/me/tasks/${encodeURIComponent(task.id)}/check-ins`, {
        method: 'POST',
        body: { type: 'DAILY', summary: '移动端打卡' },
      })
      .then(() => {
        stats.reload();
        Alert.alert('打卡成功', `「${task.title}」今日打卡完成`);
      })
      .catch((e) => Alert.alert('打卡失败', e instanceof Error ? e.message : String(e)));
  };

  return (
    <Screen>
      <View style={{ flexDirection: 'row', gap: 10 }}>
        <StatTile value={`${st.assignedDetails ?? 0}`} label="进行中" />
        <StatTile value={`${st.completedDetails ?? 0}`} label="已完成" />
        <StatTile value={`${st.checkIns ?? 0}`} label="累计打卡" />
      </View>

      <SectionTitle title="我的任务" actionText={`${list.length} 项`} />
      {tasks.loading ? <Loading /> : null}
      {!tasks.loading && list.length === 0 ? <Empty text="暂无任务" /> : null}
      {list.map((task) => (
        <Card key={task.id}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
              {task.title}
            </Text>
            {task.pendingAcceptance ? <Badge text="待验收" tone="warning" /> : null}
          </View>
          {task.description ? (
            <Text numberOfLines={2} style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, lineHeight: 17 }}>
              {task.description}
            </Text>
          ) : null}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            {task.statusCode ? <Badge text={task.statusCode} /> : null}
            {task.dueAt ? <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>截止 {fmtDate(task.dueAt)}</Text> : null}
            <View style={{ flex: 1 }} />
            {!task.pendingAcceptance ? (
              <Pressable
                onPress={() => doCheckIn(task)}
                style={{ paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8, backgroundColor: c.accent }}
              >
                <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>打卡</Text>
              </Pressable>
            ) : (
              <Badge text="等待管理员验收" tone="accent" />
            )}
          </View>
        </Card>
      ))}

      <SectionTitle title="最近打卡" actionText="验收审批" onAction={onOpenAcceptance} />
      {checkIns.loading ? <Loading /> : null}
      {!checkIns.loading && (checkIns.data ?? []).length === 0 ? <Empty text="还没有打卡记录" /> : null}
      <Card>
        {(checkIns.data ?? []).map((ci, i, arr) => (
          <View key={ci.id}>
            {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 9 }}>
              <View style={{ width: 7, height: 7, borderRadius: 4, backgroundColor: c.success ?? '#16a34a' }} />
              <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, flex: 1 }}>
                {ci.summary || ci.type || '打卡'}
              </Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{fmtDate(ci.createdAt)}</Text>
            </View>
          </View>
        ))}
      </Card>
    </Screen>
  );
}

/* ---------------- 验收审批 ---------------- */

interface PendingRow {
  id?: string;
  detailId?: string;
  title?: string;
  projectName?: string;
  submitterName?: string;
  acceptanceSummary?: string;
  acceptanceFiles?: { objectKey?: string; filename?: string }[];
  createdAt?: number | string;
}

function AcceptanceAdmin() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const pending = useResource<PendingRow[]>(
    () => sdk.api
      .request<PendingRow[] | { records?: PendingRow[] }>(`${API}/acceptance/pending`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const list = pending.data ?? [];

  const review = (row: PendingRow, accept: boolean) => {
    const detailId = row.detailId ?? row.id ?? '';
    Alert.alert(accept ? '通过验收' : '驳回', `${accept ? '通过' : '驳回'}「${row.title || '该任务'}」？`, [
      { text: '取消', style: 'cancel' },
      {
        text: accept ? '通过' : '驳回',
        style: accept ? 'default' : 'destructive',
        onPress: () => {
          void sdk.api
            .request(`${API}/details/${encodeURIComponent(detailId)}/${accept ? 'accept' : 'reject'}`, {
              method: 'POST',
              body: accept ? {} : { reason: '移动端驳回' },
            })
            .then(() => pending.reload())
            .catch((e) => Alert.alert('操作失败', e instanceof Error ? e.message : String(e)));
        },
      },
    ]);
  };

  return (
    <Screen>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <Badge text={`待验收 ${list.length}`} tone="warning" />
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>提交后 48 小时内需处理</Text>
      </View>
      {pending.loading ? <Loading /> : null}
      {!pending.loading && list.length === 0 ? <Empty text="暂无待验收提交" /> : null}
      {list.map((row, i) => {
        const detailId = row.detailId ?? row.id ?? '';
        const files = (row.acceptanceFiles ?? []).slice(0, 2);
        return (
          <Card key={detailId || i}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
              <View style={{ width: 34, height: 34, borderRadius: 17, backgroundColor: c.accent, alignItems: 'center', justifyContent: 'center' }}>
                <Text style={{ color: c.onAccent, fontSize: 13, fontWeight: '700' }}>
                  {(row.title || '任').slice(0, 1)}
                </Text>
              </View>
              <View style={{ flex: 1, gap: 1 }}>
                <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700' }}>
                  {row.title || '任务'}
                </Text>
                <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                  {fmtDate(row.createdAt)}
                </Text>
              </View>
            </View>
            {row.acceptanceSummary ? (
              <Text numberOfLines={2} style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, lineHeight: 17 }}>
                {row.acceptanceSummary}
              </Text>
            ) : null}
            {files.length > 0 ? (
              <View style={{ flexDirection: 'row', gap: 8 }}>
                {files.map((f, fi) => f.objectKey ? (
                  <Image
                    key={fi}
                    source={{ uri: `${sdk.baseUrl}${API}/files/download?objectKey=${encodeURIComponent(f.objectKey)}` }}
                    style={{ width: '47.5%', height: 72, borderRadius: 10, backgroundColor: c.fillHover }}
                    resizeMode="cover"
                  />
                ) : null)}
              </View>
            ) : null}
            <View style={{ flexDirection: 'row', gap: 8, justifyContent: 'flex-end' }}>
              <Pressable
                onPress={() => review(row, false)}
                style={{ paddingHorizontal: 14, paddingVertical: 7, borderRadius: 9, borderWidth: 1, borderColor: c.danger ?? '#dc2626', backgroundColor: c.bgSurface }}
              >
                <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeXs, fontWeight: '500' }}>✕ 驳回</Text>
              </Pressable>
              <Pressable
                onPress={() => review(row, true)}
                style={{ paddingHorizontal: 14, paddingVertical: 7, borderRadius: 9, backgroundColor: c.success ?? '#16a34a' }}
              >
                <Text style={{ color: '#ffffff', fontSize: t.typography.sizeXs, fontWeight: '500' }}>✓ 通过</Text>
              </Pressable>
            </View>
          </Card>
        );
      })}
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ = { name: 'mine' } | { name: 'acceptance' };

function ProgressApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<View_>(initialRoute === '/acceptance' ? { name: 'acceptance' } : { name: 'mine' });
  useEffect(() => {
    currentSdk?.navigation?.setTitle(view.name === 'mine' ? '我的任务' : '验收审批');
    currentSdk?.navigation?.setBackAction?.(view.name === 'mine' ? null : () => setView({ name: 'mine' }));
  }, [view, initialRoute]);
  return view.name === 'mine' ? (
    <View style={{ flex: 1 }}>
      <ProgressMine onOpenAcceptance={() => setView({ name: 'acceptance' })} />
    </View>
  ) : (
    <AcceptanceAdmin />
  );
}

const ProgressModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('project-progress: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <ProgressApp initialRoute={route} />
      </UiProvider>
    );
  },
};

function UiProvider({ sdk, children }: { sdk: PluginMobileSdk; children: React.ReactNode }) {
  const { UiProvider: Provider } = require('@yudream/plugin-mobile-ui');
  return <Provider sdk={sdk}>{children}</Provider>;
}

export default ProgressModule;
