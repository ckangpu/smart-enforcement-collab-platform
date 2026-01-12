function qs(name) {
  return new URLSearchParams(window.location.search).get(name);
}

let _i18n = null;

export async function initUiI18n() {
  if (_i18n) return _i18n;
  try {
    const res = await fetch('/ui/i18n/zh-CN.json', { cache: 'no-cache' });
    if (!res.ok) {
      _i18n = {};
      return _i18n;
    }
    _i18n = await res.json();
    return _i18n;
  } catch {
    _i18n = {};
    return _i18n;
  }
}

export function t(key, params = null) {
  const dict = _i18n || {};
  let s = dict[key] ?? key;
  if (params && typeof s === 'string') {
    for (const [k, v] of Object.entries(params)) {
      s = s.replaceAll(`{${k}}`, String(v));
    }
  }
  return s;
}

function localizeCommonErrorCode(code) {
  const c = String(code ?? '').trim();
  if (!c) return '';

  // Prefer i18n if present.
  const map = {
    UNAUTHORIZED: t('error.unauthorized'),
    INVALID_TOKEN: t('error.unauthorized'),
    FORBIDDEN: t('error.forbiddenInternalOnly'),
    INVALID_CREDENTIALS: '用户名或密码错误。',
    INVALID_CODE: '验证码错误。',
    NOT_FOUND: t('error.notFound'),
    CONFLICT: '请求冲突，请稍后重试。',
    UNPROCESSABLE_ENTITY: '请求参数不符合要求。',
    VALIDATION_ERROR: t('error.validation'),
    BAD_JSON: t('error.badJson'),
    RATE_LIMITED: '操作过于频繁，请稍后再试。',
    SMS_COOLDOWN: '发送过于频繁，请稍后再试。',
    SMS_DAILY_LIMIT: '今日验证码发送次数已达上限，请明天再试。',
    SERVER_ERROR: t('error.server'),
    PDF_RENDER_FAILED: '打印文件生成失败，请稍后重试。'
  };

  if (map[c]) return map[c];
  return '';
}

export function applyI18n(root = document) {
  if (!root) return;
  root.querySelectorAll('[data-i18n-key]').forEach((el) => {
    const key = el.getAttribute('data-i18n-key');
    if (!key) return;
    el.textContent = t(key);
  });
  root.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
    const key = el.getAttribute('data-i18n-placeholder');
    if (!key) return;
    el.setAttribute('placeholder', t(key));
  });
}

export function getToken() {
  try {
    return localStorage.getItem('secp_token');
  } catch {
    return null;
  }
}

export function setToken(token) {
  localStorage.setItem('secp_token', token);
}

export function clearToken() {
  localStorage.removeItem('secp_token');
}

export function authHeader() {
  const t = getToken();
  if (!t) return {};
  return { Authorization: `Bearer ${t}` };
}

function redirectToAuthPage(reason, message) {
  const u = new URL('/ui/login.html', window.location.origin);
  if (reason) u.searchParams.set('reason', reason);
  if (message) u.searchParams.set('message', message);
  window.location.replace(u.toString());
}

export async function apiFetch(path, options = {}) {
  const opts = { ...options };
  const headers = new Headers(opts.headers || {});

  const t = getToken();
  if (t && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${t}`);
  }

  opts.headers = headers;

  const res = await fetch(path, opts);
  if (res.status === 401) {
    clearToken();
    redirectToAuthPage('unauthorized', t('error.unauthorized'));
    throw new Error('UNAUTHORIZED');
  }
  if (res.status === 403) {
    redirectToAuthPage('forbidden', t('error.forbiddenInternalOnly'));
    throw new Error('FORBIDDEN');
  }

  return res;
}

export function formatDate(v) {
  if (!v) return '';
  try {
    const d = new Date(v);
    if (Number.isNaN(d.getTime())) return String(v);
    return d.toISOString();
  } catch {
    return String(v);
  }
}

export function maskPhone(phone) {
  if (!phone) return '';
  const p = String(phone).trim();
  if (p.length <= 4) return `****${p}`;
  const last4 = p.slice(-4);
  if (p.length >= 7) return `${p.slice(0, 3)}****${last4}`;
  return `****${last4}`;
}

export function showReasonBanner(el) {
  if (!el) return;
  const reason = qs('reason');
  const message = qs('message');
  if (!reason && !message) return;

  let text = '';
  if (message) {
    text = message;
  } else if (reason === 'unauthorized') {
    text = t('error.toLogin');
  } else if (reason === 'logout') {
    text = '已退出登录';
  } else if (reason === 'forbidden') {
    text = t('error.forbiddenInternalOnly');
  } else {
    // Unknown reason: keep user-facing text Chinese, put details into a hidden debug section.
    el.innerHTML = `<div>发生错误。</div><details style="margin-top:6px"><summary>调试信息</summary><pre style="white-space:pre-wrap">reason=${String(reason ?? '')}</pre></details>`;
    el.style.display = 'block';
    return;
  }

  el.textContent = text;
  el.style.display = 'block';
}

export function renderError(el, message, debug = '') {
  if (!el) return;
  const msg = String(message ?? '').trim();
  const dbg = String(debug ?? '').trim();

  if (!msg && !dbg) {
    el.textContent = '';
    el.style.display = 'none';
    return;
  }

  const esc = (s) => String(s)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');

  let html = `<div>${esc(msg || t('common.requestFailed'))}</div>`;
  if (dbg) {
    html += `<details style="margin-top:6px"><summary>调试信息</summary><pre style="white-space:pre-wrap">${esc(dbg)}</pre></details>`;
  }
  el.innerHTML = html;
  el.style.display = 'block';
}

export function parseErrorBodyText(text) {
  const raw = String(text ?? '').trim();
  if (!raw) return { message: '', debug: '' };

  try {
    const obj = JSON.parse(raw);
    const msg = (obj && typeof obj === 'object') ? (obj.message ?? '') : '';
    const code = (obj && typeof obj === 'object') ? (obj.code ?? obj.error ?? '') : '';
    if (msg) {
      return { message: String(msg), debug: raw };
    }
    if (code) {
      const localized = localizeCommonErrorCode(code);
      return { message: localized || String(code), debug: raw };
    }
  } catch {
    // ignore
  }
  return { message: raw, debug: raw };
}
