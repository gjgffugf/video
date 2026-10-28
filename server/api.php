<?php
/**
 * zilizili 接口入口
 * 公共配置与工具函数在 api_config.php
 */

require_once dirname(__FILE__) . '/api_config.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store, no-cache, must-revalidate');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type, X-ZLZ-Token, X-ZLZ-Session');

// ================= 路由 =================

$action = strtolower(zlz_p('action', 'feed'));
$base = zlz_base($CFG);

$ACTIONS = array('feed','sync','import','probe','warm','selftest','status','reset','upload',
    'upload_init','upload_chunk','upload_finish',
    'register','login','logout','me','update_profile','upload_avatar',
    'like','comments','comment','my_works','delete_work','update_work','user_space','video_info');
if (!in_array($action, $ACTIONS, true)) {
    zlz_out(array('ok' => false, 'error' => 'unknown_action', 'action' => $action, 'available' => $ACTIONS), 404);
}

// ---------- 视频信息装配（公共） ----------
function zlz_build_item($CFG, $v, $sha1, $base) {
    $file = isset($v['file']) ? $v['file'] : '';
    if ($file !== '' && is_file(zlz_path($file))) $url = $base . '/' . zlz_urlenc($file);
    elseif (!empty($v['source_url'])) $url = $v['source_url'];
    else return null;

    $cover = isset($v['cover']) ? $v['cover'] : '';
    if ($cover !== '') $cover = $base . '/' . zlz_urlenc($cover);

    $authorId = isset($v['author_id']) ? $v['author_id'] : '';
    $authorName = isset($v['author_name']) ? $v['author_name'] : '';
    $authorAvatar = '';
    if ($authorId !== '') {
        $au = zlz_user_get($CFG, $authorId);
        if ($au) {
            if ($authorName === '' && isset($au['username'])) $authorName = $au['username'];
            $av = isset($au['avatar']) ? $au['avatar'] : '';
            if ($av !== '') $authorAvatar = $base . '/' . zlz_urlenc($av);
        }
    }

    $r = zlz_reaction_read($CFG, $authorId, $sha1);
    $likes = (int) (isset($r['likes']) ? $r['likes'] : 0);
    $comments = isset($r['comments']) && is_array($r['comments']) ? $r['comments'] : array();

    // 点赞/评论 JSON 按 sha1 命名：user/{作者id}/{sha1}.json
    return array(
        'sha1' => $sha1,
        'title' => isset($v['title']) ? $v['title'] : '',
        'intro' => isset($v['intro']) ? $v['intro'] : '',
        'url' => $url,
        'cover' => $cover,
        'size' => (int) (isset($v['size']) ? $v['size'] : 0),
        'mime' => isset($v['mime']) ? $v['mime'] : 'video/mp4',
        'author_id' => $authorId,
        'author_name' => $authorName,
        'author_avatar' => $authorAvatar,
        'created_at' => isset($v['created_at']) ? $v['created_at'] : (isset($v['added_at']) ? $v['added_at'] : ''),
        'likes' => $likes,
        'comment_count' => count($comments),
    );
}

// ---------- feed：随机推送（游客可用） ----------
if ($action === 'feed') {
    $count = max(1, min((int) zlz_p('count', $CFG['feed_size']), 20));

    $res = zlz_locked($CFG, 'pool', function () use ($CFG, $count) {
        $st = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array(), 'pushed' => array()));
        $meta = zlz_read($CFG, 'videos.json', array());
        $all = array_keys($meta);
        $st['pool'] = array_values(array_intersect($st['pool'], $all));
        $st['pushed'] = array_values(array_intersect($st['pushed'], $all));
        $reset = false;
        if (empty($st['pool'])) {
            $sh = $all; shuffle($sh);
            $st['pool'] = $sh; $st['pushed'] = array();
            $st['round'] = (int) $st['round'] + 1; $st['reset_at'] = date('c');
            $reset = true;
        }
        $taken = array(); $rest = $st['pool'];
        for ($i = 0; $i < $count && !empty($rest); $i++) {
            $idx = array_rand($rest);
            $taken[] = $rest[$idx];
            array_splice($rest, $idx, 1);
        }
        $st['pool'] = array_values($rest);
        $st['pushed'] = array_values(array_unique(array_merge($st['pushed'], $taken)));
        $st['updated_at'] = date('c');
        zlz_write($CFG, 'pool.json', $st);
        return array('taken' => $taken, 'round' => $st['round'], 'reset' => $reset, 'left' => count($st['pool']));
    });

    $meta = zlz_read($CFG, 'videos.json', array());
    $items = array();
    foreach ($res['taken'] as $sha1) {
        if (!isset($meta[$sha1])) continue;
        $it = zlz_build_item($CFG, $meta[$sha1], $sha1, $base);
        if ($it) $items[] = $it;
    }
    zlz_out(array('ok' => true, 'round' => $res['round'], 'reset' => $res['reset'],
        'remaining' => $res['left'], 'count' => count($items), 'items' => $items));
}

// ---------- video_info：单个视频（滑动时刷新直链用） ----------
if ($action === 'video_info') {
    $sha1 = strtolower(trim(zlz_p('sha1', '')));
    $meta = zlz_read($CFG, 'videos.json', array());
    if (!isset($meta[$sha1])) zlz_out(array('ok' => false, 'error' => 'not_found'), 404);
    $it = zlz_build_item($CFG, $meta[$sha1], $sha1, $base);
    zlz_out(array('ok' => true, 'item' => $it));
}

// ================= 账号 =================

