/**
 * shop 移动端（设计稿 shopPlaza / shopProduct / shopOrders）：
 * 积分商城广场（双列商品卡 + 搜索）、商品详情（型号 + 购买，库存 -1 为无限）、
 * 我的订单（买入/卖出 + 发货凭证 + 核验 + 取消）、我要出售（玩家上架，资格校验）、
 * 商店管理（管理员：商品上下架/删除/新建 + 订单代发货/补发/退款）。
 * 数据走 /api/plugins/shop/**；金额为字符串、Long ID 为字符串；
 * 视觉经 sdk.theme 与 plugin-mobile-ui，不写死色值。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Image, Pressable, Text, TextInput, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, Icon, Loading, PrimaryButton, Screen, SearchField, SectionTitle,
  UiProvider, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/shop';

interface ShopUser { id?: string; username?: string; nickname?: string; avatar?: string | null }
interface VariantRow { id: string; name: string; price?: string | number; stock: number; image?: string | null; soldOut?: boolean }
interface ProductRow {
  id: string;
  owner?: ShopUser | null;
  platformOwned?: boolean;
  ownerLabel?: string | null;
  title: string;
  summary?: string | null;
  descriptionMd?: string | null;
  images?: string[];
  coverImage?: string | null;
  assetCode: string;
  assetSymbol?: string;
  price: string | number;
  stock: number;
  perUserLimit?: number;
  variants?: VariantRow[];
  soldCount?: number | string;
  type?: string;
  typeDisplayName?: string;
  settlement?: string;
  status?: string;
  statusText?: string;
}
interface OrderRow {
  id: string;
  productId: string;
  productTitle: string;
  productImage?: string | null;
  settlement?: string;
  buyer?: ShopUser | null;
  seller?: ShopUser | null;
  sellerLabel?: string | null;
  assetCode: string;
  price: string | number;
  variantId?: string | null;
  variantName?: string | null;
  quantity: number;
  totalAmount: string | number;
  status: string;
  statusText?: string;
  deliveryMessage?: string | null;
  deliveryContent?: string | null;
  deliveryVoucher?: string | null;
  cancellable?: boolean;
  voucherVerified?: boolean;
  createdAt?: number;
}
interface Qualification {
  allowed?: boolean;
  reason?: string;
  publishAssetCode?: string | null;
  publishMinBalance?: string | number | null;
  balance?: string | number | null;
  walletAvailable?: boolean;
}

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));
function money(v: string | number | null | undefined): string {
  const n = Number(v);
  return Number.isFinite(n) ? String(n) : String(v ?? '');
}
function fmtTime(ts?: number | string | null): string {
  const n = Number(ts);
  if (!ts || !Number.isFinite(n) || n <= 0) return '';
  const d = new Date(n);
  return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
function statusTone(status?: string): 'default' | 'accent' | 'success' | 'warning' | 'danger' {
  if (status === 'PAID') return 'warning';
  if (status === 'DELIVERED' || status === 'DELIVERING') return 'success';
  if (status === 'DELIVERY_FAILED') return 'danger';
  return 'default';
}
/** 库存展示：-1 = 无限存量。 */
function stockText(stock?: number): string {
  if (stock == null) return '—';
  if (stock < 0) return '无限';
  if (stock === 0) return '售罄';
  return `库存 ${stock}`;
}
const soldOut = (p: { stock?: number }) => (p.stock ?? 0) === 0;

function Field({
  value, onChangeText, placeholder, multiline, height,
}: {
  value: string;
  onChangeText: (v: string) => void;
  placeholder: string;
  multiline?: boolean;
  height?: number;
}) {
  const t = useSdk().theme;
  const c = t.colors;
  return (
    <TextInput
      value={value}
      onChangeText={onChangeText}
      placeholder={placeholder}
      placeholderTextColor={c.textTertiary}
      multiline={multiline}
      textAlignVertical={multiline ? 'top' : 'center'}
      autoCapitalize="none"
      autoCorrect={false}
      style={{
        height: height ?? (multiline ? 72 : 42),
        borderRadius: t.radii.md,
        borderWidth: 1,
        borderColor: c.borderSubtle,
        backgroundColor: c.bgPage,
        color: c.textPrimary,
        fontSize: t.typography.sizeSm,
        paddingHorizontal: 10,
        paddingVertical: multiline ? 8 : 0,
      }}
    />
  );
}

function ProductCover({ p, height }: { p: ProductRow; height: number }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const [broken, setBroken] = useState(false);
  const uri = p.coverImage || (p.images ?? [])[0] || '';
  if (!uri || broken) {
    return (
      <View style={{ width: '100%', height, borderRadius: 10, backgroundColor: t.colors.fillHover, alignItems: 'center', justifyContent: 'center' }}>
        <Icon name="image" size={height > 100 ? 26 : 18} color={t.colors.textTertiary} />
      </View>
    );
  }
  return (
    <Image
      source={{ uri: `${sdk.baseUrl}${uri}` }}
      onError={() => setBroken(true)}
      style={{ width: '100%', height, borderRadius: 10, backgroundColor: t.colors.fillHover }}
      resizeMode="cover"
    />
  );
}

