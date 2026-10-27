<?php
/**
 * zilizili 服务端 —— 单文件版
 * ============================================================
 * 部署：把本文件上传到虚拟主机，访问 https://xfan.ccccocccc.cc/api.php
 *
 * 所有功能靠 ?action= 区分：
 *   feed    随机推送（App 调用，无需令牌）
 *   sync    从远程源站抓取视频 -> 存本地 -> 算 SHA1
 *   import  扫描本地目录，把已有视频登记入库（FTP 传完文件用这个）
 *   probe   探测源站返回格式（排查用，强烈建议先跑一次）
 *   status  查看库存 / 推送状态
 *   reset   立即重置推送池
 *   upload  App 上传视频（备选）
 *
 * 推送逻辑：
 *   1. 从待推送池 pool 里随机取 count 条
 *   2. 取出的立刻从 pool 移除、记入 pushed（推送完清除）
 *   3. pool 空了 -> 全部视频重新打乱填满 pool，清空 pushed，round+1
 * ============================================================
 */

// ---------------- 配置区（改这里）----------------

$CFG = array(
    // 管理令牌，sync/import/reset/upload 需要带 ?token=xxx，务必修改
    'token'       => '2287681747',

    // 本服务端对外根地址。留空则自动探测（推荐留空）
    'base'        => '',

    // 视频源站列表地址。远程抓取模式下必填，例如：
    //   'https://xfan.ccccocccc.cc/list.json'
    //   'https://xfan.ccccocccc.cc/videos/'
    // 留空 = 只用 import 导入本地文件，不做远程抓取
    'source_url'  => '',

    // 源站解析模式：auto=自动 / json=JSON列表 / dir=HTML目录 / text=纯文本一行一条 / custom=自定义
    'source_mode' => 'auto',

    // 本地待导入目录（相对本文件）。用 FTP 把视频丢进去，再调 import
    'import_dir'  => 'source',

    // 视频落盘目录（远程抓取下载到这里）
    'video_dir'   => 'videos',

    // JSON 状态文件目录
    'data_dir'    => 'data',

    // 单个视频大小上限。
    // ⚠️ iFastNet / InfinityFree 免费版单文件上限约 10MB，务必按你的主机调整。
    // 换到无此限制的主机（付费主机 / 对象存储）后再调大。
    'max_bytes'   => 10 * 1024 * 1024,

    // 单次 sync / import 最多处理多少个
    'sync_limit'  => 20,

    // 默认推送条数
    'feed_size'   => 5,

    // 识别的视频后缀
    'exts'        => 'mp4,webm,mov,m4v,mkv,ts,flv,m3u8',

    // ============ 浏览器伪装（绕过 WAF / 反爬的基础拦截）============
    // true = 用浏览器请求头 + 持久化 Cookie + Referer + HTTP/2 访问源站
    'browser_mode' => true,

    // 伪装成哪个浏览器（可用自己浏览器 F12 里复制的 UA）
    'browser_ua'  => 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',

    // 浏览器语言头
    'browser_lang' => 'zh-CN,zh;q=0.9,en;q=0.8',

    // 每次请求之间的礼貌间隔（毫秒）。太小会被判成爬虫，建议 500~1500
    'crawl_delay_ms' => 800,

    // ============ 分片上传（App 端 250MB 视频）============
    // 单次 POST 扛不住大文件（虚拟主机 upload_max_filesize 通常 2~20M），
    // 所以走 init -> chunk(多次) -> finish 三段式。
    'upload_max_bytes' => 250 * 1024 * 1024,  // 单文件上限 250MB

    // 分片临时目录（放在 data/ 下，已被 .htaccess 拒绝外部访问）
    'chunk_dir'   => 'data/uploads',

    // 分片临时文件保留时长（秒），超时自动清理
    'chunk_ttl'   => 24 * 3600,
);

// 缺省值兜底：升级脚本时新增的配置项若不存在，自动补上，避免报错
$CFG = array_merge(array(
    'token'          => 'change-me-please',
    'base'           => '',
    'source_url'     => '',
    'source_mode'    => 'auto',
    'import_dir'     => 'source',
    'video_dir'      => 'videos',
    'data_dir'       => 'data',
    'max_bytes'      => 10 * 1024 * 1024,
    'sync_limit'     => 20,
    'feed_size'      => 5,
    'exts'           => 'mp4,webm,mov,m4v,mkv,ts,flv,m3u8',
    'browser_mode'   => true,
    'browser_ua'     => 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
    'browser_lang'   => 'zh-CN,zh;q=0.9,en;q=0.8',
    'crawl_delay_ms' => 800,
    'upload_max_bytes' => 250 * 1024 * 1024,
    'chunk_dir'      => 'data/uploads',
    'chunk_ttl'      => 24 * 3600,
), $CFG);

// ---------------- 以下为功能代码，一般不用改 ----------------

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store, no-cache, must-revalidate');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type, X-ZLZ-Token');

define('ZLZ_ROOT', dirname(__FILE__));
define('ZLZ_UA', 'zilizili/1.0');

// ---- 小工具 ----

function zlz_out($data, $status = 200) {
    http_response_code($status);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
    exit;
}

function zlz_p($key, $default = '') {
    if (isset($_GET[$key]) && $_GET[$key] !== '') return $_GET[$key];
    if (isset($_POST[$key]) && $_POST[$key] !== '') return $_POST[$key];
    return $default;
}

