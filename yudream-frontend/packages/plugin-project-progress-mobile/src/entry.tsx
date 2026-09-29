/**
 * project-progress 移动端（设计稿 progressMine / progressAdmin）：
 * 我的任务（统计 + 打卡 + 提交验收）、认领中心、项目进度仪表盘、验收审批、发布任务。
 * 数据走 /api/plugins/project-progress/**；打卡类型按项目 allowedCheckInTypes 渲染，
 * Minecraft 时长打卡一键直发；视觉经 sdk.theme 与 plugin-mobile-ui，不写死色值。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Image, Pressable, Text, TextInput, View } from 'react-native';
import type { MobilePluginModule, PluginMobileSdk } from '@yudream/plugin-sdk-mobile';
import {
  Badge, Card, Empty, Icon, Loading, PrimaryButton, Screen, SectionTitle, StatTile,
  UiProvider, useResource,
} from '@yudream/plugin-mobile-ui';

let currentSdk: PluginMobileSdk | null = null;
function useSdk(): PluginMobileSdk {
  return currentSdk!;
}

const API = '/api/plugins/project-progress';

interface StatusDTO { code: string; label: string; terminal: boolean; sort: number }
interface ProjectRow {
  id: string;
  name: string;
  description?: string;
  managerUserIds?: string[];
  memberUserIds?: string[];
  statuses?: StatusDTO[];
  defaultStatusCode?: string;
  doneStatusCode?: string;
  reworkStatusCode?: string;
  minCheckInIntervalMinutes?: number;
  allowedCheckInTypes?: string[];
  minecraftPolicy?: { enabled?: boolean; serverId?: string; requiredOnlineMinutes?: number; autoCheckInEnabled?: boolean };
  enabled?: boolean;
}
interface FileEvidenceDTO { objectKey?: string; filename?: string; image?: boolean }
interface TaskRow {
  id: string;
  projectId: string;
  title: string;
  description?: string;
  statusCode?: string;
  assignmentMode?: string;
  requiredAssigneeCount?: number;
  assigneeUserIds?: string[];
  published?: boolean;
  pendingAcceptance?: boolean;
  acceptanceSummary?: string;
  acceptanceFiles?: FileEvidenceDTO[];
  dueAt?: number | string | null;
}
interface Stats {
  assignedDetails?: number;
  completedDetails?: number;
  pendingAcceptanceDetails?: number;
  acceptedReviews?: number;
  rejectedReviews?: number;
  checkIns?: number;
}
interface CheckInRow {
  id: string;
  detailId?: string;
  userId?: string;
  type?: string;
  summary?: string;
  createdAt?: number | string;
  reviewStatus?: string;
}
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

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));
function fmtDate(v?: number | string | null): string {
  const n = Number(v);
  if (!v || !Number.isFinite(n) || n <= 0) return '';
  const d = new Date(n);
  return `${d.getMonth() + 1}.${d.getDate()}`;
}
function fmtDateTime(v?: number | string | null): string {
  const n = Number(v);
  if (!v || !Number.isFinite(n) || n <= 0) return '';
  const d = new Date(n);
  return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
const checkInTypeLabel = (t: string) =>
  t === 'IMAGE' ? '图文打卡' : t === 'FILE' ? '文件打卡' : t === 'LOCATION' ? '位置打卡' : t === 'MINECRAFT_ONLINE' ? 'MC 时长打卡' : t;

/** 输入框（卡片内统一视觉）。 */
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
        height: height ?? (multiline ? 76 : 42),
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

/* ---------------- 我的任务 ---------------- */

