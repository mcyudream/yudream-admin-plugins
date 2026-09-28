/**
 * shop 移动端（设计稿 shopPlaza / shopProduct / shopOrders）：
 * 积分商城广场（双列商品卡 + 搜索）、商品详情（购买）、我的订单（核销码/取消）。
 * 数据走 /api/plugins/shop/plaza/** 与 /me/**；金额为字符串、Long ID 为字符串；
 * 视觉经 sdk.theme 与 plugin-mobile-ui，不写死色值。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Image, Pressable, Text, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  BackRow, Badge, Card, Empty, InfoRows, Loading, PrimaryButton, Screen,
  SearchField, SectionTitle, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/shop';

interface ProductRow {
  id: string;
  title: string;
  summary?: string;
  coverImage?: string | null;
  images?: string[];
  assetCode: string;
  price: string;
  stock: number;
  soldCount: number;
  ownerLabel?: string;
  status?: string;
}
interface OrderRow {
  id: string;
  productId: string;
  productTitle: string;
  productImage?: string | null;
  price: string;
  totalAmount: string;
  status: string;
  statusText?: string;
  deliveryVoucher?: string | null;
  cancellable?: boolean;
  createdAt?: number;
}

function money(v: string | number): string {
  const n = Number(v);
  return Number.isFinite(n) ? String(n) : String(v ?? '');
}
function fmtTime(ts?: number | string): string {
  const n = Number(ts);
  if (!ts || !Number.isFinite(n) || n <= 0) return '';
  const d = new Date(n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
function statusTone(status: string): 'default' | 'accent' | 'success' | 'warning' | 'danger' {
  if (status === 'PAID') return 'warning';
  if (status === 'DELIVERED') return 'success';
  if (status === 'CANCELLED' || status === 'REFUNDED') return 'default';
  if (status === 'DELIVERY_FAILED') return 'danger';
  return 'default';
}

/* ---------------- 商城广场 ---------------- */

function Plaza({ onOpenProduct }: { onOpenProduct: (p: ProductRow) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [items, setItems] = useState<ProductRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [more, setMore] = useState(false);
  const [diag, setDiag] = useState('');
  const req = React.useRef(0);

  const load = useCallback(
    async (target: number, replace: boolean) => {
      const my = ++req.current;
      if (replace) setLoading(true); else setMore(true);
      try {
        const qs = [`page=${target}`, 'size=20'];
        if (query) qs.push(`keyword=${encodeURIComponent(query)}`);
        const res = await sdk.api.request<{ records?: ProductRow[]; total?: number }>(
          `${API}/plaza/products?${qs.join('&')}`,
        );
        if (my !== req.current) return;
        if (!res || !Array.isArray(res.records)) {
          setDiag('响应异常: ' + JSON.stringify(res).slice(0, 140));
        }
        const fresh = res.records ?? [];
        setTotal(res.total ?? fresh.length);
        setPage(target);
        setItems((prev) => (replace ? fresh : [...prev, ...fresh.filter((x) => !prev.some((p) => p.id === x.id))]));
      } catch (e) {
        if (my === req.current) setDiag('请求失败: ' + (e instanceof Error ? e.message : String(e)));
      } finally {
        if (my === req.current) { setLoading(false); setMore(false); }
      }
    },
    [sdk, query],
  );

  useEffect(() => { void load(1, true); }, [load]);

  return (
    <Screen>
      <SearchField value={keyword} onChangeText={setKeyword} placeholder="搜索商品…" onSubmit={() => setQuery(keyword.trim())} />
      <SectionTitle title="商品" actionText={total ? `共 ${total}` : undefined} />
      {loading ? <Loading /> : null}
      {!loading && diag ? <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeXs }}>{diag}</Text> : null}
      {!loading && !diag && items.length === 0 ? <Empty text={query ? '没有匹配的商品' : '商城还是空的'} /> : null}
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: t.spacing.md }}>
        {items.map((p) => (
          <Pressable
            key={p.id}
            onPress={() => onOpenProduct(p)}
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
              gap: 8,
            })}
          >
            {p.coverImage || (p.images ?? [])[0] ? (
              <Image
                source={{ uri: `${sdk.baseUrl}${p.coverImage ?? (p.images ?? [])[0]}` }}
                style={{ width: '100%', height: 92, borderRadius: 10, backgroundColor: c.fillHover }}
                resizeMode="cover"
              />
            ) : (
              <View style={{ width: '100%', height: 92, borderRadius: 10, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
                <Text style={{ color: c.textTertiary, fontSize: 20 }}>🎁</Text>
              </View>
            )}
            <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700' }}>
              {p.title}
            </Text>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '700' }}>{money(p.price)}</Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>积分</Text>
              <View style={{ flex: 1 }} />
              <Badge text={p.stock > 0 ? `库存 ${p.stock}` : '售罄'} tone={p.stock > 0 ? 'default' : 'danger'} />
            </View>
            <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
              {p.ownerLabel || '官方'}
            </Text>
          </Pressable>
        ))}
      </View>
      {items.length < total ? (
        <Pressable onPress={() => void load(page + 1, false)} style={{ alignItems: 'center', paddingVertical: 12 }}>
          {more ? <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm }}>加载中…</Text> : <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm }}>加载更多</Text>}
        </Pressable>
      ) : null}
    </Screen>
  );
}