function zlz_base($CFG) {
    if (!empty($CFG['base'])) return rtrim($CFG['base'], '/');
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (isset($_SERVER['SERVER_PORT']) && $_SERVER['SERVER_PORT'] == 443)
        || (isset($_SERVER['HTTP_X_FORWARDED_PROTO']) && $_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https');
    $scheme = $https ? 'https' : 'http';
    $host = isset($_SERVER['HTTP_HOST']) ? $_SERVER['HTTP_HOST'] : 'localhost';
    $dir = rtrim(str_replace('\\', '/', dirname(isset($_SERVER['SCRIPT_NAME']) ? $_SERVER['SCRIPT_NAME'] : '/')), '/');
    return $scheme . '://' . $host . $dir;
}

function zlz_path($rel) {
    return ZLZ_ROOT . '/' . ltrim($rel, '/');
}

function zlz_mkdir($dir) {
    if (!is_dir($dir)) @mkdir($dir, 0755, true);
    return is_dir($dir);
}

function zlz_urlencode_path($rel) {
    $parts = explode('/', $rel);
    return implode('/', array_map('rawurlencode', $parts));
}

/** 安全拼接：禁止 ../ 逃出根目录 */
function zlz_safe_rel($p) {
    $p = str_replace('\\', '/', $p);
    if (strpos($p, '..') !== false) return '';
    return ltrim(trim($p, '/'), '/');
}

// ---- JSON 文件仓库 + 文件锁 ----

function zlz_read($CFG, $name, $default = array()) {
    $path = zlz_path($CFG['data_dir'] . '/' . $name);
    if (!is_file($path)) return $default;
    $raw = @file_get_contents($path);
    if ($raw === false || $raw === '') return $default;
    $d = json_decode($raw, true);
    return is_array($d) ? $d : $default;
}

function zlz_write($CFG, $name, $data) {
    zlz_mkdir(zlz_path($CFG['data_dir']));
    $path = zlz_path($CFG['data_dir'] . '/' . $name);
    $tmp = $path . '.tmp';
    $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if (@file_put_contents($tmp, $json, LOCK_EX) === false) return false;
    @rename($tmp, $path);
    return true;
}

/** 单文件 JSON 读写（分片上传元数据用，不带锁，调用处已加锁或单请求内） */
function zlz_read_raw($path, $default = null) {
    if (!is_file($path)) return $default;
    $raw = @file_get_contents($path);
    if ($raw === false || $raw === '') return $default;
    $d = json_decode($raw, true);
    return is_array($d) ? $d : $default;
}

function zlz_write_raw($path, $data) {
    $dir = dirname($path);
    if (!is_dir($dir)) @mkdir($dir, 0755, true);
    $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    return @file_put_contents($path, $json, LOCK_EX) !== false;
}

/** 清理过期的分片临时文件 */
function zlz_gc_chunks($CFG) {
    $dir = zlz_path($CFG['chunk_dir']);
    if (!is_dir($dir)) return;
    $ttl = (int) $CFG['chunk_ttl'];
    $now = time();
    $dh = @opendir($dir);
    if (!$dh) return;
    while (($f = readdir($dh)) !== false) {
        if ($f === '.' || $f === '..') continue;
        $abs = $dir . '/' . $f;
        if (!is_file($abs)) continue;
        $mt = @filemtime($abs);
        if ($mt !== false && ($now - $mt) > $ttl) @unlink($abs);
    }
    closedir($dh);
}

/** 带互斥锁执行，避免并发重复推送 */
function zlz_locked($CFG, $name, $fn) {
    zlz_mkdir(zlz_path($CFG['data_dir']));
    $lock = zlz_path($CFG['data_dir'] . '/' . $name . '.lock');
    $fp = @fopen($lock, 'c');
    if (!$fp) return call_user_func($fn);
    flock($fp, LOCK_EX);
    try {
        $result = call_user_func($fn);
    } catch (Exception $e) {
        flock($fp, LOCK_UN);
        fclose($fp);
        throw $e;
    }
    flock($fp, LOCK_UN);
    fclose($fp);
    return $result;
}

// ---- HTTP（浏览器伪装 + Cookie 会话 + 反爬页处理）----
//
// 说明：本段只做"让自己的服务端像普通浏览器一样发请求"这件事——
// 补全浏览器请求头、持久化 Cookie、按站点来源带 Referer、请求间加礼貌间隔。
// 它适用于你自己有权访问的主机 / 已授权的源站。
//
// 如果对方返回的是真正的人机验证（JS 挑战、滑块、验证码），
// 那是对方有意设置的访问控制：请走"申请接口权限 / 把自己的服务器 IP 加入白名单"，
// 脚本不会也不应该去破解它。

/** 取站点根地址，用于 Referer / Origin */
function zlz_origin($url) {
    $p = parse_url($url);
    if (!$p || empty($p['host'])) return '';
    $scheme = isset($p['scheme']) ? $p['scheme'] : 'https';
    return $scheme . '://' . $p['host'] . (isset($p['port']) ? ':' . $p['port'] : '');
}

/** Cookie 文件（存在 data/ 下，已被 .htaccess 拒绝外部访问） */
function zlz_cookie_path($CFG) {
    zlz_mkdir(zlz_path($CFG['data_dir']));
    $host = parse_url($CFG['source_url'] !== '' ? $CFG['source_url'] : 'http://localhost', PHP_URL_HOST);
    return zlz_path($CFG['data_dir'] . '/cookie_' . preg_replace('/[^a-z0-9]/i', '_', $host) . '.txt');
}

/** 生成一套 Chrome 风格请求头。$kind = nav(页面) | media(视频文件) */
function zlz_browser_headers($CFG, $targetUrl, $referer = '', $kind = 'nav') {
    $ua = $CFG['browser_ua'];
    $lang = $CFG['browser_lang'];
    $origin = zlz_origin($targetUrl);

    $h = array();
    $h[] = 'User-Agent: ' . $ua;
    $h[] = 'Accept-Language: ' . $lang;

    if ($kind === 'media') {
        $h[] = 'Accept: */*';
        $h[] = 'Sec-Fetch-Dest: video';
        $h[] = 'Sec-Fetch-Mode: no-cors';
        $h[] = 'Sec-Fetch-Site: same-origin';
        $h[] = 'Range: bytes=0-';           // 浏览器拉视频都会带 Range
    } else {
        $h[] = 'Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7';
        $h[] = 'Upgrade-Insecure-Requests: 1';
        $h[] = 'Sec-Fetch-Dest: document';
        $h[] = 'Sec-Fetch-Mode: navigate';
        $h[] = 'Sec-Fetch-Site: ' . ($referer === '' ? 'none' : 'same-origin');
        $h[] = 'Sec-Fetch-User: ?1';
    }

    // Client Hints（现代 Chrome 必带，缺了容易被判为脚本）
    $h[] = 'sec-ch-ua: "Google Chrome";v="131", "Chromium";v="131", "Not_A Brand";v="24"';
    $h[] = 'sec-ch-ua-mobile: ?0';
    $h[] = 'sec-ch-ua-platform: "Windows"';

    if ($referer === '') {
        $referer = $origin . '/';
    }
    if ($referer !== '') {
        $h[] = 'Referer: ' . $referer;
        if ($kind !== 'media') $h[] = 'Origin: ' . zlz_origin($referer);
    }
    return $h;
}

/** 判断响应是不是 WAF / 反爬拦截页 */
function zlz_is_challenge($code, $body) {
    if ($code == 403 || $code == 429 || $code == 503) return true;
    if ($body === '') return false;

    // 只取前 8KB 判断，避免大文件误判
    $head = substr($body, 0, 8192);
    $markers = array(
        '__cf_chl', 'cf-browser-verification', 'challenge-platform', 'cf_chl_opt',
        'Just a moment', 'Checking your browser', 'Attention Required',
        '_incapsula_', 'Incapsula', 'Sucuri', 'ModSecurity', 'mod_security',
        'yunsuo', 'Yunsuo', 'safe3waf', 'jiasule', '知道创宇',
        'policy_default_denied', 'Request denied',
        'enable JavaScript', 'Please enable JavaScript', 'javascript required',
        '正在验证', '安全验证', '人机验证', '访问验证', '防火墙', '请输入验证码', '滑动验证',
        // iFastNet / InfinityFree 免费版浏览器验证（aes.js 挑战，免费版强制且无法关闭）
        'aes.js', 'This site requires Javascript', 'requires javascript to work',
        'infinityfree', 'ifastnet',
    );
    foreach ($markers as $m) {
        if (stripos($head, $m) !== false) return true;
    }
    return false;
}

/**
 * 带浏览器伪装的 GET
 * @return array(code, body, err)
 */
function zlz_http_get($CFG, $url, $timeout = 20, $referer = '', $kind = 'nav') {
    if (!function_exists('curl_init')) {
        $hdr = '';
        foreach (zlz_browser_headers($CFG, $url, $referer, $kind) as $h) $hdr .= $h . "\r\n";
        $ctx = stream_context_create(array(
            'http' => array('method' => 'GET', 'timeout' => $timeout, 'header' => $hdr, 'follow_location' => 1),
            'ssl'  => array('verify_peer' => false),
        ));
        $body = @file_get_contents($url, false, $ctx);
        if ($body === false) return array(0, '', 'request failed');
        return array(200, $body, '');
    }

    $ch = curl_init($url);
    $opts = array(
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS      => 5,
        CURLOPT_CONNECTTIMEOUT => 10,
        CURLOPT_TIMEOUT        => $timeout,
        CURLOPT_SSL_VERIFYPEER => false,
        CURLOPT_SSL_VERIFYHOST => false,
        // 自动发 Accept-Encoding: gzip,deflate,br 并自动解压
        CURLOPT_ENCODING       => '',
        CURLOPT_HTTPHEADER     => zlz_browser_headers($CFG, $url, $referer, $kind),
    );
    // 强制 HTTP/2 —— 浏览器都是 HTTP/2，很多 WAF 会据此识别（老环境可能没有此常量）
    if (defined('CURL_HTTP_VERSION_2TLS')) {
        $opts[CURLOPT_HTTP_VERSION] = CURL_HTTP_VERSION_2TLS;
    }
    if ($CFG['browser_mode']) {
        $cookieFile = zlz_cookie_path($CFG);
        $opts[CURLOPT_COOKIEFILE] = $cookieFile;   // 带上之前的 Cookie
        $opts[CURLOPT_COOKIEJAR]  = $cookieFile;   // 回写新 Cookie
    }
    curl_setopt_array($ch, $opts);

    $body = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);

    if ($body !== false) return array($code ? $code : 200, $body, '');
    return array(0, '', $err);
}

