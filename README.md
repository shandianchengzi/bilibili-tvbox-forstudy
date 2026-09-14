# Bilibili TVBox · For Study

面向 TVBox 的 Bilibili 原生 Java 视频源。GitHub Pages 托管订阅与插件，GitHub Actions 定时更新公开视频目录；账号登录、私人数据与实时播放均在 TVBox 设备上完成。

**订阅地址（仓库启用 Pages 且部署成功后可用）：**

```text
https://shandianchengzi.github.io/bilibili-tvbox-forstudy/tvbox.json
```

[项目页面](https://shandianchengzi.github.io/bilibili-tvbox-forstudy/) · [构建与部署](https://github.com/shandianchengzi/bilibili-tvbox-forstudy/actions) · [Pages 设置](https://github.com/shandianchengzi/bilibili-tvbox-forstudy/settings/pages)

## 功能

| 模块 | 功能 |
| --- | --- |
| Bilibili 影视 | 扫码登录、动态、收藏夹、历史记录、电影、综艺、纪录片、国创、剧集、番剧 |
| Bilibili 合集 | 按兴趣分类查找视频；多 P 视频分 P 播放；视频包含 UGC 合集时显示合集分集线路 |
| 视频查找 | 关键词查找、BV 号或 Bilibili 视频链接直达；支持 `ep123` / `ss123` 影视编号 |

兴趣分类包含：知识、嵌入式设备与固件仿真、芯片设计、硬件设计、数字电路与 FPGA、AI 应用、大模型应用、具身智能、复杂工程问题、同态加密、量子计算、听书 / 有声书。修改 [config/interests.json](config/interests.json) 的 `name` 和 `queries` 即可调整分类，提交后自动重建。

所有兴趣分类提供相同筛选：

| 筛选 | 选项 |
| --- | --- |
| 排序 | 综合排序、最多点击、最新发布、最多弹幕、最多收藏 |
| 时长 | 全部、60 分钟以上、30–60 分钟、10–30 分钟、10 分钟以下 |
| 主题 | 分类下配置的关键词 |

筛选通过 Bilibili 实时搜索执行；遇到风控时不会把不匹配时长或关键词的缓存结果伪装成筛选结果。无筛选的首页请求失败时，可以显示 Actions 上次成功的公共目录。

## 在 TVBox 使用

1. 使用支持 **CatVod Java `type: 3` JAR** 的 TVBox，Android 5.0 以上。在设置的配置地址 / 接口地址处导入上面的 `tvbox.json`。
2. 选择「Bilibili 影视」「Bilibili 合集」或「视频查找」。配置默认使用 **Exo**；DASH 音视频分离流需要 Exo 播放器。
3. 打开「账号 / 扫码登录」→「扫码登录 / 重新登录」，用 Bilibili 手机客户端扫描电视详情页封面上的二维码，并在手机确认。无需点击播放。
4. 电视提示成功后返回并刷新首页。收藏夹在分类筛选中选择；首次显示默认收藏夹。动态和历史记录按顺序翻页。
5. 二维码过期或登录失效时，重新打开登录详情生成二维码。「退出登录」清除本机登录信息。

不同 TVBox 分支的界面和清晰度支持存在差异。此工程验证构建、接口映射和本地服务行为；在真实 Android 电视上的扫码、音视频同步及账号权限播放仍需设备验收。TVBox 若无法显示本机 HTTP 图片或无法播放本机 MPD，需要换用支持这些功能的客户端构建；插件不能修改宿主 Android 网络策略。

## 自动维护与部署

工作流在 `main` 提交、手动运行及每日 UTC 02:17 / 14:17 执行；Pull Request 只检查，不部署。工作流编译 Java → DEX JAR，运行测试，抓取公共元数据，生成订阅，上传构建附件，再部署到 Pages。具体触发时间以工作流为准，GitHub 定时任务可能排队延迟。

首次必须在仓库 **Settings → Pages → Build and deployment → Source** 选择 **GitHub Actions**。仅写入工作流不会自动开启新仓库的 Pages；默认 `GITHUB_TOKEN` 没有替仓库开启 Pages 所需的管理权限。设置完成后重新运行失败的部署作业或手动运行工作流即可。

抓取只保存 BV 编号、标题、封面、作者、时长，不保存视频文件、临时播放地址或账号数据。Bilibili 返回风控 / 限流时停止当前轮抓取，保留上次成功目录，并在 `catalog.json` 和项目页面标明状态。首次抓取失败会明确显示空目录；TVBox 仍可以在设备网络下实时搜索。

公开仓库的计划任务可能在长期无仓库活动后被 GitHub 停用；可在 Actions 页面重新启用。登录不需要 GitHub Secrets，也不要把 Bilibili Cookie 放入仓库、Pages、Actions 日志或公共配置。

## 播放范围

仅使用 Bilibili 正常接口返回、当前账号有权访问的流。免费、会员、单独购买、地域和 DRM 限制均保留；没有可用流时显示原因。当前实现优先可用的 AVC/AAC 与最高 1080P 请求，实际画质由接口和账号决定。旧版多段 FLV、DRM 内容不支持。账号 Cookie 过期后需重新扫码，不承诺自动无限续期。

## 本地构建

准备 JDK 17、Python 3.11+、Android SDK：

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0"
export ANDROID_SDK_ROOT=/path/to/android-sdk
python3 -m unittest discover -s tests -v
bash scripts/build.sh
python3 scripts/crawl.py --config config/interests.json --output build/catalog.json
python3 scripts/build_site.py --help
```

`dist/bili-study.jar` 是包含 `classes.dex` 的 TVBox 插件，不是普通 JVM JAR。`compile-stubs/` 的宿主声明不进入发布包。构建不执行来源不明的成品爬虫 JAR。

## 参考实现与协议

- [TVBox 的 JAR 加载与 Spider 调用](https://github.com/q215613905/TVBoxOS/blob/main/app/src/main/java/com/github/catvod/crawler/JarLoader.java)
- [TVBox Exo DASH 检测](https://github.com/q215613905/TVBoxOS/blob/main/player/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaSourceHelper.java)
- [PiliPlus 的 Bilibili 接口定义](https://github.com/bggRGjQaUbCoE/PiliPlus/blob/main/lib/http/api.dart)
- [PiliPlus WBI 签名实现](https://github.com/bggRGjQaUbCoE/PiliPlus/blob/main/lib/utils/wbi_sign.dart)
- [2026 年扫码跨域登录兼容记录](https://github.com/public-clis/bilibili-cli/pull/27)
- [GitHub Pages 自定义工作流](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)

第三方组件信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
