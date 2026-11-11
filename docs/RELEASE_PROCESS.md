# 发布流程（Release Process）

> 适用仓库：aixtin/blogpad。本文档是发版的唯一操作手册。
> 原则：只有两个地方是版本号的真实来源 —— `app/build.gradle.kts` 与 GitHub tag。其余全是文档，漏改不会报错，只会慢慢烂掉。

---

## 0. 发版节奏（先于版本号，最重要）

**不按版本号发布，按功能闭环发布。** 当前已发布 v1.0.0、v1.5.0、v1.5.1；下一个对外版本在功能
闭环后决定。中间的 v1.1 ~ v1.4 是内部里程碑，不走向下流程。

内部里程碑只做三件事：编译通过、装真机自测、在 ROADMAP 里把状态改成「已完成」。不打 tag、不建
Release、不动下载点。判断某个里程碑要不要破例发版，只问一句：**已有用户正在受这个问题困扰吗？**
答不出「是」，就不发。

依据：应用没有内置更新检查，发版不会自动触达用户；单人维护，发版是固定开销。

## 1. 版本号规则

| 项 | 位置 | 规则 |
|----|------|------|
| `versionCode` | `app/build.gradle.kts` → `defaultConfig` | 整数，严格单调递增，发一次版加 1，永不回退、永不复用 |
| `versionName` | 同上 | `X.Y.Z` 语义化，只增不减 |
| Git tag | GitHub 仓库 | `vX.Y.Z`，必须与 `versionName` 逐字符一致 |
| Release 资产名 | GitHub Release | `blogpad-vX.Y.Z.apk` |

命名含义：

- 第三位（Z，补丁）：只修缺陷、改文案、调布局，无新功能
- 第二位（Y，次版本）：新增功能或结构调整
- 第一位（X，大版本）：破坏性变更（配置格式不兼容、需要重建服务器侧约定）

开发期约定：`versionName` 直接写目标版本并带 `-dev`（如 `1.6.0-dev`），`versionCode` 保持在上一个已发布版本的值（当前为 4，下一个发布版本为 5）不递增；真正发布时去掉 `-dev`、`versionCode` 加 1。

**为什么 tag 要与 versionName 一致**：tag 是人在 Releases 页面上认版本的唯一锚点，与代码里的 `versionName` 错位后，"这个包到底是哪版"就只能靠翻构建日志回答。本仓库没有内置更新检查，tag 不规范不会报错，只会让追溯失效。

---

## 2. 发布前（代码冻结）

- [ ] 过一遍《规范_开源平台发布检查清单》：硬敏感（凭据/密钥/公网 IP/设备序列号/debug token/第三方品牌词）**0 命中**，软敏感（内部代号/拓扑词）人工抽查通过
- [ ] 工作区干净：`git status` 无未提交改动
- [ ] `gradle :app:assembleRelease` 编译通过（仓库不含 gradlew，使用系统 Gradle 8.13 + JDK 17 + SDK 34）
- [ ] 真机冒烟，四项都跑一遍：
  - 全新安装 → 填配置 → 测试连接 → 指纹确认
  - 新建文章 → 发布 → 远端文件内容与 front matter 正确
  - 编辑已有文章 → 改标题/正文 → 保存 → 远端原地覆盖
  - 改 slug → 保存 → 远端旧文件名已清理，新文件存在
- [ ] 若本次动了这些区域，另做专项验证：
  - 回滚链路 → 故意让构建命令失败，确认新增文件被删除、覆盖文件恢复原内容
  - 认证 → 密钥与密码两种方式各连一次
  - 异常路径 → 指纹变化时拒绝连接；错误口令有明确提示
- [ ] 涉及列表/编辑的交互改动，确认无未保存修改时直接返回会拦一道确认

---

## 3. 构建安装包

对外分发的是 **release 签名包**（`app/build/outputs/apk/release/app-release.apk`）。

签名配置在 `app/build.gradle.kts`：读 `keystore.properties`，查找顺序是「仓库根 → `~/keys/`」，两者都没有时**回落到 debug 构建**。这是有意为之——新克隆的仓库不需要任何私钥材料也能编译，只有在持有密钥的机器上才会产出正式签名包。

