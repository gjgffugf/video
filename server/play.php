<?php
/**
 * zilizili 分享播放页（网页预览版）
 * ============================================================
 * 单个文件承担所有预览播放，三种模式：
 *
 *   1. ?video=用户名/视频名        播放站上某个用户的作品
 *   2. ?video-url=https://a/b.mp4 代理/直连播放外链（mp4 / webm / m3u8 等）
 *   3. ?iframe=<嵌入代码>          直接嵌入第三方播放器代码
 *
 * 顶部显示 App logo + 下载按钮；每次访问都尝试唤起 App，
 * 没安装则静默失败（不做任何提示、不跳商店）。
 * 只做视频预览，不提供点赞/评论。
 * ============================================================
 */

$ROOT = dirname(__FILE__);
require_once $ROOT . '/api_config.php';

function h($s) { return htmlspecialchars((string) $s, ENT_QUOTES, 'UTF-8'); }

/**
 * 多重解码。
 *
 * 分享出去的链接常被浏览器/微信/备忘录再编码一次，
 * 于是 %2F 变成 %252F、%3A 变成 %253A，PHP 自动解一层之后仍是 %xx，
 * 直接用会匹配不到视频。这里循环解到没有 %2F/%3A/%2F 为止。
 */
function zlz_deep_decode($s) {
    for ($i = 0; $i < 3; $i++) {
        if (stripos($s, '%2F') === false && stripos($s, '%3A') === false) break;
        $d = rawurldecode($s);
        if ($d === $s) break;
        $s = $d;
    }
    return $s;
}

/** 规范化：去掉首尾空白与多余斜杠，用于宽松匹配 */
function zlz_norm($s) { return strtolower(trim((string) $s)); }

// ---------- 读取参数 ----------
$videoPath = isset($_GET['video'])    ? trim(zlz_deep_decode($_GET['video']))       : '';
$videoUrl  = isset($_GET['video-url']) ? trim(zlz_deep_decode($_GET['video-url']))  : '';
$iframe    = isset($_GET['iframe'])   ? trim($_GET['iframe'])                       : '';
$sha1Param = isset($_GET['sha1'])     ? strtolower(trim($_GET['sha1']))             : '';

$title = 'zilizili';
$src   = '';
$type  = 'mp4';
$poster = '';
$uploader = '';
$intro = '';
$createdAt = '';

// ---------- 模式 1：?video=用户名/视频名 ----------
if ($videoPath !== '') {
    $meta = zlz_read($CFG, 'videos.json', array());

    // 优先用 sha1 精确定位（最可靠，不受编码影响）
    if ($sha1Param !== '' && isset($meta[$sha1Param])) {
        $found = $meta[$sha1Param];
    } else {
        $found = null;
        // 分隔符既可能是解码后的 '/'，也可能是没解干净的 %2F
        $sep = (strpos($videoPath, '/') !== false) ? '/' : '%2F';
        $parts = explode($sep, $videoPath, 2);
        $uname = isset($parts[0]) ? trim($parts[0]) : '';
        $vname = isset($parts[1]) ? trim($parts[1]) : '';
        if ($vname === '') { $vname = $uname; $uname = ''; }

        $uid = ($uname !== '') ? zlz_find_by_name($CFG, $uname) : '';

        // 1) 作者 + 标题
        if ($uid !== '') {
            foreach ($meta as $sha1 => $v) {
                if (!isset($v['author_id']) || $v['author_id'] !== $uid) continue;
                if ($vname !== '' && zlz_norm($v['title']) !== zlz_norm($vname)) continue;
                $found = $v; break;
            }
        }
        // 2) 只看标题
        if (!$found && $vname !== '') {
            foreach ($meta as $sha1 => $v) {
                if (isset($v['title']) && zlz_norm($v['title']) === zlz_norm($vname)) { $found = $v; break; }
            }
        }
        // 3) 标题去掉扩展名再比一次（有些分享会带上 .mp4）
        if (!$found && $vname !== '') {
            $base = preg_replace('/\.[a-z0-9]+$/i', '', zlz_norm($vname));
            foreach ($meta as $sha1 => $v) {
                $t = isset($v['title']) ? preg_replace('/\.[a-z0-9]+$/i', '', zlz_norm($v['title'])) : '';
                if ($t !== '' && $t === $base) { $found = $v; break; }
            }
        }
    }

    if ($found) {
        $base = zlz_base($CFG);
        $file = isset($found['file']) ? $found['file'] : '';
        $src = ($file !== '' && is_file($ROOT . '/' . $file))
            ? $base . '/' . zlz_urlenc($file)
            : (isset($found['source_url']) ? $found['source_url'] : '');
        $title = isset($found['title']) ? $found['title'] : 'zilizili';
        $intro = isset($found['intro']) ? $found['intro'] : '';
        $createdAt = isset($found['created_at']) ? $found['created_at'] : '';
        $cover = isset($found['cover']) ? $found['cover'] : '';
        if ($cover !== '') $poster = $base . '/' . zlz_urlenc($cover);
        $type = strtolower(pathinfo($file !== '' ? $file : $src, PATHINFO_EXTENSION));
        if ($type === '') $type = 'mp4';

        $aid = isset($found['author_id']) ? $found['author_id'] : '';
        if ($aid !== '') {
            $au = zlz_user_get($CFG, $aid);
            if ($au && isset($au['username'])) $uploader = $au['username'];
        }
    }
}

