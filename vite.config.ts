import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// `pnpm dev` serves the page and sends /api to `pnpm server`.
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    proxy: { '/api': 'http://127.0.0.1:3150' },
  },
});
