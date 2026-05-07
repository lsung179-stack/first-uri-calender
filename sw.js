/* 우리 캘린더 V2 — Service Worker */
const CACHE_NAME = 'uri-cal-v2-v1';
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
  // POST/PUT/DELETE 등은 캐시 X
  if (req.method !== 'GET') return;
  // chrome-extension, blob 등 무시
  if (!req.url.startsWith('http')) return;
  // Supabase API는 항상 네트워크
  if (req.url.includes('supabase.co')) return;
  // 카카오 API도 네트워크
  if (req.url.includes('kakao')) return;
  
  // 네트워크 우선, 실패 시 캐시
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