function HomePage({ onOpenTask, onOpenClaim, onOpenProjects, onOpenAcceptance }: {
  onOpenTask: (task: TaskRow) => void;
  onOpenClaim: () => void;
  onOpenProjects: () => void;
  onOpenAcceptance: () => void;
}) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const stats = useResource<Stats>(() => sdk.api.request(`${API}/me/statistics`), [sdk]);
  const tasks = useResource<TaskRow[]>(
    () => sdk.api
      .request<TaskRow[] | { records?: TaskRow[] }>(`${API}/me/tasks?page=1&size=30`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const claimable = useResource<TaskRow[]>(
    () => sdk.api
      .request<TaskRow[] | { records?: TaskRow[] }>(`${API}/me/tasks/claimable?page=1&size=10`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const st = stats.data ?? {};
  const list = tasks.data ?? [];

  return (
    <Screen>
      <View style={{ flexDirection: 'row', gap: 10 }}>
        <StatTile value={`${st.assignedDetails ?? 0}`} label="进行中" />
        <StatTile value={`${st.completedDetails ?? 0}`} label="已完成" />
        <StatTile value={`${st.checkIns ?? 0}`} label="累计打卡" />
      </View>

      <View style={{ flexDirection: 'row', gap: 8 }}>
        <Card onPress={onOpenClaim} style={{ flex: 1, alignItems: 'center', paddingVertical: 12, gap: 4 }}>
          <Icon name="add" size={20} color={c.accent} />
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>认领中心</Text>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs - 1 }}>{(claimable.data ?? []).length} 项可认领</Text>
        </Card>
        <Card onPress={onOpenProjects} style={{ flex: 1, alignItems: 'center', paddingVertical: 12, gap: 4 }}>
          <Icon name="folder-open-outline" size={20} color={c.accent} />
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>项目进度</Text>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs - 1 }}>仪表盘与任务</Text>
        </Card>
        <Card onPress={onOpenAcceptance} style={{ flex: 1, alignItems: 'center', paddingVertical: 12, gap: 4 }}>
          <Icon name="checkmark" size={20} color={c.accent} />
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600' }}>验收审批</Text>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs - 1 }}>通过 / 驳回</Text>
        </Card>
      </View>

      <SectionTitle title="我的任务" actionText={`${list.length} 项`} />
      {tasks.loading ? <Loading /> : null}
      {!tasks.loading && list.length === 0 ? <Empty text="暂无任务，去认领中心看看" /> : null}
      {list.map((task) => (
        <Card key={task.id} onPress={() => onOpenTask(task)}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
              {task.title}
            </Text>
            {task.pendingAcceptance ? <Badge text="待验收" tone="warning" solid /> : null}
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
            {task.pendingAcceptance ? (
              <Badge text="等待管理员验收" tone="accent" />
            ) : (
              <Icon name="forward" size={14} color={c.textTertiary} />
            )}
          </View>
        </Card>
      ))}
    </Screen>
  );
}

/* ---------------- 任务详情：打卡 + 提交验收 ---------------- */

