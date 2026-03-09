import axios from 'axios';
const env = import.meta.env || {};
const resolvedBaseUrl = env.VITE_BACKEND_BASE_URL || env.VITE_API_BASE_URL || 'http://localhost:8080';
const api = axios.create({
    baseURL: resolvedBaseUrl,
    withCredentials: true,
});
api.interceptors.request.use((c) => {
    const isAuthRoute = c.url?.startsWith('/api/auth/');
    if (isAuthRoute)
        return c;
    const t = localStorage.getItem('token');
    if (t)
        c.headers.Authorization = `Bearer ${t}`;
    return c;
});
export default api;
