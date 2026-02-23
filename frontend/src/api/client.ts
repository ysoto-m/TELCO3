import axios from 'axios';
const api = axios.create({
  baseURL: import.meta.env.VITE_BACKEND_BASE_URL || 'http://localhost:8080',
  withCredentials: true,
});
api.interceptors.request.use((c)=>{
  const isAuthRoute = c.url?.startsWith('/api/auth/');
  if (isAuthRoute) return c;

  const t = localStorage.getItem('token');
  if(t) c.headers.Authorization=`Bearer ${t}`;
  return c;
});
export default api;
