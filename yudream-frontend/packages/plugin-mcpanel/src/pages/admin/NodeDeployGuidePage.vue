<script setup lang="ts">
import type { YuDreamPluginSdk } from '@yudream/plugin-sdk'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { FaAlert, FaButton, FaCard, FaInput, FaPageHeader, FaPageMain } from '@yudream/components'
import CodeBlock from '../../components/CodeBlock.vue'

/**
 * 节点部署指南（静态教程页，无业务请求）：
 * 面板地址可编辑，用于实时生成下方三种部署方式（二进制 / Docker / Compose）
 * 的命令块；部署命令口径与节点程序仓库 deploy/ 产物一致，并对齐生产实测形态
 * （bind-mount /data + MCNODE_DATA_HOST_ROOT + env-file 凭据）。
 */
// 本页为静态教程，不消费 SDK；保留 prop 声明以对齐全页面组件签名。
defineProps<{ sdk: YuDreamPluginSdk }>()

const router = useRouter()

/** 用于生成命令的面板地址：默认取当前站点地址，节点走其它地址访问面板时可改。 */
const panelUrl = ref(window.location.origin)

const safePanelUrl = computed(() => panelUrl.value.trim() || 'https://panel.example.com')

const NODE_IMAGE = 'registry.yudream.online/yda-skin/mcpanel-node:0.7.2'

const DATA_DIR = '/opt/mcpanel-node/data'

const joinCommand = computed(() => [
  '# 按提示输入面板签发的一次性注册 token（stdin 输入，不进 shell history）',
  `mcpanel-node join ${safePanelUrl.value} \\`,
  `  --data-dir ${DATA_DIR} \\`,
  '  --listen 0.0.0.0:7443',
  '# 看到「注册成功」并保持前台运行后，Ctrl+C 退出，交给 systemd 常驻',
].join('\n'))

const binaryInstallCommand = [
  '# 获取对应平台的 mcpanel-node 可执行文件后，放到固定路径并准备数据目录',
  'sudo install -m 755 mcpanel-node-linux-amd64 /usr/local/bin/mcpanel-node',
  `sudo mkdir -p ${DATA_DIR}`,
].join('\n')

const systemdUnit = [
  '# /etc/systemd/system/mcpanel-node.service',
  '[Unit]',
  'Description=MCPanel Node Agent',
  'After=network-online.target docker.service',
  'Wants=network-online.target',
  '',
  '[Service]',
  `ExecStart=/usr/local/bin/mcpanel-node serve --data-dir ${DATA_DIR} --listen 0.0.0.0:7443`,
  'Restart=always',
  'RestartSec=5',
  '',
  '[Install]',
  'WantedBy=multi-user.target',
].join('\n')

const systemdEnableCommand = [
  'sudo systemctl daemon-reload',
  'sudo systemctl enable --now mcpanel-node',
  'systemctl status mcpanel-node',
].join('\n')

const dockerEnvFileCommand = [
  'sudo mkdir -p /etc/mcpanel-node ' + DATA_DIR,
  "sudo tee /etc/mcpanel-node/enroll.env >/dev/null <<'EOF'",
  `MCNODE_PANEL_URL=${safePanelUrl.value}`,
  'MCNODE_ENROLL_TOKEN=粘贴面板签发的一次性token',
  'EOF',
  'sudo chmod 600 /etc/mcpanel-node/enroll.env',
].join('\n')

const dockerRunCommand = [
  'docker run -d --name mcpanel-node \\',
  '  --restart unless-stopped \\',
  '  -v /var/run/docker.sock:/var/run/docker.sock \\',
  `  -v ${DATA_DIR}:/data \\`,
  `  -e MCNODE_DATA_HOST_ROOT=${DATA_DIR} \\`,
  '  -p 7443:7443 \\',
  '  -p 2121-2136:2121-2136 \\',
  '  --env-file /etc/mcpanel-node/enroll.env \\',
  `  ${NODE_IMAGE}`,
].join('\n')

const dockerTokenCleanupCommand = [
  '# 注册成功（面板节点变「在线」）后，从 env 文件删除 token 行：',
  '# 凭据已落盘 /data，重启不再需要 token；保留过期 token 只有泄露风险',
  "sudo sed -i '/MCNODE_ENROLL_TOKEN/d' /etc/mcpanel-node/enroll.env",
].join('\n')

