<?php
/**
 * zilizili 公共配置与工具函数
 * 供 api.php（接口）和 play.php（分享播放页）共用。
 * 只做定义，不产生任何输出。
 */

/**
 * zilizili 服务端 v2 —— 含账号体系
 * ============================================================
 * 单文件部署：上传到虚拟主机，访问 https://你的域名/api.php
 *
 * 【游客】只能刷视频
 *   feed / comments / user_space / status / probe / play
 *
 * 【登录用户】额外可用
 *   upload / like / comment / my_works / delete_work / update_work
 *   me / update_profile / upload_avatar / logout
 *
 * 【管理员】token
 *   sync / import / reset / selftest / warm / upload(整文件,需登录)
 *
 * 目录结构
 *   data/videos.json        全局视频库（sha1 => 视频信息）
 *   data/pool.json          推送池
 *   data/sessions.json      登录会话
 *   user/index.json         用户名 => 用户ID
 *   user/{id}/data.json     用户资料
 *   user/{id}/avatar.jpg    头像（<=8MB）
 *   user/{id}/{sha1}.json   该视频的点赞/评论
 *   user/{id}/covers/*      封面（<=10MB）
 *   videos/*                视频文件
 * ============================================================
 */


// ---------------- 配置区 ----------------

$CFG = array(
    // 管理令牌（sync/import/reset 等）
    'token'       => 'change-me-please',

    'base'        => '',          // 留空自动探测
    'source_url'  => '',          // 远程源站列表地址
    'source_mode' => 'auto',

    'import_dir'  => 'source',
    'video_dir'   => 'videos',
    'data_dir'    => 'data',
    'user_dir'    => 'user',

    'max_bytes'        => 200 * 1024 * 1024,  // 抓取落盘上限
    'sync_limit'       => 20,
    'feed_size'        => 5,

    'upload_max_bytes' => 250 * 1024 * 1024,  // 整文件上传上限
    'avatar_max_bytes' => 8  * 1024 * 1024,   // 头像 8MB
    'cover_max_bytes'  => 10 * 1024 * 1024,   // 封面 10MB
    'chunk_dir'        => 'data/uploads',
    'chunk_ttl'        => 24 * 3600,
    'session_ttl'      => 86400 * 30,         // 登录有效期 30 天

    'exts'        => 'mp4,webm,mov,m4v,mkv,ts,flv,m3u8',
    'img_exts'    => 'jpg,jpeg,png,gif,webp,bmp',

    'browser_mode'   => true,
    'apk_url'       => 'https://github.com/gjgffugf/update/releases/latest',
    'browser_ua'     => 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
    'browser_lang'   => 'zh-CN,zh;q=0.9,en;q=0.8',
    'crawl_delay_ms' => 800,
);

$CFG = array_merge(array(
    'token' => 'change-me-please', 'base' => '', 'source_url' => '', 'source_mode' => 'auto',
    'import_dir' => 'source', 'video_dir' => 'videos', 'data_dir' => 'data', 'user_dir' => 'user',
    'max_bytes' => 200 * 1024 * 1024, 'sync_limit' => 20, 'feed_size' => 5,
    'upload_max_bytes' => 250 * 1024 * 1024, 'avatar_max_bytes' => 8 * 1024 * 1024,
    'cover_max_bytes' => 10 * 1024 * 1024, 'chunk_dir' => 'data/uploads', 'chunk_ttl' => 86400,
    'session_ttl' => 2592000,
    'exts' => 'mp4,webm,mov,m4v,mkv,ts,flv,m3u8', 'img_exts' => 'jpg,jpeg,png,gif,webp,bmp',
    'browser_mode' => true,
    'browser_ua' => 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
    'browser_lang' => 'zh-CN,zh;q=0.9,en;q=0.8', 'crawl_delay_ms' => 800,
), $CFG);

define('ZLZ_ROOT', dirname(__FILE__));
define('ZLZ_UA', 'zilizili/2.0');

