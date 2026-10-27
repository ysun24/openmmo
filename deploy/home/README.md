# OpenMMO 家用服务器（Windows + Docker Desktop + Pinggy）

把整个游戏（数据库、登录服、游戏服、网页网关）跑在家里的 Windows 电脑上，朋友用手机或电脑的浏览器打开
`https://<你的子域名>.a.pinggy.link` 就能玩。只有朋友能进：网站有一层 Pinggy 的访问密码，注册还需要你发的邀请码。

不需要路由器端口映射、不需要公网 IP：Pinggy 隧道是从你电脑主动连出去的（SSH，443 端口）。
电脑上只在 `127.0.0.1:8080` 开了一个本机端口，局域网里的其他设备也访问不到。

---

## 1. 准备 Windows 电脑（一次）

1. **安装 Docker Desktop**：<https://www.docker.com/products/docker-desktop/>，安装时选 **WSL 2**（默认）。
   装完重启，打开 Docker Desktop，等左下角显示 *Engine running*。
2. Docker Desktop → Settings → General → 勾选 **Start Docker Desktop when you sign in to your computer**（开机自动启动）。
3. （建议）Settings → Resources → WSL 内存默认即可；本游戏全部容器大约用 2–3 GB 内存。
4. **不让电脑睡眠**（睡眠了朋友就掉线）：以管理员身份打开 PowerShell：
   ```powershell
   powercfg /change standby-timeout-ac 0
   powercfg /change hibernate-timeout-ac 0
   ```
   或 设置 → 系统 → 电源 → “接通电源后，在此时间后进入睡眠状态” 选 **从不**。屏幕可以照常关闭。
5. （建议）买一个 **Pinggy Pro** 并在 <https://dashboard.pinggy.io> 里：Tokens 页复制你的 token；
   Subdomains/Domains 页建一个固定子域名并把它拖到这个 token 上。
   不用 token 也能跑（免费版），但网址每次重连都会变、每 60 分钟断一次。

## 2. 把压缩包从 clouddesk 拿到 Windows

压缩包在 clouddesk 上：`~/projects/OpenMMO-deploy/deploy/home/dist/openmmo-home-<版本>.zip`。

在 **Mac** 的终端里：
```bash
scp <clouddesk主机名>:/local/home/yichengs/projects/OpenMMO-deploy/deploy/home/dist/openmmo-home-*.zip ~/Downloads/
```
再从 Mac 拷到 Windows：U 盘、AirDrop 不行就用局域网共享，或者在 Windows 上（Win10/11 自带 OpenSSH 客户端）：
```powershell
scp <你的Mac用户名>@<Mac的IP>:Downloads/openmmo-home-*.zip $env:USERPROFILE\Downloads\
```
（Mac 需开启 系统设置 → 通用 → 共享 → 远程登录。）

## 3. 安装（一条命令）

1. 右键 zip → **全部解压缩**，比如解到 `下载\openmmo-home-<版本>`。
2. 在那个文件夹的地址栏输入 `powershell` 回车，打开 PowerShell，运行：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\setup.ps1
   ```
3. 它会问 Pinggy token（输入时不显示，直接回车 = 免费版），再问固定子域名（例如 `mymon.a.pinggy.link`，没有就回车）。
4. 等几分钟（第一次要导入约 250 MB 的镜像、创建数据库）。最后会打印：
   - 本机地址 `http://127.0.0.1:8080/`
   - 公网地址 `https://....pinggy.link/`
   - 管理员账号 `admin`，密码在 `.env` 文件的 `OPENMMO_ADMIN_PASSWORD`
   - 网站访问账号 `friends`，密码在 `.env` 的 `SITE_PASS`（发给朋友）

服务器装在 `%USERPROFILE%\OpenMMO`（例如 `C:\Users\你\OpenMMO`），以后所有命令都在这个文件夹里运行。
`.env` 里是所有密码，`setup.ps1` 已经把它设成只有你的 Windows 账号能读。**不要把 `.env` 发给别人。**

`setup.ps1` 可以随时重新运行：已有的密码、数据都不会被改动。

## 4. 让朋友进来：邀请码

```powershell
cd $env:USERPROFILE\OpenMMO
.\invite.ps1                      # 1 个码，用 1 次，7 天有效
.\invite.ps1 -Uses 5 -Days 30     # 1 个码，能用 5 次，30 天有效
.\invite.ps1 -Count 3             # 一次生成 3 个
.\invite.ps1 -List                # 查看还能用的码
.\invite.ps1 -Revoke ABCD-EFGH-JKLM
```
有公网地址时会顺便打印一个邀请链接 `https://.../#invite=XXXX-XXXX-XXXX`，朋友点开就直接是注册页、邀请码已填好。

发给朋友的东西：网址 + 网站账号/密码（`friends` / `SITE_PASS`）+ 邀请码。朋友在页面上点 **注册 / Register**：
用户名 3–16 位（字母、数字、下划线，不区分大小写），密码至少 8 位，填邀请码，注册后自动进入游戏。

## 5. 日常操作

