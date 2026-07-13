import { fengyu } from './sdk.js';
const source = document.querySelector('#source'); const preview = document.querySelector('#preview'); const error = document.querySelector('#error');
const env = await fengyu.ready(); document.documentElement.dataset.theme = env.theme;
let timer;
async function render() { try { const result = await fengyu.invoke('render', { markdown: source.value }); preview.innerHTML = result.html; error.textContent = ''; } catch (e) { error.textContent = e.message; } }
source.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(render, 180); }); render();
