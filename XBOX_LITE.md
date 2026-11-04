# XBOX_LITE.md — xbox-lite 分支说明

## 这个分支是什么
- 基座：webhtv/webhtv 上游 main @ 4e30ffa（fork：kuby007/webhtv，分支 xbox-lite）。
- 路线反转：以前是 Xbox 为基座往里搬 webhtv（补丁叠补丁，v1.0.0–v1.0.5 的教训）；
  这次以**原版 webhtv 为基座**，Xbox 的功能只做**增量移植**，一样一样加、一样一样验。
- 铁律：webhtv 的数据层（VodConfig/SiteApi/Source）、播放链、嗅探链**不改逻辑**；
  Xbox 功能以新增模块/新增 UI 层的方式挂上去，不动原版链路。

## 精简面盘点（上游现状）
- flavor：mode（leanback 3.5M / mobile 3.9M）× abi（arm64_v8a / armeabi_v7a），
  app/src/main 40M；third_party 189M（运行时二进制依赖，不能删）。
- 不参与编译的目录：serverless 17M、webhome-devkit 2.5M、plans、other、scripts。

### 精简候选（按风险从低到高，等 Mark 拍板再动刀）
1. **删非编译目录**（serverless/plans/other）：✅ 已在本分支首个提交删除（构建只 include :app/:catvod/:chaquo/:quickjs，这三处零参与）；webhome-devkit 是 WebHome 开发工具，先保留。
2. **去 armeabi_v7a**：只留 arm64_v8a，APK 内 .so 减半；现代手机全是 arm64。
3. **去 leanback（TV 版）**：只留 mobile 一种 flavor；构建改造中等风险，
   收益是源码面和后续移植面大幅缩小。**建议第一刀就做，但单独提交单独验。**

## Xbox 保留功能清单（增量移植，按顺序）
1. **独立身份**：applicationId 改独立包名（与原版/Xbox 共存）+ 沿用 XHTV 的
   keystore 签名与 GitHub Actions 发版方式（上游已有 android-release.yml，在其上改）。
2. **真机基线验证**：未加任何 Xbox 功能前，先验原版点播/直播/WebHome 全链可用，
   这是后续每一步的对照组。
3. **Compose 宫格首页 + 设置外观**：作为新增 UI 层挂接，只读原版数据层，
   不替换原版详情/播放页（原版皮肤在 v1.0.1–v1.0.5 已验证可用）。
4. **迅雷**：Xbox 版 thunder SDK 集成方式。
5. **WebViewPool 预热**：Xbox 的 WebView 预热池（嗅探起播提速）。
6. **AdBlocker 规则**：Xbox 的广告拦截规则与原版规则取并集（轨3 已验证的做法）。
7. **BootGuard 可见报错理念**：jar 被禁用必须弹提示，绝不静默（轨4 的教训），
   以小补丁形式加在原版加载链上。

## 每步的验收标准
- 每项功能一个提交、一次 CI、一次真机复验；不过验不叠下一项。
- 任何一项移植若需要改动原版数据层/播放链逻辑，先停下来重新评估，不硬搬。
