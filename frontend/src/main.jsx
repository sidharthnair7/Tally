import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import { startHue } from './rainbow.js';
import { startQuality } from './quality.js';
import './styles.css';

// Started once, outside React, so a remount cannot tear them down.
startQuality();
startHue();

// No StrictMode: its double mount in dev makes GradientASCII lose its WebGL context on the first cleanup, and the
// second mount then draws onto a dead canvas.
createRoot(document.getElementById('root')).render(<App />);