/* ---------------- 商城广场 ---------------- */

function Plaza({ onOpenProduct, onOpenOrders, onOpenSell, onOpenAdmin }: {
  onOpenProduct: (p: ProductRow) => void;
  onOpenOrders: () => void;
  onOpenSell: () => void;
  onOpenAdmin: () => void;
}) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [settlement, setSettlement] = useState<'SELLER' | 'BURN'>('SELLER');
  const [items, setItems] = useState<ProductRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [more, setMore] = useState(false);
  const req = useRef(0);

  const load = useCallback(
    async (target: number, replace: boolean) => {
      const my = ++req.current;
      if (replace) setLoading(true); else setMore(true);
      try {
        const qs = [`page=${target}`, 'size=20', `settlement=${settlement}`];
        if (query) qs.push(`keyword=${encodeURIComponent(query)}`);
        const res = await sdk.api.request<{ records?: ProductRow[]; total?: number }>(
          `${API}/plaza/products?${qs.join('&')}`,
        );
        if (my !== req.current) return;
        const fresh = res.records ?? [];
        setTotal(Number(res.total ?? fresh.length));
        setPage(target);
        setItems((prev) => (replace ? fresh : [...prev, ...fresh.filter((x) => !prev.some((p) => p.id === x.id))]));
      } catch {
        if (my === req.current) setItems([]);
      } finally {
        if (my === req.current) { setLoading(false); setMore(false); }
      }
    },
    [sdk, query, settlement],
  );

  useEffect(() => { void load(1, true); }, [load]);

  return (
    <Screen>
      <SearchField value={keyword} onChangeText={setKeyword} placeholder="搜索商品…" onSubmit={() => setQuery(keyword.trim())} />
      <View style={{ flexDirection: 'row', gap: 8 }}>
        {([
          { key: 'SELLER', label: '玩家市场' },
          { key: 'BURN', label: '积分兑换' },
        ] as const).map((st) => (
          <Pressable
            key={st.key}
            onPress={() => setSettlement(st.key)}
            style={{
              flex: 1, height: 40, borderRadius: t.radii.md, alignItems: 'center', justifyContent: 'center',
              backgroundColor: settlement === st.key ? t.colors.accent : t.colors.bgSurface,
              borderWidth: 1, borderColor: settlement === st.key ? t.colors.accent : t.colors.borderSubtle,
            }}
          >
            <Text style={{ color: settlement === st.key ? t.colors.onAccent : t.colors.textSecondary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>
              {st.label}
            </Text>
          </Pressable>
        ))}
      </View>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <Card onPress={onOpenOrders} style={{ flex: 1, alignItems: 'center', paddingVertical: 11, gap: 3 }}>
          <Icon name="time" size={18} color={c.accent} />
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>我的订单</Text>
        </Card>
        <Card onPress={onOpenSell} style={{ flex: 1, alignItems: 'center', paddingVertical: 11, gap: 3 }}>
          <Icon name="add" size={18} color={c.accent} />
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>我要出售</Text>
        </Card>
        <Card onPress={onOpenAdmin} style={{ flex: 1, alignItems: 'center', paddingVertical: 11, gap: 3 }}>
          <Icon name="settings" size={18} color={c.accent} />
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>商店管理</Text>
        </Card>
      </View>

      <SectionTitle title="商品" actionText={total ? `共 ${total}` : undefined} />
      {loading ? <Loading /> : null}
      {!loading && items.length === 0 ? <Empty text={query ? '没有匹配的商品' : '商城还是空的'} /> : null}
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
            <ProductCover p={p} height={92} />
            <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700' }}>
              {p.title}
            </Text>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '700' }}>{money(p.price)}</Text>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>积分</Text>
              <View style={{ flex: 1 }} />
              {soldOut(p) ? (
                <Badge text="售罄" tone="danger" />
              ) : (p.stock ?? 0) < 0 ? (
                <Badge text="无限" tone="success" />
              ) : (
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>×{p.stock}</Text>
              )}
            </View>
            <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
              {p.platformOwned ? (p.ownerLabel || '官方') : (p.owner?.nickname || p.owner?.username || '玩家')}
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

function ProductPage({ productId, onOrdered }: { productId: string; onOrdered: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const detail = useResource<ProductRow>(() => sdk.api.request(`${API}/plaza/products/${encodeURIComponent(productId)}`), [productId]);
  const balance = useResource<{ available?: boolean; balance?: string }>(
    () => sdk.api.request(`${API}/me/wallet/balance?assetCode=${encodeURIComponent(detail.data?.assetCode ?? 'POINT')}`),
    [detail.data?.assetCode],
  );
  const p = detail.data;
  const [busy, setBusy] = useState(false);
  const [variantId, setVariantId] = useState('');

  const buy = () => {
    if (!p || busy) return;
    if (soldOut(p)) return;
    if (p.variants && p.variants.length > 0 && !variantId) {
      Alert.alert('请选择型号');
      return;
    }
    const bal = balance.data;
    const enough = bal?.available === false ? true : Number(bal?.balance ?? 0) >= Number(p.price);
    Alert.alert(
      '确认购买',
      `使用 ${money(p.price)} 积分购买「${p.title}」${p.variants?.length ? `（${p.variants.find((v) => v.id === variantId)?.name ?? ''}）` : ''}？${bal?.available === false ? '\n（钱包暂不可用，以下单时系统校验为准）' : enough ? '' : '\n（余额不足）'}`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '购买',
          onPress: () => {
            setBusy(true);
            void sdk.api
              .request(`${API}/me/orders`, {
                method: 'POST',
                body: { productId: p.id, variantId: variantId || undefined, quantity: 1 },
              })
              .then(() => {
                setBusy(false);
                Alert.alert('下单成功', '可在「我的订单」查看发货与核销', [
                  { text: '查看订单', onPress: onOrdered },
                  { text: '继续逛', style: 'cancel' },
                ]);
              })
              .catch((e) => { setBusy(false); Alert.alert('购买失败', errText(e)); });
          },
        },
      ],
    );
  };

  if (detail.loading && !p) return <Screen><Loading /></Screen>;
  if (detail.error) return <Screen><Empty text={detail.error} /></Screen>;
  if (!p) return null;
  const chosen = (p.variants ?? []).find((v) => v.id === variantId);
  const price = chosen?.price ?? p.price;
  const ownerLine = p.platformOwned ? (p.ownerLabel || '官方') : (p.owner?.nickname || p.owner?.username || '玩家');

  return (
    <Screen>
      <ProductCover p={p} height={150} />
      <View style={{ gap: 5 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeXl, fontWeight: '700', flexShrink: 1 }}>
            {p.title}
          </Text>
          {p.typeDisplayName ? <Badge text={p.typeDisplayName} /> : null}
        </View>
        <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: 6 }}>
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeXl, fontWeight: '700' }}>{money(price)}</Text>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm }}>积分</Text>
          <View style={{ flex: 1 }} />
          <Badge text={stockText(p.stock)} tone={soldOut(p) ? 'danger' : (p.stock ?? 0) < 0 ? 'success' : 'default'} solid={soldOut(p)} />
        </View>
      </View>

      {p.summary ? (
        <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, lineHeight: 20 }}>{p.summary}</Text>
      ) : null}

      {(p.variants ?? []).length > 0 ? (
        <Card>
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700' }}>选择型号</Text>
          <View style={{ flexDirection: 'row', gap: 6, flexWrap: 'wrap' }}>
            {(p.variants ?? []).map((v) => (
              <Pressable
                key={v.id}
                onPress={() => (v.soldOut ? undefined : setVariantId(v.id))}
                style={{
                  paddingHorizontal: 10, paddingVertical: 6, borderRadius: 999, opacity: v.soldOut ? 0.45 : 1,
                  backgroundColor: variantId === v.id ? c.accent : c.bgSurface,
                  borderWidth: 1, borderColor: variantId === v.id ? c.accent : c.borderSubtle,
                }}
              >
                <Text style={{ color: variantId === v.id ? c.onAccent : c.textSecondary, fontSize: t.typography.sizeXs }}>
                  {v.name} · {money(v.price ?? p.price)}{v.soldOut ? ' · 售罄' : ''}
                </Text>
              </Pressable>
            ))}
          </View>
        </Card>
      ) : null}

      <Card>
        {[
          ['归属', ownerLine],
          ['已售', `${Number(p.soldCount ?? 0)} 件`],
          ['库存', stockText(p.stock)],
          ['每人限购', (p.perUserLimit ?? 0) > 0 ? `${p.perUserLimit} 件` : '不限'],
        ].map(([k, v], i, arr) => (
          <View key={k}>
            {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
            <View style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: 9 }}>
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeSm, width: 90 }}>{k}</Text>
              <View style={{ flex: 1 }} />
              <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>{v}</Text>
            </View>
          </View>
        ))}
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
          余额 {balance.data?.available === false ? '暂不可用' : `${money(balance.data?.balance ?? 0)} 积分`}
        </Text>
      </Card>

      {p.descriptionMd ? (
        <>
          <SectionTitle title="商品说明" />
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, lineHeight: 21 }}>
            {p.descriptionMd}
          </Text>
        </>
      ) : null}

      <PrimaryButton
        title={soldOut(p) ? '已售罄' : `购买（${money(price)} 积分）`}
        onPress={buy}
        busy={busy}
        disabled={soldOut(p)}
      />
    </Screen>
  );
}

