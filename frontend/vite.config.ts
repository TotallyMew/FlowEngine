import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const backend = process.env.FLOW_DEV_API ?? 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '127.0.0.1',
    strictPort: true,
    proxy: {
      '/api': backend,
      '/actuator': backend,
      '/swagger-ui': backend,
      '/v3/api-docs': backend,
    },
  },
})
