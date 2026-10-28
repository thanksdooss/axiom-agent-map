import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// GitHub Pages에서는 BASE_PATH=/axiom-agent-map/ 으로 빌드합니다.
export default defineConfig({
  base: process.env.BASE_PATH ?? '/',
  plugins: [react()],
  server: { port: 5173 },
});
