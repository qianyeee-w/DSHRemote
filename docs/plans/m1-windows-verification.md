# M1 T10：在 Windows 官方 Desktop 上验证插件

M1 的插件已经在 Linux 和 Windows 的 `dsh web` 上通过了全部自动化测试（单元、加载检查、契约、端到端）。这份清单验证的是**只有真机才能确认的部分**：

- 插件能装进官方 Desktop，并且正常加载。
- 插件在 Desktop 里能读到 DSH 的版本（目前只在 `dsh web` 上验证过）。
- Desktop 里插件到 DSH 的连接正常。
- Windows 防火墙放行后，局域网里的其他设备能访问插件。
- 完全退出并重新打开 Desktop 后（DSH 端口会变），已配对的设备不需要重新配对。
- 和 Desktop 窗口同时使用时互不影响。

验证用的脚本是 `plugin/scripts/verify.mjs`。它模拟手机走一遍插件的对外接口，配对的是一台临时设备，检查完会自动取消配对。每次运行最后输出一行 JSON，里面不包含 token 和配对码，**把这些 JSON 行发给开发者即可**。

预计用时 20 分钟左右。

## 0. 准备

在 Windows 电脑上：

- 已安装官方 DeepSeek Harness Desktop，DSH 版本为 0.2.2-rc.1（脚本会读出实际版本）。
- 已安装 Node.js 20 或更高版本、Git。在 PowerShell 中确认：

  ```powershell
  node -v
  git --version
  ```

在局域网里另找一台机器做第 5 步，例如 Linux 开发服务器。它也需要 Node.js 20+ 和 Git。

## 1. 获取代码

```powershell
cd $HOME
git clone https://github.com/qianyeee-w/DSHRemote.git
cd DSHRemote\plugin
npm install --omit=dev
```

已经克隆过的话，在 `DSHRemote` 目录下运行 `git pull`，再运行 `npm install --omit=dev`。

`npm install --omit=dev` 只会安装运行时依赖 `ws`。

## 2. 在 Desktop 中安装插件

1. 打开 Desktop，进入「插件 → 安装第三方插件」。
2. 填写插件目录的绝对路径，例如 `C:\Users\<你的用户名>\DSHRemote\plugin`。
3. 安装后，插件列表中应出现 `dshremote`，并且处于启用状态。

**第一次加载时，浏览器会自动打开本机配对页**（`http://127.0.0.1:3191`）。请记下它有没有自动打开。没有打开的话，手动在浏览器中输入这个地址。

配对页「这台电脑」一栏中：

- 「DeepSeek Harness」一行应显示版本号和「已连接」。显示「未知版本」或「无法连接」时，也请继续往下做，脚本会收集更多信息。
- 「证书指纹」一行有完整的指纹，第 5 步要用。

## 3. 防火墙

插件第一次在局域网端口 3190 上监听时，Windows 安全中心可能会弹出「允许应用访问网络」的对话框（应用名可能是 DeepSeek Harness 或 Node.js）：

- **只勾选「专用网络」**，不要勾选「公用网络」，然后点「允许访问」。
- 请记下有没有弹出这个对话框。

确认当前网络是「专用网络」。在 PowerShell 中运行：

```powershell
Get-NetConnectionProfile
```

`NetworkCategory` 应为 `Private`。如果是 `Public`，在**管理员** PowerShell 中改为专用网络（`<编号>` 换成上面输出中的 `InterfaceIndex`）：

```powershell
Set-NetConnectionProfile -InterfaceIndex <编号> -NetworkCategory Private
```

如果没有弹出对话框，或者不小心点了取消，第 5 步可能连不上。这时在**管理员** PowerShell 中手动添加一条只对专用网络生效的规则：

```powershell
New-NetFirewallRule -DisplayName "DSHRemote" -Direction Inbound -Protocol TCP -LocalPort 3190 -Action Allow -Profile Private
```

以后不用插件时，可以删除这条规则：`Remove-NetFirewallRule -DisplayName "DSHRemote"`。

## 4. 本机验证

在 `DSHRemote\plugin` 目录下运行：

```powershell
node scripts/verify.mjs
```

脚本会检查以下几项：

- 配对页和插件到 DSH 的连接；
- DSH 版本；
- 局域网 HTTPS 接口，以及证书指纹错误、没有 token 这两种情况会被拒绝；
- 配对；
- 读取会话、模型和权限模式；
- 实时流。

**把最后一行 JSON 发给开发者**（记为「结果 A」）。

## 5. 局域网验证（防火墙）

这一步在**另一台机器**上运行，例如 Linux 服务器。它验证局域网里的手机能不能访问插件。