// ---------- register ----------
if ($action === 'register') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') zlz_out(array('ok' => false, 'error' => 'POST required'), 405);
    $name = trim(zlz_p('username', ''));
    $pw = (string) zlz_p('password', '');
    $pw2 = (string) zlz_p('password2', '');

    if ($name === '') zlz_out(array('ok' => false, 'error' => 'empty_username'), 400);
    if (mb_strlen($name, 'UTF-8') < 2 || mb_strlen($name, 'UTF-8') > 20) zlz_out(array('ok' => false, 'error' => 'username_length', 'tip' => '用户名 2-20 字'), 400);
    if (preg_match('/[@:,#\/\\\\]/u', $name)) zlz_out(array('ok' => false, 'error' => 'bad_username', 'tip' => '不能包含 @ : , # / \\'), 400);
    if ($pw === '') zlz_out(array('ok' => false, 'error' => 'empty_password'), 400);
    if (strlen($pw) < 6) zlz_out(array('ok' => false, 'error' => 'password_short', 'tip' => '密码至少 6 位'), 400);
    if ($pw !== $pw2) zlz_out(array('ok' => false, 'error' => 'password_mismatch', 'tip' => '两次密码不一致'), 400);

    if (zlz_find_by_name($CFG, $name) !== '') zlz_out(array('ok' => false, 'error' => 'username_taken', 'tip' => '用户名已被占用'), 409);

    $id = zlz_new_id($CFG);
    if ($id === '') zlz_out(array('ok' => false, 'error' => 'id_alloc_failed'), 500);

    $u = array('id' => $id, 'username' => $name, 'bio' => '', 'avatar' => '',
        'pass' => function_exists('password_hash') ? password_hash($pw, PASSWORD_DEFAULT) : md5($pw),
        'created_at' => date('Y-m-d H:i:s'));

    zlz_locked($CFG, 'user_index', function () use ($CFG, $id, $name, $u) {
        $idx = zlz_user_index($CFG);
        if (isset($idx[strtolower($name)])) throw new Exception('taken');
        zlz_user_save($CFG, $id, $u);
        $idx[strtolower($name)] = $id;
        zlz_save_index($CFG, $idx);
    });

    $token = zlz_session_put($CFG, $id);
    zlz_out(array('ok' => true, 'session' => $token, 'user' => zlz_public_user($CFG, $u)));
}

// ---------- login ----------
if ($action === 'login') {
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') zlz_out(array('ok' => false, 'error' => 'POST required'), 405);
    $acct = trim(zlz_p('account', ''));
    $pw = (string) zlz_p('password', '');
    if ($acct === '' || $pw === '') zlz_out(array('ok' => false, 'error' => 'empty_input'), 400);

    // 可用 6 位 ID 或用户名登录
    $id = zlz_valid_id($acct);
    if ($id === '') $id = zlz_find_by_name($CFG, $acct);
    if ($id === '') zlz_out(array('ok' => false, 'error' => 'user_not_found', 'tip' => '账号不存在'), 404);

    $u = zlz_user_get($CFG, $id);
    if (!$u) zlz_out(array('ok' => false, 'error' => 'user_not_found'), 404);

    $hp = isset($u['pass']) ? $u['pass'] : '';
    $ok = false;
    if ($hp !== '') {
        if (function_exists('password_verify') && (strpos($hp, '$2y$') === 0 || strpos($hp, '$argon') === 0)) $ok = password_verify($pw, $hp);
        else $ok = hash_equals($hp, md5($pw));
    }
    if (!$ok) zlz_out(array('ok' => false, 'error' => 'bad_password', 'tip' => '密码错误'), 401);

    $token = zlz_session_put($CFG, $id);
    zlz_out(array('ok' => true, 'session' => $token, 'user' => zlz_public_user($CFG, $u)));
}

// ---------- logout ----------
if ($action === 'logout') {
    zlz_session_del($CFG, zlz_session_token());
    zlz_out(array('ok' => true));
}

// ---------- me ----------
if ($action === 'me') {
    $u = zlz_require_user($CFG);
    zlz_out(array('ok' => true, 'user' => zlz_public_user($CFG, $u)));
}

// ---------- update_profile：改用户名/简介 ----------
if ($action === 'update_profile') {
    $u = zlz_require_user($CFG);
    $id = $u['id'];
    $newName = trim(zlz_p('username', ''));
    $bio = trim(zlz_p('bio', ''));

    if ($bio !== '' && mb_strlen($bio, 'UTF-8') > 200) zlz_out(array('ok' => false, 'error' => 'bio_too_long', 'tip' => '简介最多 200 字'), 400);

    if ($newName !== '' && $newName !== $u['username']) {
        if (mb_strlen($newName, 'UTF-8') < 2 || mb_strlen($newName, 'UTF-8') > 20) zlz_out(array('ok' => false, 'error' => 'username_length'), 400);
        if (preg_match('/[@:,#\/\\\\]/u', $newName)) zlz_out(array('ok' => false, 'error' => 'bad_username'), 400);
        if (zlz_find_by_name($CFG, $newName) !== '') zlz_out(array('ok' => false, 'error' => 'username_taken'), 409);

        zlz_locked($CFG, 'user_index', function () use ($CFG, $id, $u, $newName, $bio) {
            $idx = zlz_user_index($CFG);
            unset($idx[strtolower($u['username'])]);
            $idx[strtolower($newName)] = $id;
            zlz_save_index($CFG, $idx);
            $u['username'] = $newName;
            if ($bio !== '') $u['bio'] = $bio;
            zlz_user_save($CFG, $id, $u);
        });
    } else {
        if ($bio !== '') { $u['bio'] = $bio; zlz_user_save($CFG, $id, $u); }
    }

    // 同步更新该用户已发布视频里的作者名
    if ($newName !== '' && $newName !== (isset($u['username']) ? $u['username'] : '')) {
        zlz_locked($CFG, 'videos', function () use ($CFG, $id, $newName) {
            $meta = zlz_read($CFG, 'videos.json', array());
            $changed = false;
            foreach ($meta as $k => $v) {
                if (isset($v['author_id']) && $v['author_id'] === $id) { $meta[$k]['author_name'] = $newName; $changed = true; }
            }
            if ($changed) zlz_write($CFG, 'videos.json', $meta);
        });
    }

    $nu = zlz_user_get($CFG, $id);
    zlz_out(array('ok' => true, 'user' => zlz_public_user($CFG, $nu)));
}

