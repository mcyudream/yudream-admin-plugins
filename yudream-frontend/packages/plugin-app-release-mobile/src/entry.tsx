/**
 * app-release 移动端（管理页）：
 * 更新包发布管理——版本列表（发布/下架/删除/下载安装）、强制更新线设置。
 * 上传更新包需在电脑端后台「平台 → 更新发布」操作；本页负责日常发布流转。
 * 数据走 /api/plugins/app-release/**；视觉经 sdk.theme 与 plugin-mobile-ui，不写死色值。
 */
import React, { useState } from 'react';
import { Pressable, Text, TextInput, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, Loading, PrimaryButton, Screen, SectionTitle, UiProvider, useResource,  uiAlert,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/app-release';

interface ReleaseRow {
  id: string;
  platform: string;
  versionCode: number;
  versionName: string;
  changelog: string;
  fileName: string;
  fileSize: number;
  forceUpdate: boolean;
  published: boolean;
  createdAt: number;
  publishedAt: number;
}

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));

function fmtSize(size: number): string {
  if (!size) {
    return '-';
  }
  if (size >= 1024 * 1024) {
    return `${(size / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${Math.max(1, Math.round(size / 1024))} KB`;
}

function fmtTime(ts?: number | string | null): string {
  const n = Number(ts);
  if (!ts || !Number.isFinite(n) || n <= 0) {
    return '';
  }
  const d = new Date(n);
  return `${d.getFullYear()}.${d.getMonth() + 1}.${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function Field({
  value, onChangeText, placeholder, keyboard,
}: {
  value: string;
  onChangeText: (v: string) => void;
  placeholder: string;
  keyboard?: 'default' | 'numeric';
}) {
  const t = useSdk().theme;
  const c = t.colors;
  return (
    <TextInput
      value={value}
      onChangeText={onChangeText}
      placeholder={placeholder}
      placeholderTextColor={c.textTertiary}
      keyboardType={keyboard === 'numeric' ? 'numeric' : 'default'}
      autoCapitalize="none"
      autoCorrect={false}
      style={{
        height: 42,
        borderRadius: t.radii.md,
        borderWidth: 1,
        borderColor: c.borderSubtle,
        backgroundColor: c.bgPage,
        color: c.textPrimary,
        fontSize: t.typography.sizeSm,
        paddingHorizontal: 10,
      }}
    />
  );
}

/** 版本卡：版本名 + versionCode、状态角标、更新日志、文件信息与操作行。 */
function ReleaseCard({ row, onChanged }: { row: ReleaseRow; onChanged: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [busy, setBusy] = useState('');

  const act = (action: 'publish' | 'unpublish') => {
    if (busy) {
      return;
    }
    setBusy(action);
    void sdk.api
      .request(`${API}/admin/releases/${encodeURIComponent(row.id)}/${action}`, { method: 'POST' })
      .then(() => {
        setBusy('');
        onChanged();
        uiAlert(action === 'publish' ? '已发布' : '已下架', action === 'publish' ? '客户端将收到更新提示' : '客户端不再提示该版本');
      })
      .catch((e) => {
        setBusy('');
        uiAlert('操作失败', errText(e));
      });
  };

  const confirmDelete = () => {
    if (busy) {
      return;
    }
    uiAlert('删除版本包', `确定删除 ${row.versionName}（versionCode ${row.versionCode}）？文件将一并删除，不可恢复。`, [
      { text: '取消', style: 'cancel' },
      {
        text: '删除',
        style: 'destructive',
        onPress: () => {
          setBusy('delete');
          void sdk.api
            .request(`${API}/admin/releases/${encodeURIComponent(row.id)}`, { method: 'DELETE' })
            .then(() => {
              setBusy('');
              onChanged();
            })
            .catch((e) => {
              setBusy('');
              uiAlert('删除失败', errText(e));
            });
        },
      },
    ]);
  };

  const download = () => {
    // 匿名下载端点：交给系统浏览器下载 APK（浏览器侧完成安装确认）
    void sdk.deeplink.open(`${sdk.baseUrl}${API}/public/download/${encodeURIComponent(row.id)}`).catch((e) => {
      uiAlert('无法打开下载', errText(e));
    });
  };

  return (
    <Card>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <View style={{ flex: 1, flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd + 1, fontWeight: '700' }}>
            {row.versionName}
          </Text>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
            versionCode {row.versionCode} · {row.platform}
          </Text>
        </View>
        {row.published ? <Badge text="已发布" tone="success" solid /> : <Badge text="未发布" tone="default" />}
        {row.forceUpdate ? <Badge text="强制" tone="danger" solid /> : null}
      </View>
      {row.changelog ? (
        <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, lineHeight: 17 }}>
          {row.changelog}
        </Text>
      ) : (
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>未填写更新日志</Text>
      )}
      <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
        {[row.fileName, fmtSize(row.fileSize), row.published ? fmtTime(row.publishedAt) : '未发布'].filter(Boolean).join(' · ')}
      </Text>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <ActionChip
          label={row.published ? '下架' : '发布'}
          tone={row.published ? 'plain' : 'primary'}
          busy={busy === 'publish' || busy === 'unpublish'}
          onPress={() => act(row.published ? 'unpublish' : 'publish')}
        />
        <ActionChip label="下载安装" tone="plain" onPress={download} />
        <View style={{ flex: 1 }} />
        <ActionChip label="删除" tone="danger" busy={busy === 'delete'} onPress={confirmDelete} />
      </View>
    </Card>
  );
}

function ActionChip({ label, tone, onPress, busy }: {
  label: string;
  tone: 'primary' | 'plain' | 'danger';
  onPress: () => void;
  busy?: boolean;
}) {
  const t = useSdk().theme;
  const c = t.colors;
  const styles: Record<string, { bg: string; fg: string; border?: string }> = {
    primary: { bg: c.accent, fg: c.onAccent },
    plain: { bg: c.bgSurface, fg: c.textSecondary, border: c.borderSubtle },
    danger: { bg: c.bgSurface, fg: c.danger ?? '#dc2626', border: c.danger ?? '#dc2626' },
  };
  const style = styles[tone]!;
  return (
    <Pressable
      onPress={onPress}
      disabled={busy}
      style={{
        paddingHorizontal: 14,
        paddingVertical: 7,
        borderRadius: 9,
        backgroundColor: style.bg,
        borderWidth: style.border ? 1 : 0,
        borderColor: style.border,
        opacity: busy ? 0.5 : 1,
      }}
    >
      <Text style={{ color: style.fg, fontSize: t.typography.sizeXs, fontWeight: '500' }}>{label}</Text>
    </Pressable>
  );
}

function AdminPage() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const releases = useResource<ReleaseRow[]>(
    () => sdk.api
      .request<ReleaseRow[] | { records?: ReleaseRow[] }>(`${API}/admin/releases`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const settings = useResource<{ minVersionCode: number }>(() => sdk.api.request(`${API}/admin/settings`), [sdk]);
  const [minVersion, setMinVersion] = useState('');
  const [saving, setSaving] = useState(false);
  const [settingsLoaded, setSettingsLoaded] = useState(false);
  const list = releases.data ?? [];

  if (!settingsLoaded && settings.data) {
    setSettingsLoaded(true);
    setMinVersion(String(settings.data.minVersionCode ?? 0));
  }

  const saveSettings = () => {
    const parsed = Math.max(0, Math.round(Number(minVersion) || 0));
    setSaving(true);
    void sdk.api
      .request(`${API}/admin/settings`, { method: 'PUT', body: { minVersionCode: parsed } })
      .then(() => {
        setSaving(false);
        uiAlert('已保存', parsed > 0 ? `低于 versionCode ${parsed} 的客户端将强制更新` : '已关闭强制更新线');
      })
      .catch((e) => {
        setSaving(false);
        uiAlert('保存失败', errText(e));
      });
  };

  const latestPublished = list.filter((r) => r.published).reduce((max, r) => Math.max(max, r.versionCode), 0);

  return (
    <Screen>
      <Card>
        <SectionTitle title="强制更新线" />
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs, lineHeight: 16 }}>
          低于该 versionCode 的客户端必须更新后才能继续使用（0 = 不启用）。
        </Text>
        <View style={{ flexDirection: 'row', gap: 8, alignItems: 'center' }}>
          <View style={{ width: 120 }}>
            <Field value={minVersion} onChangeText={setMinVersion} placeholder="0" keyboard="numeric" />
          </View>
          <View style={{ flex: 1 }}>
            <PrimaryButton title="保存设置" busy={saving} onPress={saveSettings} height={42} />
          </View>
        </View>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
          最新已发布 versionCode {latestPublished}
          {'\n'}上传更新包请在电脑端后台「平台 → 更新发布」操作
        </Text>
      </Card>

      <SectionTitle title={`版本列表（${list.length}）`} />
      {releases.loading ? <Loading /> : null}
      {!releases.loading && list.length === 0 ? <Empty text="暂无版本包，请在电脑端上传 APK" /> : null}
      {list.map((row) => (
        <ReleaseCard key={row.id} row={row} onChanged={releases.reload} />
      ))}
    </Screen>
  );
}

function AppReleaseApp({ initialRoute }: { initialRoute?: string }) {
  void initialRoute;
  return <AdminPage />;
}

const AppReleaseModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('app-release: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <AppReleaseApp initialRoute={route} />
      </UiProvider>
    );
  },
};

export default AppReleaseModule;