/* ---------------- 商品详情 ---------------- */

function ProductPage({ product, onBack, onOrdered }: { product: ProductRow; onBack: () => void; onOrdered: () => void }) {
  const sdk = useSdk();
  const detail = useResource<ProductRow & { typeDisplayName?: string; perUserLimit?: number }>(
    () => sdk.api.request(`${API}/plaza/products/${product.id}`),
    [product.id],
  );
  const p = detail.data ?? product;
  const [busy, setBusy] = useState(false);
  const cover = p.coverImage || (p.images ?? [])[0] || '';

  const buy = () => {
    Alert.alert('确认购买', `使用 ${money(p.price)} 积分购买「${p.title}」？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '购买',
        onPress: () => {
          setBusy(true);
          void sdk.api
            .request(`${API}/me/orders`, { method: 'POST', body: { productId: p.id, quantity: 1 } })
            .then(() => {
              setBusy(false);
              Alert.alert('下单成功', '可在「我的订单」查看核销码', [
                { text: '查看订单', onPress: onOrdered },
                { text: '继续逛', style: 'cancel' },
              ]);
            })
            .catch((e) => {
              setBusy(false);
              Alert.alert('购买失败', e instanceof Error ? e.message : String(e));
            });
        },
      },
    ]);
  };

  return (
    <Screen>
      <BackRow onBack={onBack} />
      {cover ? (
        <Image
          source={{ uri: `${sdk.baseUrl}${cover}` }}
          style={{ width: '100%', height: 150, borderRadius: t.radii.lg, backgroundColor: t.colors.fillHover }}
          resizeMode="cover"
        />
      ) : null}
      <View style={{ gap: 5 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: t.colors.textPrimary, fontSize: t.typography.sizeXl, fontWeight: '700', flexShrink: 1 }}>
            {p.title}
          </Text>
          {p.typeDisplayName ? <Badge text={p.typeDisplayName} /> : null}
        </View>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
          <Text style={{ color: t.colors.textPrimary, fontSize: t.typography.sizeXl, fontWeight: '700' }}>{money(p.price)}</Text>
          <Text style={{ color: t.colors.textTertiary, fontSize: t.typography.sizeSm }}>积分 · 库存 {p.stock}</Text>
        </View>
      </View>

      {p.summary ? (
        <Text style={{ color: t.colors.textSecondary, fontSize: t.typography.sizeSm, lineHeight: 20 }}>{p.summary}</Text>
      ) : null}

      <InfoRows rows={[
        ['商品类型', p.typeDisplayName ?? '通用'],
        ['已售', `${p.soldCount} 件`],
        ['库存', p.stock > 0 ? `${p.stock} 件` : '售罄'],
        ['结算方式', '积分兑换'],
      ]} />

      <PrimaryButton title={`购买（${money(p.price)} 积分）`} onPress={buy} busy={busy} disabled={p.stock <= 0} />
    </Screen>
  );
}

/* ---------------- 我的订单 ---------------- */

function Orders() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const orders = useResource<{ records?: OrderRow[]; total?: number }>(
    () => sdk.api.request(`${API}/me/orders?page=1&size=20`),
    [sdk],
  );
  const list = orders.data?.records ?? [];

  const cancel = (o: OrderRow) => {
    Alert.alert('取消订单', `取消「${o.productTitle}」？积分将原路退回。`, [
      { text: '取消', style: 'cancel' },
      {
        text: '确认取消', style: 'destructive',
        onPress: () => {
          void sdk.api
            .request(`${API}/me/orders/${encodeURIComponent(o.id)}/cancel`, { method: 'POST' })
            .then(() => orders.reload())
            .catch((e) => Alert.alert('取消失败', e instanceof Error ? e.message : String(e)));
        },
      },
    ]);
  };

  const showVoucher = (o: OrderRow) => {
    Alert.alert('核销码', o.deliveryVoucher || '商家发货后生成', [{ text: '好的' }]);
  };

  return (
    <Screen>
      <SectionTitle title="买入" actionText={list.length ? `${list.length} 笔` : undefined} />
      {orders.loading ? <Loading /> : null}
      {!orders.loading && list.length === 0 ? <Empty text="还没有订单，去商城逛逛" /> : null}
      {orders.error ? (
        <Card>
          <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeSm }}>{orders.error}</Text>
        </Card>
      ) : null}
      {list.map((o) => (
        <Card key={o.id}>
          <View style={{ flexDirection: 'row', gap: 10 }}>
            {o.productImage ? (
              <Image
                source={{ uri: `${sdk.baseUrl}${o.productImage}` }}
                style={{ width: 48, height: 48, borderRadius: 10, backgroundColor: c.fillHover }}
                resizeMode="cover"
              />
            ) : (
              <View style={{ width: 48, height: 48, borderRadius: 10, backgroundColor: c.fillHover, alignItems: 'center', justifyContent: 'center' }}>
                <Text style={{ color: c.textTertiary, fontSize: 18 }}>🎁</Text>
              </View>
            )}
            <View style={{ flex: 1, gap: 3 }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
                  {o.productTitle}
                </Text>
                <Badge text={o.statusText || o.status} tone={statusTone(o.status)} />
              </View>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                {money(o.totalAmount)} 积分 · {fmtTime(o.createdAt)}
              </Text>
              <View style={{ flexDirection: 'row', gap: 8, marginTop: 2 }}>
                {o.status === 'PAID' && o.deliveryVoucher ? (
                  <Pressable
                    onPress={() => showVoucher(o)}
                    style={{ paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8, backgroundColor: c.accent }}
                  >
                    <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>核销码</Text>
                  </Pressable>
                ) : null}
                {o.cancellable ? (
                  <Pressable
                    onPress={() => cancel(o)}
                    style={{ paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface }}
                  >
                    <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs }}>取消订单</Text>
                  </Pressable>
                ) : null}
              </View>
            </View>
          </View>
        </Card>
      ))}
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ = { name: 'plaza' } | { name: 'product'; product: ProductRow } | { name: 'orders' };

function ShopApp({ initialRoute }: { initialRoute?: string }) {
  const [view, setView] = useState<View_>({ name: 'plaza' });
  useEffect(() => {
    const title = view.name === 'plaza' ? '积分商城' : view.name === 'orders' ? '我的订单' : '商品详情';
    currentSdk?.navigation?.setTitle(title);
  }, [view, initialRoute]);

  const goOrders = () => setView({ name: 'orders' });

  return view.name === 'plaza' ? (
    <View style={{ flex: 1 }}>
      <Plaza onOpenProduct={(p) => setView({ name: 'product', product: p })} />
      {/* 订单入口：悬浮于底部（v1 简化，不作独立 tab） */}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="我的订单"
        onPress={goOrders}
        style={{
          position: 'absolute', right: 20, bottom: 24,
          paddingHorizontal: 16, paddingVertical: 10, borderRadius: 999,
          backgroundColor: currentSdk!.theme.colors.accent,
        }}
      >
        <Text style={{ color: currentSdk!.theme.colors.onAccent, fontSize: 13, fontWeight: '500' }}>我的订单</Text>
      </Pressable>
    </View>
  ) : view.name === 'product' ? (
    <ProductPage product={view.product} onBack={() => setView({ name: 'plaza' })} onOrdered={goOrders} />
  ) : (
    <View style={{ flex: 1 }}>
      <View style={{ paddingTop: 12, paddingHorizontal: 20 }}>
        <BackRow onBack={() => setView({ name: 'plaza' })} />
      </View>
      <Orders />
    </View>
  );
}

const ShopModule: MobilePluginModule = {
  default: (props: Record<string, unknown>) => {
    const sdk = (props as { sdk?: PluginMobileSdk }).sdk;
    if (!sdk) {
      throw new Error('shop: 宿主未注入 sdk');
    }
    currentSdk = sdk;
    const route = (props as { route?: string }).route;
    return (
      <UiProvider sdk={sdk}>
        <ShopApp initialRoute={route} />
      </UiProvider>
    );
  },
};

function UiProvider({ sdk, children }: { sdk: PluginMobileSdk; children: React.ReactNode }) {
  const { UiProvider: Provider } = require('@yudream/plugin-mobile-ui');
  return <Provider sdk={sdk}>{children}</Provider>;
}

export default ShopModule;