/**
 * 先访问站点首页拿 Cookie（"暖机"），再请求真正的目标。
 * 很多 WAF 只拦截"没有会话就直接访问深层路径"的请求。
 */
function zlz_http_get_smart($CFG, $url, $timeout = 20) {
    if (!$CFG['browser_mode']) {
        return zlz_http_get($CFG, $url, $timeout);
    }

    $origin = zlz_origin($url);

    // 第 1 步：暖机 —— 用浏览器头访问站点根，建立会话 Cookie
    zlz_http_get($CFG, $origin . '/', 15, '', 'nav');
    if ($CFG['crawl_delay_ms'] > 0) usleep($CFG['crawl_delay_ms'] * 1000);

    // 第 2 步：带 Referer 访问目标
    list($code, $body, $err) = zlz_http_get($CFG, $url, $timeout, $origin . '/', 'nav');

    // 第 3 步：如果还是被拦，清掉 Cookie 重来一次（有时旧 Cookie 已失效）
    if (zlz_is_challenge($code, $body)) {
        $cf = zlz_cookie_path($CFG);
        @unlink($cf);
        if ($CFG['crawl_delay_ms'] > 0) usleep($CFG['crawl_delay_ms'] * 2000);
        zlz_http_get($CFG, $origin . '/', 15, '', 'nav');
        if ($CFG['crawl_delay_ms'] > 0) usleep($CFG['crawl_delay_ms'] * 1000);
        list($code, $body, $err) = zlz_http_get($CFG, $url, $timeout, $origin . '/', 'nav');
    }

    return array($code, $body, $err);
}

function zlz_download($CFG, $url, $dest, $maxBytes, $referer = '') {
    @unlink($dest);
    if ($referer === '') $referer = zlz_origin($url) . '/';

    if (function_exists('curl_init')) {
        $fp = @fopen($dest, 'wb');
        if (!$fp) return array(false, 'cannot create file');
        $ch = curl_init($url);
        $opts = array(
            CURLOPT_FILE           => $fp,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS      => 5,
            CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_TIMEOUT        => 0,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
            CURLOPT_ENCODING       => '',
            CURLOPT_HTTPHEADER     => zlz_browser_headers($CFG, $url, $referer, 'media'),
            CURLOPT_NOPROGRESS     => false,
            CURLOPT_PROGRESSFUNCTION => function ($ch, $dlSize, $dlNow, $ulSize, $ulNow) use ($maxBytes) {
                return ($dlNow > $maxBytes) ? 1 : 0;
            },
        );
        if ($CFG['browser_mode']) {
            $cookieFile = zlz_cookie_path($CFG);
            $opts[CURLOPT_COOKIEFILE] = $cookieFile;
            $opts[CURLOPT_COOKIEJAR]  = $cookieFile;
        }
        if (defined('CURL_HTTP_VERSION_2TLS')) {
            $opts[CURLOPT_HTTP_VERSION] = CURL_HTTP_VERSION_2TLS;
        }
        curl_setopt_array($ch, $opts);

        $ok = curl_exec($ch);
        $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $err = curl_error($ch);
        curl_close($ch);
        fclose($fp);

        if ($ok === false && $err !== '' && !is_file($dest)) return array(false, $err);
        if ($code >= 400) {
            @unlink($dest);
            // 被 WAF 拦了：暖机后重试一次
            if ($CFG['browser_mode'] && zlz_is_challenge($code, '')) {
                if ($CFG['crawl_delay_ms'] > 0) usleep($CFG['crawl_delay_ms'] * 1000);
                return zlz_download($CFG, $url, $dest, $maxBytes, zlz_origin($url) . '/');
            }
            return array(false, 'HTTP ' . $code);
        }
        if (!is_file($dest) || filesize($dest) == 0) { @unlink($dest); return array(false, 'empty file'); }
        return array(true, '');
    }

    $hdr = '';
    foreach (zlz_browser_headers($CFG, $url, $referer, 'media') as $h) $hdr .= $h . "\r\n";
    $ctx = stream_context_create(array(
        'http' => array('method' => 'GET', 'timeout' => 60, 'header' => $hdr),
        'ssl'  => array('verify_peer' => false),
    ));
    $data = @file_get_contents($url, false, $ctx);
    if ($data === false) return array(false, 'download failed');
    @file_put_contents($dest, $data);
    return array(true, '');
}

function zlz_abs($url, $baseUrl) {
    $url = trim($url);
    if ($url === '') return '';
    if (preg_match('#^https?://#i', $url)) return $url;
    if (strpos($url, '//') === 0) return 'https:' . $url;
    $parts = parse_url($baseUrl);
    if (!$parts || empty($parts['host'])) return $url;
    $scheme = isset($parts['scheme']) ? $parts['scheme'] : 'https';
    $host = $parts['host'];
    if (strpos($url, '/') === 0) return $scheme . '://' . $host . $url;
    $path = isset($parts['path']) ? $parts['path'] : '/';
    $dir = rtrim(substr($path, 0, strrpos($path, '/')), '/');
    return $scheme . '://' . $host . $dir . '/' . ltrim($url, '/');
}

// ---- 源站解析 ----

function zlz_ext_list($CFG) {
    return array_filter(array_map('trim', explode(',', $CFG['exts'])));
}

function zlz_is_video($name, $CFG) {
    $ext = strtolower(pathinfo($name, PATHINFO_EXTENSION));
    return in_array($ext, zlz_ext_list($CFG), true);
}

function zlz_parse_json($body, $baseUrl) {
    $data = json_decode($body, true);
    if (!is_array($data)) return array();

    $candidates = array($data);
    foreach (array('list', 'data', 'videos', 'items', 'results', 'vod_list', 'files') as $k) {
        if (isset($data[$k]) && is_array($data[$k])) $candidates[] = $data[$k];
    }
    foreach ($candidates as $rows) {
        $out = array();
        foreach ($rows as $row) {
            if (!is_array($row)) continue;
            $url = '';
            foreach (array('url', 'videoUrl', 'video_url', 'playUrl', 'play_url', 'play', 'src', 'link', 'file', 'path', 'm3u8') as $k) {
                if (!empty($row[$k]) && is_string($row[$k])) { $url = $row[$k]; break; }
            }
            if ($url === '') continue;
            $title = '';
            foreach (array('title', 'name', 'vod_name', 'subject', 'filename') as $k) {
                if (!empty($row[$k]) && is_string($row[$k])) { $title = $row[$k]; break; }
            }
            $cover = '';
            foreach (array('cover', 'pic', 'thumb', 'poster', 'vod_pic', 'image') as $k) {
                if (!empty($row[$k]) && is_string($row[$k])) { $cover = $row[$k]; break; }
            }
            $out[] = array(
                'title' => $title !== '' ? $title : basename(parse_url($url, PHP_URL_PATH)),
                'url'   => zlz_abs($url, $baseUrl),
                'cover' => $cover !== '' ? zlz_abs($cover, $baseUrl) : '',
            );
        }
        if (!empty($out)) return $out;
    }
    return array();
}