| 做什么 | 命令（在 `%USERPROFILE%\OpenMMO` 里） |
|---|---|
| 启动 | `.\start.ps1` |
| 停止（会先保存所有角色） | `.\stop.ps1` |
| 状态 + 当前公网地址 | `.\status.ps1` |
| 看日志 | `.\logs.ps1`，`.\logs.ps1 tunnel`，`.\logs.ps1 gateway -Follow` |
| 备份 | `.\backup.ps1` |
| 恢复 | `.\restore.ps1`（最新备份）或 `.\restore.ps1 -From .\backups\2026-10-27_031500` |
| 开机自动启动 | `.\register-autostart.ps1`（取消：`.\register-autostart.ps1 -Remove`） |

第一次运行 `.ps1` 若提示“禁止运行脚本”，先执行一次：
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`（或者每次都用 `powershell -ExecutionPolicy Bypass -File .\xxx.ps1`）。

**开机自启**：Docker Desktop 设为登录时启动（第 1 步），再运行一次 `register-autostart.ps1`。它在任务计划程序里建了
“OpenMMO server” 任务：登录 1 分钟后等 Docker 就绪，然后运行 `start.ps1`。其实容器本身都是
`restart: unless-stopped`，Docker 起来后会自己恢复；这个任务只是兜底。需要你登录 Windows（可以设自动登录）。

## 6. 备份与恢复

- `backup.ps1` 把两个数据库（`pg_dump`）、服务器密钥、`.env` 备份到 `OpenMMO\backups\<日期_时间>\`，只保留最近 14 份。
  服务器运行中也可以备份。建议每周跑一次，或者在任务计划程序里加个每天的任务：
  ```powershell
  $a = New-ScheduledTaskAction -Execute powershell.exe -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$env:USERPROFILE\OpenMMO\backup.ps1`""
  Register-ScheduledTask -TaskName 'OpenMMO backup' -Action $a -Trigger (New-ScheduledTaskTrigger -Daily -At 5am)
  ```
- 最好把 `backups` 文件夹再拷一份到别的盘或网盘（里面有 `.env` 的副本，注意保密）。
- `restore.ps1` 会先自动再做一次备份，然后用指定的备份覆盖**所有**账号和游戏数据，需要输入 `yes` 确认。
  只有密钥丢了（例如删过 Docker 卷）才需要加 `-WithKeys`。

## 7. 更新到新版本

1. 把新的 `openmmo-home-<新版本>.zip` 拷到 Windows 解压（随便哪个文件夹，**不要**解到 `OpenMMO` 里面）。
2. 在新解压的文件夹里运行：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\update.ps1
   ```
   它会：先备份 → 导入新镜像 → 更新 `OpenMMO` 里的 compose 和脚本 → 保留 `.env`（新版本新增的设置会自动加上）→
   重建容器。所有数据在 Docker 卷里，不受影响；数据库结构升级在服务启动时自动完成。
3. 旧镜像可以删掉：`docker image rm openmmo:<旧版本>`。

## 8. 设置项（`.env`）

改完 `.env` 后运行 `.\start.ps1` 生效。常用的：

- `TUNNEL=off`：关掉公网，只能本机 `http://127.0.0.1:8080` 玩。
- `PINGGY_TOKEN` / `PINGGY_SUBDOMAIN`：Pinggy token 和绑定在它上面的固定域名。
- `SITE_USER` / `SITE_PASS`：网站访问账号密码。两个都清空 = 不要这一层（只靠邀请码 + 游戏账号）。
- `PINGGY_ALLOW_IPS`：只允许这些 IP（逗号分隔，可写 CIDR），例如只让家里和朋友家的宽带进来。
- `LOCAL_PORT`：本机端口（默认 8080）。
- 其它每一项在 `.env.example` 里都有说明。

## 9. 排错

- **`setup.ps1` 说 Docker 没运行**：打开 Docker Desktop，等 *Engine running*。
- **看状态**：`.\status.ps1`，所有服务应为 `running`，`keys` 为 `exited (0)` 是正常的（只在第一次生成密钥）。
- **没有公网地址**：`.\logs.ps1 tunnel`。看到 `ssh exited ... reconnecting` 一直重复，多半是 token 错了、
  网络不通，或者公司/学校网络屏蔽了 Pinggy；它会自动无限重试。`PUBLIC URL:` 那一行就是地址。
- **朋友打开网页要输密码**：这是 `SITE_USER`/`SITE_PASS`（不是游戏账号）。
- **网页能开但一直“Connecting… / Cannot reach the gateway”**：`.\logs.ps1 gateway`。若有
  `refused ws upgrade from origin`，说明网址不在允许列表里：用固定域名时确认 `PINGGY_SUBDOMAIN` 写对了
  （不带 `https://`），或者在 `GATEWAY_ALLOW_ORIGIN` 里写上完整的 `https://你的域名`。
- **iPhone Safari 能打开页面但进不了游戏**：个别 Safari 版本的 WebSocket 不带网站密码。试试关掉网站密码
  （清空 `SITE_USER` 和 `SITE_PASS`，靠邀请码挡人），或改用 `PINGGY_ALLOW_IPS`。
- **登录提示 RATE_LIMITED**：同一个用户名 15 分钟内输错密码 10 次会被锁 15 分钟；等一会儿即可。
- **忘了管理员密码**：在 `.env` 的 `OPENMMO_ADMIN_PASSWORD`（它只在第一次启动时用来创建账号，之后改它没用）。
- **完全重来（会删光所有数据！）**：`docker compose -p openmmo --env-file .env -f compose.yml down -v`，然后重新 `setup.ps1`。
