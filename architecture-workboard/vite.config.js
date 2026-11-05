import {defineConfig} from 'vite';
export default defineConfig({build:{outDir:'build',chunkSizeWarningLimit:1800},server:{proxy:{'/api':'http://127.0.0.1:4320'}},worker:{format:'es'}});