function zlz_parse_dir($body, $baseUrl, $CFG) {
    $exts = zlz_ext_list($CFG);
    $pattern = '#(?:href|src)=["\']([^"\'\s]+\.(?:' . implode('|', $exts) . '))["\']#i';
    preg_match_all($pattern, $body, $m);
    $out = array();
    $seen = array();
    foreach ($m[1] as $href) {
        $url = zlz_abs(html_entity_decode($href), $baseUrl);
        if (isset($seen[$url])) continue;
        $seen[$url] = true;
        $path = parse_url($url, PHP_URL_PATH);
        $out[] = array(
            'title' => rawurldecode(basename($path)),
            'url'   => $url,
            'cover' => '',
        );
    }
    return $out;
}

function zlz_parse_text($body, $baseUrl) {
    $out = array();
    foreach (preg_split('/\r\n|\r|\n/', $body) as $line) {
        $line = trim($line);
        if ($line === '' || strpos($line, '#') === 0) continue;
        $parts = preg_split('/[|,;\t]+/', $line);
        $url = null;
        $title = null;
        foreach ($parts as $p) {
            if (preg_match('#^https?://#i', $p) || preg_match('#\.(mp4|webm|mov|m4v|mkv|ts|flv|m3u8)$#i', $p)) {
                if ($url === null) $url = $p;
            } elseif ($title === null) {
                $title = $p;
            }
        }
        if ($url === null) continue;
        $out[] = array(
            'title' => $title !== null ? $title : basename(parse_url($url, PHP_URL_PATH)),
            'url'   => zlz_abs($url, $baseUrl),
            'cover' => '',
        );
    }
    return $out;
}

/**
 * ★ 自定义解析钩子 ★
 * 如果 auto/json/dir/text 都匹配不上你的源站，改这一个函数即可。
 * $body = 源站原始响应字符串，返回 [['title'=>..,'url'=>..,'cover'=>..], ...]
 */
function zlz_parse_custom($body, $baseUrl) {
    // 示例 1：源站返回 {"code":200,"msg":"ok","data":{"list":[{"name":"xx","play":"1.mp4"}]}}
    // $j = json_decode($body, true);
    // $out = array();
    // foreach ($j['data']['list'] as $r) {
    //     $out[] = array('title' => $r['name'], 'url' => zlz_abs($r['play'], $baseUrl), 'cover' => '');
    // }
    // return $out;

    // 示例 2：源站返回每行一个文件名，如 "a.mp4"
    // $out = array();
    // foreach (preg_split('/\r\n|\r|\n/', $body) as $line) {
    //     $line = trim($line);
    //     if ($line !== '') $out[] = array('title' => $line, 'url' => zlz_abs($line, $baseUrl), 'cover' => '');
    // }
    // return $out;

    return array();
}

function zlz_source_list($CFG) {
    $url = $CFG['source_url'];
    if ($url === '') return array();
    // 用带浏览器伪装 + 暖机的请求
    list($code, $body, $err) = zlz_http_get_smart($CFG, $url);
    if ($code == 0 || $body === '') {
        throw new Exception('源站请求失败: ' . ($err ? $err : 'HTTP ' . $code));
    }
    if (zlz_is_challenge($code, $body)) {
        throw new Exception('被 WAF / 反爬页拦截（HTTP ' . $code . '）。'
            . '先用 ?action=probe&token=xxx 看原始返回；'
            . '若是人机验证，请在对方网关把自己的服务器 IP 加入白名单');
    }
    if ($code >= 400) {
        throw new Exception('源站返回 HTTP ' . $code . '（见 action=probe）');
    }

    $mode = $CFG['source_mode'];
    if ($mode === 'json')   return zlz_parse_json($body, $url);
    if ($mode === 'dir')    return zlz_parse_dir($body, $url, $CFG);
    if ($mode === 'text')   return zlz_parse_text($body, $url);
    if ($mode === 'custom') return zlz_parse_custom($body, $url);

    // auto：依次尝试
    $r = zlz_parse_json($body, $url);   if (!empty($r)) return array($r, 'json');
    $r = zlz_parse_dir($body, $url, $CFG); if (!empty($r)) return array($r, 'dir');
    $r = zlz_parse_text($body, $url);   if (!empty($r)) return array($r, 'text');
    $r = zlz_parse_custom($body, $url); if (!empty($r)) return array($r, 'custom');
    return array(array(), 'none');
}

// ---- 入库 ----

function zlz_add_video($CFG, &$meta, &$pool, $sha1, $relFile, $title, $sourceUrl, $cover, $size, $ext) {
    if (isset($meta[$sha1])) return false;
    $meta[$sha1] = array(
        'sha1'       => $sha1,
        'title'      => $title,
        'file'       => $relFile,
        'source_url' => $sourceUrl,
        'cover'      => $cover,
        'size'       => $size,
        'mime'       => zlz_mime($ext),
        'added_at'   => date('c'),
    );
    $pool[] = $sha1;
    return true;
}

function zlz_mime($ext) {
    $map = array(
        'mp4' => 'video/mp4', 'm4v' => 'video/mp4', 'mov' => 'video/quicktime',
        'webm' => 'video/webm', 'mkv' => 'video/x-matroska',
        'ts' => 'video/mp2t', 'flv' => 'video/x-flv', 'm3u8' => 'application/vnd.apple.mpegurl',
    );
    $ext = strtolower($ext);
    return isset($map[$ext]) ? $map[$ext] : 'video/mp4';
}

// ================= 路由 =================

$action = strtolower(zlz_p('action', 'feed'));
$base = zlz_base($CFG);

$ACTIONS = array('feed', 'sync', 'import', 'probe', 'warm', 'selftest', 'status', 'reset',
                 'upload', 'upload_init', 'upload_chunk', 'upload_finish');
if (!in_array($action, $ACTIONS, true)) {
    zlz_out(array('ok' => false, 'error' => 'unknown_action', 'action' => $action, 'available' => $ACTIONS), 404);
}