// ---------- 模式 2：?video-url=... ----------
elseif ($videoUrl !== '') {
    $src = $videoUrl;
    $title = isset($_GET['title']) ? trim($_GET['title']) : '外部视频';
    $intro = isset($_GET['intro']) ? trim($_GET['intro']) : '';
    $poster = isset($_GET['poster']) ? trim($_GET['poster']) : '';
    $p = parse_url($src, PHP_URL_PATH);
    $type = strtolower(pathinfo($p !== null ? $p : $src, PATHINFO_EXTENSION));
    if ($type === '') $type = 'mp4';

    // 默认由服务端代理转发：外链常有 Referer 防盗链 / 跨域限制，
    // 浏览器直连会被源站拒绝。加 ?proxy=0 可关掉，改回直连。
    $useProxy = !isset($_GET['proxy']) || $_GET['proxy'] !== '0';
    if ($useProxy) {
        // 视频地址指向本页自己（带 proxy=1），流式转发
        $src = zlz_base($CFG) . '/play.php?video-url=' . rawurlencode($src) . '&proxy=1';
    }

    // proxy=1：真正执行转发的那一趟请求
    if (isset($_GET['proxy']) && $_GET['proxy'] === '1') {
        header('Content-Type: ' . ($type === 'm3u8' ? 'application/vnd.apple.mpegurl' : zlz_mime($type)));
        header('Access-Control-Allow-Origin: *');
        header('Accept-Ranges: bytes');
        $origin = zlz_deep_decode($videoUrl);
        if (function_exists('curl_init')) {
            $ch = curl_init($origin);
            curl_setopt_array($ch, array(
                CURLOPT_RETURNTRANSFER => false,
                CURLOPT_FOLLOWLOCATION => true,
                CURLOPT_CONNECTTIMEOUT => 10,
                CURLOPT_TIMEOUT => 0,
                CURLOPT_SSL_VERIFYPEER => false,
                CURLOPT_SSL_VERIFYHOST => false,
                CURLOPT_USERAGENT => $CFG['browser_ua'],
                CURLOPT_HTTPHEADER => array('Referer: ' . zlz_origin($origin) . '/'),
                CURLOPT_HEADERFUNCTION => function ($ch, $line) {
                    $l = strtolower($line);
                    // 透传 Content-Type / Content-Length，其余头丢弃
                    if (strpos($l, 'content-type:') === 0 || strpos($l, 'content-length:') === 0) {
                        header(trim($line));
                    }
                    return strlen($line);
                },
            ));
            curl_exec($ch);
            curl_close($ch);
            exit;
        }
        // cURL 不可用时的降级
        $d = @file_get_contents($origin);
        if ($d !== false) echo $d;
        exit;
    }
}

// ---------- 模式 3：?iframe=嵌入代码 ----------
elseif ($iframe !== '') {
    $title = isset($_GET['title']) ? trim($_GET['title']) : '嵌入视频';
}

$base = zlz_base($CFG);
$apkUrl = isset($CFG['apk_url']) ? $CFG['apk_url'] : 'https://github.com/gjgffugf/update/releases/latest';
$appScheme = 'zilizili://play?' . http_build_query(array_filter(array(
    'video' => $videoPath, 'url' => $videoUrl, 'iframe' => $iframe,
)));

$isM3u8 = ($type === 'm3u8' || stripos($src, '.m3u8') !== false);
$isEmbed = ($iframe !== '');

