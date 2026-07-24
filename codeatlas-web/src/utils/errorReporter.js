import axios from 'axios'

const report = (payload) => {
  axios.post('/api/v1/telemetry/frontend-error', payload).catch(() => {
    // silently fail — never let telemetry errors affect the app
  })
}

// JS runtime errors
window.addEventListener('error', (event) => {
  report({
    type: 'js_error',
    message: event.message,
    stack: event.error?.stack?.substring(0, 1000),
    url: window.location.href,
    timestamp: Date.now()
  })
})

// Unhandled promise rejections
window.addEventListener('unhandledrejection', (event) => {
  report({
    type: 'promise_rejection',
    message: event.reason?.message || String(event.reason),
    stack: event.reason?.stack?.substring(0, 1000),
    url: window.location.href,
    timestamp: Date.now()
  })
})

export { report }