// ---------- upload_avatar：头像 <=8MB ----------
if ($action === 'upload_avatar') {
    $u = zlz_require_user($CFG);
    if (empty($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK) {
        zlz_out(array('ok' => false, 'error' => 'no_file', 'php_error' => isset($_FILES['file']) ? $_FILES['file']['error'] : -1), 400);
    }
    $src = $_FILES['file']['tmp_name'];
    $size = (int) $_FILES['file']['size'];
    if ($size <= 0 || $size > $CFG['avatar_max_bytes']) {
        zlz_out(array('ok' => false, 'error' => 'bad_size', 'size' => $size, 'limit_mb' => round($CFG['avatar_max_bytes'] / 1048576)), 400);
    }
    $orig = isset($_FILES['file']['name']) ? $_FILES['file']['name'] : '';
    $ext = strtolower(pathinfo($orig, PATHINFO_EXTENSION));
    if ($ext === '' || !in_array($ext, zlz_img_list($CFG), true)) {
        $info = @getimagesize($src);
        $ext = ($info && isset($info['mime'])) ? (strpos($info['mime'], 'png') !== false ? 'png' : (strpos($info['mime'], 'gif') !== false ? 'gif' : 'jpg')) : 'jpg';
    }
    $dir = zlz_user_dir($CFG, $u['id']);
    $rel = $CFG['user_dir'] . '/' . $u['id'] . '/avatar.' . $ext;
    $dest = zlz_path($rel);
    if (!@move_uploaded_file($src, $dest) && !@rename($src, $dest)) @copy($src, $dest);
    if (!is_file($dest)) zlz_out(array('ok' => false, 'error' => 'save_failed'), 500);

    $u2 = zlz_user_get($CFG, $u['id']);
    $u2['avatar'] = $rel;
    zlz_user_save($CFG, $u['id'], $u2);
    zlz_out(array('ok' => true, 'user' => zlz_public_user($CFG, $u2)));
}

// ================= 互动 =================

// ---------- like：点赞（数字存储） ----------
if ($action === 'like') {
    $u = zlz_require_user($CFG);
    $sha1 = strtolower(trim(zlz_p('sha1', '')));
    $authorId = zlz_valid_id(zlz_p('author_id', ''));
    if ($sha1 === '' || $authorId === '') zlz_out(array('ok' => false, 'error' => 'bad_param'), 400);

    $r = zlz_reaction_read($CFG, $authorId, $sha1);
    if (!isset($r['likes'])) $r['likes'] = 0;
    if (!isset($r['liked_by']) || !is_array($r['liked_by'])) $r['liked_by'] = array();

    $mine = $u['id'];
    $has = in_array($mine, $r['liked_by'], true);
    if ($has) {
        $r['liked_by'] = array_values(array_diff($r['liked_by'], array($mine)));
        $r['likes'] = max(0, (int) $r['likes'] - 1);
        $liked = false;
    } else {
        $r['liked_by'][] = $mine;
        $r['likes'] = (int) $r['likes'] + 1;
        $liked = true;
    }
    zlz_reaction_save($CFG, $authorId, $sha1, $r);
    zlz_out(array('ok' => true, 'likes' => (int) $r['likes'], 'liked' => $liked));
}

// ---------- comments：评论列表（游客可读） ----------
if ($action === 'comments') {
    $sha1 = strtolower(trim(zlz_p('sha1', '')));
    $authorId = zlz_valid_id(zlz_p('author_id', ''));
    if ($sha1 === '' || $authorId === '') zlz_out(array('ok' => false, 'error' => 'bad_param'), 400);

    $r = zlz_reaction_read($CFG, $authorId, $sha1);
    $raw = isset($r['comments']) && is_array($r['comments']) ? $r['comments'] : array();

    // 每条形如 "@xxx:内容"
    $list = array();
    foreach ($raw as $i => $line) {
        $line = (string) $line;
        $name = ''; $text = $line;
        if (preg_match('/^@([^:：]+)[:：](.*)$/u', $line, $m)) { $name = trim($m[1]); $text = $m[2]; }
        $au = zlz_user_get($CFG, $authorId);
        $auName = ($au && isset($au['username'])) ? (string) $au['username'] : '';
        $isAuthor = ($name !== '' && $auName !== '' && strtolower($name) === strtolower($auName));
        $list[] = array('index' => $i, 'author' => $name, 'text' => $text, 'raw' => $line, 'is_author' => $isAuthor);
    }
    zlz_out(array('ok' => true, 'sha1' => $sha1, 'likes' => (int) (isset($r['likes']) ? $r['likes'] : 0),
        'count' => count($list), 'comments' => $list));
}

// ---------- comment：发表评论 ----------
if ($action === 'comment') {
    $u = zlz_require_user($CFG);
    $sha1 = strtolower(trim(zlz_p('sha1', '')));
    $authorId = zlz_valid_id(zlz_p('author_id', ''));
    $text = trim(zlz_p('text', ''));
    if ($sha1 === '' || $authorId === '') zlz_out(array('ok' => false, 'error' => 'bad_param'), 400);
    if ($text === '') zlz_out(array('ok' => false, 'error' => 'empty_text'), 400);
    if (mb_strlen($text, 'UTF-8') > 200) zlz_out(array('ok' => false, 'error' => 'text_too_long', 'tip' => '评论最多 200 字'), 400);
    // 逗号是分隔符，禁用以免破坏存储格式
    $text = str_replace(array(',', "\n", "\r"), array('，', ' ', ' '), $text);

    $r = zlz_reaction_read($CFG, $authorId, $sha1);
    if (!isset($r['comments']) || !is_array($r['comments'])) $r['comments'] = array();
    $r['comments'][] = '@' . $u['username'] . ':' . $text;
    zlz_reaction_save($CFG, $authorId, $sha1, $r);

    zlz_out(array('ok' => true, 'count' => count($r['comments']), 'raw' => end($r['comments'])));
}

// ================= 我的作品 / 用户空间 =================

// ---------- my_works ----------
if ($action === 'my_works') {
    $u = zlz_require_user($CFG);
    $meta = zlz_read($CFG, 'videos.json', array());
    $out = array();
    foreach ($meta as $sha1 => $v) {
        if (!isset($v['author_id']) || $v['author_id'] !== $u['id']) continue;
        $it = zlz_build_item($CFG, $v, $sha1, $base);
        if ($it) $out[] = $it;
    }
    usort($out, function ($a, $b) { return strcmp($b['created_at'], $a['created_at']); });
    zlz_out(array('ok' => true, 'count' => count($out), 'items' => $out));
}

// ---------- user_space：他人主页（游客可看） ----------
if ($action === 'user_space') {
    $acct = trim(zlz_p('uid', zlz_p('username', '')));
    $id = zlz_valid_id($acct);
    if ($id === '') $id = zlz_find_by_name($CFG, $acct);
    if ($id === '') zlz_out(array('ok' => false, 'error' => 'user_not_found'), 404);
    $u = zlz_user_get($CFG, $id);
    if (!$u) zlz_out(array('ok' => false, 'error' => 'user_not_found'), 404);

    $meta = zlz_read($CFG, 'videos.json', array());
    $out = array();
    foreach ($meta as $sha1 => $v) {
        if (!isset($v['author_id']) || $v['author_id'] !== $id) continue;
        $it = zlz_build_item($CFG, $v, $sha1, $base);
        if ($it) $out[] = $it;
    }
    usort($out, function ($a, $b) { return strcmp($b['created_at'], $a['created_at']); });
    zlz_out(array('ok' => true, 'user' => zlz_public_user($CFG, $u), 'count' => count($out), 'items' => $out));
}

// ---------- delete_work：删除自己的作品 ----------
if ($action === 'delete_work') {
    $u = zlz_require_user($CFG);
    $sha1 = strtolower(trim(zlz_p('sha1', '')));
    $meta = zlz_read($CFG, 'videos.json', array());
    if (!isset($meta[$sha1])) zlz_out(array('ok' => false, 'error' => 'not_found'), 404);
    if (!isset($meta[$sha1]['author_id']) || $meta[$sha1]['author_id'] !== $u['id']) {
        zlz_out(array('ok' => false, 'error' => 'forbidden', 'tip' => '只能删除自己的作品'), 403);
    }
    $file = isset($meta[$sha1]['file']) ? $meta[$sha1]['file'] : '';
    if ($file !== '') @unlink(zlz_path($file));
    $cover = isset($meta[$sha1]['cover']) ? $meta[$sha1]['cover'] : '';
    if ($cover !== '') @unlink(zlz_path($cover));

    zlz_locked($CFG, 'videos', function () use ($CFG, $sha1) {
        $meta = zlz_read($CFG, 'videos.json', array());
        unset($meta[$sha1]);
        zlz_write($CFG, 'videos.json', $meta);
        $st = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array(), 'pushed' => array()));
        $st['pool'] = array_values(array_diff($st['pool'], array($sha1)));
        $st['pushed'] = array_values(array_diff($st['pushed'], array($sha1)));
        zlz_write($CFG, 'pool.json', $st);
    });
    @unlink(zlz_reaction_file($CFG, $u['id'], $sha1));
    zlz_out(array('ok' => true));
}