/* ---------------- 我的订单（买入 / 卖出） ---------------- */

function OrdersPage() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [tab, setTab] = useState<'buy' | 'sell'>('buy');
  const orders = useResource<OrderRow[]>(
    () => sdk.api
      .request<OrderRow[] | { records?: OrderRow[] }>(`${API}/${tab === 'buy' ? 'me/purchases' : 'me/sales'}?page=1&size=30`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [tab],
  );
  const [busy, setBusy] = useState('');
  const [delivering, setDelivering] = useState('');
  const [voucher, setVoucher] = useState('');
  const list = orders.data ?? [];

  const cancel = (o: OrderRow) => {
    Alert.alert('取消订单', `取消「${o.productTitle}」？积分将原路退回。`, [
      { text: '取消', style: 'cancel' },
      {
        text: '确认取消', style: 'destructive',
        onPress: () => {
          setBusy(o.id);
          void sdk.api
            .request(`${API}/me/orders/${encodeURIComponent(o.id)}/cancel`, { method: 'POST', body: { reason: '买家取消' } })
            .then(() => { setBusy(''); orders.reload(); })
            .catch((e) => { setBusy(''); Alert.alert('取消失败', errText(e)); });
        },
      },
    ]);
  };

  const verify = (o: OrderRow) => {
    Alert.alert('确认收货', `确认已收到「${o.productTitle}」的发货？核验后订单完成。`, [
      { text: '取消', style: 'cancel' },
      {
        text: '确认核验',
        onPress: () => {
          setBusy(o.id);
          void sdk.api
            .request(`${API}/me/orders/${encodeURIComponent(o.id)}/verify`, { method: 'POST' })
            .then(() => { setBusy(''); orders.reload(); Alert.alert('已完成', '订单核验完成'); })
            .catch((e) => { setBusy(''); Alert.alert('核验失败', errText(e)); });
        },
      },
    ]);
  };

  const submitVoucher = (o: OrderRow) => {
    if (!voucher.trim()) {
      Alert.alert('请填写发货说明', '发货凭证需要文字说明（如卡密、领取方式）');
      return;
    }
    setBusy(o.id);
    void sdk.api
      .request(`${API}/me/orders/${encodeURIComponent(o.id)}/voucher`, {
        method: 'POST',
        body: { voucher: voucher.trim(), proofs: [] },
      })
      .then(() => {
        setBusy('');
        setDelivering('');
        setVoucher('');
        orders.reload();
        Alert.alert('已提交发货', '等待买家核验收货');
      })
      .catch((e) => { setBusy(''); Alert.alert('发货失败', errText(e)); });
  };

  const isBuyerTab = tab === 'buy';

  return (
    <Screen>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        {[
          { key: 'buy', label: '我买到的' },
          { key: 'sell', label: '我卖出的' },
        ].map((item) => (
          <Pressable
            key={item.key}
            onPress={() => setTab(item.key as 'buy' | 'sell')}
            style={{
              flex: 1, height: 40, borderRadius: t.radii.md, alignItems: 'center', justifyContent: 'center',
              backgroundColor: tab === item.key ? c.accent : c.bgSurface,
              borderWidth: 1, borderColor: tab === item.key ? c.accent : c.borderSubtle,
            }}
          >
            <Text style={{ color: tab === item.key ? c.onAccent : c.textSecondary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>
              {item.label}
            </Text>
          </Pressable>
        ))}
      </View>

      {orders.loading ? <Loading /> : null}
      {!orders.loading && list.length === 0 ? <Empty text={isBuyerTab ? '还没有购买记录，去商城逛逛' : '还没有卖出记录'} /> : null}
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
                <Icon name="image" size={16} color={c.textTertiary} />
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
                {[
                  `${money(o.totalAmount)} 积分`,
                  o.quantity > 1 ? `×${o.quantity}` : '',
                  o.variantName ?? '',
                  isBuyerTab ? (o.sellerLabel || o.seller?.nickname || '官方') : (o.buyer?.nickname || o.buyer?.username || ''),
                  fmtTime(o.createdAt),
                ].filter(Boolean).join(' · ')}
              </Text>
            </View>
          </View>

          {isBuyerTab && o.deliveryContent ? (
            <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, lineHeight: 17 }}>
              发货内容：{o.deliveryContent}
            </Text>
          ) : null}
          {!isBuyerTab && o.deliveryVoucher ? (
            <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, lineHeight: 17 }}>
              已发货：{o.deliveryVoucher}
            </Text>
          ) : null}

          {/* 买家操作 */}
          {isBuyerTab ? (
            <View style={{ flexDirection: 'row', gap: 8, flexWrap: 'wrap' }}>
              {o.deliveryVoucher ? (
                <Pressable
                  onPress={() => Alert.alert('核销码 / 发货凭证', o.deliveryVoucher ?? '', [{ text: '好的' }])}
                  style={{ paddingHorizontal: 10, paddingVertical: 6, borderRadius: 8, backgroundColor: c.accent }}
                >
                  <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>查看核销码</Text>
                </Pressable>
              ) : null}
              {o.cancellable ? (
                <Pressable
                  onPress={() => cancel(o)}
                  disabled={busy === o.id}
                  style={{ paddingHorizontal: 10, paddingVertical: 6, borderRadius: 8, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface }}
                >
                  <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs }}>取消订单</Text>
                </Pressable>
              ) : null}
              {o.deliveryVoucher && o.voucherVerified === false ? (
                <Pressable
                  onPress={() => verify(o)}
                  disabled={busy === o.id}
                  style={{ paddingHorizontal: 10, paddingVertical: 6, borderRadius: 8, backgroundColor: c.success ?? '#16a34a' }}
                >
                  <Text style={{ color: '#ffffff', fontSize: t.typography.sizeXs, fontWeight: '500' }}>确认收货</Text>
                </Pressable>
              ) : null}
            </View>
          ) : null}

          {/* 卖家操作：待发货 */}
          {!isBuyerTab && o.status === 'PAID' ? (
            delivering === o.id ? (
              <View style={{ gap: 8 }}>
                <Field value={voucher} onChangeText={setVoucher} placeholder="发货说明（卡密、领取方式等）" multiline height={60} />
                <View style={{ flexDirection: 'row', gap: 8, justifyContent: 'flex-end' }}>
                  <Pressable onPress={() => { setDelivering(''); setVoucher(''); }} style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, backgroundColor: c.fillHover }}>
                    <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs }}>取消</Text>
                  </Pressable>
                  <Pressable onPress={() => submitVoucher(o)} disabled={busy === o.id} style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, backgroundColor: c.accent }}>
                    <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>提交发货</Text>
                  </Pressable>
                </View>
              </View>
            ) : (
              <View style={{ flexDirection: 'row', justifyContent: 'flex-end' }}>
                <Pressable
                  onPress={() => setDelivering(o.id)}
                  style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, backgroundColor: c.accent }}
                >
                  <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>去发货</Text>
                </Pressable>
              </View>
            )
          ) : null}
        </Card>
      ))}
    </Screen>
  );
}

