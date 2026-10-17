// unrot service worker: keeps the app working offline.
// Bump CACHE whenever a file in SHELL other than index.html changes (fonts, icons, manifest);
// index.html is always fetched fresh when online.
const CACHE = 'unrot-v1';
const SHELL = [
  './',
  'index.html',
  'manifest.webmanifest',
  'icons/favicon-64.png',
  'icons/apple-touch-icon.png',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/maskable-512.png',
  'fonts/bricolage-latin.woff2',
  'fonts/bricolage-latin-ext.woff2',
  'fonts/bricolage-vietnamese.woff2',
];

self.addEventListener('install', e => {
  // cache: 'reload' skips the HTTP cache so a new CACHE version never stores a stale copy.
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL.map(u => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k.startsWith('unrot-') && k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

const put = (key, res) => {
  if (res.ok && res.type === 'basic') { const copy = res.clone(); caches.open(CACHE).then(c => c.put(key, copy)); }
  return res;
};

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  if (req.mode === 'navigate') {
    // Network first so a new deploy shows up straight away; offline falls back to the cached app.
    // Stored without its query string so ?utm=… visits don't pile up extra copies of the page.
    const key = new URL(req.url); key.search = ''; key.hash = '';
    e.respondWith(fetch(req).then(res => put(key.href, res))
      .catch(() => caches.match(req, { ignoreSearch: true }).then(r => r || caches.match('index.html'))));
    return;
  }
  e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(res => put(req, res))));
});
