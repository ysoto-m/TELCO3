import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Alert, Box, Button, Card, CardContent, Chip, Container, FormControl, Grid, InputLabel, LinearProgress, MenuItem, Select, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { adminVicidialRealtimeAgents, adminVicidialRealtimeCampaigns, adminVicidialRealtimePauseCodes, adminVicidialRealtimeSummary } from '../api/sdk';
const refreshMs = 5000;
const statusLegend = [
    { label: 'Disponible', color: '#2e7d32' },
    { label: 'En llamada', color: '#d32f2f' },
    { label: 'Pausa', color: '#ed6c02' },
    { label: 'Soporte', color: '#1565c0' },
    { label: 'WrapUp', color: '#6a1b9a' },
    { label: 'Desconectado', color: '#616161' }
];
function statusColor(status) {
    switch (status) {
        case 'Disponible':
            return '#2e7d32';
        case 'En llamada':
            return '#d32f2f';
        case 'WrapUp':
            return '#6a1b9a';
        case 'Soporte':
            return '#1565c0';
        case 'Break':
        case 'Baño':
        case 'Capacitación':
        case 'Consulta supervisor':
        case 'Refrigerio':
        case 'Reunión':
        case 'Back Office':
        case 'Pausa':
            return '#ed6c02';
        case 'Desconectado':
            return '#616161';
        default:
            return '#455a64';
    }
}
function formatSeconds(raw) {
    const total = Number(raw ?? 0);
    if (!Number.isFinite(total) || total <= 0) {
        return '00:00';
    }
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = Math.floor(total % 60);
    if (hours > 0) {
        return [hours, minutes, seconds].map(v => String(v).padStart(2, '0')).join(':');
    }
    return [minutes, seconds].map(v => String(v).padStart(2, '0')).join(':');
}
function formatDate(raw) {
    if (!raw) {
        return '-';
    }
    const parsed = new Date(raw);
    if (Number.isNaN(parsed.getTime())) {
        return '-';
    }
    return parsed.toLocaleString();
}
function cleanFilters(filters) {
    return Object.fromEntries(Object.entries(filters)
        .map(([k, v]) => [k, v.trim()])
        .filter(([, v]) => Boolean(v)));
}
export default function AdminRealtimePage() {
    const navigate = useNavigate();
    const [filters, setFilters] = useState({
        campaign: '',
        status: '',
        pauseCode: '',
        search: ''
    });
    const summary = useQuery({
        queryKey: ['admin-vicidial-realtime-summary'],
        queryFn: adminVicidialRealtimeSummary,
        refetchInterval: refreshMs,
        refetchIntervalInBackground: true
    });
    const campaigns = useQuery({
        queryKey: ['admin-vicidial-realtime-campaigns'],
        queryFn: adminVicidialRealtimeCampaigns,
        refetchInterval: 30000,
        refetchIntervalInBackground: true
    });
    const pauseCodes = useQuery({
        queryKey: ['admin-vicidial-realtime-pause-codes'],
        queryFn: adminVicidialRealtimePauseCodes,
        refetchInterval: 30000,
        refetchIntervalInBackground: true
    });
    const agents = useQuery({
        queryKey: ['admin-vicidial-realtime-agents', filters],
        queryFn: () => adminVicidialRealtimeAgents(cleanFilters(filters)),
        refetchInterval: refreshMs,
        refetchIntervalInBackground: true,
        placeholderData: previous => previous
    });
    const logout = () => {
        localStorage.removeItem('token');
        localStorage.removeItem('role');
        sessionStorage.clear();
        navigate('/login', { replace: true });
    };
    const lastUpdate = agents.data?.generatedAt || summary.data?.generatedAt;
    const kpiCards = useMemo(() => [
        ['Agentes conectados', summary.data?.connectedAgents ?? 0],
        ['Disponibles', summary.data?.availableAgents ?? 0],
        ['En llamada', summary.data?.incallAgents ?? 0],
        ['En pausa', summary.data?.pausedAgents ?? 0],
        ['WrapUp', summary.data?.wrapupAgents ?? 0],
        ['Atendidas', summary.data?.answeredCalls ?? 0],
        ['Abandonadas', summary.data?.abandonedCalls ?? 0],
        ['Nivel servicio', `${summary.data?.serviceLevelPercent ?? 0}%`],
        ['TMO espera', formatSeconds(summary.data?.averageWaitSeconds ?? 0)],
        ['TMO hablado', formatSeconds(summary.data?.averageTalkSeconds ?? 0)]
    ], [summary.data]);
    const rows = agents.data?.items || [];
    return (_jsx(Container, { maxWidth: 'xl', sx: { py: 3 }, children: _jsxs(Stack, { gap: 2, children: [_jsxs(Stack, { direction: 'row', justifyContent: 'space-between', alignItems: 'center', children: [_jsxs(Stack, { children: [_jsx(Typography, { variant: 'h4', children: "Admin Realtime" }), _jsxs(Typography, { variant: 'body2', color: 'text.secondary', children: ["\u00DAltima actualizaci\u00F3n: ", lastUpdate ? formatDate(lastUpdate) : '-'] })] }), _jsxs(Stack, { direction: 'row', gap: 1, children: [_jsx(Button, { variant: 'outlined', onClick: () => navigate('/admin'), children: "Volver Admin" }), _jsx(Button, { variant: 'outlined', color: 'inherit', onClick: logout, children: "Cerrar sesi\u00F3n" })] })] }), (summary.isFetching || agents.isFetching) && _jsx(LinearProgress, {}), (summary.isError || agents.isError) && (_jsx(Alert, { severity: 'error', children: "No se pudo cargar el realtime de Vicidial. Verifica settings y conectividad de BD Vicidial." })), _jsx(Grid, { container: true, spacing: 2, children: kpiCards.map(([label, value]) => (_jsx(Grid, { item: true, xs: 12, sm: 6, md: 3, children: _jsx(Card, { children: _jsxs(CardContent, { children: [_jsx(Typography, { variant: 'caption', color: 'text.secondary', children: String(label) }), _jsx(Typography, { variant: 'h5', children: String(value) })] }) }) }, String(label)))) }), _jsx(Card, { children: _jsx(CardContent, { children: _jsxs(Stack, { gap: 1, children: [_jsx(Typography, { variant: 'h6', children: "Leyenda" }), _jsx(Stack, { direction: 'row', gap: 1, flexWrap: 'wrap', children: statusLegend.map(item => (_jsx(Chip, { label: item.label, size: 'small', sx: { backgroundColor: item.color, color: '#fff', fontWeight: 600 } }, item.label))) })] }) }) }), _jsx(Card, { children: _jsx(CardContent, { children: _jsxs(Stack, { gap: 2, children: [_jsx(Typography, { variant: 'h6', children: "Filtros" }), _jsxs(Grid, { container: true, spacing: 1, children: [_jsx(Grid, { item: true, xs: 12, md: 2, children: _jsxs(FormControl, { fullWidth: true, size: 'small', children: [_jsx(InputLabel, { id: 'campaign-filter-label', children: "Campa\u00F1a" }), _jsxs(Select, { labelId: 'campaign-filter-label', label: 'Campa\u00F1a', value: filters.campaign, onChange: e => setFilters({ ...filters, campaign: String(e.target.value) }), children: [_jsx(MenuItem, { value: '', children: "Todas" }), (campaigns.data?.items || []).map((campaign) => (_jsx(MenuItem, { value: campaign, children: campaign }, campaign)))] })] }) }), _jsx(Grid, { item: true, xs: 12, md: 2, children: _jsxs(FormControl, { fullWidth: true, size: 'small', children: [_jsx(InputLabel, { id: 'status-filter-label', children: "Estado" }), _jsxs(Select, { labelId: 'status-filter-label', label: 'Estado', value: filters.status, onChange: e => setFilters({ ...filters, status: String(e.target.value) }), children: [_jsx(MenuItem, { value: '', children: "Todos" }), ['Disponible', 'En llamada', 'WrapUp', 'Pausa', 'Break', 'Baño', 'Capacitación', 'Soporte', 'Consulta supervisor', 'Refrigerio', 'Reunión', 'Back Office', 'Desconectado', 'Desconocido']
                                                                .map(state => _jsx(MenuItem, { value: state, children: state }, state))] })] }) }), _jsx(Grid, { item: true, xs: 12, md: 2, children: _jsxs(FormControl, { fullWidth: true, size: 'small', children: [_jsx(InputLabel, { id: 'pause-filter-label', children: "Subestado" }), _jsxs(Select, { labelId: 'pause-filter-label', label: 'Subestado', value: filters.pauseCode, onChange: e => setFilters({ ...filters, pauseCode: String(e.target.value) }), children: [_jsx(MenuItem, { value: '', children: "Todos" }), (pauseCodes.data?.items || []).map((item) => (_jsxs(MenuItem, { value: item.pauseCode, children: [item.pauseCode, " - ", item.visibleName] }, item.pauseCode)))] })] }) }), _jsx(Grid, { item: true, xs: 12, md: 5, children: _jsx(TextField, { fullWidth: true, size: 'small', label: 'Buscar agente, nombre o tel\u00E9fono', value: filters.search, onChange: e => setFilters({ ...filters, search: e.target.value }) }) }), _jsx(Grid, { item: true, xs: 12, md: 1, children: _jsx(Button, { fullWidth: true, variant: 'outlined', onClick: () => setFilters({ campaign: '', status: '', pauseCode: '', search: '' }), children: "Limpiar" }) })] })] }) }) }), _jsx(Card, { children: _jsx(CardContent, { children: _jsxs(Stack, { gap: 1, children: [_jsxs(Typography, { variant: 'h6', children: ["Agentes realtime (", agents.data?.total ?? rows.length, ")"] }), _jsx(Box, { sx: { maxHeight: '62vh', overflow: 'auto' }, children: _jsxs(Table, { size: 'small', stickyHeader: true, children: [_jsx(TableHead, { children: _jsxs(TableRow, { children: [_jsx(TableCell, { children: "Estado" }), _jsx(TableCell, { children: "Subestado" }), _jsx(TableCell, { children: "Agente" }), _jsx(TableCell, { children: "Nombre" }), _jsx(TableCell, { children: "Anexo" }), _jsx(TableCell, { children: "Campa\u00F1a" }), _jsx(TableCell, { children: "Lead/Tel\u00E9fono" }), _jsx(TableCell, { children: "Conectado a" }), _jsx(TableCell, { children: "Tiempo estado" }), _jsx(TableCell, { children: "\u00DAltima llamada" }), _jsx(TableCell, { children: "Gestionadas hoy" })] }) }), _jsxs(TableBody, { children: [rows.map((row) => (_jsxs(TableRow, { children: [_jsx(TableCell, { children: _jsx(Chip, { size: 'small', label: row.visibleStatus || 'Desconocido', sx: { backgroundColor: statusColor(row.visibleStatus), color: '#fff', fontWeight: 600 } }) }), _jsx(TableCell, { children: row.subStatus || '-' }), _jsx(TableCell, { children: row.agentUser || '-' }), _jsx(TableCell, { children: row.agentName || row.agentUser || '-' }), _jsx(TableCell, { children: row.extension || '-' }), _jsx(TableCell, { children: row.campaignId || '-' }), _jsx(TableCell, { children: row.currentPhone || '-' }), _jsx(TableCell, { children: row.connectedTo || '-' }), _jsx(TableCell, { children: formatSeconds(row.stateSeconds) }), _jsx(TableCell, { children: formatDate(row.lastCallTime) }), _jsx(TableCell, { children: row.completedToday ?? 0 })] }, row.agentUser))), rows.length === 0 && !agents.isLoading && (_jsx(TableRow, { children: _jsx(TableCell, { colSpan: 11, children: "Sin datos para los filtros actuales." }) }))] })] }) })] }) }) })] }) }));
}