// ---------- update_work：改简介/封面 ----------
if ($action === 'update_work') {
    $u = zlz_require_user($CFG);
    $sha1 = strtolower(trim(zlz_p('sha1', '')));
    $meta = zlz_read($CFG, 'videos.json', array());
    if (!isset($meta[$sha1])) zlz_out(array('ok' => false, 'error' => 'not_found'), 404);
    if (!isset($meta[$sha1]['author_id']) || $meta[$sha1]['author_id'] !== $u['id']) {
        zlz_out(array('ok' => false, 'error' => 'forbidden'), 403);
    }
    $intro = trim(zlz_p('intro', ''));
    $changed = false;
    if ($intro !== '') { $meta[$sha1]['intro'] = $intro; $changed = true; }

    if (!empty($_FILES['cover']) && $_FILES['cover']['error'] === UPLOAD_ERR_OK) {
        $size = (int) $_FILES['cover']['size'];
        if ($size <= 0 || $size > $CFG['cover_max_bytes']) {
            zlz_out(array('ok' => false, 'error' => 'cover_too_large',
                'limit_mb' => round($CFG['cover_max_bytes'] / 1048576)), 400);
        }
        $orig = isset($_FILES['cover']['name']) ? $_FILES['cover']['name'] : '';
        $ext = strtolower(pathinfo($orig, PATHINFO_EXTENSION));
        if ($ext === '' || !in_array($ext, zlz_img_list($CFG), true)) $ext = 'jpg';
        $dir = zlz_user_dir($CFG, $u['id']) . '/covers';
        zlz_mkdir($dir);
        $rel = $CFG['user_dir'] . '/' . $u['id'] . '/covers/' . $sha1 . '.' . $ext;
        $dest = zlz_path($rel);
        @unlink($dest);
        if (!@move_uploaded_file($_FILES['cover']['tmp_name'], $dest) && !@rename($_FILES['cover']['tmp_name'], $dest)) {
            @copy($_FILES['cover']['tmp_name'], $dest);
        }
        if (is_file($dest)) { $meta[$sha1]['cover'] = $rel; $changed = true; }
    }

    if ($changed) {
        zlz_locked($CFG, 'videos', function () use ($CFG, $meta) { zlz_write($CFG, 'videos.json', $meta); });
    }
    $it = zlz_build_item($CFG, $meta[$sha1], $sha1, $base);
    zlz_out(array('ok' => true, 'item' => $it));
}

// ================= 上传 =================

/** 保存上传的文件到 videos/ 并入库（带作者） */
function zlz_store_upload($CFG, $u, $src, $size, $origName, $title, $intro, $coverRel, $base) {
    $ext = strtolower(pathinfo($origName, PATHINFO_EXTENSION));
    if ($ext === '' || !in_array($ext, zlz_ext_list($CFG), true)) $ext = 'mp4';
    $sha1 = @sha1_file($src);
    if ($sha1 === false) return array(null, 'hash_failed');

    $relFile = $CFG['video_dir'] . '/' . $sha1 . '.' . $ext;
    zlz_mkdir(zlz_path($CFG['video_dir']));
    $dest = zlz_path($relFile);
    if (!is_file($dest)) {
        if (!@move_uploaded_file($src, $dest) && !@rename($src, $dest)) @copy($src, $dest);
    }
    if (!is_file($dest)) return array(null, 'save_failed');

    $meta = zlz_read($CFG, 'videos.json', array());
    $dup = isset($meta[$sha1]);

    if (!$dup) {
        zlz_locked($CFG, 'videos', function () use ($CFG, $sha1, $relFile, $title, $intro, $coverRel, $size, $ext, $u) {
            $meta = zlz_read($CFG, 'videos.json', array());
            $poolState = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array()));
            $pool = $poolState['pool'];
            zlz_add_video($CFG, $meta, $pool, $sha1, $relFile, $title, '', $coverRel, $size, $ext, array(
                'intro' => $intro,
                'author_id' => $u['id'],
                'author_name' => $u['username'],
                'created_at' => date('Y-m-d H:i:s'),
            ));
            $poolState['pool'] = array_values(array_unique($pool));
            zlz_write($CFG, 'pool.json', $poolState);
            zlz_write($CFG, 'videos.json', $meta);
        });
    }

    $meta = zlz_read($CFG, 'videos.json', array());
    $it = isset($meta[$sha1]) ? zlz_build_item($CFG, $meta[$sha1], $sha1, $base) : null;
    return array($it, $dup ? 'duplicate' : '');
}