function TaskPage({ taskId, projectId, onBack }: { taskId: string; projectId?: string; onBack: () => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const task = useResource<TaskRow>(() => {
    const fromList = (rows: TaskRow[]) => {
      const found = rows.find((row) => row.id === taskId);
      if (!found) throw new Error('任务不存在');
      return found;
    };
    const mine = sdk.api
      .request<TaskRow[] | { records?: TaskRow[] }>(`${API}/me/tasks?page=1&size=50`)
      .then((r) => fromList(Array.isArray(r) ? r : (r?.records ?? [])));
    if (!projectId) return mine;
    // 非负责人的任务（项目仪表盘进入）从项目任务列表取
    return mine.catch(() =>
      sdk.api
        .request<TaskRow[] | { records?: TaskRow[] }>(`${API}/projects/${encodeURIComponent(projectId)}/details?page=1&size=50`)
        .then((r) => fromList(Array.isArray(r) ? r : (r?.records ?? []))),
    );
  }, [taskId, projectId]);
  const a = task.data;
  const project = useResource<ProjectRow>(
    () => (a ? sdk.api.request(`${API}/projects/${encodeURIComponent(a.projectId)}`) : Promise.reject(new Error('等待任务'))),
    [a?.projectId],
  );
  const history = useResource<CheckInRow[]>(
    () => sdk.api
      .request<CheckInRow[] | { records?: CheckInRow[] }>(`${API}/details/${encodeURIComponent(taskId)}/check-ins?page=1&size=10`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [taskId],
  );
  const [summary, setSummary] = useState('');
  const [type, setType] = useState('');
  const [busy, setBusy] = useState('');
  const [acceptSummary, setAcceptSummary] = useState('');

  const allowed = (project.data?.allowedCheckInTypes ?? []).filter((x) => x !== 'MINECRAFT_ONLINE');
  const activeType = type || allowed[0] || 'IMAGE';
  const done = Boolean(a && project.data && a.statusCode === project.data.doneStatusCode);
  const reviewing = a?.pendingAcceptance === true;
  const reloadAll = useCallback(() => { task.reload(); history.reload(); }, [task, history]);

  const checkIn = () => {
    if (busy || !a) return;
    setBusy('checkin');
    void sdk.api
      .request(`${API}/me/tasks/${encodeURIComponent(a.id)}/check-ins`, {
        method: 'POST',
        body: { type: activeType, summary: summary.trim() || '移动端打卡' },
      })
      .then(() => {
        setBusy('');
        setSummary('');
        reloadAll();
        Alert.alert('打卡成功', '已记录本次打卡');
      })
      .catch((e) => { setBusy(''); Alert.alert('打卡失败', errText(e)); });
  };

  const minecraftCheckIn = () => {
    if (busy || !a) return;
    setBusy('mc');
    void sdk.api
      .request(`${API}/me/tasks/${encodeURIComponent(a.id)}/check-ins/minecraft`, { method: 'POST' })
      .then(() => { setBusy(''); reloadAll(); Alert.alert('打卡成功', 'Minecraft 在线时长已计入'); })
      .catch((e) => { setBusy(''); Alert.alert('打卡失败', errText(e)); });
  };

  const submitAcceptance = () => {
    if (busy || !a) return;
    Alert.alert('提交验收', '提交后由验收人审核，确定提交？', [
      { text: '取消', style: 'cancel' },
      {
        text: '提交',
        onPress: () => {
          setBusy('accept');
          void sdk.api
            .request(`${API}/me/tasks/${encodeURIComponent(a.id)}/submit-acceptance`, {
              method: 'POST',
              body: { type: 'IMAGE', summary: acceptSummary.trim() || '移动端提交验收' },
            })
            .then(() => { setBusy(''); setAcceptSummary(''); task.reload(); Alert.alert('已提交', '等待验收人审核'); })
            .catch((e) => { setBusy(''); Alert.alert('提交失败', errText(e)); });
        },
      },
    ]);
  };

  if (task.loading && !a) return <Screen><Loading /></Screen>;
  if (task.error) return <Screen><Empty text={task.error} /></Screen>;
  if (!a) return null;
  const p = project.data;

  return (
    <Screen>
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd, fontWeight: '700', flex: 1 }}>
            {a.title}
          </Text>
          {a.statusCode ? <Badge text={a.statusCode} tone={done ? 'success' : 'accent'} solid={done} /> : null}
        </View>
        {a.description ? (
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, lineHeight: 20 }}>
            {a.description}
          </Text>
        ) : null}
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
          {[
            p?.name ? `项目 ${p.name}` : '',
            a.dueAt ? `截止 ${fmtDate(a.dueAt)}` : '',
            a.assignmentMode === 'CLAIM' ? '认领模式' : '指派模式',
          ].filter(Boolean).join(' · ')}
        </Text>
      </Card>

      {/* 打卡 */}
      {!done && !reviewing ? (
        <Card>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Icon name="time" size={16} color={c.accent} />
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>任务打卡</Text>
            {p && (p.minCheckInIntervalMinutes ?? 0) > 0 ? (
              <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>间隔 ≥ {p.minCheckInIntervalMinutes} 分钟</Text>
            ) : null}
          </View>
          {p?.minecraftPolicy?.enabled ? (
            <SecondaryMcButton onPress={minecraftCheckIn} busy={busy === 'mc'} minutes={p.minecraftPolicy?.requiredOnlineMinutes ?? 0} />
          ) : null}
          {allowed.length > 0 ? (
            <>
              {allowed.length > 1 ? (
                <View style={{ flexDirection: 'row', gap: 6, flexWrap: 'wrap' }}>
                  {allowed.map((tp) => (
                    <Pressable
                      key={tp}
                      onPress={() => setType(tp)}
                      style={{
                        paddingHorizontal: 10, paddingVertical: 6, borderRadius: 999,
                        backgroundColor: activeType === tp ? c.accent : c.bgSurface,
                        borderWidth: 1, borderColor: activeType === tp ? c.accent : c.borderSubtle,
                      }}
                    >
                      <Text style={{ color: activeType === tp ? c.onAccent : c.textSecondary, fontSize: t.typography.sizeXs }}>
                        {checkInTypeLabel(tp)}
                      </Text>
                    </Pressable>
                  ))}
                </View>
              ) : null}
              <Field value={summary} onChangeText={setSummary} placeholder="打卡说明（做了什么、进度如何）" multiline />
              <PrimaryButton title="提交打卡" onPress={checkIn} busy={busy === 'checkin'} height={44} />
            </>
          ) : p?.minecraftPolicy?.enabled ? (
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
              本项目仅支持 Minecraft 在线时长自动核验打卡
            </Text>
          ) : (
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
              本项目未开放打卡方式，完成后可直接提交验收
            </Text>
          )}
        </Card>
      ) : null}

      {/* 提交验收 */}
      {!done && !reviewing ? (
        <Card>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Icon name="checkmark" size={16} color={c.accent} />
            <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>提交验收</Text>
          </View>
          <Field value={acceptSummary} onChangeText={setAcceptSummary} placeholder="验收说明（成果、佐证）" multiline />
          <PrimaryButton title="提交验收" onPress={submitAcceptance} busy={busy === 'accept'} height={44} />
        </Card>
      ) : null}
      {reviewing ? (
        <Card>
          <Badge text="已提交验收，等待审核" tone="warning" solid />
          {a?.acceptanceSummary ? (
            <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1 }}>{a.acceptanceSummary}</Text>
          ) : null}
        </Card>
      ) : null}

      {/* 打卡记录 */}
      <SectionTitle title="打卡记录" actionText={`${(history.data ?? []).length} 条`} />
      {history.loading ? <Loading /> : null}
      {!history.loading && (history.data ?? []).length === 0 ? <Empty text="还没有打卡记录" /> : null}
      {(history.data ?? []).length > 0 ? (
        <Card>
          {(history.data ?? []).map((ci, i) => (
            <View key={ci.id}>
              {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 9 }}>
                <View style={{ width: 7, height: 7, borderRadius: 4, backgroundColor: c.success ?? '#16a34a' }} />
                <View style={{ flex: 1, gap: 1 }}>
                  <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm }}>
                    {ci.summary || checkInTypeLabel(ci.type ?? '')}
                  </Text>
                  <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{fmtDateTime(ci.createdAt)}</Text>
                </View>
                <Badge text={checkInTypeLabel(ci.type ?? '')} />
              </View>
            </View>
          ))}
        </Card>
      ) : null}
    </Screen>
  );
}

