# BlogPad

一个极简的安卓写作台：在手机上写 Markdown，通过 SSH 直接发布到 Hugo 站点。

写完点发布，App 把 Markdown 文件通过 SFTP 传到站点的内容目录，在服务器上执行构建命令，
然后返回文章链接。没有 CMS、没有数据库、不依赖任何第三方服务，只需要一台能 SSH 的服务器和
一个基于文件（Git）的站点。

## 功能

- **手机端写作与发布** —— 标题、slug、分类、标签、正文，以及草稿开关。
- **自动生成 Hugo front matter** —— `title`、`date`、`draft`、`slug`、`categories`、`tags`
  由 App 写入，文件可直接被 Hugo 识别，无需手工拼头信息。
- **SSH 传输** —— 同一会话内完成 SFTP 上传与远程命令执行，支持密钥（OpenSSH `ed25519` /
  `rsa`）与密码两种认证方式。
- **主机指纹固定** —— 首次连接需核对 SHA-256 指纹，确认后写入配置；指纹变化时拒绝连接。
- **构建失败自动回滚** —— 远端构建返回非零时，新增文件被删除、覆盖文件恢复原内容，
  不会留下构建失败的半成品文章。
- **草稿模式** —— 写入 `draft: true`，不生成链接。
- **编辑内容自动缓存** —— 退出或切后台后内容保留，下次打开自动恢复。
- **配置加密存储** —— 主机、凭据、私钥整体序列化为 JSON，用 Android Keystore 中的
  AES-256-GCM 密钥加密后存入 `SharedPreferences`；应用已关闭系统备份。

## 系统要求

| 项 | 要求 |
|----|------|
| 安卓 | 8.0（API 26）及以上 |
| 服务器 | 任意开启 SFTP 的 SSH 服务 |
| 站点 | 服务器上的 Hugo 工程，且构建工具链已安装 |
| 构建 | JDK 17、Android SDK 34、Gradle 8.13 |

## 安装

从 [Releases](../../releases) 页面下载 APK 安装。应用不预置任何服务器配置，首次使用需自行填写。

## 配置

打开「设置」，共两组字段。

### 连接

| 字段 | 默认值 | 说明 |
|------|--------|------|
| 主机 | — | 服务器域名或 IP |
| 端口 | `22` | SSH 端口 |
| 用户名 | — | SSH 登录用户 |
| 认证方式 | 密钥 | 密钥 / 密码切换 |
| 私钥 | — | 可直接粘贴或从文件导入，仅支持 OpenSSH 格式，不支持 `.ppk` |
| 私钥口令 | — | 有口令的密钥才需要填写 |
| 密码 | — | 选择密码认证时使用 |

填好后点「测试连接」。首次连接会显示服务器指纹，确认后才写入信任；之后每次发布都会校验该指纹。

### 站点

| 字段 | 默认值 | 说明 |
|------|--------|------|
| 站点根目录 | — | 服务器上 Hugo 工程的绝对路径 |
| 文章目录 | `content/post` | 相对站点根目录 |
| 构建命令 | `hugo --gc --minify` | 上传完成后在站点根目录执行 |
| 站点地址 | — | 用于拼接文章链接的基础地址 |
| 链接模板 | — | 可选，如 `https://example.com/posts/{slug}/`，`{slug}` 会被替换 |

注意：

- 构建工具不在非交互 shell 的默认 `PATH` 中时，构建命令要写绝对路径，例如
  `/usr/local/bin/hugo`。
- 设置了链接模板时优先使用模板；未设置时按 `<站点地址>/archives/<slug>/` 拼接。

## 使用

1. 填写标题与正文，slug、分类、标签可选。
2. slug 留空时按时间戳生成。
3. 勾选「存为草稿」则以 `draft: true` 写入，不生成链接。
4. 点「发布」。状态栏会显示远端路径、文章链接，以及构建输出的尾部内容。
5. 发布成功后链接可一键复制。

## 从源码构建

```bash
# 需要 JDK 17 与 Android SDK 34
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

也可直接用 Android Studio 打开工程。`local.properties` 不纳入版本管理。

## 安全说明

应用会在所配置的服务器上执行 shell 命令，因此所用账号应做最小化授权。

- 建议新建专用部署账号，不要复用个人或管理账号。
- 将该账号的可访问范围限制在站点目录与构建所需的少量命令上，可配合受限 shell 包装脚本，
  或 SSH 的 `command=` / `ForceCommand` 规则。
- 构建命令与文章目录是自由文本，会原样拼入 shell 命令；应自行控制其内容，不要拼接不可信输入。
- 凭据除用于 SSH 认证外不会离开设备；应用不上传任何数据到第三方服务，也不含统计与遥测。

## 工程结构

```
app/src/main/java/io/github/aixtin/blogpad/
├── ConfigStore.kt      # 配置模型、Keystore 加密、持久化
├── MainActivity.kt     # 编辑器、发布流程、本地草稿缓存
├── SettingsActivity.kt # 连接与站点设置、指纹确认
└── SshPublisher.kt     # SFTP 上传、远程构建、回滚、front matter 渲染
```

## 依赖

| 库 | 版本 | 许可证 |
|----|------|--------|
| [JSch（mwiede 分支）](https://github.com/mwiede/jsch) | 0.2.26 | BSD 类 |
| [Bouncy Castle](https://www.bouncycastle.org/)（`bcprov-jdk18on`） | 1.82 | MIT 类 |

## 开发路线图

版本规划、明确不做的功能，以及编辑/预览的交互规则见
[ROADMAP.zh-CN.md](ROADMAP.zh-CN.md)（[English](ROADMAP.md)）。

## 许可证

[MIT](LICENSE)