/* ---------------- 我要出售（玩家上架） ---------------- */

function ProductFormCard({
  defaultTitle, submitTitle, onSubmit, busy,
}: {
  defaultTitle?: string;
  submitTitle: string;
  onSubmit: (body: Record<string, unknown>) => void;
  busy: boolean;
}) {
  const t = useSdk().theme;
  const c = t.colors;
  const [title, setTitle] = useState(defaultTitle ?? '');
  const [summary, setSummary] = useState('');
  const [price, setPrice] = useState('');
  const [stock, setStock] = useState('-1');
  return (
    <Card style={{ gap: 10 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <Icon name="add" size={16} color={c.accent} />
        <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>新建商品</Text>
      </View>
      <Field value={title} onChangeText={setTitle} placeholder="商品标题（必填）" />
      <Field value={summary} onChangeText={setSummary} placeholder="一句话介绍" />
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <View style={{ flex: 1, gap: 4 }}>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>价格（积分）</Text>
          <Field value={price} onChangeText={setPrice} placeholder="100" />
        </View>
        <View style={{ flex: 1, gap: 4 }}>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>库存（-1 = 无限）</Text>
          <Field value={stock} onChangeText={setStock} placeholder="-1" />
        </View>
      </View>
      <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
        普通商品直接文字发货；图片、型号等请在网页端补充
      </Text>
      <PrimaryButton
        title={submitTitle}
        onPress={() => {
          const n = Number(price);
          if (!title.trim() || !Number.isFinite(n) || n <= 0) {
            Alert.alert('请检查', '标题必填，价格需为正数');
            return;
          }
          const st = Number(stock);
          onSubmit({
            title: title.trim(),
            summary: summary.trim(),
            assetCode: 'POINT',
            price: n,
            stock: Number.isFinite(st) ? st : -1,
            perUserLimit: 0,
            type: 'POINTS_REDEEM',
            images: [],
            variants: [],
          });
        }}
        busy={busy}
      />
    </Card>
  );
}

function SellPage() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const qual = useResource<Qualification>(() => sdk.api.request(`${API}/me/publish-qualification`), [sdk]);
  const products = useResource<ProductRow[]>(
    () => sdk.api
      .request<ProductRow[] | { records?: ProductRow[] }>(`${API}/me/products?page=1&size=30`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const [busy, setBusy] = useState('');
  const list = products.data ?? [];
  const q = qual.data;

  const create = (body: Record<string, unknown>) => {
    setBusy('create');
    void sdk.api
      .request(`${API}/me/products`, { method: 'POST', body })
      .then(() => { setBusy(''); products.reload(); Alert.alert('已上架', '商品已进入商城'); })
      .catch((e) => { setBusy(''); Alert.alert('上架失败', errText(e)); });
  };

  const toggleShelf = (p: ProductRow) => {
    const on = p.status !== 'ON_SHELF';
    setBusy(p.id);
    void sdk.api
      .request(`${API}/me/products/${encodeURIComponent(p.id)}/shelf`, { method: 'POST', body: { onShelf: on } })
      .then(() => { setBusy(''); products.reload(); })
      .catch((e) => { setBusy(''); Alert.alert('操作失败', errText(e)); });
  };

  const remove = (p: ProductRow) => {
    Alert.alert('删除商品', `删除「${p.title}」？删除后不可恢复。`, [
      { text: '取消', style: 'cancel' },
      {
        text: '删除', style: 'destructive',
        onPress: () => {
          setBusy(p.id);
          void sdk.api
            .request(`${API}/me/products/${encodeURIComponent(p.id)}`, { method: 'DELETE' })
            .then(() => { setBusy(''); products.reload(); })
            .catch((e) => { setBusy(''); Alert.alert('删除失败', errText(e)); });
        },
      },
    ]);
  };

  return (
    <Screen>
      {qual.loading ? <Loading /> : null}
      {q && q.allowed === false ? (
        <Card style={{ borderColor: c.warning ?? '#d97706', borderWidth: 1 }}>
          <Badge text="暂不具备上架资格" tone="warning" solid />
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, lineHeight: 17 }}>
            {q.reason || '请查看系统设置'}
          </Text>
        </Card>
      ) : null}
      {q && q.allowed !== false ? <ProductFormCard submitTitle="上架商品" onSubmit={create} busy={busy === 'create'} /> : null}

      <SectionTitle title="我的商品" actionText={list.length ? `${list.length} 件` : undefined} />
      {products.loading ? <Loading /> : null}
      {!products.loading && list.length === 0 ? <Empty text="还没有发布过商品" /> : null}
      {list.map((p) => (
        <Card key={p.id}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
              {p.title}
            </Text>
            <Badge text={p.statusText || (p.status === 'ON_SHELF' ? '上架中' : '已下架')} tone={p.status === 'ON_SHELF' ? 'success' : 'default'} />
          </View>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>{money(p.price)} 积分</Text>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{stockText(p.stock)}</Text>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>已售 {Number(p.soldCount ?? 0)}</Text>
            <View style={{ flex: 1 }} />
            <Pressable
              onPress={() => toggleShelf(p)}
              disabled={busy === p.id}
              style={{ paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface }}
            >
              <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs }}>{p.status === 'ON_SHELF' ? '下架' : '上架'}</Text>
            </Pressable>
            <Pressable
              onPress={() => remove(p)}
              disabled={busy === p.id}
              style={{ paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8, borderWidth: 1, borderColor: c.danger ?? '#dc2626', backgroundColor: c.bgSurface }}
            >
              <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeXs }}>删除</Text>
            </Pressable>
          </View>
        </Card>
      ))}
    </Screen>
  );
}