// ---------------- 工具 ----------------

function zlz_out($data, $status = 200) {
    http_response_code($status);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
    exit;
}
function zlz_p($k, $d = '') {
    if (isset($_GET[$k]) && $_GET[$k] !== '') return $_GET[$k];
    if (isset($_POST[$k]) && $_POST[$k] !== '') return $_POST[$k];
    return $d;
}
function zlz_base($CFG) {
    if (!empty($CFG['base'])) return rtrim($CFG['base'], '/');
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (isset($_SERVER['SERVER_PORT']) && $_SERVER['SERVER_PORT'] == 443)
        || (isset($_SERVER['HTTP_X_FORWARDED_PROTO']) && $_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https');
    $host = isset($_SERVER['HTTP_HOST']) ? $_SERVER['HTTP_HOST'] : 'localhost';
    $dir = rtrim(str_replace('\\', '/', dirname(isset($_SERVER['SCRIPT_NAME']) ? $_SERVER['SCRIPT_NAME'] : '/')), '/');
    return ($https ? 'https' : 'http') . '://' . $host . $dir;
}
function zlz_path($rel) { return ZLZ_ROOT . '/' . ltrim($rel, '/'); }
function zlz_mkdir($d) { if (!is_dir($d)) @mkdir($d, 0755, true); return is_dir($d); }
function zlz_urlenc($rel) {
    return implode('/', array_map('rawurlencode', explode('/', $rel)));
}
function zlz_safe_rel($p) {
    $p = str_replace('\\', '/', $p);
    if (strpos($p, '..') !== false) return '';
    return ltrim(trim($p, '/'), '/');
}
function zlz_ext_list($CFG) { return array_filter(array_map('trim', explode(',', $CFG['exts']))); }
function zlz_img_list($CFG) { return array_filter(array_map('trim', explode(',', $CFG['img_exts']))); }
function zlz_mime($ext) {
    $m = array('mp4'=>'video/mp4','m4v'=>'video/mp4','mov'=>'video/quicktime','webm'=>'video/webm',
        'mkv'=>'video/x-matroska','ts'=>'video/mp2t','flv'=>'video/x-flv','m3u8'=>'application/vnd.apple.mpegurl');
    $ext = strtolower($ext);
    return isset($m[$ext]) ? $m[$ext] : 'video/mp4';
}
function zlz_img_mime($ext) {
    $m = array('jpg'=>'image/jpeg','jpeg'=>'image/jpeg','png'=>'image/png','gif'=>'image/gif',
        'webp'=>'image/webp','bmp'=>'image/bmp');
    $ext = strtolower($ext);
    return isset($m[$ext]) ? $m[$ext] : 'image/jpeg';
}

function zlz_read($CFG, $name, $default = array()) {
    $p = zlz_path($CFG['data_dir'] . '/' . $name);
    if (!is_file($p)) return $default;
    $raw = @file_get_contents($p);
    if ($raw === false || $raw === '') return $default;
    $d = json_decode($raw, true);
    return is_array($d) ? $d : $default;
}
function zlz_write($CFG, $name, $data) {
    zlz_mkdir(zlz_path($CFG['data_dir']));
    $p = zlz_path($CFG['data_dir'] . '/' . $name);
    $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if (@file_put_contents($p . '.tmp', $json, LOCK_EX) === false) return false;
    @rename($p . '.tmp', $p);
    return true;
}
function zlz_read_raw($path, $default = null) {
    if (!is_file($path)) return $default;
    $raw = @file_get_contents($path);
    if ($raw === false || $raw === '') return $default;
    $d = json_decode($raw, true);
    return is_array($d) ? $d : $default;
}
function zlz_write_raw($path, $data) {
    $d = dirname($path);
    if (!is_dir($d)) @mkdir($d, 0755, true);
    return @file_put_contents($path, json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES), LOCK_EX) !== false;
}
function zlz_locked($CFG, $name, $fn) {
    zlz_mkdir(zlz_path($CFG['data_dir']));
    $lock = zlz_path($CFG['data_dir'] . '/' . $name . '.lock');
    $fp = @fopen($lock, 'c');
    if (!$fp) return call_user_func($fn);
    flock($fp, LOCK_EX);
    try { $r = call_user_func($fn); }
    catch (Exception $e) { flock($fp, LOCK_UN); fclose($fp); throw $e; }
    flock($fp, LOCK_UN); fclose($fp);
    return $r;
}

