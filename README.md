# zilizili

学习测试用的原生短视频 App（上下滑动刷视频）+ **单文件 PHP 服务端**。

- 服务端：`server/api.php` —— 一个文件搞定所有接口，传到虚拟主机即可
- App：Android 原生 Kotlin + ViewPager2 + ExoPlayer(Media3)
- 构建：GitHub Actions 自动编译 Debug APK

```
zilizili/
├── server/
│   ├── api.php          ★ 单文件服务端，上传这一个就够了
│   ├── data/            videos.json + pool.json（需可写）
│   ├── videos/          远程抓取的视频落盘目录（需可写）
│   └── source/          本地待导入目录（自己建，FTP 丢视频进去）
├── app/                 Android 工程
└── .github/workflows/   GitHub Actions 构建 APK
```

---

## 一、部署（三步）

1. **把 `server/api.php` 上传到虚拟主机根目录**，让它能通过 `https://xfan.ccccocccc.cc/api.php` 访问。
2. **建三个目录并保证可写**（权限 0755，不行就 0777）：
   - `data/` —— 存 JSON 状态
   - `videos/` —— 远程抓取的视频落盘
   - `source/` —— 你用 FTP 手动丢视频的地方（可选）
3. **改 `api.php` 顶部的 `$CFG`**：

```php
'token'      => '换成你自己的长随机字符串',   // 必改
'source_url' => 'https://xfan.ccccocccc.cc/你的列表地址',  // 远程抓取才填
```

验证：浏览器打开 `https://xfan.ccccocccc.cc/api.php?action=status&token=你的令牌`，能返回 JSON 就成功。

---

## 二、⚠️ 先跑一次 probe（重要）

我这边访问 `https://xfan.ccccocccc.cc/api.php` 全部返回 403：

```json
{"title":"Request denied","status":403,"reason":"policy_default_denied","source":{"type":"policy","policy_name":"main-policy"}}
```

这是网关策略拦截（Pangolin 一类），**公网目前访问不到任何路径**，所以我也没法确定你的源站返回什么格式。请确认两件事：

1. 网关上给 `/api.php` 加放行规则，否则 App 连不上
2. 上传后访问 `?action=probe&token=xxx&url=源站地址`，它会把源站原始返回的前 2000 字符吐出来，并告诉你自动识别成了哪种格式

---

## 三、接口一览（全部走 `api.php?action=`）

| action | 鉴权 | 说明 |
|---|---|---|
| `feed&count=5` | 无 | **随机推送**，App 调这个 |
| `import&path=source` | token | 扫描本地目录，把已有视频登记入库 |
| `sync&limit=20` | token | 从远程源站抓取 → 下载到本地 → 算 SHA1 |
| `probe&url=xxx` | token | 探测源站格式 + 对比朴素请求/浏览器请求的拦截情况 |
| `warm&url=xxx` | token | 暖机：先访问站点首页把 Cookie 存下来 |
| `status` | token | 查看库存 / 推送轮次 / 磁盘占用 |
| `reset` | token | 立即重置推送池 |
| `upload` | token | POST 上传视频（form-data: file, title） |

---

## 四·补、WAF / 反爬页处理

源站有 WAF 或反爬页时，服务端默认以**浏览器方式**访问（`browser_mode => true`）：

- **完整浏览器请求头**：Chrome UA、`Accept`、`Accept-Language`、`Upgrade-Insecure-Requests`、`Sec-Fetch-*`、`sec-ch-ua` 客户端提示
- **持久化 Cookie**：存在 `data/cookie_xxx.txt`，请求自动带上、回写（`.htaccess` 已禁止外部访问）
- **Referer / Origin**：按目标站点自动生成，模拟同源跳转
- **HTTP/2**：`CURL_HTTP_VERSION_2TLS`，浏览器都是 HTTP/2
- **暖机机制**：先访问站点首页建立会话，再带 Referer 请求深层路径；仍被拦则清 Cookie 重来
- **视频请求降级**：下载时用 `Sec-Fetch-Dest: video` + `Range: bytes=0-`，跟浏览器拉视频一致
- **礼貌间隔**：`crawl_delay_ms`（默认 800ms），请求间自动 sleep

### 排查

```bash
# 1. 暖机拿 Cookie
?action=warm&token=xxx

# 2. 对比"朴素请求"和"浏览器请求"是否被拦
?action=probe&token=xxx
```

