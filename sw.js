/* 우리 캘린더 V2 — Service Worker (v5: 프리캐시 교정 + SKIP_WAITING + 알림 옵션 정리) */
const CACHE_NAME = 'uri-cal-v2-v9';
const PRECACHE_URLS = [
  '/',
  '/index.html',
  '/manifest.json',
  '/icon-192.png',
  '/icon-512.png',
  '/icon-192-maskable.png',
  '/icon-512-maskable.png',
  '/icon-180.png',
  '/icon-monochrome.png',
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

/* index.html이 새 SW에게 즉시 활성화 명령 (postMessage SKIP_WAITING) */
self.addEventListener('message', (e) => {
  if (e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting();
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
    icon: data.icon || '/icon-192.png',          // 컬러 large icon (알림 본문 옆)
    badge: '/icon-monochrome.png',                // monochrome small icon (상태바)
    // tag: 송신측이 지정하면 그대로(다이제스트 등 묶기), 없으면 일정마다 unique
    // → 같은 tag로 덮여서 heads-up 안 뜨는 문제 방지
    tag: data.tag || ('wuri-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)),
    data: { url: data.url || '/' },
    vibrate: [200, 100, 200],
    renotify: !!data.tag,                         // 송신측이 tag 지정한 경우만 renotify
    silent: false,
    // requireInteraction / actions 제거:
    //  - requireInteraction은 일부 Android에서 고정 알림으로 동작 → heads-up 약화
    //  - actions가 있으면 expanded 형태로 표시되어 heads-up이 작게 뜨거나 안 뜸
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
      // 이미 앱 열려있으면 포커스 + 메시지로 navigate
      for (const client of list) {
        if ('focus' in client) {
          client.postMessage({ type: 'navigate', url: targetUrl });
          return client.focus();
        }
      }
      // 없으면 새 창 (URL에 ?room=xxx 포함)
      if (self.clients.openWindow) {
        return self.clients.openWindow(fullUrl);
      }
    })
  );
});
