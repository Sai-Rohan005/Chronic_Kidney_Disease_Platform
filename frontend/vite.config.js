
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],

  server: {
    proxy: {
      '/auth': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
        secure: false,

        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq, req) => {
            console.log(
              '[AUTH FORWARD]',
              req.method,
              req.url,
              '->',
              `http://${proxyReq.getHeader('host')}${proxyReq.path}`
            )
          })

          proxy.on('proxyRes', (proxyRes, req) => {
            console.log(
              '[AUTH RESPONSE]',
              req.method,
              req.url,
              proxyRes.statusCode
            )
          })

          proxy.on('error', (err, req) => {
            console.error(
              '[AUTH PROXY ERROR]',
              req?.url,
              err.message
            )
          })
        },
      },

      '/appointments': {
        target: 'http://127.0.0.1:8082',
        changeOrigin: true,
      },

      '/reports': {
        target: 'http://127.0.0.1:8083',
        changeOrigin: true,
      },

      '/predictions': {
        target: 'http://127.0.0.1:8084',
        changeOrigin: true,
      },
    },
  },
})
