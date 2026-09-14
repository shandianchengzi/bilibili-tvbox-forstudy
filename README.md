# Bilibili TVBox · For Study

面向 TVBox 的 Bilibili 原生 Java 视频源。GitHub Pages 托管订阅与插件，GitHub Actions 定时更新公开视频目录；账号登录、私人数据与实时播放均在 TVBox 设备上完成。

**订阅地址：**

```text
https://shandianchengzi.github.io/bilibili-tvbox-forstudy/tvbox.json
```

[项目页面](https://shandianchengzi.github.io/bilibili-tvbox-forstudy/) · [构建与部署](https://github.com/shandianchengzi/bilibili-tvbox-forstudy/actions) · [Pages 设置](https://github.com/shandianchengzi/bilibili-tvbox-forstudy/settings/pages)

## 功能

| 模块 | 功能 |
| --- | --- |
| Bilibili 影视 | 电影、综艺、纪录片、国创、剧集、番剧；关键词搜索官方影视与番剧；另有动态、收藏夹、历史记录 |
| Bilibili 合集 | 按兴趣分类查找 UP 主作品，支持多 P、UP 主 UGC 合集及单个长视频；详情展示 BV 号、北京时间发布时间及精确播放量 |
| Bilibili 周深 | 综艺、演唱会、歌曲、采访、剪辑、搞笑、舞台、卡布；独立公共目录与筛选 |
| Bilibili 扫码登录 | 统一扫码登录、账号状态与退出登录入口；登录状态由三个内容模块共用 |

关键词搜索按模块区分：

| 搜索位置 | 搜索内容 |
| --- | --- |
| Bilibili 影视 | Bilibili 官方影视与番剧条目（`media_ft` / `media_bangumi`） |
| Bilibili 合集 | UP 主投稿，如课程、听书、合集视频与专题作品（`video`） |
| Bilibili 周深 | 周深相关的 UP 主投稿，关键词自动关联「周深」（`video`） |

例如，搜索「红星照耀中国」时，影视查找官方影视条目，合集查找 UP 主发布的有声书、课程或相关作品；各模块以对应接口的实际结果为准。输入明确的 BV 号、Bilibili 视频链接或 `ep123` / `ss123` 影视编号时，仍可直接打开指定内容。扫码登录是独立站点，不参与搜索，也不出现在内容模块的子分类中。

合集详情展开多 P 视频和视频关联的 UP 主 UGC 合集，按目录提供分集播放。整本有声书或“一口气看完”作品也可能是单个长视频，同样可以搜索和播放。若接口只返回部分合集目录，详情会标明已加载集数与总集数。

三个内容模块统一从「全部」浏览，不再重复提供「推荐」内容。研究兴趣和周深专区的「全部」汇总 Actions 已收录的本专区视频，去除重复 BV，先筛选、排序，再按每页 20 条展示；它不是 Bilibili 全量视频。进入具体标签可继续实时查找。影视的「全部」汇总六类影视条目，支持类型、排序及付费筛选。

部分 TVBox 分支固定显示客户端自带的「主页」，视频源无法删除这个固定页面；本源已清空重复推荐列表，「全部」仍是完整的分类与筛选入口。

兴趣分类包含：知识、嵌入式设备与固件仿真、芯片设计、硬件设计、数字电路与 FPGA、AI 应用、大模型应用、具身智能、复杂工程问题、同态加密、量子计算、听书 / 有声书。修改 [config/interests.json](config/interests.json) 的 `name` 和 `queries` 即可调整分类，提交后自动重建。

周深专区的分类配置位于 [config/zhou_shen.json](config/zhou_shen.json)，与研究兴趣目录分别维护。

研究兴趣和周深专区的「全部」与标签页都支持以下筛选：

| 筛选 | 选项 |
| --- | --- |
| 排序 | 综合排序、最多点击、最新发布、最多弹幕、最多收藏 |
| 时长 | 全部、60 分钟以上、30–60 分钟、10–30 分钟、10 分钟以下 |
| 播放量 | 全部、1w–10w、10w 以上、1k–1w、1k 以下 |
| 分类 / 主题 | 「全部」可选本专区分类；具体标签页可选该分类配置的关键词 |

影视的「全部」提供类型（全部、电影、综艺、纪录片、国创、剧集、番剧）、排序（最多播放、最近更新、最高评分）与付费（全部、免费、大会员）筛选。进入具体影视类型后仍可按排序和付费筛选。跨类型的「最近更新」保留各类型内部的上游顺序；选定一种类型可直接按该类型的更新顺序翻页。电影分类另有「最近上映」，使用 Bilibili 上映日期降序排列，支持与付费条件组合。动态、收藏夹和历史记录均提供原始顺序、最多点击、最新发布、最多弹幕、最多收藏，以及时长和四档播放量筛选；收藏夹继续保留文件夹选择。

播放量使用接口返回的精确计数：1k 为 1,000，1w 为 10,000；区间分别是 `[10000,100000)`、`[100000,+∞)`、`[1000,10000)`、`[0,1000)`。缺失或未知播放量不会被当作零，也不会进入「1k 以下」。官方影视分类接口只有经过舍入的播放量展示文本，因此影视条目不提供播放量区间筛选。

个人列表的排序针对当前加载批次，不代表整份历史或全部关注投稿的全局排名。默认保留动态时间、收藏加入时间、观看时间顺序；「最新发布」使用视频的实际发布时间，不把转发、收藏或观看时间当作发布时间。缺少筛选必需字段时，插件在电视端按需查询普通视频详情，短时缓存统计；请求并发及等待时间有限，失败字段仍为未知，不会误算为零。个人列表中的影视条目若缺少精确统计，仍按未知字段处理。账号变更会使缓存和分页游标失效，个人数据不会上传到 Pages 或 Actions。

具体标签页的主题、排序及时长由 Bilibili 实时搜索执行。启用播放量区间时，标签页每批最多检查连续 3 个上游结果页，按精确计数筛选；本批有匹配且上游还有内容时，可以继续加载下一批。如果本批均无匹配，则停止翻页并提示「本次检查的搜索结果中没有符合播放量的视频，请调整筛选」，不代表后续所有结果都无匹配。个人列表启用时长或播放量区间时，也按最多 3 个上游页组成一批；当前批次没有匹配时提示调整条件并停止翻页，避免客户端重复请求空页。这避免了部分客户端收到空页后反复请求同一页的问题。「全部」会完整筛选本专区已收录目录，没有上述每批检查范围限制。

遇到风控时不会把不匹配条件的缓存结果伪装成筛选结果；「全部」的公开目录则保留 Actions 上次成功收录的内容与更新状态。

## 在 TVBox 使用

1. 使用支持 **CatVod Java `type: 3` JAR** 的 TVBox，Android 5.0 以上。在设置的配置地址 / 接口地址处导入上面的 `tvbox.json`。
2. 选择「Bilibili 影视」「Bilibili 合集」或「Bilibili 周深」。配置默认使用 **Exo**；DASH 音视频分离流需要 Exo 播放器。
3. 需要登录时切换到独立的「Bilibili 扫码登录」站点，打开「扫码登录 / 重新登录」，用 Bilibili 手机客户端扫描原生弹窗中的二维码，并在手机确认。弹窗实时显示状态并可刷新二维码；若客户端无法提供弹窗宿主，详情封面仍显示备用二维码。无需点击播放。
4. 电视提示成功后重新加载源配置，或重启 TVBox。部分客户端会缓存未登录时的分类，单纯返回首页不足以刷新收藏夹筛选。收藏夹在分类筛选中选择；首次显示默认收藏夹。动态和历史记录按顺序翻页。
5. 视频详情或播放界面的线路只显示清晰度名称，例如「自动」「360P」「480P」「720P」「1080P」「4K」，不带播放来源前缀；高码率、60 帧、HDR / 杜比、8K 等档位以接口实际返回为准。选择清晰度后点击分 P / 分集播放，合集或影视分区信息保留在分集名称中。「自动」保持最高 1080P 的默认请求。
6. 二维码过期或登录失效时，在弹窗点击「刷新二维码」。短暂网络错误会自动重试；关闭弹窗会取消待完成的登录，但保留原有账号。「退出登录」才会清除本机登录信息。

不同 TVBox 分支的界面和清晰度支持存在差异。此工程验证构建、接口映射、本地服务行为以及模拟 HTTP 的完整扫码流程；在真实 Android 电视上的扫码、音视频同步及账号权限播放仍需设备验收。TVBox 若无法显示本机 HTTP 图片或无法播放本机 MPD，需要换用支持这些功能的客户端构建；插件不能修改宿主 Android 网络策略。

切换清晰度使用 TVBox 通用播放线路，不要求客户端实现独立的画质菜单；切换后的播放进度是否保留由 TVBox 分支决定。登录后如需刷新高画质选项，请重新进入视频详情。

## 自动维护与部署

工作流在 `main` 提交、手动运行及每日 UTC 02:17 / 14:17 执行；Pull Request 只检查，不部署。工作流编译 Java → DEX JAR，运行测试，抓取公共元数据，生成订阅，上传构建附件，再部署到 Pages。具体触发时间以工作流为准，GitHub 定时任务可能排队延迟。

匿名真实播放探测遇到明确的上游风控或限流时，会显示 `BLOCKED` 警告并停止探测；这不表示真实播放验证通过。清晰度选择、播放回调与音视频清单的离线回归仍是发布必需检查，其他接口或清单错误继续阻断提交构建。

首次必须在仓库 **Settings → Pages → Build and deployment → Source** 选择 **GitHub Actions**。仅写入工作流不会自动开启新仓库的 Pages；默认 `GITHUB_TOKEN` 没有替仓库开启 Pages 所需的管理权限。设置完成后重新运行失败的部署作业或手动运行工作流即可。

抓取只保存 BV 编号、标题、封面、作者、时长及筛选所需的公开元数据，不保存视频文件、临时播放地址或账号数据。Bilibili 返回风控 / 限流时停止当前轮抓取，保留上次成功目录，并在 `catalog.json` 和项目页面标明状态。首次抓取失败会明确显示空目录；TVBox 仍可以在设备网络下实时搜索。

公开仓库的计划任务可能在长期无仓库活动后被 GitHub 停用；可在 Actions 页面重新启用。登录不需要 GitHub Secrets，也不要把 Bilibili Cookie 放入仓库、Pages、Actions 日志或公共配置。

## 播放范围

仅使用 Bilibili 正常接口返回、当前账号有权访问的流。免费、会员、单独购买、地域和 DRM 限制均保留；没有可用流时显示原因。清晰度线路按首个视频实际可用的音视频轨道生成，切换画质时按所选档位重新请求播放地址；同一档位内优先 AVC/AAC。后续分 P / 分集缺少该档位时会明确提示，可切换其他档位或「自动」。高分辨率、HDR、杜比及 HEVC / AV1 需要设备解码支持。获取清晰度列表失败不影响原有分集与自动播放，重新进入详情页可重试。旧版多段 FLV、DRM 内容不支持。账号 Cookie 过期后需重新扫码，不承诺自动无限续期。

视频详情中的发布时间取 `pubdate`（公开发布时间），不以 `ctime` 上传时间替代；播放量取 `stat.view`。接口缺失相应数据时明确显示暂无数据，不将未知播放量显示为零。

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

- [用户提供的参考源配置](https://9280.kstore.vip/newwex.json)：影视和合集分别使用 `csp_BiliYsGuard`、`csp_BiliGuard`，合集分类以关键词配置。其加密爬虫内部无法从公开配置核对；本项目依据可读源码和接口返回实现，未运行该成品爬虫。
- [CatVod Bili 的关键词搜索与多 P 播放](https://github.com/liu673cn/CatVodSpider/blob/978d46b4255b3844a05c3cddab7d48fb625e9263/app/src/main/java/com/github/catvod/spider/Bili.java)：使用普通投稿搜索并展开 `pages`，没有要求每条结果必须属于 UGC 合集。
- [TVBox Python Bilibili 的 UGC 合集分集处理](https://github.com/li5bo5/TVBox/blob/8badb1f82d88edcd8b7fdcb1a047bf637d5fb45c/py/py_bilibili.py)
- [TVBox 的 JAR 加载与 Spider 调用](https://github.com/q215613905/TVBoxOS/blob/main/app/src/main/java/com/github/catvod/crawler/JarLoader.java)
- [TVBox Exo DASH 检测](https://github.com/q215613905/TVBoxOS/blob/main/player/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaSourceHelper.java)
- [PiliPlus 的 Bilibili 接口定义](https://github.com/bggRGjQaUbCoE/PiliPlus/blob/main/lib/http/api.dart)
- [PiliPlus WBI 签名实现](https://github.com/bggRGjQaUbCoE/PiliPlus/blob/main/lib/utils/wbi_sign.dart)
- [2026 年扫码跨域登录兼容记录](https://github.com/public-clis/bilibili-cli/pull/27)
- [GitHub Pages 自定义工作流](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)

第三方组件信息见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
