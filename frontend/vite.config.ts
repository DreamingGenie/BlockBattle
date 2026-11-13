import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 개발 서버는 /api·/ws(백엔드)와 /ai(AI 서버)를 같은 출처로 프록시한다(T-18).
// Refresh 쿠키가 SameSite=Strict·Path=/api/v1/auth라 같은 출처여야 붙고, CORS 설정도 필요 없다.
const BACKEND = process.env.BACKEND_URL ?? 'http://localhost:8080';
const AI = process.env.AI_URL ?? 'http://localhost:8000';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/ws': { target: BACKEND.replace(/^http/, 'ws'), ws: true, changeOrigin: true },
      '/ai': { target: AI, changeOrigin: true },
    },
  },
});
