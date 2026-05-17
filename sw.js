/* 우리 캘린더 V2 — Service Worker (v7: 무한로딩 방지 + 자동 업데이트) */
const CACHE_NAME = 'uri-cal-v2-v7';
const HTML_TIMEOUT_MS = 3000;
const PRECACHE_URLS = [
  '/',
  '/index.html',
  '/r.html',
  '/manifest.json',
  '/icon-192.png',
  '/icon-512.png',
  '/icon-180.png',
  '/terms.html',
  '/privacy.html',
];

self.addEventListener('install', (e) => {
  self.skipWaiting();
  e.waitUntil(
    caches.open(CACHE_NAME).then((cache) =>
      Promise.all(PRECACHE_URLS.map(url =>
        cache.add(url).catch(err => console.warn('Pre-cache 스킵:', url, err.message))
      ))
    )
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((names) =>
      Promise.all(names.filter(n => n !== CACHE_NAME).map(n => caches.delete(n)))
    ).then(() => self.clients.claim())
  );
});

/* 메인 앱이 새 SW 즉시 활성화 요청 시 (자동 업데이트 메커니즘) */
self.addEventListener('message', (e) => {
  if (e.data && e.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

/* HTML 요청 판별 헬퍼 */
function isHtmlRequest(req) {
  return req.mode === 'navigate' ||
    (req.destination === 'document') ||
    (req.headers.get('accept') || '').includes('text/html');
}

/* 캐시 가능한 도메인 화이트리스트 */
function isCacheable(url) {
  return url.includes(self.location.origin) ||
    url.includes('cdn.jsdelivr.net') ||
    url.includes('fonts.googleapis.com') ||
    url.includes('fonts.gstatic.com');
}

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  if (!req.url.startsWith('http')) return;
  if (req.url.includes('supabase.co')) return;
  if (req.url.includes('kakao')) return;

  /* HTML 요청 — network-first with timeout (무한 로딩 방지 핵심) */
  if (isHtmlRequest(req)) {
    e.respondWith((async () => {
      try {
        const res = await Promise.race([
          fetch(req).then((r) => {
            if (r.ok) {
              const clone = r.clone();
              caches.open(CACHE_NAME).then((c) => c.put(req, clone)).catch(() => {});
            }
            return r;
          }),
          new Promise((_, reject) =>
            setTimeout(() => reject(new Error('html fetch timeout')), HTML_TIMEOUT_MS)
          ),
        ]);
        return res;
      } catch (err) {
        /* 네트워크 실패 또는 timeout — 캐시에서 응답 (없으면 offline 메시지) */
        const cached =
          (await caches.match(req)) ||
          (await caches.match('/index.html')) ||
          (await caches.match('/'));
        if (cached) return cached;
        return new Response(
          '<!doctype html><meta charset="utf-8"><title>오프라인</title>' +
          '<div style="font-family:sans-serif;padding:40px;text-align:center;color:#8b3a2a">' +
          '연결이 불안정해요.<br>네트워크 확인 후 다시 시도해주세요.</div>',
          { status: 503, headers: { 'Content-Type': 'text/html; charset=utf-8' } }
        );
      }
    })());
    return;
  }

  /* 기타 자원 — network-first with cache fallback */
  e.respondWith(
    fetch(req).then((res) => {
      if (res.ok && isCacheable(req.url)) {
        const clone = res.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(req, clone)).catch(() => {});
      }
      return res;
    }).catch(() => caches.match(req))
  );
});

/* ═══════════════════════════════════
   푸시 알림 수신
   ═══════════════════════════════════ */
self.addEventListener('push', (e) => {
  let data = {};
  try {
    data = e.data ? e.data.json() : {};
  } catch {
    data = { title: '우리 캘린더', body: e.data ? e.data.text() : '새 알림' };
  }

  const title = data.title || '우리 캘린더';
  const opts = {
    body: data.body || '',
    icon: data.icon || '/icon-192.png',
    badge: '/icon-192.png',
    tag: data.tag || 'wuri-calendar',
    data: { url: data.url || '/' },
    vibrate: [200, 100, 200],
    renotify: true,
    requireInteraction: false,
    silent: false,
  };

  e.waitUntil(self.registration.showNotification(title, opts));
});

/* 알림 클릭 시 앱 열기/포커스 + URL 전달 */
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const targetUrl = (e.notification.data && e.notification.data.url) || '/';
  const fullUrl = new URL(targetUrl, self.location.origin).href;

  e.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      for (const client of list) {
        if ('focus' in client) {
          client.postMessage({ type: 'navigate', url: targetUrl });
          return client.focus();
        }
      }
      if (self.clients.openWindow) {
        return self.clients.openWindow(fullUrl);
      }
    })
  );
});
