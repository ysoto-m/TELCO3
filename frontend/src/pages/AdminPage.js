import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Alert, Box, Button, Card, CardContent, Container, Grid, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { adminAgents, adminCampaigns, adminCreateUser, adminExportCsvUrl, adminInteractions, adminManual2ExportCsvUrl, adminManual2Report, adminManual2SaveSubtipificacion, adminManual2SetSubtipificacionActivo, adminManual2Subtipificaciones, adminSettings, adminSummary, adminUpdateAgentPass, adminUpdateSettings, adminUsers } from '../api/sdk';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
const today = new Date().toISOString().slice(0, 10);
const MASKED_SECRET = '********';
function buildEditableConfig(source) {
    const data = source || {};
    return {
        baseUrl: data.baseUrl || '',
        apiUser: data.apiUser || '',
        apiPass: '',
        source: data.source || '',
        dbHost: data.dbHost || '',
        dbPort: data.dbPort || '',
        dbName: data.dbName || '',
        dbUser: data.dbUser || '',
        dbPass: '',
    };
}
export default function AdminPage() {
    const navigate = useNavigate();
    const [filters, setFilters] = useState({ campaign: '', agentUser: '', dispo: '', from: today, to: today, page: 0, size: 20 });
    const [manual2Filters, setManual2Filters] = useState({ campana: 'Manual2', agente: '', tipificacion: '', disposicion: '', subtipificacion: '', telefono: '', cliente: '', from: today, to: today, page: 0, size: 20 });
    const [subtipForm, setSubtipForm] = useState({ campana: 'Manual2', codigo: '', nombre: '', tipificacion: '', activo: true });
    const summary = useQuery({ queryKey: ['admin-summary'], queryFn: adminSummary, refetchInterval: 8000 });
    const agents = useQuery({ queryKey: ['admin-agents'], queryFn: adminAgents, refetchInterval: 8000 });
    const campaigns = useQuery({ queryKey: ['admin-campaigns'], queryFn: adminCampaigns });
    const interactions = useQuery({ queryKey: ['admin-interactions', filters], queryFn: () => adminInteractions(filters) });
    const manual2Report = useQuery({ queryKey: ['admin-manual2-report', manual2Filters], queryFn: () => adminManual2Report(manual2Filters) });
    const subtipificaciones = useQuery({ queryKey: ['admin-manual2-subtipificaciones', manual2Filters.campana], queryFn: () => adminManual2Subtipificaciones(manual2Filters.campana) });
    const users = useQuery({ queryKey: ['admin-users'], queryFn: adminUsers });
    const settings = useQuery({ queryKey: ['admin-settings'], queryFn: adminSettings });
    const createUser = useMutation({ mutationFn: adminCreateUser, onSuccess: () => users.refetch() });
    const updateSettings = useMutation({ mutationFn: adminUpdateSettings, onSuccess: () => settings.refetch() });
    const saveSubtipificacion = useMutation({
        mutationFn: adminManual2SaveSubtipificacion,
        onSuccess: () => {
            setSubtipForm((prev) => ({ ...prev, codigo: '', nombre: '' }));
            subtipificaciones.refetch();
        }
    });
    const toggleSubtipificacion = useMutation({
        mutationFn: ({ codigo, activo }) => adminManual2SetSubtipificacionActivo(codigo, { campana: manual2Filters.campana, activo }),
        onSuccess: () => subtipificaciones.refetch()
    });
    const [newUser, setNewUser] = useState({ username: '', password: '', role: 'AGENT', active: true });
    const [cfg, setCfg] = useState(null);
    const [agentPassByUser, setAgentPassByUser] = useState({});
    const updateAgentPass = useMutation({ mutationFn: ({ id, agentPass }) => adminUpdateAgentPass(id, { agentPass }) });
    const logout = () => {
        localStorage.removeItem('token');
        localStorage.removeItem('role');
        sessionStorage.clear();
        navigate('/login', { replace: true });
    };
    const exportUrl = useMemo(() => adminExportCsvUrl(filters), [filters]);
    const manual2ExportUrl = useMemo(() => adminManual2ExportCsvUrl(manual2Filters), [manual2Filters]);
    const k = summary.data || {};
    const rows = agents.data?.items || [];
    const ir = interactions.data || {};
    const manual2Rows = manual2Report.data || {};
    const formCfg = cfg || buildEditableConfig(settings.data);
    const mergeCfg = (patch) => {
        setCfg((prev) => ({ ...(prev || buildEditableConfig(settings.data)), ...patch }));
    };
    const buildSettingsPayload = () => {
        const base = settings.data || {};
        const current = cfg || buildEditableConfig(base);
        const apiPass = String(current.apiPass || '').trim();
        const dbPass = String(current.dbPass || '').trim();
        return {
            baseUrl: current.baseUrl || base.baseUrl || '',
            apiUser: current.apiUser || base.apiUser || '',
            apiPass: apiPass && apiPass !== MASKED_SECRET ? apiPass : '',
            source: current.source ?? base.source ?? '',
            dbHost: current.dbHost ?? base.dbHost ?? '',
            dbPort: current.dbPort ?? base.dbPort ?? '',
            dbName: current.dbName ?? base.dbName ?? '',
            dbUser: current.dbUser ?? base.dbUser ?? '',
            dbPass: dbPass && dbPass !== MASKED_SECRET ? dbPass : '',
        };
    };
    return _jsx(Container, { maxWidth: 'xl', sx: { py: 3 }, children: _jsxs(Stack, { gap: 2, children: [_jsxs(Stack, { direction: 'row', justifyContent: 'space-between', alignItems: 'center', children: [_jsx(Typography, { variant: 'h4', children: "Admin Dashboard" }), _jsxs(Stack, { direction: 'row', gap: 1, children: [_jsx(Button, { variant: 'outlined', onClick: () => navigate('/admin/realtime'), children: "Realtime" }), _jsx(Button, { variant: 'outlined', color: 'inherit', onClick: logout, children: "Cerrar sesi\u00F3n" })] })] }), k.degraded && _jsx(Alert, { severity: 'warning', children: "Vicidial degradado: se muestran datos parciales/locales." }), _jsx(Grid, { container: true, spacing: 2, children: [['Agentes activos', k.activeAgents], ['Incall', k.incallAgents], ['Pausados', k.pausedAgents], ['Interacciones hoy', k.interactionsToday]].map(([label, value]) => _jsx(Grid, { item: true, xs: 12, md: 3, children: _jsx(Card, { children: _jsxs(CardContent, { children: [_jsx(Typography, { variant: 'caption', children: label }), _jsx(Typography, { variant: 'h4', children: String(value ?? 0) })] }) }) }, String(label))) }), _jsx(Card, { children: _jsx(CardContent, { children: _jsxs(Stack, { gap: 1, children: [_jsx(Typography, { variant: 'h6', children: "Agentes live" }), _jsx(TextField, { label: 'Buscar agente', value: filters.agentUser, onChange: e => setFilters({ ...filters, agentUser: e.target.value, page: 0 }), sx: { maxWidth: 320 } }), _jsx(Box, { sx: { maxHeight: 260, overflow: 'auto' }, children: _jsxs(Table, { size: 'small', children: [_jsx(TableHead, { children: _jsxs(TableRow, { children: [_jsx(TableCell, { children: "Agent" }), _jsx(TableCell, { children: "Status" }), _jsx(TableCell, { children: "Campa\u00F1a" }), _jsx(TableCell, { children: "Extensi\u00F3n" }), _jsx(TableCell, { children: "Duraci\u00F3n" })] }) }), _jsx(TableBody, { children: rows.filter(r => !filters.agentUser || String(r.agentUser || '').includes(filters.agentUser)).map((r, idx) => _jsxs(TableRow, { children: [_jsx(TableCell, { children: r.agentUser || '-' }), _jsx(TableCell, { children: r.status || '-' }), _jsx(TableCell, { children: r.campaign || '-' }), _jsx(TableCell, { children: r.extension || '-' }), _jsx(TableCell, { children: r.duration || '-' })] }, idx)) })] }) })] }) }) }), _jsx(Card, { children: _jsxs(CardContent, { children: [_jsx(Typography, { variant: 'h6', children: "Campa\u00F1as" }), _jsx(Stack, { direction: 'row', gap: 1, flexWrap: 'wrap', children: (campaigns.data?.items || []).map((c, i) => _jsx(Alert, { severity: 'info', children: String(c) }, i)) })] }) }), _jsx(Card, { children: _jsx(CardContent, { children: _jsxs(Stack, { gap: 1, children: [_jsx(Typography, { variant: 'h6', children: "Interactions" }), _jsxs(Stack, { direction: 'row', gap: 1, children: [_jsx(TextField, { label: 'Campaign', value: filters.campaign, onChange: e => setFilters({ ...filters, campaign: e.target.value, page: 0 }) }), _jsx(TextField, { label: 'Dispo', value: filters.dispo, onChange: e => setFilters({ ...filters, dispo: e.target.value, page: 0 }) }), _jsx(TextField, { type: 'date', label: 'Desde', value: filters.from, onChange: e => setFilters({ ...filters, from: e.target.value, page: 0 }), InputLabelProps: { shrink: true } }), _jsx(TextField, { type: 'date', label: 'Hasta', value: filters.to, onChange: e => setFilters({ ...filters, to: e.target.value, page: 0 }), InputLabelProps: { shrink: true } }), _jsx(Button, { href: exportUrl, target: '_blank', children: "Export CSV" })] }), _jsxs(Table, { size: 'small', children: [_jsx(TableHead, { children: _jsxs(TableRow, { children: [_jsx(TableCell, { children: "ID" }), _jsx(TableCell, { children: "Agent" }), _jsx(TableCell, { children: "Campaign" }), _jsx(TableCell, { children: "Dispo" }), _jsx(TableCell, { children: "Fecha" })] }) }), _jsx(TableBody, { children: (ir.items || []).map((r) => _jsxs(TableRow, { children: [_jsx(TableCell, { children: r.id }), _jsx(TableCell, { children: r.agentUser }), _jsx(TableCell, { children: r.campaign }), _jsx(TableCell, { children: r.dispo }), _jsx(TableCell, { children: r.createdAt })] }, r.id)) })] })] }) }) }), _jsx(Card, { children: _jsx(CardContent, { children: _jsxs(Stack, { gap: 1, children: [_jsx(Typography, { variant: 'h6', children: "Reporte Manual2 (BD sistema)" }), _jsxs(Stack, { direction: 'row', gap: 1, flexWrap: 'wrap', children: [_jsx(TextField, { label: 'Campa\u00C3\u00B1a', value: manual2Filters.campana, onChange: e => setManual2Filters({ ...manual2Filters, campana: e.target.value, page: 0 }) }), _jsx(TextField, { label: 'Agente', value: manual2Filters.agente, onChange: e => setManual2Filters({ ...manual2Filters, agente: e.target.value, page: 0 }) }), _jsx(TextField, { label: 'Tipificacion', value: manual2Filters.tipificacion, onChange: e => setManual2Filters({ ...manual2Filters, tipificacion: e.target.value, page: 0 }) }), _jsx(TextField, { label: 'Disposicion', value: manual2Filters.disposicion, onChange: e => setManual2Filters({ ...manual2Filters, disposicion: e.target.value, page: 0 }) }), _jsx(TextField, { label: 'Subtipificacion', value: manual2Filters.subtipificacion, onChange: e => setManual2Filters({ ...manual2Filters, subtipificacion: e.target.value, page: 0 }) }), _jsx(TextField, { label: 'Telefono', value: manual2Filters.telefono, onChange: e => setManual2Filters({ ...manual2Filters, telefono: e.target.value, page: 0 }) }), _jsx(TextField, { label: 'Cliente', value: manual2Filters.cliente, onChange: e => setManual2Filters({ ...manual2Filters, cliente: e.target.value, page: 0 }) }), _jsx(TextField, { type: 'date', label: 'Desde', value: manual2Filters.from, onChange: e => setManual2Filters({ ...manual2Filters, from: e.target.value, page: 0 }), InputLabelProps: { shrink: true } }), _jsx(TextField, { type: 'date', label: 'Hasta', value: manual2Filters.to, onChange: e => setManual2Filters({ ...manual2Filters, to: e.target.value, page: 0 }), InputLabelProps: { shrink: true } }), _jsx(Button, { href: manual2ExportUrl, target: '_blank', children: "Export CSV" })] }), _jsxs(Table, { size: 'small', children: [_jsx(TableHead, { children: _jsxs(TableRow, { children: [_jsx(TableCell, { children: "Fecha" }), _jsx(TableCell, { children: "Agente" }), _jsx(TableCell, { children: "Campa\u00C3\u00B1a" }), _jsx(TableCell, { children: "Tel\u00C3\u00A9fono" }), _jsx(TableCell, { children: "Cliente" }), _jsx(TableCell, { children: "Tipificacion" }), _jsx(TableCell, { children: "Dispo" }), _jsx(TableCell, { children: "Subtip" }), _jsx(TableCell, { children: "Comentario" }), _jsx(TableCell, { children: "Audio" }), _jsx(TableCell, { children: "Lead" }), _jsx(TableCell, { children: "Call" }), _jsx(TableCell, { children: "Unique" })] }) }), _jsx(TableBody, { children: (manual2Rows.items || []).map((r) => _jsxs(TableRow, { children: [_jsx(TableCell, { children: r.fechaGestion }), _jsx(TableCell, { children: r.agente }), _jsx(TableCell, { children: r.campana }), _jsx(TableCell, { children: r.telefono }), _jsx(TableCell, { children: `${r.nombres || ''} ${r.apellidos || ''}`.trim() }), _jsx(TableCell, { children: r.tipificacion }), _jsx(TableCell, { children: r.disposicion }), _jsx(TableCell, { children: r.subtipificacion }), _jsx(TableCell, { children: r.comentario || r.observaciones }), _jsx(TableCell, { children: r.nombreAudio }), _jsx(TableCell, { children: r.leadId }), _jsx(TableCell, { children: r.callId }), _jsx(TableCell, { children: r.uniqueId })] }, r.id)) })] })] }) }) }), _jsx(Card, { children: _jsx(CardContent, { children: _jsxs(Stack, { gap: 1, children: [_jsx(Typography, { variant: 'h6', children: "Subtipificaciones Manual2" }), _jsxs(Stack, { direction: 'row', gap: 1, flexWrap: 'wrap', children: [_jsx(TextField, { label: 'Campana', value: subtipForm.campana, onChange: e => setSubtipForm({ ...subtipForm, campana: e.target.value }) }), _jsx(TextField, { label: 'Tipificacion', value: subtipForm.tipificacion, onChange: e => setSubtipForm({ ...subtipForm, tipificacion: e.target.value }) }), _jsx(TextField, { label: 'Codigo', value: subtipForm.codigo, onChange: e => setSubtipForm({ ...subtipForm, codigo: e.target.value }) }), _jsx(TextField, { label: 'Nombre', value: subtipForm.nombre, onChange: e => setSubtipForm({ ...subtipForm, nombre: e.target.value }) }), _jsx(Button, { variant: 'contained', disabled: !subtipForm.tipificacion || !subtipForm.codigo || !subtipForm.nombre || saveSubtipificacion.isPending, onClick: () => saveSubtipificacion.mutate({
                                                campana: subtipForm.campana || 'Manual2',
                                                tipificacion: subtipForm.tipificacion,
                                                codigo: subtipForm.codigo,
                                                nombre: subtipForm.nombre,
                                                activo: subtipForm.activo,
                                            }), children: "Guardar" })] }), saveSubtipificacion.isError && _jsx(Alert, { severity: 'error', children: "No se pudo guardar la subtipificacion." }), _jsxs(Table, { size: 'small', children: [_jsx(TableHead, { children: _jsxs(TableRow, { children: [_jsx(TableCell, { children: "Tipificacion" }), _jsx(TableCell, { children: "Codigo" }), _jsx(TableCell, { children: "Nombre" }), _jsx(TableCell, { children: "Activo" }), _jsx(TableCell, { children: "Accion" })] }) }), _jsx(TableBody, { children: (subtipificaciones.data?.items || []).map((s) => _jsxs(TableRow, { children: [_jsx(TableCell, { children: s.tipificacion }), _jsx(TableCell, { children: s.codigo }), _jsx(TableCell, { children: s.nombre }), _jsx(TableCell, { children: String(s.activo) }), _jsx(TableCell, { children: _jsx(Button, { size: 'small', variant: 'outlined', onClick: () => toggleSubtipificacion.mutate({ codigo: s.codigo, activo: !s.activo }), children: s.activo ? 'Desactivar' : 'Activar' }) })] }, `${s.campana}-${s.codigo}`)) })] })] }) }) }), _jsxs(Grid, { container: true, spacing: 2, children: [_jsx(Grid, { item: true, xs: 12, md: 6, children: _jsx(Card, { children: _jsx(CardContent, { children: _jsxs(Stack, { gap: 1, children: [_jsx(Typography, { variant: 'h6', children: "Usuarios" }), _jsxs(Stack, { direction: 'row', gap: 1, children: [_jsx(TextField, { label: 'Username', value: newUser.username, onChange: e => setNewUser({ ...newUser, username: e.target.value }) }), _jsx(TextField, { label: 'Password', type: 'password', value: newUser.password, onChange: e => setNewUser({ ...newUser, password: e.target.value }) }), _jsx(TextField, { label: 'Role', value: newUser.role, onChange: e => setNewUser({ ...newUser, role: e.target.value }) }), _jsx(Button, { variant: 'contained', onClick: () => createUser.mutate(newUser), children: "Crear" })] }), _jsxs(Table, { size: 'small', children: [_jsx(TableHead, { children: _jsxs(TableRow, { children: [_jsx(TableCell, { children: "User" }), _jsx(TableCell, { children: "Role" }), _jsx(TableCell, { children: "Activo" }), _jsx(TableCell, { children: "agent_pass" })] }) }), _jsx(TableBody, { children: (users.data || []).map((u) => _jsxs(TableRow, { children: [_jsx(TableCell, { children: u.username }), _jsx(TableCell, { children: u.role }), _jsx(TableCell, { children: String(u.active) }), _jsx(TableCell, { children: _jsxs(Stack, { direction: 'row', gap: 1, children: [_jsx(TextField, { size: 'small', type: 'password', placeholder: 'Nuevo agent_pass', value: agentPassByUser[u.id] || '', onChange: e => setAgentPassByUser({ ...agentPassByUser, [u.id]: e.target.value }) }), _jsx(Button, { size: 'small', variant: 'outlined', onClick: () => updateAgentPass.mutate({ id: u.id, agentPass: agentPassByUser[u.id] || '' }), disabled: !(agentPassByUser[u.id] || ''), children: "Guardar" })] }) })] }, u.id)) })] })] }) }) }) }), _jsx(Grid, { item: true, xs: 12, md: 6, children: _jsx(Card, { children: _jsx(CardContent, { children: _jsxs(Stack, { gap: 1, children: [_jsx(Typography, { variant: 'h6', children: "Settings Vicidial" }), _jsx(TextField, { label: 'Base URL', value: formCfg.baseUrl, onChange: e => mergeCfg({ baseUrl: e.target.value }) }), _jsx(TextField, { label: 'API User', value: formCfg.apiUser, onChange: e => mergeCfg({ apiUser: e.target.value }) }), _jsx(TextField, { label: 'API Pass', type: 'password', placeholder: '********', value: formCfg.apiPass, onChange: e => mergeCfg({ apiPass: e.target.value }) }), _jsx(TextField, { label: 'Source', value: formCfg.source, onChange: e => mergeCfg({ source: e.target.value }) }), _jsx(TextField, { label: 'DB Host', value: formCfg.dbHost, onChange: e => mergeCfg({ dbHost: e.target.value }) }), _jsx(TextField, { label: 'DB Port', value: formCfg.dbPort, onChange: e => mergeCfg({ dbPort: e.target.value }) }), _jsx(TextField, { label: 'DB Name', value: formCfg.dbName, onChange: e => mergeCfg({ dbName: e.target.value }) }), _jsx(TextField, { label: 'DB User', value: formCfg.dbUser, onChange: e => mergeCfg({ dbUser: e.target.value }) }), _jsx(TextField, { label: 'DB Pass', type: 'password', placeholder: '********', value: formCfg.dbPass, onChange: e => mergeCfg({ dbPass: e.target.value }) }), _jsx(Button, { variant: 'contained', onClick: () => updateSettings.mutate(buildSettingsPayload()), children: "Guardar settings" })] }) }) }) })] })] }) });
}
