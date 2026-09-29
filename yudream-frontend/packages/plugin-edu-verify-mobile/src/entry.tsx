/**
 * edu-verify 移动端（设计稿 verifyAdmin）：
 * 学历认证审批——待审列表（申请人/类型/材料数）、通过/驳回（写审计、通知申请人）。
 * 数据走 /api/plugins/edu-verify/admin/verifications**；时间为字符串（后端已 String.valueOf）；
 * 视觉经 sdk.theme 与 plugin-mobile-ui，不写死色值。
 */
import React, { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Avatar, Badge, Card, Chip, Empty, Loading, Screen, SectionTitle, UiProvider, useResource,,
  uiAlert,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/edu-verify';

interface VerificationRow {
  id: string;
  email?: string;
  channel?: string;
  channelName?: string;
  status?: string;
  statusName?: string;
  realName?: string;
  schoolName?: string;
  submittedAt?: string;
  materialCount?: number;
}

const STATUS_TONE: Record<string, 'default' | 'accent' | 'success' | 'warning' | 'danger'> = {
  PENDING: 'warning',
  PASSED: 'success',
  APPROVED: 'success',
  REJECTED: 'danger',
  REVOKED: 'default',
};

function fmtDate(v?: string | number | null): string {
  const n = Number(v);
  if (!v || !Number.isFinite(n) || n <= 0) return '';
  const d = new Date(n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function ReviewPage() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [statusFilter, setStatusFilter] = useState('PENDING');
  const verifications = useResource<{ records?: VerificationRow[]; total?: number }>(
    () => sdk.api.request(`${API}/admin/verifications?status=${statusFilter}&page=1&size=20`),
    [sdk, statusFilter],
  );
  const list = verifications.data?.records ?? [];

  const review = (row: VerificationRow, accept: boolean) => {
    const body = accept
      ? { realName: row.realName ?? '', schoolName: row.schoolName ?? '' }
      : { reason: '移动端驳回' };
    uiAlert(accept ? '通过认证' : '驳回认证', `${accept ? '通过' : '驳回'}「${row.realName || row.email}」的${row.channelName || '学历'}认证？`, [
      { text: '取消', style: 'cancel' },
      {
        text: accept ? '通过' : '驳回',
        style: accept ? 'default' : 'destructive',
        onPress: () => {
          void sdk.api
            .request(`${API}/admin/verifications/${encodeURIComponent(row.id)}/${accept ? 'approve' : 'reject'}`, {
              method: 'POST',
              body,
            })
            .then(() => verifications.reload())
            .catch((e) => uiAlert('操作失败', e instanceof Error ? e.message : String(e)));
        },
      },
    ]);
  };

  return (
    <Screen>
      <View style={{ flexDirection: 'row' }}>
        <Chip label="待审核" active={statusFilter === 'PENDING'} onPress={() => setStatusFilter('PENDING')} />
        <Chip label="已通过" active={statusFilter === 'PASSED'} onPress={() => setStatusFilter('PASSED')} />
        <Chip label="已驳回" active={statusFilter === 'REJECTED'} onPress={() => setStatusFilter('REJECTED')} />
      </View>

      {verifications.loading ? <Loading /> : null}
      {!verifications.loading && list.length === 0 ? <Empty text={statusFilter === 'PENDING' ? '暂无待审申请' : '暂无记录'} /> : null}

      {list.map((q) => {
        const pending = q.status === 'PENDING';
        return (
          <Card key={q.id}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
              <Avatar uri={null} name={q.realName || q.email || '?'} size={34} />
              <View style={{ flex: 1, gap: 1 }}>
                <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700' }}>
                  {q.realName || q.email}
                </Text>
                <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
                  {q.channelName || '学历认证'}{q.schoolName ? ` · ${q.schoolName}` : ''}
                </Text>
              </View>
              <Badge text={q.statusName || q.status || ''} tone={STATUS_TONE[q.status ?? ''] ?? 'default'} />
            </View>
            <View style={{ borderRadius: t.radii.sm, backgroundColor: c.fillHover, padding: 12, alignItems: 'center', gap: 3 }}>
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeXs + 1, fontWeight: '500' }}>
                📄 毕业证书等 {q.materialCount ?? 0} 份材料
              </Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>点击查看原件</Text>
            </View>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs, flex: 1 }} numberOfLines={1}>
                提交于 {fmtDate(q.submittedAt) || '—'}
              </Text>
              {pending ? (
                <>
                  <Pressable
                    onPress={() => review(q, false)}
                    style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 9, borderWidth: 1, borderColor: c.danger ?? '#dc2626', backgroundColor: c.bgSurface }}
                  >
                    <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeXs, fontWeight: '500' }}>✕ 驳回</Text>
                  </Pressable>
                  <Pressable
                    onPress={() => review(q, true)}
                    style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 9, backgroundColor: c.success ?? '#16a34a' }}
                  >
                    <Text style={{ color: '#ffffff', fontSize: t.typography.sizeXs, fontWeight: '500' }}>✓ 通过</Text>
                  </Pressable>
                </>
              ) : null}
            </View>
          </Card>
        );
      })}
    </Screen>
  );
}

const VerifyModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('edu-verify: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <ReviewPage key={route ?? 'default'} />
      </UiProvider>
    );
  },
};

export default VerifyModule;