// ---------------- 账号体系 ----------------

function zlz_user_root($CFG) { $d = zlz_path($CFG['user_dir']); zlz_mkdir($d); return $d; }
function zlz_user_dir($CFG, $id) { $d = zlz_user_root($CFG) . '/' . $id; zlz_mkdir($d); return $d; }
function zlz_valid_id($s) { return preg_match('/^\d{6}$/', (string)$s) ? (string)$s : ''; }

/** 生成未占用的 6 位 ID */
function zlz_new_id($CFG) {
    $root = zlz_user_root($CFG);
    for ($i = 0; $i < 200; $i++) {
        $id = str_pad((string) mt_rand(100000, 999999), 6, '0', STR_PAD_LEFT);
        if (!is_dir($root . '/' . $id)) return $id;
    }
    return '';
}
function zlz_user_index($CFG) {
    return zlz_read_raw(zlz_user_root($CFG) . '/index.json', array());
}
function zlz_save_index($CFG, $idx) {
    zlz_mkdir(zlz_user_root($CFG));
    return zlz_write_raw(zlz_user_root($CFG) . '/index.json', $idx);
}
function zlz_user_get($CFG, $id) {
    $id = zlz_valid_id($id);
    if ($id === '') return null;
    return zlz_read_raw(zlz_user_dir($CFG, $id) . '/data.json', null);
}
function zlz_user_save($CFG, $id, $u) { return zlz_write_raw(zlz_user_dir($CFG, $id) . '/data.json', $u); }

/** 用户名 => id */
function zlz_find_by_name($CFG, $name) {
    $idx = zlz_user_index($CFG);
    $key = strtolower(trim($name));
    return isset($idx[$key]) ? $idx[$key] : '';
}

/** 对外展示的用户信息（绝不带密码） */
function zlz_public_user($CFG, $u) {
    $av = isset($u['avatar']) ? $u['avatar'] : '';
    return array(
        'id'       => isset($u['id']) ? $u['id'] : '',
        'username' => isset($u['username']) ? $u['username'] : '',
        'bio'      => isset($u['bio']) ? $u['bio'] : '',
        'avatar'   => $av !== '' ? zlz_base($CFG) . '/' . zlz_urlenc($av) : '',
        'created_at' => isset($u['created_at']) ? $u['created_at'] : '',
    );
}