// ---------- feed：随机推送（App 调用）----------
if ($action === 'feed') {
    $count = (int) zlz_p('count', $CFG['feed_size']);
    $count = max(1, min($count, 20));

    $result = zlz_locked($CFG, 'pool', function () use ($CFG, $count) {
        $poolState = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array(), 'pushed' => array()));
        $meta = zlz_read($CFG, 'videos.json', array());

        $all = array_keys($meta);
        $poolState['pool']   = array_values(array_intersect($poolState['pool'], $all));
        $poolState['pushed'] = array_values(array_intersect($poolState['pushed'], $all));

        $reset = false;
        if (empty($poolState['pool'])) {
            $shuffled = $all;
            shuffle($shuffled);
            $poolState['pool'] = $shuffled;
            $poolState['pushed'] = array();
            $poolState['round'] = (int) $poolState['round'] + 1;
            $poolState['reset_at'] = date('c');
            $reset = true;
        }

        $taken = array();
        $rest = $poolState['pool'];
        for ($i = 0; $i < $count && !empty($rest); $i++) {
            $idx = array_rand($rest);
            $taken[] = $rest[$idx];
            array_splice($rest, $idx, 1);
        }

        $poolState['pool'] = array_values($rest);
        $poolState['pushed'] = array_values(array_unique(array_merge($poolState['pushed'], $taken)));
        $poolState['updated_at'] = date('c');
        zlz_write($CFG, 'pool.json', $poolState);

        return array('taken' => $taken, 'round' => $poolState['round'], 'reset' => $reset, 'left' => count($poolState['pool']));
    });

    $meta = zlz_read($CFG, 'videos.json', array());
    $items = array();
    foreach ($result['taken'] as $sha1) {
        if (!isset($meta[$sha1])) continue;
        $v = $meta[$sha1];
        $file = isset($v['file']) ? $v['file'] : '';
        if ($file !== '' && is_file(zlz_path($file))) {
            $url = $base . '/' . zlz_urlencode_path($file);
        } elseif (!empty($v['source_url'])) {
            // 未落盘（download=0 模式）时直接播源站直链
            $url = $v['source_url'];
        } else {
            continue;
        }
        $items[] = array(
            'sha1'     => $sha1,
            'title'    => $v['title'],
            'url'      => $url,
            'cover'    => $v['cover'],
            'size'     => (int) $v['size'],
            'mime'     => $v['mime'],
            'added_at' => $v['added_at'],
        );
    }

    zlz_out(array(
        'ok'        => true,
        'round'     => $result['round'],
        'reset'     => $result['reset'],
        'remaining' => $result['left'],
        'count'     => count($items),
        'items'     => $items,
    ));
}

// ---------- 令牌校验：以下 action 都需要 ----------
$token = zlz_p('token', isset($_SERVER['HTTP_X_ZLZ_TOKEN']) ? $_SERVER['HTTP_X_ZLZ_TOKEN'] : '');
if ($token === '' || !hash_equals($CFG['token'], $token)) {
    zlz_out(array('ok' => false, 'error' => 'unauthorized', 'tip' => '请在 URL 加 ?token=你的令牌'), 401);
}

// ---------- warm：暖机，先访问站点首页把 Cookie 存下来 ----------
if ($action === 'warm') {
    $url = zlz_p('url', $CFG['source_url']);
    if ($url === '') zlz_out(array('ok' => false, 'error' => 'no_url'), 400);
    $origin = zlz_origin($url);
    list($c1, $b1, $e1) = zlz_http_get($CFG, $origin . '/', 20, '', 'nav');
    $cookie = zlz_cookie_path($CFG);
    $txt = is_file($cookie) ? @file_get_contents($cookie) : '';
    $n = 0;
    foreach (preg_split('/\r\n|\r|\n/', $txt) as $line) {
        if ($line !== '' && strpos($line, '#') !== 0) $n++;
    }
    zlz_out(array(
        'ok' => true,
        'origin' => $origin,
        'home_code' => $c1,
        'home_bytes' => strlen($b1),
        'cookies_saved' => $n,
        'blocked' => zlz_is_challenge($c1, $b1),
        'tip' => $n > 0 ? 'Cookie 已存好，现在去跑 sync' : '没拿到 Cookie，检查源站是否可达',
    ));
}

// ---------- selftest：检查本主机是否满足运行条件 ----------
if ($action === 'selftest') {
    $uploadMax = ini_get('upload_max_filesize');
    $postMax = ini_get('post_max_size');
    $toBytes = function ($v) {
        $v = strtolower(trim((string) $v));
        if ($v === '' || $v === '0') return 0;
        $unit = substr($v, -1);
        $n = (float) $v;
        if ($unit === 'g') return (int) ($n * 1073741824);
        if ($unit === 'm') return (int) ($n * 1048576);
        if ($unit === 'k') return (int) ($n * 1024);
        return (int) $n;
    };

    $checks = array();
    $checks['php_version'] = PHP_VERSION;
    $checks['curl_available'] = function_exists('curl_init');
    $checks['allow_url_fopen'] = (bool) ini_get('allow_url_fopen');
    $checks['exec_disabled'] = !function_exists('exec');
    $checks['upload_max_filesize'] = $uploadMax;
    $checks['post_max_size'] = $postMax;
    $checks['upload_limit_bytes'] = $toBytes($uploadMax);
    $checks['max_execution_time'] = ini_get('max_execution_time');
    $checks['memory_limit'] = ini_get('memory_limit');
    $checks['data_writable'] = is_writable(zlz_path($CFG['data_dir']));
    $checks['video_dir_writable'] = is_writable(zlz_path($CFG['video_dir']));
    $checks['chunk_dir_writable'] = is_writable(zlz_chunk_dir($CFG));
    $checks['upload_max_bytes_config'] = round($CFG['upload_max_bytes'] / 1048576) . ' MB';
    $checks['post_max_size'] = ini_get('post_max_size');
    $checks['post_limit_bytes'] = $toBytes(ini_get('post_max_size'));
    $checks['chunk_hint'] = '单分片大小建议 < ' . max(1, (int) floor($toBytes(ini_get('post_max_size')) / 1048576) - 2) . ' MB';
    $free = @disk_free_space(ZLZ_ROOT);
    $checks['disk_free_mb'] = $free === false ? null : round($free / 1048576, 1);
    $checks['max_bytes_config'] = round($CFG['max_bytes'] / 1048576, 1) . ' MB';

    // 自身可达性：带浏览器头访问自己的 feed，看是否被本机的验证系统拦
    $self = zlz_base($CFG) . '/api.php?action=feed&count=1';
    list($sc, $sb) = zlz_http_get($CFG, $self, 20, '', 'nav');
    $checks['self_request'] = array(
        'url' => $self,
        'http_code' => $sc,
        'blocked' => zlz_is_challenge($sc, $sb),
        'note' => zlz_is_challenge($sc, $sb)
            ? '⚠️ 服务端自己访问自己都被拦 —— 说明是主机层面的浏览器验证（如 iFastNet 免费版），App 必然连不上'
            : '本机自访问正常',
    );

    zlz_out(array('ok' => true, 'selftest' => $checks));
}

// ---------- probe：探测源站格式 ----------
if ($action === 'probe') {
    $url = zlz_p('url', $CFG['source_url']);
    if ($url === '') {
        zlz_out(array('ok' => false, 'error' => 'no_url', 'tip' => '用 ?url=xxx 指定，或先在配置里填 source_url'));
    }

    // A. 先用朴素请求试一次（作为对照）
    list($rawCode, $rawBody) = zlz_http_get($CFG, $url, 20, '', 'nav');
    // B. 再用浏览器伪装 + 暖机试一次
    list($code, $body, $err) = zlz_http_get_smart($CFG, $url);

    $out = array(
        'ok' => true,
        'url' => $url,
        'browser_mode' => $CFG['browser_mode'],
        'plain_request'   => array('http_code' => $rawCode, 'blocked' => zlz_is_challenge($rawCode, $rawBody)),
        'browser_request' => array('http_code' => $code, 'blocked' => zlz_is_challenge($code, $body)),
        'curl_error' => $err,
        'bytes' => strlen($body),
        'content_type_guess' => (strpos(ltrim($body), '{') === 0 || strpos(ltrim($body), '[') === 0) ? 'json' : 'html/text',
        'raw_head' => substr($body, 0, 2000),
    );

    if (zlz_is_challenge($code, $body)) {
        $out['hint'] = '仍被拦截。若是 JS/滑块/验证码类人机验证，脚本无法也不应绕过 —— '
            . '请在对方网关把本服务器 IP 加入白名单，或改用对方开放的接口。';
    }

    if ($code > 0 && $body !== '' && !zlz_is_challenge($code, $body)) {
        $r = zlz_source_list($CFG);
        if (is_array($r) && count($r) == 2 && is_string($r[1])) {
            $out['detected_mode'] = $r[1];
            $out['parsed_count'] = count($r[0]);
            $out['sample'] = array_slice($r[0], 0, 3);
        } else {
            $out['detected_mode'] = 'unknown';
            $out['parsed_count'] = 0;
        }
    }
    zlz_out($out);
}

