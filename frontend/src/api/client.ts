import axios from 'axios'

// withCredentials so the JSESSIONID cookie set at login is sent on every call.
export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
})
