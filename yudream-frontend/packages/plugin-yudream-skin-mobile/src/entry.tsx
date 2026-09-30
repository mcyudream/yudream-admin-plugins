/**
 * yudream-skin 移动端（设计稿 skinHome / skinLibrary / skinCloset）：
 * 顶部双 Tab——「我的」（角色列表 + 正面渲染立绘 + 衣柜）与「皮肤库」（公共材质库，
 * 皮肤/披风筛选、立绘预览、应用到角色）。皮肤预览用 64x64 材质的精灵裁剪合成正面
 * 立绘（头/身体/双臂/双腿 + 外层），不再是 2D 展开图；数据走 /api/plugins/yudream-skin/**。
 */
import React, { useEffect, useState } from 'react';
import { Image, Pressable, ScrollView, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Chip, Empty, Icon, Loading, PrimaryButton, Screen, Tile, UiProvider, useResource,  uiAlert,
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
  lastModified: number | string;
}
interface LibraryTexture {
  hash: string;
  name?: string;
  type: string; // skin | cape
  model?: string; // default | slim
  publicAccess?: boolean;
  uploadedAt?: number | string;
}
interface ClosetItem {
  id: string;
  textureHash: string;
  itemName: string;
  createdAt: number | string;
  textureType?: string; // skin | cape（后端按材质库实时派生）
}

