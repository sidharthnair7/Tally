import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The landing page builds into ../web, next to the workspace page, so the FastAPI app serves it at /.
// In dev, the API and the workspace are proxied to the app on :8000.
const app = 'http://127.0.0.1:8000';

export default defineConfig({
  plugins: [react()],
  build: { outDir: '../web', emptyOutDir: false, assetsDir: 'assets' },
  server: { port: 5173, proxy: { '/api': app, '/workspace': app } }
});