/* ---------------- 商店管理（管理员） ---------------- */

function AdminPage() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const [tab, setTab] = useState<'products' | 'orders'>('products');
  const products = useResource<ProductRow[]>(
    () => sdk.api
      .request<ProductRow[] | { records?: ProductRow[] }>(`${API}/admin/products?page=1&size=50`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [tab],
  );
  const orders = useResource<OrderRow[]>(
    () => sdk.api
      .request<OrderRow[] | { records?: OrderRow[] }>(`${API}/admin/orders?page=1&size=30`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [tab],
  );
  const [busy, setBusy] = useState('');
  const [creating, setCreating] = useState(false);
  const [delivering, setDelivering] = useState('');
  const [voucher, setVoucher] = useState('');
  const pList = products.data ?? [];
  const oList = orders.data ?? [];

  const toggleShelf = (p: ProductRow) => {
    setBusy(p.id);
    void sdk.api
      .request(`${API}/admin/products/${encodeURIComponent(p.id)}/shelf`, {
        method: 'POST',
        body: { onShelf: p.status !== 'ON_SHELF' },
      })
      .then(() => { setBusy(''); products.reload(); })
      .catch((e) => { setBusy(''); Alert.alert('操作失败', errText(e)); });
  };

  const remove = (p: ProductRow) => {
    Alert.alert('删除商品', `删除「${p.title}」？删除后不可恢复。`, [
      { text: '取消', style: 'cancel' },
      {
        text: '删除', style: 'destructive',
        onPress: () => {
          setBusy(p.id);
          void sdk.api
            .request(`${API}/admin/products/${encodeURIComponent(p.id)}`, { method: 'DELETE' })
            .then(() => { setBusy(''); products.reload(); })
            .catch((e) => { setBusy(''); Alert.alert('删除失败', errText(e)); });
        },
      },
    ]);
  };

  const create = (body: Record<string, unknown>) => {
    setBusy('create');
    void sdk.api
      .request(`${API}/admin/products`, { method: 'POST', body })
      .then(() => { setBusy(''); setCreating(false); products.reload(); Alert.alert('已上架', '官方商品已进入商城'); })
      .catch((e) => { setBusy(''); Alert.alert('上架失败', errText(e)); });
  };

  const orderAction = (o: OrderRow, action: 'redeliver' | 'refund') => {
    Alert.alert(
      action === 'refund' ? '确认退款' : '补发商品',
      action === 'refund' ? `对「${o.productTitle}」订单退款？积分将退回买家。` : `对「${o.productTitle}」重新执行发货？`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: action === 'refund' ? '退款' : '补发',
          style: action === 'refund' ? 'destructive' : 'default',
          onPress: () => {
            setBusy(o.id);
            void sdk.api
              .request(`${API}/admin/orders/${encodeURIComponent(o.id)}/${action}`, { method: 'POST' })
              .then(() => { setBusy(''); orders.reload(); })
              .catch((e) => { setBusy(''); Alert.alert('操作失败', errText(e)); });
          },
        },
      ],
    );
  };

  const adminDeliver = (o: OrderRow) => {
    if (!voucher.trim()) {
      Alert.alert('请填写发货说明');
      return;
    }
    setBusy(o.id);
    void sdk.api
      .request(`${API}/admin/orders/${encodeURIComponent(o.id)}/delivery`, {
        method: 'POST',
        body: { voucher: voucher.trim(), proofs: [] },
      })
      .then(() => {
        setBusy('');
        setDelivering('');
        setVoucher('');
        orders.reload();
        Alert.alert('已代发货', '等待买家核验收货');
      })
      .catch((e) => { setBusy(''); Alert.alert('发货失败', errText(e)); });
  };

  return (
    <Screen>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        {[
          { key: 'products', label: '商品管理' },
          { key: 'orders', label: '订单管理' },
        ].map((item) => (
          <Pressable
            key={item.key}
            onPress={() => setTab(item.key as 'products' | 'orders')}
            style={{
              flex: 1, height: 40, borderRadius: t.radii.md, alignItems: 'center', justifyContent: 'center',
              backgroundColor: tab === item.key ? c.accent : c.bgSurface,
              borderWidth: 1, borderColor: tab === item.key ? c.accent : c.borderSubtle,
            }}
          >
            <Text style={{ color: tab === item.key ? c.onAccent : c.textSecondary, fontSize: t.typography.sizeSm, fontWeight: '500' }}>
              {item.label}
            </Text>
          </Pressable>
        ))}
      </View>

      {tab === 'products' ? (
        <>
          {creating ? (
            <ProductFormCard submitTitle="上架官方商品" onSubmit={create} busy={busy === 'create'} />
          ) : (
            <PrimaryButton title="新建官方商品" onPress={() => setCreating(true)} height={44} />
          )}
          <SectionTitle title="全部商品" actionText={`${pList.length} 件`} />
          {products.loading ? <Loading /> : null}
          {!products.loading && pList.length === 0 ? <Empty text="还没有商品" /> : null}
          {pList.map((p) => (
            <Card key={p.id}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
                  {p.title}
                </Text>
                <Badge text={p.statusText || (p.status === 'ON_SHELF' ? '上架中' : '已下架')} tone={p.status === 'ON_SHELF' ? 'success' : 'default'} />
              </View>
              <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                {[
                  `${money(p.price)} 积分`,
                  stockText(p.stock),
                  `已售 ${Number(p.soldCount ?? 0)}`,
                  p.platformOwned ? (p.ownerLabel || '官方') : (p.owner?.nickname || '玩家'),
                ].join(' · ')}
              </Text>
              <View style={{ flexDirection: 'row', gap: 8, justifyContent: 'flex-end' }}>
                <Pressable
                  onPress={() => toggleShelf(p)}
                  disabled={busy === p.id}
                  style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, borderWidth: 1, borderColor: c.borderSubtle, backgroundColor: c.bgSurface }}
                >
                  <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs }}>{p.status === 'ON_SHELF' ? '下架' : '上架'}</Text>
                </Pressable>
                <Pressable
                  onPress={() => remove(p)}
                  disabled={busy === p.id}
                  style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, borderWidth: 1, borderColor: c.danger ?? '#dc2626', backgroundColor: c.bgSurface }}
                >
                  <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeXs }}>删除</Text>
                </Pressable>
              </View>
            </Card>
          ))}
        </>
      ) : (
        <>
          <SectionTitle title="全部订单" actionText={`${oList.length} 笔`} />
          {orders.loading ? <Loading /> : null}
          {!orders.loading && oList.length === 0 ? <Empty text="暂无订单" /> : null}
          {oList.map((o) => (
            <Card key={o.id}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
                  {o.productTitle}
                </Text>
                <Badge text={o.statusText || o.status} tone={statusTone(o.status)} />
              </View>
              <Text numberOfLines={1} style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
                {[
                  `${money(o.totalAmount)} 积分`,
                  o.quantity > 1 ? `×${o.quantity}` : '',
                  `买家 ${o.buyer?.nickname || o.buyer?.username || '—'}`,
                  fmtTime(o.createdAt),
                ].filter(Boolean).join(' · ')}
              </Text>
              {o.deliveryVoucher ? (
                <Text numberOfLines={2} style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1 }}>
                  发货：{o.deliveryVoucher}
                </Text>
              ) : null}
              {delivering === o.id ? (
                <View style={{ gap: 8 }}>
                  <Field value={voucher} onChangeText={setVoucher} placeholder="发货说明（卡密、领取方式等）" multiline height={60} />
                  <View style={{ flexDirection: 'row', gap: 8, justifyContent: 'flex-end' }}>
                    <Pressable onPress={() => { setDelivering(''); setVoucher(''); }} style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, backgroundColor: c.fillHover }}>
                      <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs }}>取消</Text>
                    </Pressable>
                    <Pressable onPress={() => adminDeliver(o)} disabled={busy === o.id} style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, backgroundColor: c.accent }}>
                      <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>确认代发货</Text>
                    </Pressable>
                  </View>
                </View>
              ) : (
                <View style={{ flexDirection: 'row', gap: 8, justifyContent: 'flex-end' }}>
                  {o.status === 'PAID' ? (
                    <Pressable
                      onPress={() => { setDelivering(o.id); setVoucher(''); }}
                      style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, backgroundColor: c.accent }}
                    >
                      <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>代发货</Text>
                    </Pressable>
                  ) : null}
                  {o.status === 'DELIVERY_FAILED' ? (
                    <Pressable
                      onPress={() => orderAction(o, 'redeliver')}
                      disabled={busy === o.id}
                      style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, borderWidth: 1, borderColor: c.accent, backgroundColor: c.bgSurface }}
                    >
                      <Text style={{ color: c.accent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>补发</Text>
                    </Pressable>
                  ) : null}
                  {o.status === 'PAID' || o.status === 'DELIVERY_FAILED' || o.status === 'DELIVERING' ? (
                    <Pressable
                      onPress={() => orderAction(o, 'refund')}
                      disabled={busy === o.id}
                      style={{ paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, borderWidth: 1, borderColor: c.danger ?? '#dc2626', backgroundColor: c.bgSurface }}
                    >
                      <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeXs }}>退款</Text>
                    </Pressable>
                  ) : null}
                </View>
              )}
            </Card>
          ))}
        </>
      )}
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ =
  | { name: 'plaza' }
  | { name: 'product'; id: string }
  | { name: 'orders' }
  | { name: 'sell' }
  | { name: 'admin' };