function textureUri(hash: string | null | undefined): string {
  if (!hash) return '';
  return `${currentSdk?.baseUrl ?? ''}${API}/textures/${hash}`;
}
function fmtTime(ts?: number | string | null): string {
  const n = Number(ts);
  if (!ts || !Number.isFinite(n) || n <= 0) return '';
  const d = new Date(n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * 皮肤正面立绘：走皮肤站渲染端点（服务端按官方布局拼装正面像，邻近采样像素风），
 * 坏图回退占位字符。
 */
function SkinRender({ hash, height = 168, style }: { hash?: string | null; height?: number; style?: object }) {
  const sdk = useSdk();
  const c = sdk.theme.colors;
  const [broken, setBroken] = useState(false);
  const uri = hash ? `${sdk.baseUrl}${API}/textures/${hash}/render?height=${Math.min(640, Math.round(height * 2))}` : '';
  if (!uri || broken) {
    return <Tile glyph="肤" size={Math.round(height / 2.4)} />;
  }
  return (
    <Image
      source={{ uri }}
      onError={() => setBroken(true)}
      style={[{ width: height / 2, height, backgroundColor: c.fillHover, borderRadius: Math.round(height / 21) }, style]}
      resizeMode="contain"
      fadeDuration={0}
    />
  );
}

/* ---------------- 顶部 Tab ---------------- */

function TabBar({ tab, onChange }: { tab: 'mine' | 'library'; onChange: (t: 'mine' | 'library') => void }) {
  const t = useSdk().theme;
  const c = t.colors;
  return (
    <View style={{ flexDirection: 'row', gap: 8, paddingBottom: 2 }}>
      {[
        { key: 'mine', label: '我的', icon: 'person' },
        { key: 'library', label: '皮肤库', icon: 'image' },
      ].map((item) => (
        <Pressable
          key={item.key}
          onPress={() => onChange(item.key as 'mine' | 'library')}
          style={{
            flex: 1, height: 42, borderRadius: t.radii.md, alignItems: 'center', justifyContent: 'center',
            flexDirection: 'row', gap: 6,
            backgroundColor: tab === item.key ? c.accent : c.bgSurface,
            borderWidth: 1, borderColor: tab === item.key ? c.accent : c.borderSubtle,
          }}
        >
          <Icon name={item.icon} size={15} color={tab === item.key ? c.onAccent : c.textSecondary} />
          <Text style={{ color: tab === item.key ? c.onAccent : c.textSecondary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>
            {item.label}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}

/* ---------------- 我的 ---------------- */

function MineTab({ players, onOpenCloset, gotoLibrary }: {
  players: { data: SkinPlayer[] | null; loading: boolean; error: string | null; reload: () => void };
  onOpenCloset: () => void;
  gotoLibrary: () => void;
}) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [busy, setBusy] = useState('');
  const list = players.data ?? [];

  const setDefault = (name: string) => {
    uiAlert('设为默认角色', `将「${name}」设为默认角色？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '确定',
        onPress: () => {
          setBusy(name);
          void sdk.api
            .request(`${API}/me/default-player`, { method: 'PUT', body: { name } })
            .then(() => { setBusy(''); players.reload(); })
            .catch((e) => { setBusy(''); uiAlert('设置失败', errText(e)); });
        },
      },
    ]);
  };

  return (
    <Screen>
      {players.loading ? <Loading /> : null}
      {players.error ? (
        <Card><Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeSm }}>{players.error}</Text></Card>
      ) : null}

      {list.map((p) => (
        <Card key={p.uuid}>
          <View style={{ flexDirection: 'row', gap: 14 }}>
            <SkinRender hash={p.skinHash} height={150} />
            <View style={{ flex: 1, gap: 5, justifyContent: 'center' }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeMd + 1, fontWeight: '700', flex: 1 }}>
                  {p.name}
                </Text>
                {p.capeHash ? <Badge text="有披风" tone="accent" /> : null}
              </View>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs + 1 }}>
                {p.skinHash ? '已设置皮肤' : '未设置皮肤'}{p.capeHash ? ' · 已设置披风' : ''} · {fmtTime(p.lastModified)}
              </Text>
              <Pressable
                onPress={() => setDefault(p.name)}
                disabled={busy === p.name}
                style={{ alignSelf: 'flex-start', paddingHorizontal: 10, paddingVertical: 5, borderRadius: 999, backgroundColor: c.fillHover, marginTop: 2 }}
              >
                <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs, fontWeight: '500' }}>设为默认</Text>
              </Pressable>
              <PrimaryButton title="换肤 / 换披风" onPress={gotoLibrary} height={38} />
            </View>
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

      <Card onPress={onOpenCloset}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Icon name="folder-open-outline" size={18} color={c.accent} />
          <View style={{ flex: 1 }}>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '600' }}>我的衣柜</Text>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>收藏的皮肤，一键应用到角色</Text>
          </View>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeLg }}>{'›'}</Text>
        </View>
      </Card>

      {list[0]?.name ? (
        <View style={{ borderRadius: t.radii.lg, backgroundColor: c.fillHover, padding: 14, gap: 6 }}>
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, fontWeight: '500' }}>
            外置皮肤接口（CustomSkinAPI）
          </Text>
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1 }}>{`/csl/${list[0].name}`}</Text>
        </View>
      ) : null}
    </Screen>
  );
}

/* ---------------- 皮肤库（公共材质库） ---------------- */

function LibraryTab({ players, gotoMine }: { players: { data: SkinPlayer[] | null; reload: () => void }; gotoMine: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const lib = useResource<LibraryTexture[]>(() => sdk.api.request(`${API}/textures`), [sdk]);
  const [typeFilter, setTypeFilter] = useState<'skin' | 'cape'>('skin');
  const [keyword, setKeyword] = useState('');
  const [busy, setBusy] = useState('');
  const all = lib.data ?? [];
  const list = all
    .filter((tex) => (tex.type ?? 'skin') === typeFilter)
    .filter((tex) => !keyword.trim() || (tex.name ?? '').toLowerCase().includes(keyword.trim().toLowerCase()))
    .slice(0, 60);

  const apply = (tex: LibraryTexture) => {
    const target = (players.data ?? [])[0];
    if (!target) {
      uiAlert('无法应用', '当前账号没有角色，请先在皮肤站创建角色');
      return;
    }
    setBusy(tex.hash);
    const body = tex.type === 'cape' ? { capeHash: tex.hash } : { skinHash: tex.hash };
    void sdk.api
      .request(`${API}/me/players/${encodeURIComponent(target.name)}/textures`, { method: 'PUT', body })
      .then(() => {
        setBusy('');
        players.reload();
        uiAlert('已应用', `「${tex.name || tex.hash.slice(0, 8)}」已应用到角色「${target.name}」`, [
          { text: '查看角色', onPress: gotoMine },
          { text: '继续逛', style: 'cancel' },
        ]);
      })
      .catch((e) => { setBusy(''); uiAlert('应用失败', errText(e)); });
  };

  return (
    <Screen>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <Chip label="皮肤" active={typeFilter === 'skin'} onPress={() => setTypeFilter('skin')} />
        <Chip label="披风" active={typeFilter === 'cape'} onPress={() => setTypeFilter('cape')} />
      </View>
      {lib.loading ? <Loading /> : null}
      {lib.error ? <Empty text={lib.error} /> : null}
      {!lib.loading && all.length === 0 ? <Empty text="皮肤库还是空的" /> : null}
      {!lib.loading && all.length > 0 && list.length === 0 ? <Empty text="没有匹配的材质" /> : null}

      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: t.spacing.md }}>
        {typeFilter === 'skin'
          ? list.map((tex) => (
            <Pressable
              key={tex.hash}
              onPress={() => apply(tex)}
              disabled={busy === tex.hash}
              android_ripple={{ color: c.fillHover }}
              style={({ pressed }) => ({
                width: '31%',
                flexGrow: 1,
                maxWidth: '31%',
                borderRadius: t.radii.lg,
                borderWidth: 1,
                borderColor: c.borderSubtle,
                backgroundColor: pressed ? c.fillHover : c.bgSurface,
                padding: 8,
                gap: 6,
                alignItems: 'center',
              })}
            >
              <SkinRender hash={tex.hash} height={96} />
              <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeXs + 1, fontWeight: '600', maxWidth: '100%' }}>
                {tex.name || tex.hash.slice(0, 8)}
              </Text>
              <Badge text={tex.model === 'slim' ? '纤细' : '经典'} />
            </Pressable>
          ))
          : list.map((tex) => (
            <Pressable
              key={tex.hash}
              onPress={() => apply(tex)}
              disabled={busy === tex.hash}
              android_ripple={{ color: c.fillHover }}
              style={({ pressed }) => ({
                width: '47.5%',
                flexGrow: 1,
                maxWidth: '47.5%',
                borderRadius: t.radii.lg,
                borderWidth: 1,
                borderColor: c.borderSubtle,
                backgroundColor: pressed ? c.fillHover : c.bgSurface,
                padding: 10,
                gap: 6,
                flexDirection: 'row',
                alignItems: 'center',
              })}
            >
              <Image
                source={{ uri: textureUri(tex.hash) }}
                style={{ width: 36, height: 18, backgroundColor: c.fillHover, borderRadius: 4 }}
                resizeMode="contain"
              />
              <View style={{ flex: 1, gap: 2 }}>
                <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeXs + 1, fontWeight: '600' }}>
                  {tex.name || tex.hash.slice(0, 8)}
                </Text>
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>披风 · 点击应用</Text>
              </View>
            </Pressable>
          ))}
      </View>
      {all.length > list.length ? (
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs, textAlign: 'center' }}>
          仅显示前 {list.length} 项，可按名称筛选
        </Text>
      ) : null}
    </Screen>
  );
}

/* ---------------- 衣柜 ---------------- */

function ClosetPage({ onBack }: { onBack: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const closet = useResource<ClosetItem[]>(() => sdk.api.request(`${API}/me/closet`), [sdk]);
  const players = useResource<SkinPlayer[]>(() => sdk.api.request(`${API}/me/players`), [sdk]);
  const [busy, setBusy] = useState('');
  const list = closet.data ?? [];
  const target = (players.data ?? [])[0];

  const apply = (item: ClosetItem) => {
    if (!target) {
      uiAlert('无法应用', '当前账号没有角色');
      return;
    }
    const cape = (item.textureType ?? 'skin') === 'cape';
    // 后端 PUT 语义是整档替换（缺省槽清空）：必须双槽位一起提交，
    // 否则应用披风会把皮肤槽清掉（反之亦然）。
    const body = cape
      ? { skinHash: target.skinHash ?? undefined, capeHash: item.textureHash }
      : { skinHash: item.textureHash, capeHash: target.capeHash ?? undefined };
    setBusy(item.id);
    void sdk.api
      .request(`${API}/me/players/${encodeURIComponent(target.name)}/textures`, {
        method: 'PUT',
        body,
      })
      .then(() => {
        setBusy('');
        players.reload();
        uiAlert('已应用', `已将${cape ? '披风' : '皮肤'}应用到角色「${target.name}」`);
      })
      .catch((e) => { setBusy(''); uiAlert('应用失败', errText(e)); });
  };

  const remove = (item: ClosetItem) => {
    uiAlert('移出衣柜', `确定将「${item.itemName || '该物品'}」移出衣柜？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '移出', style: 'destructive',
        onPress: () => {
          setBusy(item.id);
          void sdk.api
            .request(`${API}/me/closet/${encodeURIComponent(item.id)}`, { method: 'DELETE' })
            .then(() => { setBusy(''); closet.reload(); })
            .catch((e) => { setBusy(''); uiAlert('操作失败', errText(e)); });
        },
      },
    ]);
  };

  return (
    <Screen>
      {closet.loading ? <Loading /> : null}
      {!closet.loading && list.length === 0 ? <Empty text="衣柜还是空的，在皮肤库点皮肤可收藏" /> : null}
      {list.map((item) => (
        <Card key={item.id}>
          <View style={{ flexDirection: 'row', gap: 14, alignItems: 'center' }}>
            {(item.textureType ?? 'skin') === 'cape' ? (
              <View style={{ width: 96, height: 96, borderRadius: 8, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center', gap: 4 }}>
                <Image
                  source={{ uri: textureUri(item.textureHash) }}
                  style={{ width: 72, height: 36 }}
                  resizeMode="contain"
                />
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>披风</Text>
              </View>
            ) : (
              <SkinRender hash={item.textureHash} height={96} />
            )}
            <View style={{ flex: 1, gap: 4 }}>
              <Text numberOfLines={2} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '600' }}>
                {item.itemName || item.textureHash.slice(0, 8)}
              </Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>存入于 {fmtTime(item.createdAt)}</Text>
              <View style={{ flexDirection: 'row', gap: 8, marginTop: 2 }}>
                <Pressable
                  onPress={() => apply(item)}
                  disabled={busy === item.id}
                  style={{ paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8, backgroundColor: c.accent }}
                >
                  <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>应用到角色</Text>
                </Pressable>
                <Pressable
                  onPress={() => remove(item)}
                  disabled={busy === item.id}
                  style={{ paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface }}
                >
                  <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs }}>移出</Text>
                </Pressable>
              </View>
            </View>
          </View>
        </Card>
      ))}
      <View style={{ height: 8 }} />
      <PrimaryButton title="返回我的" onPress={onBack} height={42} />
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function SkinApp({ initialRoute }: { initialRoute?: string }) {
  const [tab, setTab] = useState<'mine' | 'library'>(initialRoute === '/library' ? 'library' : 'mine');
  const [closetOpen, setClosetOpen] = useState(false);
  const players = useResource<SkinPlayer[]>(() => {
    return currentSdk!.api.request<SkinPlayer[]>(`${API}/me/players`);
  }, [tab, closetOpen]);

  useEffect(() => {
    currentSdk?.navigation?.setTitle(closetOpen ? '我的衣柜' : tab === 'mine' ? '皮肤站' : '皮肤库');
    currentSdk?.navigation?.setBackAction?.(closetOpen ? () => setClosetOpen(false) : null);
  }, [tab, closetOpen]);

  if (closetOpen) {
    return (
      <UiProvider sdk={currentSdk!}>
        <ClosetPage onBack={() => setClosetOpen(false)} />
      </UiProvider>
    );
  }

  return (
    <UiProvider sdk={currentSdk!}>
      <View style={{ flex: 1, backgroundColor: currentSdk!.theme.colors.bgPage, paddingTop: 8 }}>
        <View style={{ paddingHorizontal: 16 }}>
          <TabBar tab={tab} onChange={setTab} />
        </View>
        {tab === 'mine' ? (
          <MineTab players={players} onOpenCloset={() => setClosetOpen(true)} gotoLibrary={() => setTab('library')} />
        ) : (
          <LibraryTab players={players} gotoMine={() => setTab('mine')} />
        )}
      </View>
    </UiProvider>
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
    return <SkinApp initialRoute={route} />;
  },
};

export default SkinModule;