function SecondaryMcButton({ onPress, busy, minutes }: { onPress: () => void; busy: boolean; minutes: number }) {
  const t = useSdk().theme;
  const c = t.colors;
  const [pressed, setPressed] = useState(false);
  return (
    <Pressable
      onPress={onPress}
      disabled={busy}
      onPressIn={() => setPressed(true)}
      onPressOut={() => setPressed(false)}
      style={{
        height: 44, borderRadius: t.radii.md,
        borderWidth: 1, borderColor: c.accent,
        backgroundColor: pressed ? c.fillHover : c.bgSurface,
        alignItems: 'center', justifyContent: 'center', flexDirection: 'row', gap: 8,
      }}
    >
      <Icon name="play" size={15} color={c.accent} />
      <Text style={{ color: c.accent, fontSize: t.typography.sizeSm, fontWeight: '500' }}>
        {busy ? '核验中…' : `Minecraft 时长打卡${minutes > 0 ? `（需 ≥ ${minutes} 分钟）` : ''}`}
      </Text>
    </Pressable>
  );
}

/* ---------------- 认领中心 ---------------- */

function ClaimPage() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const claimable = useResource<TaskRow[]>(
    () => sdk.api
      .request<TaskRow[] | { records?: TaskRow[] }>(`${API}/me/tasks/claimable?page=1&size=30`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const projects = useResource<ProjectRow[]>(
    () => sdk.api.request<ProjectRow[] | { records?: ProjectRow[] }>(`${API}/projects?page=1&size=50`).then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const [claiming, setClaiming] = useState('');
  const list = claimable.data ?? [];
  const projectName = (id: string) => (projects.data ?? []).find((p) => p.id === id)?.name ?? '';

  const claim = (task: TaskRow) => {
    if (claiming) return;
    setClaiming(task.id);
    void sdk.api
      .request(`${API}/me/tasks/${encodeURIComponent(task.id)}/claim`, { method: 'POST' })
      .then(() => {
        setClaiming('');
        Alert.alert('认领成功', `「${task.title}」已加入我的任务`);
        claimable.reload();
      })
      .catch((e) => { setClaiming(''); Alert.alert('认领失败', errText(e)); });
  };

  return (
    <Screen>
      {claimable.loading ? <Loading /> : null}
      {!claimable.loading && list.length === 0 ? <Empty text="暂时没有可认领的任务" /> : null}
      {list.map((task) => (
        <Card key={task.id}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
              {task.title}
            </Text>
            {task.requiredAssigneeCount && task.requiredAssigneeCount > 1 ? (
              <Badge text={`需 ${task.requiredAssigneeCount} 人`} tone="accent" />
            ) : null}
          </View>
          {task.description ? (
            <Text numberOfLines={2} style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, lineHeight: 17 }}>
              {task.description}
            </Text>
          ) : null}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            {projectName(task.projectId) ? <Badge text={projectName(task.projectId)} /> : null}
            {task.dueAt ? <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>截止 {fmtDate(task.dueAt)}</Text> : null}
            <View style={{ flex: 1 }} />
            <Pressable
              onPress={() => claim(task)}
              disabled={claiming === task.id}
              style={{ paddingHorizontal: 14, paddingVertical: 7, borderRadius: 9, backgroundColor: c.accent, opacity: claiming === task.id ? 0.5 : 1 }}
            >
              <Text style={{ color: c.onAccent, fontSize: t.typography.sizeXs, fontWeight: '500' }}>认领</Text>
            </Pressable>
          </View>
        </Card>
      ))}
      <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
        认领后任务进入「我的任务」，按要求打卡并在完成后提交验收
      </Text>
    </Screen>
  );
}