// ---------- upload：整文件一次上传（需登录） ----------
if ($action === 'upload') {
    $u = zlz_require_user($CFG);
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') zlz_out(array('ok' => false, 'error' => 'POST required'), 405);
    if (empty($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK) {
        zlz_out(array('ok' => false, 'error' => 'no_file',
            'php_error' => isset($_FILES['file']) ? $_FILES['file']['error'] : -1,
            'php_upload_max' => ini_get('upload_max_filesize'),
            'php_post_max' => ini_get('post_max_size'),
            'tip' => '若 php_error=1/2 说明 PHP 侧限制太小，改用 upload_init/chunk/finish 分片'), 400);
    }
    $src = $_FILES['file']['tmp_name'];
    $size = (int) $_FILES['file']['size'];
    if ($size <= 0 || $size > $CFG['upload_max_bytes']) {
        zlz_out(array('ok' => false, 'error' => 'bad_size', 'size' => $size,
            'limit_mb' => round($CFG['upload_max_bytes'] / 1048576)), 400);
    }

    // 封面（可选，<=10MB）
    $coverRel = '';
    if (!empty($_FILES['cover']) && $_FILES['cover']['error'] === UPLOAD_ERR_OK) {
        $cs = (int) $_FILES['cover']['size'];
        if ($cs > 0 && $cs <= $CFG['cover_max_bytes']) {
            $orig = isset($_FILES['cover']['name']) ? $_FILES['cover']['name'] : '';
            $ce = strtolower(pathinfo($orig, PATHINFO_EXTENSION));
            if ($ce === '' || !in_array($ce, zlz_img_list($CFG), true)) $ce = 'jpg';
            // sha1 此时还没算出来，先用时间+随机命名，落库时再改
            $tmpRel = $CFG['user_dir'] . '/' . $u['id'] . '/covers/tmp_' . time() . '_' . mt_rand(1000, 9999) . '.' . $ce;
            zlz_mkdir(zlz_path(dirname($tmpRel)));
            $td = zlz_path($tmpRel);
            if (@move_uploaded_file($_FILES['cover']['tmp_name'], $td) || @rename($_FILES['cover']['tmp_name'], $td) || @copy($_FILES['cover']['tmp_name'], $td)) {
                $coverRel = $tmpRel;
            }
        }
    }

    $origName = isset($_FILES['file']['name']) ? $_FILES['file']['name'] : '';
    $title = trim(zlz_p('title', ''));
    if ($title === '') $title = $origName !== '' ? $origName : '未命名视频';
    $intro = trim(zlz_p('intro', ''));

    list($it, $err) = zlz_store_upload($CFG, $u, $src, $size, $origName, $title, $intro, $coverRel, $base);

    // 封面改成以 sha1 命名
    if ($it && $coverRel !== '') {
        $sha1 = $it['sha1'];
        $ext = strtolower(pathinfo($coverRel, PATHINFO_EXTENSION));
        $finalRel = $CFG['user_dir'] . '/' . $u['id'] . '/covers/' . $sha1 . '.' . $ext;
        @rename(zlz_path($coverRel), zlz_path($finalRel));
        zlz_locked($CFG, 'videos', function () use ($CFG, $sha1, $finalRel) {
            $meta = zlz_read($CFG, 'videos.json', array());
            if (isset($meta[$sha1])) { $meta[$sha1]['cover'] = $finalRel; zlz_write($CFG, 'videos.json', $meta); }
        });
        $it['cover'] = $base . '/' . zlz_urlenc($finalRel);
    }

    if (!$it) zlz_out(array('ok' => false, 'error' => $err ? $err : 'store_failed'), 500);
    zlz_out(array('ok' => true, 'item' => $it, 'duplicate' => ($err === 'duplicate')));
}

// ---------- 分片上传 ----------
function zlz_chunk_dir($CFG) { $d = zlz_path($CFG['chunk_dir']); zlz_mkdir($d); return $d; }
function zlz_upload_id($sha1, $size) { return substr($sha1, 0, 24) . '_' . $size; }
function zlz_chunk_file($CFG, $id) {
    if (!preg_match('/^[a-f0-9]{24}_\d+$/', $id)) return '';
    return zlz_chunk_dir($CFG) . '/' . $id . '.part';
}
function zlz_chunk_meta($CFG, $id) {
    if (!preg_match('/^[a-f0-9]{24}_\d+$/', $id)) return '';
    return zlz_chunk_dir($CFG) . '/' . $id . '.json';
}
function zlz_gc_chunks($CFG) {
    $dir = zlz_path($CFG['chunk_dir']);
    if (!is_dir($dir)) return;
    $ttl = (int) $CFG['chunk_ttl']; $now = time();
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

if ($action === 'upload_init') {
    $u = zlz_require_user($CFG);
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') zlz_out(array('ok' => false, 'error' => 'POST required'), 405);
    $sha1 = strtolower(trim(zlz_p('sha1', '')));
    $size = (int) zlz_p('size', 0);
    $name = trim(zlz_p('name', ''));
    $chunkSize = max(1, (int) zlz_p('chunk_size', 2 * 1024 * 1024));
    if (!preg_match('/^[a-f0-9]{40}$/', $sha1)) zlz_out(array('ok' => false, 'error' => 'bad_sha1'), 400);
    if ($size <= 0) zlz_out(array('ok' => false, 'error' => 'bad_size'), 400);
    if ($size > $CFG['upload_max_bytes']) zlz_out(array('ok' => false, 'error' => 'too_large', 'limit_mb' => round($CFG['upload_max_bytes'] / 1048576)), 413);

    $ext = strtolower(pathinfo($name, PATHINFO_EXTENSION));
    if ($ext === '' || !in_array($ext, zlz_ext_list($CFG), true)) $ext = 'mp4';

    $meta = zlz_read($CFG, 'videos.json', array());
    if (isset($meta[$sha1])) {
        $f = isset($meta[$sha1]['file']) ? $meta[$sha1]['file'] : '';
        if ($f !== '' && is_file(zlz_path($f))) {
            zlz_out(array('ok' => true, 'instant' => true, 'sha1' => $sha1,
                'url' => $base . '/' . zlz_urlenc($f), 'message' => '文件已存在，秒传成功'));
        }
    }

    $id = zlz_upload_id($sha1, $size);
    $part = zlz_chunk_file($CFG, $id);
    $mf = zlz_chunk_meta($CFG, $id);
    if ($part === '') zlz_out(array('ok' => false, 'error' => 'bad_upload_id'), 400);
    $total = (int) ceil($size / $chunkSize);

    $received = array();
    if (is_file($mf)) {
        $old = zlz_read_raw($mf);
        if (is_array($old) && isset($old['received']) && is_array($old['received'])
            && $old['sha1'] === $sha1 && (int) $old['size'] === $size) {
            $received = array_map('intval', $old['received']);
        }
    }
    if (!is_file($part)) { $fp = @fopen($part, 'wb'); if (!$fp) zlz_out(array('ok' => false, 'error' => 'cannot_create_part'), 500); fclose($fp); }

    zlz_write_raw($mf, array('uid' => $u['id'], 'sha1' => $sha1, 'size' => $size, 'name' => $name,
        'title' => trim(zlz_p('title', '')), 'intro' => trim(zlz_p('intro', '')),
        'ext' => $ext, 'chunk_size' => $chunkSize, 'total' => $total,
        'received' => array_values($received), 'created_at' => time(), 'updated_at' => time()));

    zlz_out(array('ok' => true, 'instant' => false, 'upload_id' => $id, 'sha1' => $sha1,
        'chunk_size' => $chunkSize, 'total' => $total, 'received' => array_values($received),
        'received_count' => count($received)));
}

if ($action === 'upload_chunk') {
    $u = zlz_require_user($CFG);
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') zlz_out(array('ok' => false, 'error' => 'POST required'), 405);
    $id = trim(zlz_p('upload_id', ''));
    $index = (int) zlz_p('index', -1);
    $part = zlz_chunk_file($CFG, $id);
    $mf = zlz_chunk_meta($CFG, $id);
    if ($part === '' || !is_file($mf)) zlz_out(array('ok' => false, 'error' => 'no_session'), 400);
    if ($index < 0) zlz_out(array('ok' => false, 'error' => 'bad_index'), 400);
    $m = zlz_read_raw($mf);
    if (!is_array($m)) zlz_out(array('ok' => false, 'error' => 'bad_meta'), 500);
    if (!isset($m['uid']) || $m['uid'] !== $u['id']) zlz_out(array('ok' => false, 'error' => 'forbidden'), 403);
    if ($index >= (int) $m['total']) zlz_out(array('ok' => false, 'error' => 'index_out_of_range'), 400);

    $raw = @file_get_contents('php://input');
    if ($raw === false || $raw === '') zlz_out(array('ok' => false, 'error' => 'empty_chunk'), 400);
    $offset = $index * (int) $m['chunk_size'];
    $fp = @fopen($part, 'cb');
    if (!$fp) zlz_out(array('ok' => false, 'error' => 'cannot_open_part'), 500);
    flock($fp, LOCK_EX); fseek($fp, $offset);
    $w = fwrite($fp, $raw); fflush($fp); flock($fp, LOCK_UN); fclose($fp);
    if ($w === false || $w !== strlen($raw)) zlz_out(array('ok' => false, 'error' => 'write_failed'), 500);

    $received = isset($m['received']) && is_array($m['received']) ? array_map('intval', $m['received']) : array();
    if (!in_array($index, $received, true)) $received[] = $index;
    sort($received);
    $m['received'] = $received; $m['updated_at'] = time();
    zlz_write_raw($mf, $m);
    zlz_out(array('ok' => true, 'index' => $index, 'written' => $w,
        'received_count' => count($received), 'total' => (int) $m['total']));
}

if ($action === 'upload_finish') {
    $u = zlz_require_user($CFG);
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') zlz_out(array('ok' => false, 'error' => 'POST required'), 405);
    $id = trim(zlz_p('upload_id', ''));
    $sha1 = strtolower(trim(zlz_p('sha1', '')));
    $part = zlz_chunk_file($CFG, $id);
    $mf = zlz_chunk_meta($CFG, $id);
    if ($part === '' || !is_file($mf) || !is_file($part)) zlz_out(array('ok' => false, 'error' => 'no_session'), 400);
    $m = zlz_read_raw($mf);
    if (!is_array($m)) zlz_out(array('ok' => false, 'error' => 'bad_meta'), 500);
    if (!isset($m['uid']) || $m['uid'] !== $u['id']) zlz_out(array('ok' => false, 'error' => 'forbidden'), 403);
    if ($sha1 === '') $sha1 = $m['sha1'];

    $expect = (int) $m['size'];
    $actual = @filesize($part);
    if ($actual === false || $actual !== $expect) zlz_out(array('ok' => false, 'error' => 'size_mismatch', 'expect' => $expect, 'actual' => $actual), 400);
    $real = @sha1_file($part);
    if ($real === false || !hash_equals($sha1, $real)) zlz_out(array('ok' => false, 'error' => 'sha1_mismatch', 'client' => $sha1, 'server' => $real), 400);
    if ($expect > $CFG['upload_max_bytes']) zlz_out(array('ok' => false, 'error' => 'too_large'), 413);

    $ext = isset($m['ext']) && $m['ext'] !== '' ? $m['ext'] : 'mp4';
    $relFile = $CFG['video_dir'] . '/' . $sha1 . '.' . $ext;
    zlz_mkdir(zlz_path($CFG['video_dir']));
    $dest = zlz_path($relFile);
    if (!is_file($dest)) { if (!@rename($part, $dest) && !@copy($part, $dest)) zlz_out(array('ok' => false, 'error' => 'move_failed'), 500); }
    else @unlink($part);
    @unlink($mf);

    $title = isset($m['title']) && $m['title'] !== '' ? $m['title'] : (isset($m['name']) ? $m['name'] : '未命名视频');
    $intro = isset($m['intro']) ? $m['intro'] : '';

    zlz_locked($CFG, 'videos', function () use ($CFG, $sha1, $relFile, $title, $intro, $expect, $ext, $u) {
        $meta = zlz_read($CFG, 'videos.json', array());
        if (!isset($meta[$sha1])) {
            $ps = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array()));
            $pool = $ps['pool'];
            zlz_add_video($CFG, $meta, $pool, $sha1, $relFile, $title, '', '', $expect, $ext, array(
                'intro' => $intro, 'author_id' => $u['id'], 'author_name' => $u['username'],
                'created_at' => date('Y-m-d H:i:s')));
            $ps['pool'] = array_values(array_unique($pool));
            zlz_write($CFG, 'pool.json', $ps);
            zlz_write($CFG, 'videos.json', $meta);
        }
    });
    zlz_gc_chunks($CFG);
    $meta = zlz_read($CFG, 'videos.json', array());
    $it = isset($meta[$sha1]) ? zlz_build_item($CFG, $meta[$sha1], $sha1, $base) : null;
    zlz_out(array('ok' => true, 'item' => $it));
}

