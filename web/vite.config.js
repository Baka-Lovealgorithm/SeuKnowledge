import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 后端端口约定 18080（见后端 application.yml server.port；8080 属 Windows 保留端口段不可用），
        // 默认值两侧已对齐，无需手动设置；特殊环境仍可用 VITE_PROXY_TARGET 覆盖
        target: process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:18080',
        changeOrigin: true
      }
    }
  }
})
