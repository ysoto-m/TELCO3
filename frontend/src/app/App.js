import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Navigate, Route, Routes } from 'react-router-dom';
import LoginPage from '../pages/LoginPage';
import AgentPage from '../pages/AgentPage';
import AdminPage from '../pages/AdminPage';
import AdminRealtimePage from '../pages/AdminRealtimePage';
const Guard = ({ children }) => localStorage.getItem('token') ? children : _jsx(Navigate, { to: '/login' });
export const App = () => (_jsxs(Routes, { children: [_jsx(Route, { path: '/login', element: _jsx(LoginPage, {}) }), _jsx(Route, { path: '/agent', element: _jsx(Guard, { children: _jsx(AgentPage, {}) }) }), _jsx(Route, { path: '/admin', element: _jsx(Guard, { children: _jsx(AdminPage, {}) }) }), _jsx(Route, { path: '/admin/realtime', element: _jsx(Guard, { children: _jsx(AdminRealtimePage, {}) }) }), _jsx(Route, { path: '*', element: _jsx(Navigate, { to: '/login' }) })] }));