`keystore.properties` 四个字段：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`。

```bash
# 仓库根目录即工程目录
cd <repo-root>
gradle :app:clean
gradle :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
# 校验签名：~/android-sdk/build-tools/35.0.0/apksigner verify --print-certs <apk>
```

- [ ] 产物改名为 `blogpad-vX.Y.Z.apk`
- [ ] 记录校验值：`sha256sum blogpad-vX.Y.Z.apk`（Release 正文里要写）
- [ ] `apksigner verify --print-certs` 输出的证书 SHA-256 必须是 `f3d85092f51da145e60c90239804e7b3003872893188b9d5719bca4a1b6fa00a`；对不上说明这次用的是 debug key，不能发布
- [ ] 覆盖安装验证一次：签名不一致会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先 `adb uninstall io.github.aixtin.blogpad` 再装，注意会清空 App 内的 SSH 配置

### 签名材料管理（硬要求）

| 项 | 位置 | 要求 |
|----|------|------|
| keystore | 开发机 `~/keys/blogpad-release.jks` | RSA 4096，有效期 10000 天，权限 600 |
| 口令 | 同目录 `keystore.properties`，权限 600 | 已被 `.gitignore` 覆盖，**绝不入库** |
| 证书 SHA-256 | `f3:d85092:f5:1d:a1:45:e6:0c:90:23:98:04:e7:b3:00:38:72:89:31:88:b9:d5:71:9b:ca:4a:1b:6f:a0:0a` | 用于核对构建产物 |
| 备份 | 仓库之外的离线位置，至少两份 | **丢失即无法再升级已安装版本**，只能改包名重来 |

首版用正式签名的原因：v1.0.0 时期只有开发机一台设备安装，此时切换代价是一次卸载重装；一旦按旧签名对外发布，后续更换就必须让所有已装用户卸载重装。

---

## 4. 必改清单（每次发版都改）

### 4.1 代码里的版本号

- [ ] `app/build.gradle.kts`：`versionCode` +1、`versionName` 改新值 —— **第一个改的地方**

### 4.2 Git 与 GitHub 侧

- [ ] 提交并推送：`git add -A && git commit -m "release: vX.Y.Z" && git push`
- [ ] 打 tag 并推送：`git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z`
- [ ] 新建 Release：选中该 tag，标题 `vX.Y.Z`，正文写变更要点 + 完整 SHA256，上传 `blogpad-vX.Y.Z.apk`
- [ ] 三个不许犯的错：
  1. 不许存成 **Draft** —— 草稿在 Releases 页不显示，等于没发
  2. 不许勾 **Pre-release** —— 同上，且会误导 `latest` 的语义
  3. 不许传错资产名或压缩包 —— 必须是可直接安装的 `.apk`

### 4.3 双 README（两份都要，只改中文版是高频事故）

- [ ] `README.md`（英）
- [ ] `README.zh-CN.md`（中）
  - [ ] 「功能」段：本次新增的用户可见能力补进去（历史欠账：文章列表页、编辑已有文章两项目前尚未写入）
  - [ ] 「工程结构」段：新增或删除的 Kotlin 文件同步
  - [ ] 「系统要求」段：minSdk / targetSdk / 工具链版本有变化就改
  - [ ] 「版本记录」段：顶部追加一条（版本 + 日期 + versionCode + 变更要点）

### 4.4 ROADMAP 双语

- [ ] `ROADMAP.md` 与 `ROADMAP.zh-CN.md` 的版本表同步状态列：已完成的标「已完成」或「已发布」，未开始的保持空白
- [ ] 若本次发版改变了「编辑与预览的交互规则」或「明确不做」任一条，两份都要改

### 4.5 分发点同步

- [ ] 若存在对外直链下载目录，把新包上传并替换旧包，旧版本名不能与新版本并存造成混淆
- [ ] 直链文件名与 Release 资产名保持一致，便于核对

---

## 5. 条件清单（涉及什么改什么）

| 触发条件 | 要改的东西 |
|----------|-----------|
| 新增 / 删除 / 改名 Kotlin 文件 | README 双语「工程结构」段 |
| 新增 / 移除第三方依赖 | README 双语「依赖」表，版本号与许可证都要对 |
| minSdk / targetSdk / 编译工具链变化 | README 双语「系统要求」与「从源码构建」两段 |
| 安全相关变更（凭据存储、指纹校验、命令拼装、回滚策略） | README 双语「安全说明」段 |
| 发布流程本身变化（签名、CI、资产命名） | 本文档 |
| 交互或默认行为变化（默认模式、确认弹窗、刷新策略） | ROADMAP 双语对应小节 |
| 站点侧约定变化（front matter 键、文章目录默认值） | README「配置」与「使用」两段 |
| 文案里硬编码版本号或安装提示 | `app/src/main/res/values/strings.xml` |

---

## 6. 发布后验证

- [ ] 仓库首页「Latest release」显示的正是刚发的版本，不是草稿、不是 pre-release
- [ ] 资产可下载，文件名 `blogpad-vX.Y.Z.apk`，SHA256 与本地一致
- [ ] 覆盖安装后：启动 → 配置仍在 → 文章列表能拉到远端条目
- [ ] 发一条测试文章，随后在站点确认已生成页面（验证完删掉或改回草稿）
- [ ] 直链下载点返回的是新包
- [ ] 清掉发布验证时留下的测试文章，站点上不留调试内容

---

## 7. 出问题时

- **包已上传但发现严重缺陷**：不要删 Release 重传同名 tag 覆盖安装包（已下载者的校验值会对不上）。保留该版本 Release，把它改成 Pre-release 让后来者跳过，然后立刻发补丁版 `X.Y.Z+1`。
- **tag 打错**：`git push origin :refs/tags/vX.Y.Z` 删远端 tag，本地 `git tag -d` 后改对再推。已建 Release 的先在页面删掉。
- **versionCode 忘了加**：安装会被系统拒绝覆盖升级，必须重新构建、重新打 tag、重新发版，不能只改文档。
- **误改了签名材料**：老用户只能卸载重装（Keystore 加密的配置会丢，需重新填写服务器信息）。这是不可逆事故，构建前先确认用的是同一份签名。

---

## 8. 尚未落地（待办）

1. ~~正式签名~~ **已落地（2026-11-10）**：见 §3。`signingConfigs` 已接入，`assembleRelease` 产出正式签名包，资产名去掉 `-debug`。签名材料托管在开发机 `~/keys/`，备份义务写在 §3「签名材料管理」。
2. **CI**：目前无 GitHub Actions。最小可用版本是 push 触发 `assembleDebug` 与 lint，仅在 tag 时产出资产。
3. **发版脚本**：把 §4 的必改清单做成 `scripts/release.sh`：读入 versionName → 校验 versionCode 递增 → 改 `build.gradle.kts` → 全文 grep 文档里的旧版本号并列未同步文件，漏改当场点名。
