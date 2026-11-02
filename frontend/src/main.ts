import { createElement, StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './styles.css';

const root = document.getElementById('root');
if (!root) throw new Error('Root element is missing');
createRoot(root).render(createElement(StrictMode, null, createElement(App)));