function seedStack(route?: string): View_[] {
  if (route === '/admin') return [{ name: 'plaza' }, { name: 'admin' }];
  if (route === '/orders') return [{ name: 'plaza' }, { name: 'orders' }];
  return [{ name: 'plaza' }];
}

function ShopApp({ initialRoute }: { initialRoute?: string }) {
  const [stack, setStack] = useState<View_[]>(() => seedStack(initialRoute));
  const view = stack[stack.length - 1];

  useEffect(() => {
    setStack(seedStack(initialRoute));
  }, [initialRoute]);

  useEffect(() => {
    const titles: Record<View_['name'], string> = {
      plaza: '积分商城', product: '商品详情', orders: '我的订单', sell: '我要出售', admin: '商店管理',
    };
    currentSdk?.navigation?.setTitle(titles[view.name]);
    currentSdk?.navigation?.setBackAction?.(view.name === 'plaza' ? null : () => setStack((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev)));
  }, [view, initialRoute]);

  const push = (next: View_) => setStack((prev) => [...prev, next]);
  const back = () => setStack((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev));

  return view.name === 'plaza' ? (
    <Plaza
      onOpenProduct={(p) => push({ name: 'product', id: p.id })}
      onOpenOrders={() => push({ name: 'orders' })}
      onOpenSell={() => push({ name: 'sell' })}
      onOpenAdmin={() => push({ name: 'admin' })}
    />
  ) : view.name === 'product' ? (
    <ProductPage productId={view.id} onOrdered={() => { setStack([{ name: 'plaza' }, { name: 'orders' }]); }} />
  ) : view.name === 'orders' ? (
    <OrdersPage />
  ) : view.name === 'sell' ? (
    <SellPage />
  ) : (
    <AdminPage />
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

export default ShopModule;
