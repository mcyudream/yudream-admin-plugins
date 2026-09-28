/**
 * yudream-skin 移动端（设计稿 skinHome / skinCloset）：
 * 我的角色（角色列表 + 设默认 + 换肤入口 + CSL 接口）与衣柜材质（材质/衣柜双页签，
 * 应用到角色、删除）。数据走 /api/plugins/yudream-skin/me/**，皮肤预览图用公开
 * /textures/{hash}；视觉经 sdk.theme 与 plugin-mobile-ui，不写死色值。
 */
import React, { useEffect, useState } from 'react';
import { Alert, Image, Pressable, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Chip, Empty, Loading, Screen, Tile, UiProvider, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/yudream-skin';

interface SkinPlayer {
  uuid: string;
  name: string;
  skinHash: string | null;
  capeHash: string | null;
  lastModified: number;
}
interface SkinTexture {
  hash: string;
  name: string;
  type: string; // skin | cape
  model?: string;
  size?: number;
  uploadedAt?: number;
}
interface ClosetItem {
  id: string;
  textureHash: string;
  itemName: string;
  createdAt: number;
}

function textureUrl(hash: string | null | undefined): string {
  if (!hash) return '';
  return `${currentSdk?.baseUrl ?? ''}${API}/textures/${hash}`;
}
function fmtTime(ts?: number | string): string {
  const n = Number(ts);
  if (!ts || !Number.isFinite(n) || n <= 0) return '';
  const d = new Date(n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function SkinPreview({ hash, width = 56 }: { hash: string | null | undefined; width?: number }) {
  const c = useSdk().theme.colors;
  const uri = textureUrl(hash);
  if (uri) {
    return (
      <Image
        source={{ uri }}
        style={{ width, height: Math.round(width * 1.25), borderRadius: 10, backgroundColor: c.fillHover }}
        resizeMode="contain"
      />
    );
  }
  return <Tile glyph="肤" size={width} />;
}

/* ---------------- 我的角色 ---------------- */

function SkinHome({ onOpenCloset }: { onOpenCloset: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const players = useResource<SkinPlayer[]>(
    () => sdk.api.request<SkinPlayer[]>(`${API}/me/players`),
    [sdk],
  );
  const list = players.data ?? [];

  const setDefault = (name: string) => {
    Alert.alert('设为默认角色', `将「${name}」设为默认角色？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '确定',
        onPress: () => {
          void sdk.api
            .request(`${API}/me/default-player`, { method: 'PUT', body: { name } })
            .then(() => players.reload())
            .catch((e) => Alert.alert('设置失败', e instanceof Error ? e.message : String(e)));
        },
      },
    ]);
  };

  return (
    <Screen>
      <View style={{ flexDirection: 'row', alignItems: 'center' }}>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1, flex: 1 }}>
          管理皮肤站角色、默认角色与上传材质
        </Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="打开衣柜与材质"
          onPress={onOpenCloset}
          style={{
            width: 34, height: 34, borderRadius: 17,
            backgroundColor: c.bgSurface, borderWidth: 1, borderColor: c.borderSubtle,
            alignItems: 'center', justifyContent: 'center',
          }}
        >
          <Text style={{ color: c.textPrimary, fontSize: 20, marginTop: -2 }}>＋</Text>
        </Pressable>
      </View>
      {players.loading ? <Loading /> : null}
      {players.error ? (
        <Card>
          <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeSm }}>{players.error}</Text>
        </Card>
      ) : null}

      {list.map((p) => (
        <Card key={p.uuid}>
          <View style={{ flexDirection: 'row', gap: 12 }}>
            <SkinPreview hash={p.skinHash} width={56} />
            <View style={{ flex: 1, gap: 4 }}>
              <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeMd + 1, fontWeight: '700' }}>
                {p.name}
              </Text>
              <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
                皮肤 {p.skinHash ? `${p.skinHash.slice(0, 8)}…` : '未设置'}{p.capeHash ? ' · 有披风' : ''}
              </Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                更新于 {fmtTime(p.lastModified)}
              </Text>
              <Pressable
                onPress={() => setDefault(p.name)}
                style={{
                  alignSelf: 'flex-start', marginTop: 2,
                  paddingHorizontal: 10, paddingVertical: 4, borderRadius: 999,
                  backgroundColor: c.fillHover,
                }}
              >
                <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs, fontWeight: '500' }}>
                  设为默认
                </Text>
              </Pressable>
            </View>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="换肤"
              onPress={onOpenCloset}
              style={{ alignItems: 'center', justifyContent: 'center', gap: 8 }}
            >
              <View style={{ width: 32, height: 32, borderRadius: 16, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
                <Text style={{ color: c.textPrimary, fontSize: 14 }}>衫</Text>
              </View>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>换肤</Text>
            </Pressable>
          </View>
        </Card>
      ))}
      {!players.loading && list.length === 0 && !players.error ? (
        <Card>
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, textAlign: 'center' }}>
            还没有角色，可在皮肤站网页端创建
          </Text>
        </Card>
      ) : null}

      {list[0]?.name ? (
        <View style={{ borderRadius: t.radii.lg, backgroundColor: c.fillHover, padding: 14, gap: 6 }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <Text style={{ color: c.textTertiary, fontSize: 12 }}>ⓘ</Text>
            <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, fontWeight: '500' }}>
              外置皮肤接口（CustomSkinAPI）
            </Text>
            <View style={{ flex: 1 }} />
          </View>
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1 }}>{`/csl/${list[0].name}`}</Text>
        </View>
      ) : null}
    </Screen>
  );
}

/* ---------------- 衣柜与材质 ---------------- */

function SkinCloset() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [tab, setTab] = useState<'textures' | 'closet'>('textures');
  const textures = useResource<SkinTexture[]>(() => sdk.api.request(`${API}/me/textures`), [sdk]);
  const closet = useResource<ClosetItem[]>(() => sdk.api.request(`${API}/me/closet`), [sdk]);
  const players = useResource<SkinPlayer[]>(() => sdk.api.request(`${API}/me/players`), [sdk]);
  const playersList = players.data ?? [];

  const applyToFirstPlayer = (tex: SkinTexture) => {
    const target = playersList[0];
    if (!target) {
      Alert.alert('无法应用', '当前账号没有角色');
      return;
    }
    const body = tex.type === 'cape' ? { capeHash: tex.hash } : { skinHash: tex.hash };
    void sdk.api
      .request(`${API}/me/players/${encodeURIComponent(target.name)}/textures`, { method: 'PUT', body })
      .then(() => {
        players.reload();
        Alert.alert('已应用', `已应用到角色「${target.name}」`);
      })
      .catch((e) => Alert.alert('应用失败', e instanceof Error ? e.message : String(e)));
  };

  const removeTexture = (tex: SkinTexture) => {
    Alert.alert('删除材质', `确定删除「${tex.name || tex.hash.slice(0, 8)}」？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '删除', style: 'destructive',
        onPress: () => {
          void sdk.api
            .request(`${API}/me/textures/${encodeURIComponent(tex.hash)}`, { method: 'DELETE' })
            .then(() => textures.reload())
            .catch((e) => Alert.alert('删除失败', e instanceof Error ? e.message : String(e)));
        },
      },
    ]);
  };

  const removeCloset = (item: ClosetItem) => {
    Alert.alert('移出衣柜', `确定将「${item.itemName || '该皮肤'}」移出衣柜？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '移出', style: 'destructive',
        onPress: () => {
          void sdk.api
            .request(`${API}/me/closet/${encodeURIComponent(item.id)}`, { method: 'DELETE' })
            .then(() => closet.reload())
            .catch((e) => Alert.alert('操作失败', e instanceof Error ? e.message : String(e)));
        },
      },
    ]);
  };

  const equipped = (hash: string | null | undefined) =>
    hash && playersList.some((p) => p.skinHash === hash) ? '已装备' : null;

  return (
    <Screen>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <Chip label="我的材质" active={tab === 'textures'} onPress={() => setTab('textures')} />
        <Chip label="衣柜皮肤" active={tab === 'closet'} onPress={() => setTab('closet')} />
      </View>

      {tab === 'textures' ? (
        textures.loading ? <Loading /> :
        (textures.data ?? []).length === 0 ? <Empty text="还没有材质，先在网页端上传" /> :
        (textures.data ?? []).map((tex) => {
          const applied = equipped(tex.hash);
          return (
            <Card key={tex.hash}>
              <View style={{ flexDirection: 'row', gap: 12 }}>
                <SkinPreview hash={tex.hash} width={52} />
                <View style={{ flex: 1, gap: 3 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                    <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
                      {tex.name || tex.hash.slice(0, 8)}
                    </Text>
                    {applied ? <Badge text={applied} tone="accent" /> : null}
                  </View>
                  <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
                    {tex.type === 'cape' ? '披风' : '皮肤'}{tex.model ? ` · ${tex.model}` : ''} · {fmtTime(tex.uploadedAt)}
                  </Text>
                  <View style={{ flexDirection: 'row', gap: 8 }}>
                    <Pressable
                      onPress={() => applyToFirstPlayer(tex)}
                      style={{ flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8, backgroundColor: c.fillHover }}
                    >
                      <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeXs, fontWeight: '500' }}>✓ 应用到角色</Text>
                    </Pressable>
                    <Pressable
                      onPress={() => removeTexture(tex)}
                      style={{ flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface }}
                    >
                      <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs }}>删除</Text>
                    </Pressable>
                  </View>
                </View>
              </View>
            </Card>
          );
        })
      ) : (
        closet.loading ? <Loading /> :
        (closet.data ?? []).length === 0 ? <Empty text="衣柜还是空的" /> :
        (closet.data ?? []).map((item) => {
          const applied = equipped(item.textureHash);
          return (
            <Card key={item.id}>
              <View style={{ flexDirection: 'row', gap: 12, alignItems: 'center' }}>
                <SkinPreview hash={item.textureHash} width={44} />
                <View style={{ flex: 1, gap: 3 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                    <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '500', flex: 1 }}>
                      {item.itemName || item.textureHash.slice(0, 8)}
                    </Text>
                    {applied ? <Badge text={applied} tone="accent" /> : null}
                  </View>
                  <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>存入于 {fmtTime(item.createdAt)}</Text>
                </View>
                <Pressable onPress={() => removeCloset(item)} hitSlop={6}>
                  <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>移出</Text>
                </Pressable>
              </View>
            </Card>
          );
        })
      )}

      <View style={{ borderRadius: t.radii.lg, backgroundColor: c.fillHover, padding: 14 }}>
        <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, lineHeight: 18 }}>
          衣柜皮肤保存在皮肤站，启动器与网页宠物通过 CustomSkinAPI 读取。
        </Text>
      </View>
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ = { name: 'home' } | { name: 'closet' };

function SkinApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<View_>(initialRoute === '/closet' ? { name: 'closet' } : { name: 'home' });
  useEffect(() => {
    currentSdk?.navigation?.setTitle(view.name === 'home' ? '我的角色' : '衣柜与材质');
    currentSdk?.navigation?.setBackAction?.(view.name === 'home' ? null : () => setView({ name: 'home' }));
  }, [view, initialRoute]);
  return view.name === 'home' ? (
    <SkinHome onOpenCloset={() => setView({ name: 'closet' })} />
  ) : (
    <SkinCloset />
  );
}

const SkinModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('yudream-skin: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <SkinApp initialRoute={route} />
      </UiProvider>
    );
  },
};

export default SkinModule;
