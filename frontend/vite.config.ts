import { createHash } from 'node:crypto';
import { defineConfig, type Plugin } from 'vite';

const target = new URL(process.env.WORKSPACE_PROXY_TARGET ?? 'http://127.0.0.1:8080');
if (target.protocol !== 'http:' || target.hostname !== '127.0.0.1' || !target.port || target.username || target.password ||
    target.pathname !== '/' || target.search || target.hash) {
  throw new Error('WORKSPACE_PROXY_TARGET must be a loopback HTTP origin with an explicit port');
}

// Only static build artifacts are cached; session and API responses are never cached.
function offlineShell(): Plugin {
  return {
    name: 'workspace-offline-shell', apply: 'build', enforce: 'post',
    generateBundle(_options, bundle) {
      const files = Object.keys(bundle).filter(name => name.startsWith('assets/')).sort().map(name => `/${name}`);
      const index = bundle['index.html'];
      if (!index || index.type !== 'asset') throw new Error('Missing application shell');
      const version = createHash('sha256').update(String(index.source)).update(JSON.stringify(files)).digest('hex').slice(0, 16);
      const assets = ['/', ...files];
      const source = `const NAME = ${JSON.stringify(`workspace-shell-${version}`)};\nconst ASSETS = ${JSON.stringify(assets)};\n` + `
self.addEventListener('install', event => event.waitUntil(caches.open(NAME).then(cache => cache.addAll(ASSETS)).then(() => self.skipWaiting())));
self.addEventListener('activate', event => event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('workspace-shell-') && key !== NAME).map(key => caches.delete(key)))).then(() => self.clients.claim())));
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin) return;
  const key = event.request.mode === 'navigate' && url.pathname === '/' ? '/' : url.pathname;
  if (!ASSETS.includes(key)) return;
  event.respondWith(caches.open(NAME).then(cache => cache.match(key)).then(response => response || fetch(event.request)));
});\n`;
      this.emitFile({ type: 'asset', fileName: 'sw.js', source });
    },
  };
}

const proxy = {
  '/api': { target: target.origin, ws: true },
  '/login': { target: target.origin },
  '/logout': { target: target.origin },
};

// Local learning setup: browser requests and cookies remain same-origin via this proxy.
export default defineConfig({
  plugins: [offlineShell()],
  optimizeDeps: { include: ['@tiptap/react', '@tiptap/extension-collaboration', '@tiptap/starter-kit', '@tiptap/y-tiptap', 'yjs'] },
  server: {
    host: '127.0.0.1', port: 5173, strictPort: true,
    proxy,
  },
  preview: { host: '127.0.0.1', proxy },
});
