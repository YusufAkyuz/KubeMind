import axios from 'axios'

// withCredentials so the JSESSIONID cookie set at login is sent on every call.
// withXSRFToken: axios echoes Spring's XSRF-TOKEN cookie back as X-XSRF-TOKEN
// on every mutating request (CSRF protection is enabled server-side).
export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  withXSRFToken: true,
})

/** Extracts the server's error message from an axios error, with a fallback. */
export function apiErrorMessage(error: unknown, fallback = 'Something went wrong'): string {
  if (axios.isAxiosError(error) && error.response?.data?.error) {
    return String(error.response.data.error)
  }
  return fallback
}