// ================= 管理（需 admin token） =================

$token = zlz_p('token', isset($_SERVER['HTTP_X_ZLZ_TOKEN']) ? $_SERVER['HTTP_X_ZLZ_TOKEN'] : '');
if ($token === '' || !hash_equals($CFG['token'], $token)) {
    zlz_out(array('ok' => false, 'error' => 'unauthorized', 'tip' => '请加 ?token=管理令牌'), 401);
}

// ---------- selftest ----------
if ($action === 'selftest') {
    $toBytes = function ($v) {
        $v = strtolower(trim((string) $v));
        if ($v === '' || $v === '0') return 0;
        $u = substr($v, -1); $n = (float) $v;
        if ($u === 'g') return (int) ($n * 1073741824);
        if ($u === 'm') return (int) ($n * 1048576);
        if ($u === 'k') return (int) ($n * 1024);
        return (int) $n;
    };
    $c = array(
        'php_version' => PHP_VERSION,
        'curl' => function_exists('curl_init'),
        'allow_url_fopen' => (bool) ini_get('allow_url_fopen'),
        'upload_max_filesize' => ini_get('upload_max_filesize'),
        'post_max_size' => ini_get('post_max_size'),
        'post_limit_bytes' => $toBytes(ini_get('post_max_size')),
        'max_execution_time' => ini_get('max_execution_time'),
        'data_writable' => is_writable(zlz_path($CFG['data_dir'])),
        'video_writable' => is_writable(zlz_path($CFG['video_dir'])),
        'user_writable' => is_writable(zlz_user_root($CFG)),
        'upload_limit_mb' => round($CFG['upload_max_bytes'] / 1048576),
        'avatar_limit_mb' => round($CFG['avatar_max_bytes'] / 1048576),
        'cover_limit_mb' => round($CFG['cover_max_bytes'] / 1048576),
    );
    $free = @disk_free_space(ZLZ_ROOT);
    $c['disk_free_mb'] = $free === false ? null : round($free / 1048576, 1);
    zlz_out(array('ok' => true, 'selftest' => $c));
}