/* ---------------- 项目进度仪表盘 ---------------- */

function ProjectsPage({ onOpenProject }: { onOpenProject: (project: ProjectRow) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const projects = useResource<ProjectRow[]>(
    () => sdk.api.request<ProjectRow[] | { records?: ProjectRow[] }>(`${API}/projects?page=1&size=50`).then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const list = projects.data ?? [];
  return (
    <Screen>
      {projects.loading ? <Loading /> : null}
      {!projects.loading && list.length === 0 ? <Empty text="暂无项目" /> : null}
      {list.map((p) => (
        <Card key={p.id} onPress={() => onOpenProject(p)}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>
              {p.name}
            </Text>
            <Badge text={p.enabled ? '进行中' : '已归档'} tone={p.enabled ? 'success' : 'default'} />
          </View>
          {p.description ? (
            <Text numberOfLines={2} style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, lineHeight: 17 }}>
              {p.description}
            </Text>
          ) : null}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <Icon name="person" size={12} color={c.textTertiary} />
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>成员 {(p.memberUserIds ?? []).length} 人</Text>
            <View style={{ flex: 1 }} />
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeLg }}>{'›'}</Text>
          </View>
        </Card>
      ))}
    </Screen>
  );
}

function ProjectDashboard({ projectId, onOpenTask }: { projectId: string; onOpenTask: (task: TaskRow) => void }) {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const project = useResource<ProjectRow>(() => sdk.api.request(`${API}/projects/${encodeURIComponent(projectId)}`), [projectId]);
  const details = useResource<TaskRow[]>(
    () => sdk.api
      .request<TaskRow[] | { records?: TaskRow[] }>(`${API}/projects/${encodeURIComponent(projectId)}/details?page=1&size=50`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [projectId],
  );
  const checkIns = useResource<CheckInRow[]>(
    () => sdk.api
      .request<CheckInRow[] | { records?: CheckInRow[] }>(`${API}/projects/${encodeURIComponent(projectId)}/check-ins?page=1&size=8`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [projectId],
  );
  const p = project.data;
  const tasks = details.data ?? [];
  const statusList = p?.statuses ?? [];
  const statusLabel = (code?: string) => statusList.find((s) => s.code === code)?.label ?? code ?? '—';
  const counts = new Map<string, number>();
  tasks.forEach((task) => counts.set(task.statusCode ?? '', (counts.get(task.statusCode ?? '') ?? 0) + 1));
  const doneCode = p?.doneStatusCode;
  const doneCount = counts.get(doneCode ?? '') ?? 0;
  const publishedTasks = tasks.filter((task) => task.published !== false);

  if (project.loading && !p) return <Screen><Loading /></Screen>;
  if (!p) return <Screen><Empty text="项目不存在" /></Screen>;

  return (
    <Screen>
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeMd, fontWeight: '700', flex: 1 }}>
            {p.name}
          </Text>
          <Badge text={p.enabled ? '进行中' : '已归档'} tone={p.enabled ? 'success' : 'default'} />
        </View>
        {p.description ? (
          <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeSm, lineHeight: 20 }}>{p.description}</Text>
        ) : null}
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
          成员 {(p.memberUserIds ?? []).length} 人 · 任务 {tasks.length} 项{(p.minCheckInIntervalMinutes ?? 0) > 0 ? ` · 打卡间隔 ≥ ${p.minCheckInIntervalMinutes} 分钟` : ''}
        </Text>
      </Card>

      {/* 仪表盘：状态分布 */}
      <SectionTitle title="进度仪表盘" actionText={`完成 ${doneCount}/${tasks.length}`} />
      <Card>
        {tasks.length === 0 ? <Empty text="还没有任务" /> : null}
        {statusList.filter((s) => (counts.get(s.code) ?? 0) > 0).map((s) => {
          const n = counts.get(s.code) ?? 0;
          return (
            <View key={s.code} style={{ gap: 4, paddingVertical: 5 }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, width: 90 }}>{s.label || s.code}</Text>
                <View style={{ flex: 1, height: 8, borderRadius: 4, backgroundColor: c.fillHover, overflow: 'hidden' }}>
                  <View style={{ width: `${Math.round((n / Math.max(tasks.length, 1)) * 100)}%`, height: 8, borderRadius: 4, backgroundColor: s.terminal ? (c.success ?? '#16a34a') : c.accent }} />
                </View>
                <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs, fontWeight: '600', width: 24, textAlign: 'right' }}>{n}</Text>
              </View>
            </View>
          );
        })}
        {p?.minecraftPolicy?.enabled ? (
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
            Minecraft 时长核验：≥ {p.minecraftPolicy.requiredOnlineMinutes ?? 0} 分钟{p.minecraftPolicy.autoCheckInEnabled ? ' · 自动打卡已开' : ''}
          </Text>
        ) : null}
      </Card>

      <SectionTitle title="任务列表" actionText={`${publishedTasks.length} 项`} />
      {details.loading ? <Loading /> : null}
      {!details.loading && publishedTasks.length === 0 ? <Empty text="暂无已发布任务" /> : null}
      {publishedTasks.map((task) => (
        <Card key={task.id} onPress={() => onOpenTask(task)}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, fontWeight: '600', flex: 1 }}>
              {task.title}
            </Text>
            {task.pendingAcceptance ? <Badge text="待验收" tone="warning" /> : <Badge text={statusLabel(task.statusCode)} tone={task.statusCode === doneCode ? 'success' : 'default'} />}
          </View>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
              负责人 {(task.assigneeUserIds ?? []).length} 人
            </Text>
            {task.dueAt ? <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>截止 {fmtDate(task.dueAt)}</Text> : null}
            <View style={{ flex: 1 }} />
            <Icon name="forward" size={13} color={c.textTertiary} />
          </View>
        </Card>
      ))}

      <SectionTitle title="最近打卡" />
      {checkIns.loading ? <Loading /> : null}
      {!checkIns.loading && (checkIns.data ?? []).length === 0 ? <Empty text="还没有打卡记录" /> : null}
      {(checkIns.data ?? []).length > 0 ? (
        <Card>
          {(checkIns.data ?? []).map((ci, i) => (
            <View key={ci.id}>
              {i > 0 ? <View style={{ height: 1, backgroundColor: c.borderSubtle }} /> : null}
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 9 }}>
                <View style={{ width: 7, height: 7, borderRadius: 4, backgroundColor: c.success ?? '#16a34a' }} />
                <Text numberOfLines={1} style={{ color: c.textPrimary, fontSize: t.typography.sizeSm, flex: 1 }}>
                  {ci.summary || checkInTypeLabel(ci.type ?? '')}
                </Text>
                <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>{fmtDateTime(ci.createdAt)}</Text>
              </View>
            </View>
          ))}
        </Card>
      ) : null}
    </Screen>
  );
}

