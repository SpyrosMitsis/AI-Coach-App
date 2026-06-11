// Minimal service worker: makes the app installable and survivable offline.
// Strategy: network-first for page navigations (fall back to the cached shell),
// stale-while-revalidate for same-origin static assets. Supabase API/auth
// traffic and non-GET requests are never touched.
const CACHE = "workout-maker-v1";
const SHELL = ["/dashboard", "/manifest.json", "/icon-192.png", "/icon-512.png"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  // Never intercept cross-origin requests (Supabase functions/auth/PostgREST).
  if (url.origin !== self.location.origin) return;

  if (req.mode === "navigate") {
    // Network-first so the user always gets fresh pages; cached copy offline.
    event.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE).then((cache) => cache.put(req, copy));
          return res;
        })
        .catch(() =>
          caches.match(req).then((hit) => hit ?? caches.match("/dashboard")),
        ),
    );
    return;
  }

  // Static assets (hashed _next/static files, icons, fonts): cache, refresh in
  // the background.
  if (url.pathname.startsWith("/_next/static/") || url.pathname.match(/\.(png|ico|svg|woff2?)$/)) {
    event.respondWith(
      caches.match(req).then((hit) => {
        const fresh = fetch(req)
          .then((res) => {
            const copy = res.clone();
            caches.open(CACHE).then((cache) => cache.put(req, copy));
            return res;
          })
          .catch(() => hit);
        return hit ?? fresh;
      }),
    );
  }
});