// ---------- status ----------
if ($action === 'status') {
    $meta = zlz_read($CFG, 'videos.json', array());
    $st = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array(), 'pushed' => array()));
    $used = 0;
    foreach ($meta as $v) if (!empty($v['file']) && is_file(zlz_path($v['file']))) $used += @filesize(zlz_path($v['file']));
    $idx = zlz_user_index($CFG);
    zlz_out(array('ok' => true, 'base_url' => $base, 'total_videos' => count($meta),
        'total_users' => count($idx), 'round' => $st['round'], 'pool_left' => count($st['pool']),
        'pushed' => count($st['pushed']), 'disk_used_mb' => round($used / 1048576, 1)));
}

// ---------- reset ----------
if ($action === 'reset') {
    zlz_locked($CFG, 'pool', function () use ($CFG) {
        $meta = zlz_read($CFG, 'videos.json', array());
        $st = zlz_read($CFG, 'pool.json', array('round' => 0));
        $all = array_keys($meta); shuffle($all);
        zlz_write($CFG, 'pool.json', array('round' => (int) $st['round'] + 1, 'pool' => $all,
            'pushed' => array(), 'reset_at' => date('c'), 'updated_at' => date('c')));
    });
    zlz_out(array('ok' => true, 'message' => '推送池已重置'));
}

// ---------- probe ----------
if ($action === 'probe') {
    $url = zlz_p('url', $CFG['source_url']);
    if ($url === '') zlz_out(array('ok' => false, 'error' => 'no_url'), 400);
    list($rc, $rb) = zlz_http_get($CFG, $url, 20, '', 'nav');
    list($c, $b, $e) = zlz_http_get_smart($CFG, $url);
    $out = array('ok' => true, 'url' => $url,
        'plain' => array('code' => $rc, 'blocked' => zlz_is_challenge($rc, $rb)),
        'browser' => array('code' => $c, 'blocked' => zlz_is_challenge($c, $b)),
        'curl_error' => $e, 'bytes' => strlen($b), 'raw_head' => substr($b, 0, 2000));
    if (!zlz_is_challenge($c, $b) && $b !== '') {
        $r = zlz_source_list($CFG);
        if (is_array($r) && count($r) == 2 && is_string($r[1])) {
            $out['detected_mode'] = $r[1]; $out['parsed_count'] = count($r[0]); $out['sample'] = array_slice($r[0], 0, 3);
        }
    }
    zlz_out($out);
}

// ---------- warm ----------
if ($action === 'warm') {
    $url = zlz_p('url', $CFG['source_url']);
    if ($url === '') zlz_out(array('ok' => false, 'error' => 'no_url'), 400);
    $origin = zlz_origin($url);
    list($c1, $b1) = zlz_http_get($CFG, $origin . '/', 20, '', 'nav');
    $cf = zlz_cookie_path($CFG);
    $txt = is_file($cf) ? @file_get_contents($cf) : '';
    $n = 0;
    foreach (preg_split('/\r\n|\r|\n/', $txt) as $l) if ($l !== '' && strpos($l, '#') !== 0) $n++;
    zlz_out(array('ok' => true, 'origin' => $origin, 'home_code' => $c1,
        'cookies_saved' => $n, 'blocked' => zlz_is_challenge($c1, $b1)));
}