`probe` 会同时返回 `plain_request.blocked` 和 `browser_request.blocked`，一眼看出伪装是否生效。

### 边界

若对方返回的是**真正的人机验证**（JS 挑战、滑块、验证码、Cloudflare "Just a moment"），那是对方有意设置的访问控制，脚本**不会也不应该**去破解。正确做法是：

1. 在对方网关把本服务器 IP 加入白名单
2. 改用对方开放的正式接口
3. 或用 `import` 走本地导入（FTP 传文件），完全绕开抓取

---

### feed 返回示例

```json
{
  "ok": true,
  "round": 3,
  "reset": false,
  "remaining": 12,
  "count": 5,
  "items": [
    {
      "sha1": "3f2a1b...",
      "title": "视频标题",
      "url": "https://xfan.ccccocccc.cc/videos/3f2a1b....mp4",
      "cover": "",
      "size": 10485760,
      "mime": "video/mp4"
    }
  ]
}
```

---

## 四、推送逻辑

`data/pool.json` 维护两个列表：`pool`（待推送）和 `pushed`（本轮已推送）。

1. 每次 `feed` 从 `pool` 里**随机**取 `count` 条
2. 取出的立刻从 `pool` 移除、记入 `pushed`（**推送完清除**）
3. `pool` 空了 → 把全部视频 SHA1 重新打乱填满 `pool`，清空 `pushed`，`round + 1`（**重新随机推送**）

全程 `flock` 互斥锁，并发请求不会重复推送。已用 10 个视频 × 30 次请求跑过验证：同一轮内每条只出现一次，池空后自动进入下一轮。

---

## 五、两种入库方式

### A. FTP 传文件 + import（推荐，最省事）

1. 用 FTP 或主机面板把 mp4 丢进 `source/`
2. 访问 `?action=import&path=source&token=xxx`

脚本会递归扫描目录，对每个文件算 `sha1_file()`，重复的自动跳过，**不复制文件**（不额外占磁盘）。

### B. 远程抓取 + sync

访问 `?action=sync&token=xxx&limit=10`。流程：拉源站列表 → 流式下载到 `videos/` → 算 SHA1 → 入库。超过 200MB 自动中断。

> 加 `&download=0` 则只登记直链、不下载，App 直接播源站地址（省磁盘，但依赖源站在线）。

---

## 六、源站格式不匹配怎么办

`api.php` 内置四种解析器，在 `$CFG['source_mode']` 里切换：

| 模式 | 适用 |
|---|---|
| `auto` | 依次试 json → dir → text → custom（默认） |
| `json` | 返回 JSON 数组，字段含 `url`/`videoUrl`/`playUrl`/`src` 等 |
| `dir` | HTML 目录页，能列出 `.mp4` 链接 |
| `text` | 纯文本，一行一个链接或 `标题,链接` |
| `custom` | 自定义 |

都不匹配就改 **`zlz_parse_custom()`** 这一个函数（文件里搜函数名即可），它接收源站原始响应，返回：

```php
return array(
    array('title' => '标题', 'url' => 'https://.../a.mp4', 'cover' => ''),
);
```

改这一个函数，其余代码不用动。

---

## 七、构建 Android APK

1. 整个仓库推到 GitHub
2. `app/gradle.properties` 里地址已预置为 `https://xfan.ccccocccc.cc/api.php`，需要改就改这行：

```properties
API_BASE=https://xfan.ccccocccc.cc/api.php
```

3. Actions 自动触发（或手动 Run workflow，可临时填 `api_base` 覆盖）
4. 下载 `zilizili-debug` 产物即 APK

本地构建：

```bash
cd app && gradle :app:assembleDebug
# 输出 app/app/build/outputs/apk/debug/app-debug.apk
```

> 仓库没带 `gradle-wrapper.jar`，本地先跑 `gradle wrapper` 生成。

App 端已开启明文 HTTP，方便你用 http 虚拟主机测试。

---

## 八、注意事项

- **务必改 `token`**，否则任何人都能往你服务端塞文件
- 虚拟主机磁盘和月流量有限，`max_bytes`（默认 200MB）和 `sync_limit` 按需调小
- 没 cron 的话，用主机面板的计划任务 / UptimeRobot 定时 GET `sync` 地址即可自动补货
- 抓取第三方站点内容请确认你有使用权限，仅用于自有内容或个人学习