// ---------- sync：远程抓取入库 ----------
if ($action === 'sync') {
    $limit = max(1, min((int) zlz_p('limit', $CFG['sync_limit']), 100));
    $download = zlz_p('download', '1') !== '0';

    zlz_mkdir(zlz_path($CFG['video_dir']));
    zlz_mkdir(zlz_path($CFG['data_dir']));

    try {
        $list = zlz_source_list($CFG);
    } catch (Exception $e) {
        zlz_out(array('ok' => false, 'error' => 'list_failed', 'message' => $e->getMessage(),
                      'tip' => '先访问 action=probe 看看源站到底返回什么'), 502);
    }
    if (is_array($list) && count($list) == 2 && is_string($list[1])) {
        $detected = $list[1];
        $list = $list[0];
    } else {
        $detected = $CFG['source_mode'];
    }

    if (empty($list)) {
        zlz_out(array(
            'ok' => true, 'fetched' => 0, 'added' => 0, 'detected_mode' => $detected,
            'hint' => '没解析出视频。访问 ?action=probe&token=xxx 看源站原始返回，然后在文件里改 zlz_parse_custom()',
        ));
    }

    $report = array('ok' => true, 'mode' => $detected, 'fetched' => count($list),
                    'added' => 0, 'skipped' => 0, 'failed' => 0, 'errors' => array());

    zlz_locked($CFG, 'videos', function () use ($CFG, $list, $limit, $download, &$report) {
        $meta = zlz_read($CFG, 'videos.json', array());
        $poolState = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array(), 'pushed' => array()));
        $pool = $poolState['pool'];

        $handled = 0;
        foreach ($list as $item) {
            if ($handled >= $limit) break;
            $url = isset($item['url']) ? $item['url'] : '';
            if ($url === '') continue;

            $path = parse_url($url, PHP_URL_PATH);
            $ext = strtolower(pathinfo($path, PATHINFO_EXTENSION));
            if ($ext === '' || !in_array($ext, zlz_ext_list($CFG), true)) $ext = 'mp4';

            $sha1 = '';
            $size = 0;
            $relFile = '';

            if ($download) {
                // 请求间加礼貌间隔，避免被判为爬虫
                if ($CFG['crawl_delay_ms'] > 0) usleep($CFG['crawl_delay_ms'] * 1000);
                $tmpRel = $CFG['video_dir'] . '/dl_' . md5(uniqid('', true) . $url) . '.' . $ext;
                $tmpAbs = zlz_path($tmpRel);
                list($ok, $err) = zlz_download($CFG, $url, $tmpAbs, $CFG['max_bytes']);
                if (!$ok || !is_file($tmpAbs)) {
                    @unlink($tmpAbs);
                    $report['failed']++;
                    if (count($report['errors']) < 10) $report['errors'][] = array('url' => $url, 'error' => $err);
                    continue;
                }
                $size = filesize($tmpAbs);
                if ($size == 0 || $size > $CFG['max_bytes']) {
                    @unlink($tmpAbs);
                    $report['failed']++;
                    if (count($report['errors']) < 10) $report['errors'][] = array('url' => $url, 'error' => 'empty or too large');
                    continue;
                }
                $sha1 = sha1_file($tmpAbs);
                $relFile = $CFG['video_dir'] . '/' . $sha1 . '.' . $ext;
                @rename($tmpAbs, zlz_path($relFile));
                @unlink($tmpAbs);
            } else {
                $sha1 = sha1($url);
                $relFile = '';
            }

            $handled++;

            if (!$download) {
                // 不下载时只登记直链，file 留空表示用 source_url 播放
                if (isset($meta[$sha1])) { $report['skipped']++; continue; }
                $meta[$sha1] = array(
                    'sha1' => $sha1, 'title' => isset($item['title']) ? $item['title'] : basename($path),
                    'file' => '', 'source_url' => $url, 'cover' => isset($item['cover']) ? $item['cover'] : '',
                    'size' => 0, 'mime' => zlz_mime($ext), 'added_at' => date('c'),
                );
                $pool[] = $sha1;
                $report['added']++;
                continue;
            }

            if (isset($meta[$sha1])) {
                @unlink(zlz_path($relFile));
                $report['skipped']++;
                continue;
            }
            if (zlz_add_video($CFG, $meta, $pool, $sha1, $relFile,
                isset($item['title']) ? $item['title'] : basename($path),
                $url, isset($item['cover']) ? $item['cover'] : '', $size, $ext)) {
                $report['added']++;
            } else {
                @unlink(zlz_path($relFile));
                $report['skipped']++;
            }
        }

        zlz_write($CFG, 'videos.json', $meta);
        $poolState['pool'] = array_values(array_unique($pool));
        zlz_write($CFG, 'pool.json', $poolState);
    });

    $meta = zlz_read($CFG, 'videos.json', array());
    $poolState = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array(), 'pushed' => array()));
    $report['total_videos'] = count($meta);
    $report['pool_left'] = count($poolState['pool']);
    $report['pushed'] = count($poolState['pushed']);
    zlz_out($report);
}

// ---------- import：扫描本地目录入库（FTP 传完文件用这个）----------
if ($action === 'import') {
    $rel = zlz_safe_rel(zlz_p('path', $CFG['import_dir']));
    if ($rel === '') zlz_out(array('ok' => false, 'error' => 'bad_path'), 400);
    $absDir = zlz_path($rel);
    if (!is_dir($absDir)) {
        zlz_out(array('ok' => false, 'error' => 'dir_not_found', 'path' => $rel,
                      'tip' => '先在 ' . $rel . ' 里放几个 mp4（用 FTP 或主机面板上传）'), 404);
    }
    $limit = max(1, min((int) zlz_p('limit', $CFG['sync_limit']), 500));

    $report = array('ok' => true, 'dir' => $rel, 'scanned' => 0, 'added' => 0, 'skipped' => 0);

    zlz_locked($CFG, 'videos', function () use ($CFG, $absDir, $rel, $limit, &$report) {
        $meta = zlz_read($CFG, 'videos.json', array());
        $poolState = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array(), 'pushed' => array()));
        $pool = $poolState['pool'];

        $files = zlz_scan_dir($absDir, true);
        foreach ($files as $abs) {
            if ($report['scanned'] >= $limit) break;
            $name = basename($abs);
            if (!zlz_is_video($name, $CFG)) continue;
            $size = @filesize($abs);
            if ($size === false || $size == 0 || $size > $CFG['max_bytes']) continue;
            $report['scanned']++;

            $sha1 = sha1_file($abs);
            if ($sha1 === false) continue;
            $sub = substr(str_replace('\\', '/', $abs), strlen(str_replace('\\', '/', ZLZ_ROOT)) + 1);
            $ext = strtolower(pathinfo($name, PATHINFO_EXTENSION));

            if (zlz_add_video($CFG, $meta, $pool, $sha1, $sub, $name, '', '', $size, $ext)) {
                $report['added']++;
            } else {
                $report['skipped']++;
            }
        }

        zlz_write($CFG, 'videos.json', $meta);
        $poolState['pool'] = array_values(array_unique($pool));
        zlz_write($CFG, 'pool.json', $poolState);
    });

    $meta = zlz_read($CFG, 'videos.json', array());
    $report['total_videos'] = count($meta);
    zlz_out($report);
}