// ---------- sync ----------
if ($action === 'sync') {
    $limit = max(1, min((int) zlz_p('limit', $CFG['sync_limit']), 100));
    $download = zlz_p('download', '1') !== '0';
    zlz_mkdir(zlz_path($CFG['video_dir'])); zlz_mkdir(zlz_path($CFG['data_dir']));
    try { $list = zlz_source_list($CFG); }
    catch (Exception $e) { zlz_out(array('ok' => false, 'error' => 'list_failed', 'message' => $e->getMessage()), 502); }
    if (is_array($list) && count($list) == 2 && is_string($list[1])) { $detected = $list[1]; $list = $list[0]; }
    else $detected = $CFG['source_mode'];
    if (empty($list)) zlz_out(array('ok' => true, 'fetched' => 0, 'added' => 0, 'detected_mode' => $detected));

    // 抓取入库归属：可指定 ?owner=用户名，否则无作者（游客抓取）
    $ownerName = trim(zlz_p('owner', ''));
    $ownerId = $ownerName !== '' ? zlz_find_by_name($CFG, $ownerName) : '';
    $ownerRow = ($ownerId !== '') ? zlz_user_get($CFG, $ownerId) : null;
    $ownerName2 = ($ownerRow && isset($ownerRow['username'])) ? $ownerRow['username'] : '';

    $rep = array('ok' => true, 'mode' => $detected, 'fetched' => count($list), 'added' => 0, 'skipped' => 0, 'failed' => 0, 'errors' => array());
    zlz_locked($CFG, 'videos', function () use ($CFG, $list, $limit, $download, $ownerId, $ownerName2, &$rep) {
        $meta = zlz_read($CFG, 'videos.json', array());
        $ps = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array(), 'pushed' => array()));
        $pool = $ps['pool'];
        $handled = 0;
        foreach ($list as $item) {
            if ($handled >= $limit) break;
            $url = isset($item['url']) ? $item['url'] : '';
            if ($url === '') continue;
            $path = parse_url($url, PHP_URL_PATH);
            $ext = strtolower(pathinfo($path, PATHINFO_EXTENSION));
            if ($ext === '' || !in_array($ext, zlz_ext_list($CFG), true)) $ext = 'mp4';

            if ($download) {
                if ($CFG['crawl_delay_ms'] > 0) usleep($CFG['crawl_delay_ms'] * 1000);
                $tmpRel = $CFG['video_dir'] . '/dl_' . md5(uniqid('', true) . $url) . '.' . $ext;
                $tmpAbs = zlz_path($tmpRel);
                list($ok, $err) = zlz_download($CFG, $url, $tmpAbs, $CFG['max_bytes']);
                if (!$ok || !is_file($tmpAbs)) {
                    @unlink($tmpAbs); $rep['failed']++;
                    if (count($rep['errors']) < 10) $rep['errors'][] = array('url' => $url, 'error' => $err);
                    continue;
                }
                $size = filesize($tmpAbs);
                if ($size == 0 || $size > $CFG['max_bytes']) { @unlink($tmpAbs); $rep['failed']++; continue; }
                $sha1 = sha1_file($tmpAbs);
                $relFile = $CFG['video_dir'] . '/' . $sha1 . '.' . $ext;
                @rename($tmpAbs, zlz_path($relFile)); @unlink($tmpAbs);
            } else {
                $sha1 = sha1($url); $relFile = '';
            }
            $handled++;

            if (!$download) {
                if (isset($meta[$sha1])) { $rep['skipped']++; continue; }
                $meta[$sha1] = array('sha1' => $sha1, 'title' => isset($item['title']) ? $item['title'] : basename($path),
                    'file' => '', 'source_url' => $url, 'cover' => isset($item['cover']) ? $item['cover'] : '',
                    'size' => 0, 'mime' => zlz_mime($ext), 'added_at' => date('c'), 'created_at' => date('Y-m-d H:i:s'));
                $pool[] = $sha1; $rep['added']++; continue;
            }
            if (isset($meta[$sha1])) { @unlink(zlz_path($relFile)); $rep['skipped']++; continue; }
            if (zlz_add_video($CFG, $meta, $pool, $sha1, $relFile,
                isset($item['title']) ? $item['title'] : basename($path), $url,
                isset($item['cover']) ? $item['cover'] : '', $size, $ext,
                array('intro' => '', 'author_id' => $ownerId, 'author_name' => $ownerName2, 'created_at' => date('Y-m-d H:i:s')))) {
                $rep['added']++;
            } else { @unlink(zlz_path($relFile)); $rep['skipped']++; }
        }
        zlz_write($CFG, 'videos.json', $meta);
        $ps['pool'] = array_values(array_unique($pool));
        zlz_write($CFG, 'pool.json', $ps);
    });
    $meta = zlz_read($CFG, 'videos.json', array());
    $ps = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array(), 'pushed' => array()));
    $rep['total_videos'] = count($meta); $rep['pool_left'] = count($ps['pool']);
    zlz_out($rep);
}

// ---------- import ----------
if ($action === 'import') {
    $rel = zlz_safe_rel(zlz_p('path', $CFG['import_dir']));
    if ($rel === '') zlz_out(array('ok' => false, 'error' => 'bad_path'), 400);
    $absDir = zlz_path($rel);
    if (!is_dir($absDir)) zlz_out(array('ok' => false, 'error' => 'dir_not_found', 'path' => $rel), 404);
    $limit = max(1, min((int) zlz_p('limit', $CFG['sync_limit']), 500));
    $ownerName = trim(zlz_p('owner', ''));
    $ownerId = $ownerName !== '' ? zlz_find_by_name($CFG, $ownerName) : '';
    $ownerRow = ($ownerId !== '') ? zlz_user_get($CFG, $ownerId) : null;
    $ownerName2 = ($ownerRow && isset($ownerRow['username'])) ? $ownerRow['username'] : '';

    $rep = array('ok' => true, 'dir' => $rel, 'scanned' => 0, 'added' => 0, 'skipped' => 0);
    $files = array();
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($absDir, FilesystemIterator::SKIP_DOTS));
    foreach ($it as $f) if ($f->isFile()) $files[] = $f->getPathname();
    sort($files);

    zlz_locked($CFG, 'videos', function () use ($CFG, $files, $limit, $ownerId, $ownerName2, &$rep) {
        $meta = zlz_read($CFG, 'videos.json', array());
        $ps = zlz_read($CFG, 'pool.json', array('round' => 0, 'pool' => array(), 'pushed' => array()));
        $pool = $ps['pool'];
        foreach ($files as $abs) {
            if ($rep['scanned'] >= $limit) break;
            $name = basename($abs);
            $ext = strtolower(pathinfo($name, PATHINFO_EXTENSION));
            if (!in_array($ext, zlz_ext_list($CFG), true)) continue;
            $size = @filesize($abs);
            if ($size === false || $size == 0 || $size > $CFG['max_bytes']) continue;
            $rep['scanned']++;
            $sha1 = sha1_file($abs);
            if ($sha1 === false) continue;
            $sub = substr(str_replace('\\', '/', $abs), strlen(str_replace('\\', '/', ZLZ_ROOT)) + 1);
            if (zlz_add_video($CFG, $meta, $pool, $sha1, $sub, $name, '', '', $size, $ext,
                array('intro' => '', 'author_id' => $ownerId, 'author_name' => $ownerName2, 'created_at' => date('Y-m-d H:i:s')))) {
                $rep['added']++;
            } else $rep['skipped']++;
        }
        zlz_write($CFG, 'videos.json', $meta);
        $ps['pool'] = array_values(array_unique($pool));
        zlz_write($CFG, 'pool.json', $ps);
    });
    $meta = zlz_read($CFG, 'videos.json', array());
    $rep['total_videos'] = count($meta);
    zlz_out($rep);
}

zlz_out(array('ok' => false, 'error' => 'not_handled', 'action' => $action), 404);
