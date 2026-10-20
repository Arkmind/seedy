import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';
import { seedyMock } from './dev/mock-plugin';

// The plugin serves the build at {baseUrl}/seedy/ with relative asset URLs.
// `bun run dev` serves at /seedy/ and proxies Jellyfin from dev/docker-compose.yml.
// SEEDY_MOCK=1 answers /Seedy/* from fixtures, for working on screens before the plugin is installed.
export default defineConfig(({ command }) => ({
  base: command === 'serve' ? '/seedy/' : './',
  plugins: [preact(), ...(process.env.SEEDY_MOCK === '1' ? [seedyMock()] : [])],
  build: {
    outDir: '../src/Jellyfin.Plugin.Seedy/Web',
    emptyOutDir: true,
    assetsDir: 'assets',
    target: 'es2020',
    sourcemap: false,
    chunkSizeWarningLimit: 900
  },
  server: {
    port: Number(process.env.SEEDY_PORT ?? 5173),
    strictPort: true,
    proxy: {
      '/socket': {
        target: (process.env.SEEDY_SERVER ?? 'http://localhost:8096').replace(/^http/, 'ws'),
        ws: true,
        changeOrigin: true,
        // Jellyfin checks the socket's Origin; in production Seedy is same-origin, so present the server's own.
        configure: (proxy) => proxy.on('proxyReqWs', (req) => req.setHeader('Origin', process.env.SEEDY_SERVER ?? 'http://localhost:8096'))
      },
      // Everything that isn't the app itself (served at lowercase /seedy/) goes to Jellyfin, in any letter case
      // (Jellyfin hands out lowercase URLs such as /videos/… for transcodes).
      '^/(?!seedy(/|$)).+': {
        target: process.env.SEEDY_SERVER ?? 'http://localhost:8096',
        changeOrigin: true
      }
    }
  }
}));
