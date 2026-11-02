import { defineConfig } from 'vite';

const target = new URL(process.env.WORKSPACE_PROXY_TARGET ?? 'http://127.0.0.1:8080');
if (target.protocol !== 'http:' || target.hostname !== '127.0.0.1' || !target.port || target.username || target.password ||
    target.pathname !== '/' || target.search || target.hash) {
  throw new Error('WORKSPACE_PROXY_TARGET must be a loopback HTTP origin with an explicit port');
}

// Local learning setup: browser requests and cookies remain same-origin via this proxy.
export default defineConfig({
  server: {
    host: '127.0.0.1', port: 5173, strictPort: true,
    proxy: {
      '/api': { target: target.origin, ws: true },
      '/login': { target: target.origin },
      '/logout': { target: target.origin },
    },
  },
});
