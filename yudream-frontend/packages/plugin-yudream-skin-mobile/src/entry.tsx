/**
 * 皮肤站移动端入口：我的资料 + 我的角色（用户侧 /me 系列接口）。
 * 数据经宿主注入的 sdk.api.request（指向 /api/plugins/yudream-skin/**）。
 * 皮肤库/衣柜的移动版页面在后续迭代补充。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, FlatList, RefreshControl, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';

interface SkinMe {
  username?: string;
  nickname?: string;
  email?: string;
}

interface SkinPlayer {
  name: string;
  default?: boolean;
  textureType?: string;
}

let currentSdk: PluginMobileSdk | null = null;

function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

function SkinHome() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [me, setMe] = useState<SkinMe | null>(null);
  const [players, setPlayers] = useState<SkinPlayer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const meRes = await sdk.api.request<SkinMe | { data?: SkinMe }>('/api/plugins/yudream-skin/me');
      // 站内响应是 Result 信封，业务数据在 data 字段
      const envelope = meRes as { data?: SkinMe } | null;
      setMe(Array.isArray(meRes) ? null : (envelope?.data ?? (meRes as SkinMe)));
      const playersRes = await sdk.api.request<SkinPlayer[] | { items?: SkinPlayer[]; records?: SkinPlayer[] }>(
        '/api/plugins/yudream-skin/me/players',
      );
      setPlayers(Array.isArray(playersRes) ? playersRes : (playersRes.items ?? playersRes.records ?? []));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [sdk]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <View style={{ flex: 1, backgroundColor: c.bgPage, padding: t.spacing.md, gap: t.spacing.md }}>
      <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeXl ?? 20, fontWeight: '700' }}>
        皮肤站
      </Text>

      {loading ? (
        <ActivityIndicator color={c.accent} style={{ marginTop: t.spacing.xl }} />
      ) : error ? (
        <Text style={{ color: c.danger }}>{error}</Text>
      ) : (
        <>
          <View
            style={{
              backgroundColor: c.bgSurface,
              borderRadius: t.radii.lg,
              padding: t.spacing.md,
              borderWidth: 1,
              borderColor: c.borderSubtle,
              gap: 4,
            }}
          >
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd ?? 15 }}>
              {me?.nickname || me?.username || '未登录'}
            </Text>
            {me?.email ? (
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm ?? 13 }}>{me.email}</Text>
            ) : null}
          </View>

          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm ?? 13 }}>我的角色</Text>
          <FlatList
            data={players}
            keyExtractor={(item) => item.name}
            contentContainerStyle={{ gap: t.spacing.sm }}
            refreshControl={
              <RefreshControl refreshing={loading} onRefresh={load} tintColor={c.accent} />
            }
            renderItem={({ item }) => (
              <View
                style={{
                  backgroundColor: c.bgSurface,
                  borderRadius: t.radii.lg,
                  padding: t.spacing.md,
                  borderWidth: 1,
                  borderColor: c.borderSubtle,
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd ?? 15 }}>
                  {item.name}
                </Text>
                {item.default ? (
                  <Text style={{ color: c.accent, fontSize: t.typography.sizeSm ?? 13 }}>默认</Text>
                ) : null}
              </View>
            )}
            ListEmptyComponent={
              <Text style={{ color: c.textTertiary, textAlign: 'center', marginTop: t.spacing.lg }}>
                还没有角色
              </Text>
            }
          />
        </>
      )}
    </View>
  );
}

const SkinModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (sdk) {
      currentSdk = sdk;
    }
    return <SkinHome />;
  },
};

export default SkinModule;