/** 会话：token => uid */
function zlz_session_get($CFG, $token) {
    if ($token === '') return '';
    $s = zlz_read($CFG, 'sessions.json', array());
    if (!isset($s[$token])) return '';
    if (isset($s[$token]['expires']) && $s[$token]['expires'] < time()) return '';
    return isset($s[$token]['uid']) ? $s[$token]['uid'] : '';
}
function zlz_session_put($CFG, $uid) {
    $token = bin2hex(function_exists('random_bytes') ? random_bytes(20) : substr(md5(uniqid('', true)), 0, 40));
    zlz_locked($CFG, 'sessions', function () use ($CFG, $token, $uid) {
        $s = zlz_read($CFG, 'sessions.json', array());
        $now = time();
        foreach ($s as $k => $v) if (isset($v['expires']) && $v['expires'] < $now) unset($s[$k]);
        $s[$token] = array('uid' => $uid, 'expires' => $now + (int) $CFG['session_ttl']);
        zlz_write($CFG, 'sessions.json', $s);
    });
    return $token;
}
function zlz_session_del($CFG, $token) {
    zlz_locked($CFG, 'sessions', function () use ($CFG, $token) {
        $s = zlz_read($CFG, 'sessions.json', array());
        unset($s[$token]);
        zlz_write($CFG, 'sessions.json', $s);
    });
}
function zlz_session_token() {
    $t = zlz_p('session', '');
    if ($t === '' && isset($_SERVER['HTTP_X_ZLZ_SESSION'])) $t = $_SERVER['HTTP_X_ZLZ_SESSION'];
    return is_string($t) ? trim($t) : '';
}
/** 取当前登录用户资料，未登录返回 null */
function zlz_current_user($CFG) {
    $uid = zlz_session_get($CFG, zlz_session_token());
    if ($uid === '') return null;
    return zlz_user_get($CFG, $uid);
}
function zlz_require_user($CFG) {
    $u = zlz_current_user($CFG);
    if (!$u) zlz_out(array('ok' => false, 'error' => 'need_login', 'tip' => '请先登录'), 401);
    return $u;
}

/** 视频的点赞/评论文件：user/{作者id}/{sha1}.json */
function zlz_reaction_file($CFG, $authorId, $sha1) {
    if (!preg_match('/^[a-f0-9]{40}$/i', $sha1)) return '';
    return zlz_user_dir($CFG, $authorId) . '/' . strtolower($sha1) . '.json';
}
function zlz_reaction_read($CFG, $authorId, $sha1) {
    $f = zlz_reaction_file($CFG, $authorId, $sha1);
    if ($f === '') return array('likes' => 0, 'liked_by' => array(), 'comments' => array());
    return zlz_read_raw($f, array('likes' => 0, 'liked_by' => array(), 'comments' => array()));
}
function zlz_reaction_save($CFG, $authorId, $sha1, $r) {
    $f = zlz_reaction_file($CFG, $authorId, $sha1);
    if ($f === '') return false;
    return zlz_write_raw($f, $r);
}

// ---------------- HTTP（浏览器伪装）----------------

