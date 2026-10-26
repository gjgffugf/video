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
    'token'       => 'change-me-please',

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

    // 单个视频大小上限（虚拟主机磁盘小，按需调）
    'max_bytes'   => 200 * 1024 * 1024,

    // 单次 sync / import 最多处理多少个
    'sync_limit'  => 20,

    // 默认推送条数
    'feed_size'   => 5,

    // 识别的视频后缀
    'exts'        => 'mp4,webm,mov,m4v,mkv,ts,flv,m3u8',
);

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

// ---- HTTP ----

function zlz_http_get($url, $timeout = 20) {
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, array(
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS      => 5,
            CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_TIMEOUT        => $timeout,
            CURLOPT_USERAGENT      => ZLZ_UA,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
        ));
        $body = curl_exec($ch);
        $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $err = curl_error($ch);
        curl_close($ch);
        if ($body !== false) return array($code ? $code : 200, $body, '');
        return array(0, '', $err);
    }
    $ctx = stream_context_create(array(
        'http' => array('method' => 'GET', 'timeout' => $timeout, 'header' => 'User-Agent: ' . ZLZ_UA . "\r\n"),
        'ssl'  => array('verify_peer' => false),
    ));
    $body = @file_get_contents($url, false, $ctx);
    if ($body === false) return array(0, '', 'request failed');
    return array(200, $body, '');
}

function zlz_download($url, $dest, $maxBytes) {
    @unlink($dest);
    if (function_exists('curl_init')) {
        $fp = @fopen($dest, 'wb');
        if (!$fp) return array(false, 'cannot create file');
        $ch = curl_init($url);
        curl_setopt_array($ch, array(
            CURLOPT_FILE           => $fp,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS      => 5,
            CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_TIMEOUT        => 0,
            CURLOPT_USERAGENT      => ZLZ_UA,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => false,
            CURLOPT_NOPROGRESS     => false,
            CURLOPT_PROGRESSFUNCTION => function ($ch, $dlSize, $dlNow, $ulSize, $ulNow) use ($maxBytes) {
                return ($dlNow > $maxBytes) ? 1 : 0;
            },
        ));
        $ok = curl_exec($ch);
        $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $err = curl_error($ch);
        curl_close($ch);
        fclose($fp);
        if ($ok === false && $err !== '' && !is_file($dest)) return array(false, $err);
        if ($code >= 400) { @unlink($dest); return array(false, 'HTTP ' . $code); }
        if (!is_file($dest) || filesize($dest) == 0) { @unlink($dest); return array(false, 'empty file'); }
        return array(true, '');
    }
    $data = @file_get_contents($url);
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
    list($code, $body, $err) = zlz_http_get($url);
    if ($code == 0 || $body === '') {
        throw new Exception('源站请求失败: ' . ($err ? $err : 'HTTP ' . $code));
    }
    if ($code >= 400) {
        throw new Exception('源站返回 HTTP ' . $code . '（可能被网关拦截，见 action=probe）');
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

$ACTIONS = array('feed', 'sync', 'import', 'probe', 'status', 'reset', 'upload');
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

// ---------- probe：探测源站格式 ----------
if ($action === 'probe') {
    $url = zlz_p('url', $CFG['source_url']);
    if ($url === '') {
        zlz_out(array('ok' => false, 'error' => 'no_url', 'tip' => '用 ?url=xxx 指定，或先在配置里填 source_url'));
    }
    list($code, $body, $err) = zlz_http_get($url);
    $out = array(
        'ok' => true,
        'url' => $url,
        'http_code' => $code,
        'curl_error' => $err,
        'bytes' => strlen($body),
        'content_type_guess' => (strpos(ltrim($body), '{') === 0 || strpos(ltrim($body), '[') === 0) ? 'json' : 'html/text',
        'raw_head' => substr($body, 0, 2000),
    );
    if ($code > 0 && $body !== '') {
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
                $tmpRel = $CFG['video_dir'] . '/dl_' . md5(uniqid('', true) . $url) . '.' . $ext;
                $tmpAbs = zlz_path($tmpRel);
                list($ok, $err) = zlz_download($url, $tmpAbs, $CFG['max_bytes']);
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

// ---------- upload：App 上传 ----------
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