/* ---------------- 验收审批（acceptor / 管理员） ---------------- */

function AcceptancePage() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const pending = useResource<PendingRow[]>(
    () => sdk.api
      .request<PendingRow[] | { records?: PendingRow[] }>(`${API}/acceptance/pending?page=1&size=30`)
      .then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const [rejecting, setRejecting] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState('');
  const list = pending.data ?? [];

  const review = (row: PendingRow, accept: boolean) => {
    const detailId = row.detailId ?? row.id ?? '';
    if (!detailId || busy) return;
    if (!accept && !reason.trim()) {
      Alert.alert('请填写驳回原因', '驳回需要说明原因，负责人才能整改重交');
      return;
    }
    setBusy(accept ? 'accept' : 'reject');
    void sdk.api
      .request(`${API}/details/${encodeURIComponent(detailId)}/${accept ? 'accept' : 'reject'}`, {
        method: 'POST',
        body: accept ? {} : { reason: reason.trim() },
      })
      .then(() => {
        setBusy('');
        setRejecting('');
        setReason('');
        pending.reload();
        Alert.alert(accept ? '已通过' : '已驳回', accept ? '任务标记完成' : '已通知负责人整改');
      })
      .catch((e) => { setBusy(''); Alert.alert('操作失败', errText(e)); });
  };

  return (
    <Screen>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <Badge text={`待验收 ${list.length}`} tone={list.length > 0 ? 'warning' : 'default'} solid={list.length > 0} />
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>通过即完成任务，驳回需填写原因</Text>
      </View>
      {pending.loading ? <Loading /> : null}
      {!pending.loading && list.length === 0 ? <Empty text="暂无待验收提交" /> : null}
      {list.map((row, i) => {
        const detailId = row.detailId ?? row.id ?? '';
        const files = (row.acceptanceFiles ?? []).slice(0, 2);
        const isRejecting = rejecting === detailId;
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
                  {[row.projectName, row.submitterName, fmtDateTime(row.createdAt)].filter(Boolean).join(' · ')}
                </Text>
              </View>
            </View>
            {row.acceptanceSummary ? (
              <Text numberOfLines={3} style={{ color: c.textSecondary, fontSize: t.typography.sizeXs + 1, lineHeight: 17 }}>
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
            {isRejecting ? (
              <Field value={reason} onChangeText={setReason} placeholder="驳回原因（必填，将通知负责人）" multiline height={60} />
            ) : null}
            <View style={{ flexDirection: 'row', gap: 8, justifyContent: 'flex-end' }}>
              {isRejecting ? (
                <Pressable
                  onPress={() => { setRejecting(''); setReason(''); }}
                  style={{ paddingHorizontal: 14, paddingVertical: 7, borderRadius: 9, backgroundColor: c.fillHover }}
                >
                  <Text style={{ color: c.textSecondary, fontSize: t.typography.sizeXs, fontWeight: '500' }}>取消</Text>
                </Pressable>
              ) : null}
              <Pressable
                onPress={() => (isRejecting ? review(row, false) : setRejecting(detailId))}
                style={{ paddingHorizontal: 14, paddingVertical: 7, borderRadius: 9, borderWidth: 1, borderColor: c.danger ?? '#dc2626', backgroundColor: c.bgSurface, opacity: busy === 'reject' ? 0.5 : 1 }}
              >
                <Text style={{ color: c.danger ?? '#dc2626', fontSize: t.typography.sizeXs, fontWeight: '500' }}>驳回</Text>
              </Pressable>
              <Pressable
                onPress={() => review(row, true)}
                style={{ paddingHorizontal: 14, paddingVertical: 7, borderRadius: 9, backgroundColor: c.success ?? '#16a34a', opacity: busy === 'accept' ? 0.5 : 1 }}
              >
                <Text style={{ color: '#ffffff', fontSize: t.typography.sizeXs, fontWeight: '500' }}>通过</Text>
              </Pressable>
            </View>
          </Card>
        );
      })}
    </Screen>
  );
}

/* ---------------- 发布任务（管理员） ---------------- */

function PublishPage() {
  const sdk = useSdk();
  const t = sdk.theme;
  const c = t.colors;
  const projects = useResource<ProjectRow[]>(
    () => sdk.api.request<ProjectRow[] | { records?: ProjectRow[] }>(`${API}/projects?page=1&size=50`).then((r) => (Array.isArray(r) ? r : (r?.records ?? []))),
    [sdk],
  );
  const [projectId, setProjectId] = useState('');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [count, setCount] = useState('1');
  const [busy, setBusy] = useState(false);
  const list = (projects.data ?? []).filter((p) => p.enabled);

  const submit = () => {
    const target = projectId || list[0]?.id;
    if (!target || busy) return;
    if (!title.trim()) {
      Alert.alert('请填写任务标题');
      return;
    }
    const requireCount = Number(count) > 0 ? Number(count) : 1;
    setBusy(true);
    void sdk.api
      .request(`${API}/admin/projects/${encodeURIComponent(target)}/details`, {
        method: 'POST',
        body: {
          title: title.trim(),
          description: description.trim(),
          assignmentMode: 'CLAIM',
          requiredAssigneeCount: requireCount,
          candidateUserIds: [],
          published: false,
        },
      })
      .then((created) => {
        const id = (created as { id?: string }).id ?? '';
        const publish = id
          ? sdk.api.request(`${API}/admin/details/${encodeURIComponent(id)}/publish`, { method: 'POST' })
          : Promise.resolve();
        return publish.then(() => {
          setBusy(false);
          setTitle('');
          setDescription('');
          Alert.alert('已发布', '任务已进入认领中心');
        });
      })
      .catch((e) => { setBusy(false); Alert.alert('发布失败', errText(e)); });
  };

  return (
    <Screen>
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Icon name="add" size={16} color={c.accent} />
          <Text style={{ color: c.textPrimary, fontSize: t.typography.sizeSm + 1, fontWeight: '700', flex: 1 }}>发布新任务</Text>
        </View>
        {projects.loading ? <Loading /> : null}
        <View style={{ flexDirection: 'row', gap: 6, flexWrap: 'wrap' }}>
          {list.map((p) => (
            <Pressable
              key={p.id}
              onPress={() => setProjectId(p.id)}
              style={{
                paddingHorizontal: 10, paddingVertical: 6, borderRadius: 999,
                backgroundColor: (projectId || list[0]?.id) === p.id ? c.accent : c.bgSurface,
                borderWidth: 1, borderColor: (projectId || list[0]?.id) === p.id ? c.accent : c.borderSubtle,
              }}
            >
              <Text style={{ color: (projectId || list[0]?.id) === p.id ? c.onAccent : c.textSecondary, fontSize: t.typography.sizeXs }}>
                {p.name}
              </Text>
            </Pressable>
          ))}
        </View>
        <Field value={title} onChangeText={setTitle} placeholder="任务标题（必填）" />
        <Field value={description} onChangeText={setDescription} placeholder="任务说明、验收要求" multiline />
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs, flex: 1 }}>认领人数上限</Text>
          <View style={{ width: 90 }}>
            <Field value={count} onChangeText={setCount} placeholder="1" height={38} />
          </View>
        </View>
        <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
          发布为认领任务；指派到人的任务请在网页端创建
        </Text>
        <PrimaryButton title="发布任务" onPress={submit} busy={busy} />
      </Card>
      <Text style={{ color: c.textTertiary, fontSize: t.typography.sizeXs }}>
        发布后任务立即进入认领中心（认领模式）或任务列表（指派模式）
      </Text>
    </Screen>
  );
}