function zlz_scan_dir($dir, $recursive) {
    $out = array();
    $dh = @opendir($dir);
    if (!$dh) return $out;
    while (($f = readdir($dh)) !== false) {
        if ($f === '.' || $f === '..') continue;
        $abs = $dir . '/' . $f;
        if (is_dir($abs)) {
            if ($recursive) $out = array_merge($out, zlz_scan_dir($abs, true));
            continue;
        }
        $out[] = $abs;
    }
    closedir($dh);
    sort($out);
    return $out;
}

// ---------- status ----------
if ($action === 'status') {
    $meta = zlz_read($CFG, 'videos.json', array());
    $poolState = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array(), 'pushed' => array()));
    $used = 0;
    foreach ($meta as $v) {
        if (!empty($v['file']) && is_file(zlz_path($v['file']))) $used += @filesize(zlz_path($v['file']));
    }
    zlz_out(array(
        'ok' => true,
        'base_url' => $base,
        'total_videos' => count($meta),
        'round' => $poolState['round'],
        'pool_left' => count($poolState['pool']),
        'pushed' => count($poolState['pushed']),
        'disk_used_mb' => round($used / 1048576, 1),
        'source_url' => $CFG['source_url'],
        'source_mode' => $CFG['source_mode'],
        'updated_at' => isset($poolState['updated_at']) ? $poolState['updated_at'] : null,
        'reset_at' => isset($poolState['reset_at']) ? $poolState['reset_at'] : null,
    ));
}

// ---------- reset：重置推送池 ----------
if ($action === 'reset') {
    zlz_locked($CFG, 'pool', function () use ($CFG) {
        $meta = zlz_read($CFG, 'videos.json', array());
        $poolState = zlz_read($CFG, 'pool.json', array('round' => 0));
        $all = array_keys($meta);
        shuffle($all);
        zlz_write($CFG, 'pool.json', array(
            'round' => (int) $poolState['round'] + 1,
            'pool' => $all, 'pushed' => array(),
            'reset_at' => date('c'), 'updated_at' => date('c'),
        ));
    });
    zlz_out(array('ok' => true, 'message' => '推送池已重置'));
}

// ============ 分片上传：init -> chunk(多次) -> finish ============
// 为什么分片：虚拟主机 upload_max_filesize / post_max_size 通常只有 2~20M，
// 单次 POST 传 250MB 必失败。分片后每片只有几 MB，稳。
// App 端先算好 sha1 与 size，服务端只做校验 —— 天然去重 + 支持秒传。

function zlz_chunk_dir($CFG) {
    $dir = zlz_path($CFG['chunk_dir']);
    zlz_mkdir($dir);
    return $dir;
}

function zlz_upload_id($sha1Hex, $size) {
    // 由文件内容指纹 + 大小派生，同一个文件多次上传复用同一 upload_id（断点续传）
    return substr($sha1Hex, 0, 24) . '_' . $size;
}

function zlz_chunk_file($CFG, $id) {
    if (!preg_match('/^[a-f0-9]{24}_\d+$/', $id)) return '';
    return zlz_chunk_dir($CFG) . '/' . $id . '.part';
}

function zlz_chunk_meta($CFG, $id) {
    if (!preg_match('/^[a-f0-9]{24}_\d+$/', $id)) return '';
    return zlz_chunk_dir($CFG) . '/' . $id . '.json';
}

function zlz_valid_sha1($s) {
    return is_string($s) && preg_match('/^[a-f0-9]{40}$/i', $s) ? strtolower($s) : '';
}

// ---------- upload_init：登记一次上传，返回已收分片 ----------
if ($action === 'upload_init') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') zlz_out(array('ok' => false, 'error' => 'POST required'), 405);

    $sha1 = zlz_valid_sha1(zlz_p('sha1', ''));
    $size = (int) zlz_p('size', 0);
    $name = trim(zlz_p('name', ''));
    $chunkSize = max(1, (int) zlz_p('chunk_size', 2 * 1024 * 1024));
    $title = trim(zlz_p('title', ''));

    if ($sha1 === '') zlz_out(array('ok' => false, 'error' => 'bad_sha1', 'tip' => 'sha1 需为 40 位十六进制'), 400);
    if ($size <= 0) zlz_out(array('ok' => false, 'error' => 'bad_size'), 400);
    if ($size > $CFG['upload_max_bytes']) {
        zlz_out(array('ok' => false, 'error' => 'too_large', 'size' => $size,
                      'limit' => $CFG['upload_max_bytes'],
                      'limit_mb' => round($CFG['upload_max_bytes'] / 1048576)), 413);
    }

    $ext = strtolower(pathinfo($name, PATHINFO_EXTENSION));
    if ($ext === '' || !in_array($ext, zlz_ext_list($CFG), true)) $ext = 'mp4';

    // 秒传：库里已有同 sha1 且文件还在
    $meta = zlz_read($CFG, 'videos.json', array());
    if (isset($meta[$sha1])) {
        $f = isset($meta[$sha1]['file']) ? $meta[$sha1]['file'] : '';
        if ($f !== '' && is_file(zlz_path($f))) {
            zlz_out(array(
                'ok' => true, 'instant' => true, 'sha1' => $sha1,
                'url' => $base . '/' . zlz_urlencode_path($f),
                'message' => '文件已存在，秒传成功',
            ));
        }
    }

    $id = zlz_upload_id($sha1, $size);
    $part = zlz_chunk_file($CFG, $id);
    $metaFile = zlz_chunk_meta($CFG, $id);
    if ($part === '') zlz_out(array('ok' => false, 'error' => 'bad_upload_id'), 400);

    $total = (int) ceil($size / $chunkSize);

    // 已收到的分片（支持断点续传）
    $received = array();
    if (is_file($metaFile)) {
        $old = zlz_read_raw($metaFile);
        if (is_array($old) && isset($old['received']) && is_array($old['received'])
            && $old['sha1'] === $sha1 && (int) $old['size'] === $size) {
            $received = array_map('intval', $old['received']);
        }
    }

    // 预分配临时文件（稀疏文件，不立即占满磁盘）
    if (!is_file($part)) {
        $fp = @fopen($part, 'wb');
        if (!$fp) zlz_out(array('ok' => false, 'error' => 'cannot_create_part'), 500);
        fclose($fp);
    }

    zlz_write_raw($metaFile, array(
        'sha1' => $sha1, 'size' => $size, 'name' => $name, 'title' => $title,
        'ext' => $ext, 'chunk_size' => $chunkSize, 'total' => $total,
        'received' => array_values($received),
        'created_at' => time(), 'updated_at' => time(),
    ));

    zlz_out(array(
        'ok' => true, 'instant' => false,
        'upload_id' => $id, 'sha1' => $sha1,
        'chunk_size' => $chunkSize, 'total' => $total,
        'received' => array_values($received),
        'received_count' => count($received),
    ));
}