function zlz_origin($url) {
    $p = parse_url($url);
    if (!$p || empty($p['host'])) return '';
    $s = isset($p['scheme']) ? $p['scheme'] : 'https';
    return $s . '://' . $p['host'] . (isset($p['port']) ? ':' . $p['port'] : '');
}
function zlz_cookie_path($CFG) {
    zlz_mkdir(zlz_path($CFG['data_dir']));
    $h = parse_url($CFG['source_url'] !== '' ? $CFG['source_url'] : 'http://localhost', PHP_URL_HOST);
    return zlz_path($CFG['data_dir'] . '/cookie_' . preg_replace('/[^a-z0-9]/i', '_', $h) . '.txt');
}
function zlz_browser_headers($CFG, $url, $referer = '', $kind = 'nav') {
    $h = array('User-Agent: ' . $CFG['browser_ua'], 'Accept-Language: ' . $CFG['browser_lang']);
    if ($kind === 'media') {
        $h[] = 'Accept: */*'; $h[] = 'Sec-Fetch-Dest: video';
        $h[] = 'Sec-Fetch-Mode: no-cors'; $h[] = 'Sec-Fetch-Site: same-origin';
        $h[] = 'Range: bytes=0-';
    } else {
        $h[] = 'Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8';
        $h[] = 'Upgrade-Insecure-Requests: 1'; $h[] = 'Sec-Fetch-Dest: document';
        $h[] = 'Sec-Fetch-Mode: navigate'; $h[] = 'Sec-Fetch-Site: ' . ($referer === '' ? 'none' : 'same-origin');
        $h[] = 'Sec-Fetch-User: ?1';
    }
    $h[] = 'sec-ch-ua: "Google Chrome";v="131", "Chromium";v="131", "Not_A Brand";v="24"';
    $h[] = 'sec-ch-ua-mobile: ?0'; $h[] = 'sec-ch-ua-platform: "Windows"';
    if ($referer === '') $referer = zlz_origin($url) . '/';
    if ($referer !== '//') { $h[] = 'Referer: ' . $referer; if ($kind !== 'media') $h[] = 'Origin: ' . zlz_origin($referer); }
    return $h;
}
function zlz_is_challenge($code, $body) {
    if ($code == 403 || $code == 429 || $code == 503) return true;
    if ($body === '') return false;
    $head = substr($body, 0, 8192);
    $mk = array('__cf_chl','cf-browser-verification','challenge-platform','cf_chl_opt','Just a moment',
        'Checking your browser','Attention Required','_incapsula_','Incapsula','Sucuri','ModSecurity',
        'yunsuo','safe3waf','jiasule','policy_default_denied','Request denied','enable JavaScript',
        'javascript required','正在验证','安全验证','人机验证','访问验证','防火墙','请输入验证码','滑动验证',
        'aes.js','This site requires Javascript','infinityfree','ifastnet');
    foreach ($mk as $m) if (stripos($head, $m) !== false) return true;
    return false;
}
function zlz_http_get($CFG, $url, $timeout = 20, $referer = '', $kind = 'nav') {
    if (!function_exists('curl_init')) {
        $hdr = '';
        foreach (zlz_browser_headers($CFG, $url, $referer, $kind) as $x) $hdr .= $x . "\r\n";
        $ctx = stream_context_create(array('http' => array('method' => 'GET', 'timeout' => $timeout, 'header' => $hdr), 'ssl' => array('verify_peer' => false)));
        $b = @file_get_contents($url, false, $ctx);
        return $b === false ? array(0, '', 'request failed') : array(200, $b, '');
    }
    $ch = curl_init($url);
    $o = array(CURLOPT_RETURNTRANSFER => true, CURLOPT_FOLLOWLOCATION => true, CURLOPT_MAXREDIRS => 5,
        CURLOPT_CONNECTTIMEOUT => 10, CURLOPT_TIMEOUT => $timeout, CURLOPT_SSL_VERIFYPEER => false,
        CURLOPT_SSL_VERIFYHOST => false, CURLOPT_ENCODING => '',
        CURLOPT_HTTPHEADER => zlz_browser_headers($CFG, $url, $referer, $kind));
    if (defined('CURL_HTTP_VERSION_2TLS')) $o[CURLOPT_HTTP_VERSION] = CURL_HTTP_VERSION_2TLS;
    if ($CFG['browser_mode']) { $c = zlz_cookie_path($CFG); $o[CURLOPT_COOKIEFILE] = $c; $o[CURLOPT_COOKIEJAR] = $c; }
    curl_setopt_array($ch, $o);
    $b = curl_exec($ch); $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE); $err = curl_error($ch);
    curl_close($ch);
    if ($b !== false) return array($code ? $code : 200, $b, '');
    return array(0, '', $err);
}
function zlz_http_get_smart($CFG, $url, $timeout = 20) {
    if (!$CFG['browser_mode']) return zlz_http_get($CFG, $url, $timeout);
    $origin = zlz_origin($url);
    zlz_http_get($CFG, $origin . '/', 15, '', 'nav');
    if ($CFG['crawl_delay_ms'] > 0) usleep($CFG['crawl_delay_ms'] * 1000);
    list($c, $b, $e) = zlz_http_get($CFG, $url, $timeout, $origin . '/', 'nav');
    if (zlz_is_challenge($c, $b)) {
        @unlink(zlz_cookie_path($CFG));
        if ($CFG['crawl_delay_ms'] > 0) usleep($CFG['crawl_delay_ms'] * 2000);
        zlz_http_get($CFG, $origin . '/', 15, '', 'nav');
        if ($CFG['crawl_delay_ms'] > 0) usleep($CFG['crawl_delay_ms'] * 1000);
        list($c, $b, $e) = zlz_http_get($CFG, $url, $timeout, $origin . '/', 'nav');
    }
    return array($c, $b, $e);
}
function zlz_download($CFG, $url, $dest, $maxBytes, $referer = '') {
    @unlink($dest);
    if ($referer === '') $referer = zlz_origin($url) . '/';
    if (function_exists('curl_init')) {
        $fp = @fopen($dest, 'wb');
        if (!$fp) return array(false, 'cannot create file');
        $ch = curl_init($url);
        $o = array(CURLOPT_FILE => $fp, CURLOPT_FOLLOWLOCATION => true, CURLOPT_MAXREDIRS => 5,
            CURLOPT_CONNECTTIMEOUT => 10, CURLOPT_TIMEOUT => 0, CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false, CURLOPT_ENCODING => '',
            CURLOPT_HTTPHEADER => zlz_browser_headers($CFG, $url, $referer, 'media'),
            CURLOPT_NOPROGRESS => false,
            CURLOPT_PROGRESSFUNCTION => function ($ch, $dl, $dn, $ul, $un) use ($maxBytes) { return ($dn > $maxBytes) ? 1 : 0; });
        if (defined('CURL_HTTP_VERSION_2TLS')) $o[CURLOPT_HTTP_VERSION] = CURL_HTTP_VERSION_2TLS;
        if ($CFG['browser_mode']) { $c = zlz_cookie_path($CFG); $o[CURLOPT_COOKIEFILE] = $c; $o[CURLOPT_COOKIEJAR] = $c; }
        curl_setopt_array($ch, $o);
        $ok = curl_exec($ch); $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE); $err = curl_error($ch);
        curl_close($ch); fclose($fp);
        if ($ok === false && $err !== '' && !is_file($dest)) return array(false, $err);
        if ($code >= 400) { @unlink($dest); return array(false, 'HTTP ' . $code); }
        if (!is_file($dest) || filesize($dest) == 0) { @unlink($dest); return array(false, 'empty file'); }
        return array(true, '');
    }
    $d = @file_get_contents($url);
    if ($d === false) return array(false, 'download failed');
    @file_put_contents($dest, $d);
    return array(true, '');
}