在那台机器上准备代码（只需要做一次）：

```sh
git clone https://github.com/qianyeee-w/DSHRemote.git
cd DSHRemote/plugin
npm install --omit=dev
```

然后：

1. 在 **Windows 的配对页**上点「生成配对码」。记下页面上显示的「地址」（Windows 的局域网 IP）和「配对码」，以及「这台电脑」一栏里完整的「证书指纹」。指纹可以复制带空格的分组形式。
2. 在**那台机器**上运行（配对码 5 分钟内有效）：

   ```sh
   node scripts/verify.mjs --host <Windows 的局域网 IP> --code <配对码> --fingerprint "<证书指纹>"
   ```

**把最后一行 JSON 发给开发者**（记为「结果 B」）。

如果第一项就失败，并且提示「多半是 Windows 防火墙拦截了」，请回到第 3 步检查网络类型和防火墙规则，然后重新生成配对码再试。

## 6. 重启 Desktop 后免配对

1. 在 Windows 上运行：

   ```powershell
   node scripts/verify.mjs --keep
   ```

   这次检查完后**保留**临时设备，它的 token 保存在 `%USERPROFILE%\.dshremote-verify.json`。
2. **完全退出** Desktop：如果有托盘图标，要从托盘菜单中退出，然后重新打开。
3. 等 Desktop 完全启动后运行：

   ```powershell
   node scripts/verify.mjs --reuse
   ```

   这次用保存的 token 连接，不重新配对。检查完后会取消这台临时设备的配对，并删除保存的文件。

**把 `--reuse` 那次的最后一行 JSON 发给开发者**（记为「结果 C」）。

## 7. 和 Desktop 窗口同时使用

```powershell
node scripts/verify.mjs --prompt
```

这次会新建一个会话，发一条验证消息，检查能看到模型的回复和「任务完成」通知。**会消耗少量模型额度**。

运行时请留意 Desktop 窗口：

- 左侧会话列表中，是否出现了新会话，里面有这条验证消息和模型的回复。
- Desktop 本身的使用是否一切正常。

**把最后一行 JSON 发给开发者**（记为「结果 D」）。验证完后，可以在 Desktop 中删除这个会话。

## 8. 停用插件

1. 在 Desktop 的插件列表中**停用** `dshremote`，等几秒钟。
2. 在 PowerShell 中确认端口已经关闭（端口改过的话，换成实际的端口）：

   ```powershell
   Test-NetConnection 127.0.0.1 -Port 3190
   ```

   `TcpTestSucceeded` 应为 `False`。配对页 `http://127.0.0.1:3191` 也应该打不开。
3. 重新启用插件。配对页应能再次打开，之前配对过的设备仍然有效。

## 常见问题：端口被占用

插件默认使用 3190（局域网 HTTPS）和 3191（配对页）。如果这两个端口已经被别的程序占用，插件会启动失败，配对页打不开，或者打开的是别的程序的页面。

**最常见的原因是 VS Code 的端口转发。** 用 VS Code Remote-SSH 连着 Linux 服务器时，VS Code 会把服务器上监听的端口自动转发到 Windows 本机。如果服务器上运行过装了插件的 `dsh web`，Windows 上的 3190、3191 就会被 `Code.exe` 占用。

查看占用端口的进程：

```powershell
Get-NetTCPConnection -LocalPort 3190,3191 -State Listen | Select-Object LocalAddress, LocalPort, OwningProcess
Get-Process -Id <OwningProcess>
```

解决办法任选其一：

- 在 VS Code 的「端口」面板中停止转发这两个端口。也可以在设置中把 `remote.autoForwardPorts` 设为 `false`，关闭自动转发。
- 在 Desktop 的插件配置中，把 `lanPort`、`adminPort` 改为其他端口。改了之后，运行验证脚本时要加上 `--lan-port`、`--admin-port`。

## 9. 发给开发者

请发送：

1. 结果 A、B、C、D 四行 JSON。
2. 第 2 步：配对页有没有自动打开；配对页上「DeepSeek Harness」一行显示的内容。
3. 第 3 步：有没有弹出防火墙对话框，应用名是什么；有没有手动添加规则。
4. 第 7 步：Desktop 里有没有出现新会话，Desktop 使用是否正常。
5. 第 8 步：停用后端口是否关闭；重新启用后是否正常。
6. 过程中遇到的任何报错。有截图更好，但截图中**不要包含配对码**。

开发者会根据结果补齐 M1 验收清单（`m1-plugin.md` 第 6 节），并修复发现的问题。