/* ---------------- 根组件 ---------------- */

type View_ =
  | { name: 'home' }
  | { name: 'task'; id: string; projectId?: string }
  | { name: 'claim' }
  | { name: 'projects' }
  | { name: 'project'; id: string }
  | { name: 'acceptance' }
  | { name: 'publish' };

function seedStack(route?: string): View_[] {
  if (route === '/acceptance') return [{ name: 'home' }, { name: 'acceptance' }];
  if (route === '/publish') return [{ name: 'home' }, { name: 'publish' }];
  return [{ name: 'home' }];
}

function ProgressApp({ initialRoute }: { initialRoute?: string }) {
  const [stack, setStack] = useState<View_[]>(() => seedStack(initialRoute));
  const view = stack[stack.length - 1];

  useEffect(() => {
    setStack(seedStack(initialRoute));
  }, [initialRoute]);

  useEffect(() => {
    const titles: Record<View_['name'], string> = {
      home: '我的任务', task: '任务详情', claim: '认领中心', projects: '项目进度',
      project: '项目仪表盘', acceptance: '验收审批', publish: '发布任务',
    };
    currentSdk?.navigation?.setTitle(titles[view.name]);
    currentSdk?.navigation?.setBackAction?.(view.name === 'home' ? null : () => setStack((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev)));
  }, [view]);

  const push = (next: View_) => setStack((prev) => [...prev, next]);

  return view.name === 'home' ? (
    <HomePage
      onOpenTask={(task) => push({ name: 'task', id: task.id, projectId: task.projectId })}
      onOpenClaim={() => push({ name: 'claim' })}
      onOpenProjects={() => push({ name: 'projects' })}
      onOpenAcceptance={() => push({ name: 'acceptance' })}
    />
  ) : view.name === 'task' ? (
    <TaskPage taskId={view.id} projectId={view.projectId} onBack={() => setStack((prev) => prev.slice(0, -1))} />
  ) : view.name === 'claim' ? (
    <ClaimPage />
  ) : view.name === 'projects' ? (
    <ProjectsPage onOpenProject={(p) => push({ name: 'project', id: p.id })} />
  ) : view.name === 'project' ? (
    <ProjectDashboard projectId={view.id} onOpenTask={(task) => push({ name: 'task', id: task.id, projectId: task.projectId })} />
  ) : view.name === 'acceptance' ? (
    <AcceptancePage />
  ) : (
    <PublishPage />
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

export default ProgressModule;