// ---------------- 源站解析 ----------------

function zlz_abs($url, $baseUrl) {
    $url = trim($url);
    if ($url === '') return '';
    if (preg_match('#^https?://#i', $url)) return $url;
    if (strpos($url, '//') === 0) return 'https:' . $url;
    $p = parse_url($baseUrl);
    if (!$p || empty($p['host'])) return $url;
    $scheme = isset($p['scheme']) ? $p['scheme'] : 'https';
    if (strpos($url, '/') === 0) return $scheme . '://' . $p['host'] . $url;
    $path = isset($p['path']) ? $p['path'] : '/';
    $dir = rtrim(substr($path, 0, strrpos($path, '/')), '/');
    return $scheme . '://' . $p['host'] . $dir . '/' . ltrim($url, '/');
}
function zlz_parse_json($body, $baseUrl) {
    $d = json_decode($body, true);
    if (!is_array($d)) return array();
    $cands = array($d);
    foreach (array('list','data','videos','items','results','vod_list','files') as $k)
        if (isset($d[$k]) && is_array($d[$k])) $cands[] = $d[$k];
    foreach ($cands as $rows) {
        $out = array();
        foreach ($rows as $r) {
            if (!is_array($r)) continue;
            $url = '';
            foreach (array('url','videoUrl','video_url','playUrl','play_url','play','src','link','file','path','m3u8') as $k)
                if (!empty($r[$k]) && is_string($r[$k])) { $url = $r[$k]; break; }
            if ($url === '') continue;
            $t = '';
            foreach (array('title','name','vod_name','subject','filename') as $k)
                if (!empty($r[$k]) && is_string($r[$k])) { $t = $r[$k]; break; }
            $cv = '';
            foreach (array('cover','pic','thumb','poster','vod_pic','image') as $k)
                if (!empty($r[$k]) && is_string($r[$k])) { $cv = $r[$k]; break; }
            $out[] = array('title' => $t !== '' ? $t : basename(parse_url($url, PHP_URL_PATH)),
                'url' => zlz_abs($url, $baseUrl), 'cover' => $cv !== '' ? zlz_abs($cv, $baseUrl) : '');
        }
        if (!empty($out)) return $out;
    }
    return array();
}
function zlz_parse_dir($body, $baseUrl, $CFG) {
    $exts = zlz_ext_list($CFG);
    preg_match_all('#(?:href|src)=["\']([^"\'\s]+\.(?:' . implode('|', $exts) . '))["\']#i', $body, $m);
    $out = array(); $seen = array();
    foreach ($m[1] as $h) {
        $u = zlz_abs(html_entity_decode($h), $baseUrl);
        if (isset($seen[$u])) continue;
        $seen[$u] = true;
        $out[] = array('title' => rawurldecode(basename(parse_url($u, PHP_URL_PATH))), 'url' => $u, 'cover' => '');
    }
    return $out;
}
function zlz_parse_text($body, $baseUrl) {
    $out = array();
    foreach (preg_split('/\r\n|\r|\n/', $body) as $line) {
        $line = trim($line);
        if ($line === '' || strpos($line, '#') === 0) continue;
        $ps = preg_split('/[|,;\t]+/', $line);
        $u = null; $t = null;
        foreach ($ps as $x) {
            if (preg_match('#^https?://#i', $x) || preg_match('#\.(mp4|webm|mov|m4v|mkv|ts|flv|m3u8)$#i', $x)) { if ($u === null) $u = $x; }
            elseif ($t === null) $t = $x;
        }
        if ($u === null) continue;
        $out[] = array('title' => $t !== null ? $t : basename(parse_url($u, PHP_URL_PATH)), 'url' => zlz_abs($u, $baseUrl), 'cover' => '');
    }
    return $out;
}
function zlz_parse_custom($body, $baseUrl) { return array(); }
function zlz_source_list($CFG) {
    $url = $CFG['source_url'];
    if ($url === '') return array();
    list($code, $body, $err) = zlz_http_get_smart($CFG, $url);
    if ($code == 0 || $body === '') throw new Exception('源站请求失败: ' . ($err ? $err : 'HTTP ' . $code));
    if (zlz_is_challenge($code, $body)) throw new Exception('被 WAF/反爬拦截（HTTP ' . $code . '）');
    if ($code >= 400) throw new Exception('源站返回 HTTP ' . $code);
    $mode = $CFG['source_mode'];
    if ($mode === 'json') return zlz_parse_json($body, $url);
    if ($mode === 'dir') return zlz_parse_dir($body, $url, $CFG);
    if ($mode === 'text') return zlz_parse_text($body, $url);
    if ($mode === 'custom') return zlz_parse_custom($body, $url);
    $r = zlz_parse_json($body, $url); if (!empty($r)) return array($r, 'json');
    $r = zlz_parse_dir($body, $url, $CFG); if (!empty($r)) return array($r, 'dir');
    $r = zlz_parse_text($body, $url); if (!empty($r)) return array($r, 'text');
    return array(array(), 'none');
}

function zlz_add_video($CFG, &$meta, &$pool, $sha1, $relFile, $title, $sourceUrl, $cover, $size, $ext, $extra = array()) {
    if (isset($meta[$sha1])) return false;
    $row = array('sha1' => $sha1, 'title' => $title, 'file' => $relFile, 'source_url' => $sourceUrl,
        'cover' => $cover, 'size' => $size, 'mime' => zlz_mime($ext), 'added_at' => date('c'));
    if (is_array($extra)) foreach ($extra as $k => $v) if ($k !== 'sha1') $row[$k] = $v;
    $meta[$sha1] = $row;
    $pool[] = $sha1;
    return true;
}
