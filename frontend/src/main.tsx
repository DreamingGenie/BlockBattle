import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';
import { installDevtoolsGuard } from './app/devtoolsGuard';
import { adoptInvitePath } from './realtime/room';
import './styles.css';

adoptInvitePath();
// 운영 빌드에서만 켠다. 개발·QA에서는 개발자 도구를 그대로 쓴다
if (import.meta.env.PROD) installDevtoolsGuard();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
