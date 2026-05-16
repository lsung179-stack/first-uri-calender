/* 우리 캘린더 V2 — Service Worker (v3: 푸시 알림 지원) */
const CACHE_NAME = 'uri-cal-v2-v5';
const PRECACHE_URLS = [
  '/v2.html',
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
      cache.addAll(PRECACHE_URLS).catch((err) => console.warn('Pre-cache 실패:', err))
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

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  if (!req.url.startsWith('http')) return;
  if (req.url.includes('supabase.co')) return;
  if (req.url.includes('kakao')) return;
  
  e.respondWith(
    fetch(req).then((res) => {
      if (res.ok && (req.url.includes(self.location.origin) || req.url.includes('cdn.jsdelivr.net') || req.url.includes('fonts.googleapis.com') || req.url.includes('fonts.gstatic.com'))) {
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
    vibrate: [200, 100, 200],   // 갤럭시/안드 헤드업 알림 트리거
    renotify: true,             // 같은 tag로 와도 다시 헤드업 표시
    requireInteraction: false,
    silent: false,
  };
  
  e.waitUntil(self.registration.showNotification(title, opts));
});

/* 알림 클릭 시 앱 열기/포커스 */
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const targetUrl = (e.notification.data && e.notification.data.url) || '/';
  const fullUrl = new URL(targetUrl, self.location.origin).href;
  
  e.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
      // 이미 앱 열려있으면 포커스
      for (const client of list) {
        if ('focus' in client) {
          return client.focus();
        }
      }
      // 없으면 새 창
      if (self.clients.openWindow) {
        return self.clients.openWindow(fullUrl);
      }
    })
  );
});