// ---------- upload_chunk：上传一个分片（raw body）----------
if ($action === 'upload_chunk') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') zlz_out(array('ok' => false, 'error' => 'POST required'), 405);

    $id = trim(zlz_p('upload_id', ''));
    $index = (int) zlz_p('index', -1);

    $part = zlz_chunk_file($CFG, $id);
    $metaFile = zlz_chunk_meta($CFG, $id);
    if ($part === '' || !is_file($metaFile)) {
        zlz_out(array('ok' => false, 'error' => 'no_session', 'tip' => '先调 upload_init'), 400);
    }
    if ($index < 0) zlz_out(array('ok' => false, 'error' => 'bad_index'), 400);

    $m = zlz_read_raw($metaFile);
    if (!is_array($m)) zlz_out(array('ok' => false, 'error' => 'bad_meta'), 500);
    if ($index >= (int) $m['total']) zlz_out(array('ok' => false, 'error' => 'index_out_of_range'), 400);

    // 读原始请求体（分片二进制）
    $raw = @file_get_contents('php://input');
    if ($raw === false || $raw === '') {
        zlz_out(array('ok' => false, 'error' => 'empty_chunk'), 400);
    }

    $offset = $index * (int) $m['chunk_size'];
    $fp = @fopen($part, 'cb');
    if (!$fp) zlz_out(array('ok' => false, 'error' => 'cannot_open_part'), 500);
    flock($fp, LOCK_EX);
    fseek($fp, $offset);
    $written = fwrite($fp, $raw);
    fflush($fp);
    flock($fp, LOCK_UN);
    fclose($fp);

    if ($written === false || $written !== strlen($raw)) {
        zlz_out(array('ok' => false, 'error' => 'write_failed', 'written' => $written), 500);
    }

    // 记录已收分片
    $received = isset($m['received']) && is_array($m['received']) ? $m['received'] : array();
    $received = array_map('intval', $received);
    if (!in_array($index, $received, true)) $received[] = $index;
    sort($received);
    $m['received'] = $received;
    $m['updated_at'] = time();
    zlz_write_raw($metaFile, $m);

    zlz_out(array(
        'ok' => true, 'index' => $index, 'written' => $written,
        'received_count' => count($received), 'total' => (int) $m['total'],
    ));
}

// ---------- upload_finish：校验并落盘入库 ----------
if ($action === 'upload_finish') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') zlz_out(array('ok' => false, 'error' => 'POST required'), 405);

    $id = trim(zlz_p('upload_id', ''));
    $sha1 = zlz_valid_sha1(zlz_p('sha1', ''));

    $part = zlz_chunk_file($CFG, $id);
    $metaFile = zlz_chunk_meta($CFG, $id);
    if ($part === '' || !is_file($metaFile) || !is_file($part)) {
        zlz_out(array('ok' => false, 'error' => 'no_session', 'tip' => '先调 upload_init'), 400);
    }

    $m = zlz_read_raw($metaFile);
    if (!is_array($m)) zlz_out(array('ok' => false, 'error' => 'bad_meta'), 500);
    if ($sha1 === '') $sha1 = $m['sha1'];

    $expect = (int) $m['size'];
    $actual = @filesize($part);
    if ($actual === false || $actual !== $expect) {
        zlz_out(array('ok' => false, 'error' => 'size_mismatch',
                      'expect' => $expect, 'actual' => $actual,
                      'tip' => '分片未传完或顺序错乱'), 400);
    }

    // 校验内容指纹（App 端算的 sha1 与服务端算的必须一致）
    $real = @sha1_file($part);
    if ($real === false) zlz_out(array('ok' => false, 'error' => 'hash_failed'), 500);
    if (!hash_equals($sha1, $real)) {
        zlz_out(array('ok' => false, 'error' => 'sha1_mismatch',
                      'client' => $sha1, 'server' => $real,
                      'tip' => '文件内容与声明的 sha1 不一致，已拒绝写入'), 400);
    }

    if ($expect > $CFG['upload_max_bytes']) {
        zlz_out(array('ok' => false, 'error' => 'too_large'), 413);
    }

    $ext = isset($m['ext']) && $m['ext'] !== '' ? $m['ext'] : 'mp4';
    $relFile = $CFG['video_dir'] . '/' . $sha1 . '.' . $ext;
    zlz_mkdir(zlz_path($CFG['video_dir']));
    $dest = zlz_path($relFile);

    if (!is_file($dest)) {
        if (!@rename($part, $dest) && !@copy($part, $dest)) {
            zlz_out(array('ok' => false, 'error' => 'move_failed'), 500);
        }
    } else {
        @unlink($part);
    }
    @unlink($metaFile);

    $title = isset($m['title']) ? $m['title'] : '';
    $name = isset($m['name']) ? $m['name'] : '';

    zlz_locked($CFG, 'videos', function () use ($CFG, $sha1, $relFile, $title, $name, $expect, $ext) {
        $meta = zlz_read($CFG, 'videos.json', array());
        if (!isset($meta[$sha1])) {
            $poolState = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array()));
            $pool = $poolState['pool'];
            zlz_add_video($CFG, $meta, $pool, $sha1, $relFile,
                $title !== '' ? $title : ($name !== '' ? $name : '上传 ' . substr($sha1, 0, 8)),
                '', '', $expect, $ext);
            $poolState['pool'] = array_values(array_unique($pool));
            zlz_write($CFG, 'pool.json', $poolState);
        }
        zlz_write($CFG, 'videos.json', $meta);
    });

    // 顺手清理过期分片
    zlz_gc_chunks($CFG);

    zlz_out(array(
        'ok' => true, 'sha1' => $sha1, 'size' => $expect,
        'url' => $base . '/' . zlz_urlencode_path($relFile),
    ));
}

// ---------- upload：整文件单次上传（小文件备选）----------
if ($action === 'upload') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') zlz_out(array('ok' => false, 'error' => 'POST required'), 405);
    if (empty($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK) {
        zlz_out(array('ok' => false, 'error' => 'no_file', 'php_error' => isset($_FILES['file']) ? $_FILES['file']['error'] : -1,
                      'tip' => '虚拟主机需调大 upload_max_filesize 和 post_max_size'), 400);
    }
    $src = $_FILES['file']['tmp_name'];
    $size = (int) $_FILES['file']['size'];
    if ($size <= 0 || $size > $CFG['max_bytes']) zlz_out(array('ok' => false, 'error' => 'bad_size', 'size' => $size), 400);

    $orig = isset($_FILES['file']['name']) ? $_FILES['file']['name'] : '';
    $ext = strtolower(pathinfo($orig, PATHINFO_EXTENSION));
    if ($ext === '' || !in_array($ext, zlz_ext_list($CFG), true)) $ext = 'mp4';

    $sha1 = sha1_file($src);
    $relFile = $CFG['video_dir'] . '/' . $sha1 . '.' . $ext;
    zlz_mkdir(zlz_path($CFG['video_dir']));
    $dest = zlz_path($relFile);

    if (!is_file($dest)) {
        if (!@move_uploaded_file($src, $dest) && !@rename($src, $dest)) @copy($src, $dest);
    }

    zlz_locked($CFG, 'videos', function () use ($CFG, $sha1, $relFile, $orig, $size, $ext) {
        $meta = zlz_read($CFG, 'videos.json', array());
        if (!isset($meta[$sha1])) {
            $poolState = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array()));
            $pool = $poolState['pool'];
            $title = trim(zlz_p('title', ''));
            zlz_add_video($CFG, $meta, $pool, $sha1, $relFile,
                $title !== '' ? $title : ($orig !== '' ? $orig : '上传 ' . substr($sha1, 0, 8)),
                '', '', $size, $ext);
            $poolState['pool'] = array_values(array_unique($pool));
            zlz_write($CFG, 'pool.json', $poolState);
        }
        zlz_write($CFG, 'videos.json', $meta);
    });

    zlz_out(array('ok' => true, 'sha1' => $sha1, 'url' => $base . '/' . zlz_urlencode_path($relFile)));
}

// ---------- 兜底（正常不会走到）----------
zlz_out(array('ok' => false, 'error' => 'not_handled', 'action' => $action), 404);