?><!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="theme-color" content="#0b0b0f">
<title><?php echo h($title); ?> · zilizili</title>
<style>
  *{box-sizing:border-box;margin:0;padding:0}
  body{background:#0b0b0f;color:#fff;font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
       min-height:100vh;display:flex;flex-direction:column;-webkit-tap-highlight-color:transparent}
  .top{display:flex;align-items:center;gap:10px;padding:12px 14px;border-bottom:1px solid #1e1e26;flex:0 0 auto}
  .logo{width:34px;height:34px;border-radius:9px;background:linear-gradient(135deg,#2f7bff,#7b3cff);
        display:flex;align-items:center;justify-content:center;font-weight:800;font-size:15px;flex:0 0 auto}
  .brand{font-size:15px;font-weight:700;letter-spacing:.5px}
  .sub{font-size:11px;color:#888;margin-top:1px}
  .spacer{flex:1}
  .dl{border:1px solid #2f7bff;color:#fff;background:transparent;padding:7px 14px;border-radius:18px;
      font-size:13px;text-decoration:none;cursor:pointer}
  .dl:active{background:#2f7bff22}
  .wrap{flex:1 1 auto;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:14px}
  .stage{width:100%;max-width:520px;background:#000;border-radius:12px;overflow:hidden;position:relative;
         aspect-ratio:9/16;max-height:72vh}
  .stage video,.stage iframe{width:100%;height:100%;border:0;display:block;object-fit:contain;background:#000}
  .msg{color:#888;font-size:13px;text-align:center;padding:20px}
  .meta{width:100%;max-width:520px;margin-top:14px}
  .t{font-size:15px;font-weight:600;line-height:1.5;word-break:break-all}
  .up{color:#2f7bff;font-size:13px;margin-top:6px}
  .intro{color:#aaa;font-size:13px;margin-top:6px;line-height:1.6;white-space:pre-wrap;word-break:break-word}
  .time{color:#666;font-size:11px;margin-top:8px}
  .loading{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;flex-direction:column;
           gap:10px;color:#aaa;font-size:12px;background:#000}
  .spin{width:34px;height:34px;border:3px solid #333;border-top-color:#2f7bff;border-radius:50%;
        animation:sp 1s linear infinite}
  @keyframes sp{to{transform:rotate(360deg)}}
  .foot{padding:14px;text-align:center;color:#555;font-size:11px;flex:0 0 auto}
</style>
</head>
<body>

<div class="top">
  <div class="logo">Z</div>
  <div>
    <div class="brand">zilizili</div>
    <div class="sub">网页预览版</div>
  </div>
  <div class="spacer"></div>
  <a class="dl" href="<?php echo h($apkUrl); ?>">下载 App</a>
</div>

<div class="wrap">
  <div class="stage" id="stage">
    <div class="loading" id="loading"><div class="spin"></div><div>正在链接服务器…</div></div>
    <?php if ($isEmbed): ?>
      <?php echo $iframe; ?>
    <?php elseif ($src !== ''): ?>
      <video id="v" playsinline webkit-playsinline controls autoplay loop muted
             preload="auto"<?php echo $poster !== '' ? ' poster="' . h($poster) . '"' : ''; ?>
             src="<?php echo h($src); ?>"></video>
    <?php else: ?>
      <div class="msg">视频不存在或已被删除</div>
    <?php endif; ?>
  </div>

  <div class="meta">
    <div class="t"><?php echo h($title); ?></div>
    <?php if ($uploader !== ''): ?><div class="up">@<?php echo h($uploader); ?></div><?php endif; ?>
    <?php if ($intro !== ''): ?><div class="intro"><?php echo h($intro); ?></div><?php endif; ?>
    <?php if ($createdAt !== ''): ?><div class="time"><?php echo h($createdAt); ?></div><?php endif; ?>
    <div class="time">网页预览版不支持点赞与评论，请在 App 内操作</div>
  </div>
</div>

<div class="foot">zilizili · 学习与测试用途</div>

<script>
// ---- 尝试唤起 App，没装就静默失败 ----
(function(){
  var scheme = <?php echo json_encode($appScheme); ?>;
  var tried = false;
  function tryOpen(){
    if (tried) return; tried = true;
    // 自定义 scheme：装了会被拉起，没装浏览器静默忽略
    var f = document.createElement('iframe');
    f.style.display = 'none'; f.src = scheme;
    document.body.appendChild(f);
    setTimeout(function(){ f.remove(); }, 1500);
    // Android Chrome 的 intent 兜底（无 fallback URL，没装即静默）
    if (/Android/i.test(navigator.userAgent)) {
      var host = location.host, uri = location.href.replace(/^https?:\/\//, '');
      window.location.href = 'intent://' + uri + '#Intent;scheme=zilizili;package=com.zilizili.app;end';
    }
  }
  tryOpen();
})();

// ---- 卡顿提示 ----
(function(){
  var v = document.getElementById('v');
  var ld = document.getElementById('loading');
  if (!v || !ld) { if (ld) ld.style.display = 'none'; return; }
  function show(on){ ld.style.display = on ? 'flex' : 'none'; }
  v.addEventListener('waiting', function(){ show(true); });
  v.addEventListener('stalled', function(){ show(true); });
  v.addEventListener('playing', function(){ show(false); });
  v.addEventListener('canplay', function(){ show(false); });
  v.addEventListener('loadeddata', function(){ show(false); });
  v.addEventListener('error', function(){ show(false); });
  setTimeout(function(){ if (v.readyState === 0) show(false); }, 6000);
})();

// ---- m3u8 支持（桌面 Chrome 原生不支持，用 hls.js）----
(function(){
  var v = document.getElementById('v');
  if (!v) return;
  var src = v.getAttribute('src') || '';
  var isHls = /\.m3u8(\?|$)/i.test(src);
  if (!isHls) return;
  if (v.canPlayType('application/vnd.apple.mpegurl')) return; // Safari 原生支持
  var s = document.createElement('script');
  s.src = 'https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js';
  s.onload = function(){
    if (!window.Hls || !window.Hls.isSupported()) return;
    var hls = new window.Hls();
    hls.loadSource(src);
    hls.attachMedia(v);
  };
  document.head.appendChild(s);
})();
</script>

</body>
</html>