const composeFile = [
  'services:',
  '  mcpanel-node:',
  `    image: ${NODE_IMAGE}`,
  '    container_name: mcpanel-node',
  '    restart: unless-stopped',
  '    ports:',
  '      - "7443:7443"',
  '      - "2121-2136:2121-2136"',
  '    volumes:',
  '      - /var/run/docker.sock:/var/run/docker.sock',
  `      - ${DATA_DIR}:/data`,
  '    environment:',
  `      MCNODE_DATA_HOST_ROOT: ${DATA_DIR}`,
  `      MCNODE_PANEL_URL: ${safePanelUrl.value}`,
  '      MCNODE_ENROLL_TOKEN: 粘贴一次性token',
].join('\n')

const composeUpCommand = [
  'docker compose up -d',
  '# 注册成功后删除 compose 文件里的 MCNODE_ENROLL_TOKEN 行，再执行 docker compose up -d 生效',
].join('\n')
</script>

<template>
  <FaPageHeader title="节点部署指南" description="把一台装好 Docker 的机器接入面板成为节点：二进制 / Docker / Docker Compose 三种方式">
    <FaButton variant="outline" @click="router.push('/platform/plugins/mcpanel/admin/nodes')">
      前往节点管理
    </FaButton>
  </FaPageHeader>
  <FaPageMain>
    <div class="mcp-page-stack">
      <FaCard title="部署前准备">
        <ul class="mcp-step-list">
          <li>一台 Linux 机器（amd64 / arm64），已安装 Docker——面板上的 MC 实例都以容器跑在节点上。</li>
          <li>面板服务器能访问节点的 <code class="mcp-inline-code">7443</code> 端口（控制信道 wss；建议防火墙只放行面板出口地址）。</li>
          <li>SFTP 网关模式下，面板服务器还要能访问节点的 <code class="mcp-inline-code">2121-2136</code> 端口段（临时 SFTP 端口池；容器部署需发布并在防火墙放行）。</li>
          <li>节点能访问面板站点地址（注册时节点程序要回调面板 HTTPS 接口）。</li>
          <li>账号具备面板管理权限（新增节点与签发注册凭据用）。</li>
        </ul>
      </FaCard>

      <FaCard title="接入流程" description="无论用哪种部署方式，面板侧的准备工作相同">
        <div class="mcp-form">
          <label class="mcp-form-item">
            <span class="mcp-form-label">面板地址（用于生成下方命令）</span>
            <FaInput v-model="panelUrl" clearable placeholder="https://panel.example.com" class="w-full" />
            <span class="mcp-form-hint">默认取当前站点地址；节点经其它域名或端口访问面板时，请改成节点实际使用的地址。</span>
          </label>
          <ol class="mcp-step-list">
            <li>在「节点管理」新增节点：填名称与控制信道地址（<code class="mcp-inline-code">wss://节点地址:7443</code>，只填地址不带路径，必须是<b>面板服务器可达</b>的地址）。</li>
            <li>打开节点详情，签发一次性注册凭据：token 10 分钟有效、仅当次展示，遗失需重签。</li>
            <li>在节点机器上按下方任一方式部署，粘贴 token 完成注册。</li>
            <li>回到「节点管理」，节点状态变「在线」即接入完成；之后可在该节点上创建实例。</li>
          </ol>
        </div>
      </FaCard>

      <FaCard title="方式一：二进制部署（systemd 托管）" description="单二进制直接跑在宿主机，systemd 负责开机自启与崩溃拉起">
        <div class="mcp-form">
          <CodeBlock label="① 安装二进制并准备数据目录" :code="binaryInstallCommand" />
          <CodeBlock label="② 注册并试运行（粘贴 token）" :code="joinCommand" />
          <CodeBlock label="③ 写入 systemd 服务单元" :code="systemdUnit" />
          <CodeBlock label="④ 启动并设为开机自启" :code="systemdEnableCommand" />
          <span class="mcp-form-hint">Windows 机器可用 <code class="mcp-inline-code">mcpanel-node-windows-amd64.exe</code> 以相同参数手动运行（不含 systemd 托管）。</span>
        </div>
      </FaCard>

      <FaCard title="方式二：Docker 单命令运行" description="镜像已托管在私有仓库；实例数据落在宿主机目录，升级只换镜像">
        <div class="mcp-form">
          <CodeBlock label="① 写入注册凭据文件（0600 权限）" :code="dockerEnvFileCommand" />
          <CodeBlock label="② 运行节点容器（serve 启动时自动注册）" :code="dockerRunCommand" />
          <CodeBlock label="③ 注册成功后清理 token" :code="dockerTokenCleanupCommand" />
        </div>
      </FaCard>

      <FaCard title="方式三：Docker Compose" description="与方式二等价，适合和其他服务一起用 compose 统一管理">
        <div class="mcp-form">
          <CodeBlock label="① docker-compose.yml" :code="composeFile" />
          <CodeBlock label="② 启动并完成注册" :code="composeUpCommand" />
          <span class="mcp-form-hint">compose 文件里含 token 时注意文件权限（建议 600）；注册完成后删除 token 行再 <code class="mcp-inline-code">docker compose up -d</code>。</span>
        </div>
      </FaCard>

      <FaCard title="常见问题与注意事项">
        <div class="mcp-form">
          <FaAlert title="面板地址必须 https" variant="default">
            <template #description>
              明文 http 仅放行 loopback、host.docker.internal 与私网地址；公网 / 域名面板一律 https。Docker 部署且面板只有 http（如开发环境 :9002）时：把 <code class="mcp-inline-code">MCNODE_PANEL_URL</code> 写成 <code class="mcp-inline-code">http://host.docker.internal:9002</code>，并在 <code class="mcp-inline-code">docker run</code> 加 <code class="mcp-inline-code">--add-host=host.docker.internal:host-gateway</code>。该错误发生在配置校验阶段、注册请求发出之前，token 未被消费，修正后无需重签。
            </template>
          </FaAlert>
          <FaAlert title="注册 token 是一次性凭据" variant="default">
            <template #description>
              10 分钟有效、注册成功或过期即失效；只应通过 stdin 或 0600 env 文件进入进程，不要写进命令行参数（会落入 shell history）。注册请求已发出后的失败需要重新签发。
            </template>
          </FaAlert>
          <FaAlert title="容器化部署必须 bind-mount /data 并设置 MCNODE_DATA_HOST_ROOT" variant="default">
            <template #description>
              节点创建实例时要把实例数据目录以<b>宿主真实路径</b>挂给实例容器；named volume 的宿主路径不可控，会导致实例创建失败。<code class="mcp-inline-code">MCNODE_DATA_HOST_ROOT</code> 固定填宿主侧 <code class="mcp-inline-code">/data</code> 挂载的源目录。
            </template>
          </FaAlert>
          <FaAlert title="SFTP 端口池：容器部署必须发布 2121-2136" variant="default">
            <template #description>
              节点为每次「开通 SFTP」在 2121-2136 段内按实例哈希选端口监听（基点可用 <code class="mcp-inline-code">MCNODE_FTP_BASE_PORT</code> 调整）。二进制部署直接监听宿主网络无需处理；容器部署必须 <code class="mcp-inline-code">-p 2121-2136:2121-2136</code> 发布，并在防火墙/安全组放行、保证面板服务器可达——SFTP 网关认证通过后面板会回源拨该端口段，缺发布会表现为「登录成功后连接被断开」。
            </template>
          </FaAlert>
          <FaAlert title="升级节点" variant="default">
            <template #description>
              Docker 部署：<code class="mcp-inline-code">docker pull</code> 新版本镜像 → <code class="mcp-inline-code">docker rm -f mcpanel-node</code> → 按原命令重建，数据与凭据都在 /data，升级不影响已注册状态与实例。二进制部署：替换可执行文件后 <code class="mcp-inline-code">systemctl restart mcpanel-node</code>。节点版本需满足面板功能要求（回收站等能力需节点 0.4.0+；安装文件级明细需 0.5.0+；安装停滞自愈与自动重试需 0.5.1+；MR/CF 换源下载回退需 0.6.0+；大整合包安装（帧上限协商）需 0.6.1+；存量实例容器策略自动对齐（旧节点创建的容器升级后自动重建修复 EACCES 启动失败）需 0.6.3+；启动中状态、启动成功检测与启动阶段实时输出需 0.7.0+；实例数据目录权限自动修复（启动前自动放开节点侧写入与容器内非 root 用户的属主错位，根治模组写 config 报 EACCES 启动即崩）需 0.7.1+）。
            </template>
          </FaAlert>
          <FaAlert title="控制信道地址怎么填" variant="default">
            <template #description>
              面板侧节点表单的地址填面板服务器可达的 <code class="mcp-inline-code">wss://host[:port]</code>，不带 /control 路径；节点在内网或无公网映射时，先做端口映射或内网穿透（frp、tailscale 等），填穿透后的地址。节点使用自签证书时，面板侧 TLS 校验选「指纹钉住」并把指纹留空——注册时节点上报的证书指纹会自动登记。
            </template>
          </FaAlert>
        </div>
      </FaCard>
    </div>
  </FaPageMain>
</template>
