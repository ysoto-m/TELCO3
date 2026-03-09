import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Container,
  FormControl,
  Grid,
  InputLabel,
  LinearProgress,
  MenuItem,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  adminVicidialRealtimeAgents,
  adminVicidialRealtimeCampaigns,
  adminVicidialRealtimePauseCodes,
  adminVicidialRealtimeSummary
} from '../api/sdk';

type RealtimeFilters = {
  campaign: string;
  status: string;
  pauseCode: string;
  search: string;
};

const refreshMs = 5000;

const statusLegend = [
  { label: 'Disponible', color: '#2e7d32' },
  { label: 'En llamada', color: '#d32f2f' },
  { label: 'Pausa', color: '#ed6c02' },
  { label: 'Soporte', color: '#1565c0' },
  { label: 'WrapUp', color: '#6a1b9a' },
  { label: 'Desconectado', color: '#616161' }
];

function statusColor(status: string): string {
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

function formatSeconds(raw: number | null | undefined): string {
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

function formatDate(raw?: string | null): string {
  if (!raw) {
    return '-';
  }
  const parsed = new Date(raw);
  if (Number.isNaN(parsed.getTime())) {
    return '-';
  }
  return parsed.toLocaleString();
}

function cleanFilters(filters: RealtimeFilters) {
  return Object.fromEntries(
      Object.entries(filters)
          .map(([k, v]) => [k, v.trim()])
          .filter(([, v]) => Boolean(v))
  );
}

export default function AdminRealtimePage() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState<RealtimeFilters>({
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
  const kpiCards = useMemo(
      () => [
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
      ],
      [summary.data]
  );

  const rows = agents.data?.items || [];

  return (
      <Container maxWidth='xl' sx={{ py: 3 }}>
        <Stack gap={2}>
          <Stack direction='row' justifyContent='space-between' alignItems='center'>
            <Stack>
              <Typography variant='h4'>Admin Realtime</Typography>
              <Typography variant='body2' color='text.secondary'>
                Última actualización: {lastUpdate ? formatDate(lastUpdate) : '-'}
              </Typography>
            </Stack>
            <Stack direction='row' gap={1}>
              <Button variant='outlined' onClick={() => navigate('/admin')}>Volver Admin</Button>
              <Button variant='outlined' color='inherit' onClick={logout}>Cerrar sesión</Button>
            </Stack>
          </Stack>

          {(summary.isFetching || agents.isFetching) && <LinearProgress />}

          {(summary.isError || agents.isError) && (
              <Alert severity='error'>
                No se pudo cargar el realtime de Vicidial. Verifica settings y conectividad de BD Vicidial.
              </Alert>
          )}

          <Grid container spacing={2}>
            {kpiCards.map(([label, value]) => (
                <Grid item xs={12} sm={6} md={3} key={String(label)}>
                  <Card>
                    <CardContent>
                      <Typography variant='caption' color='text.secondary'>{String(label)}</Typography>
                      <Typography variant='h5'>{String(value)}</Typography>
                    </CardContent>
                  </Card>
                </Grid>
            ))}
          </Grid>

          <Card>
            <CardContent>
              <Stack gap={1}>
                <Typography variant='h6'>Leyenda</Typography>
                <Stack direction='row' gap={1} flexWrap='wrap'>
                  {statusLegend.map(item => (
                      <Chip
                          key={item.label}
                          label={item.label}
                          size='small'
                          sx={{ backgroundColor: item.color, color: '#fff', fontWeight: 600 }}
                      />
                  ))}
                </Stack>
              </Stack>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Stack gap={2}>
                <Typography variant='h6'>Filtros</Typography>
                <Grid container spacing={1}>
                  <Grid item xs={12} md={2}>
                    <FormControl fullWidth size='small'>
                      <InputLabel id='campaign-filter-label'>Campaña</InputLabel>
                      <Select
                          labelId='campaign-filter-label'
                          label='Campaña'
                          value={filters.campaign}
                          onChange={e => setFilters({ ...filters, campaign: String(e.target.value) })}
                      >
                        <MenuItem value=''>Todas</MenuItem>
                        {(campaigns.data?.items || []).map((campaign: string) => (
                            <MenuItem key={campaign} value={campaign}>{campaign}</MenuItem>
                        ))}
                      </Select>
                    </FormControl>
                  </Grid>
                  <Grid item xs={12} md={2}>
                    <FormControl fullWidth size='small'>
                      <InputLabel id='status-filter-label'>Estado</InputLabel>
                      <Select
                          labelId='status-filter-label'
                          label='Estado'
                          value={filters.status}
                          onChange={e => setFilters({ ...filters, status: String(e.target.value) })}
                      >
                        <MenuItem value=''>Todos</MenuItem>
                        {['Disponible', 'En llamada', 'WrapUp', 'Pausa', 'Break', 'Baño', 'Capacitación', 'Soporte', 'Consulta supervisor', 'Refrigerio', 'Reunión', 'Back Office', 'Desconectado', 'Desconocido']
                            .map(state => <MenuItem key={state} value={state}>{state}</MenuItem>)}
                      </Select>
                    </FormControl>
                  </Grid>
                  <Grid item xs={12} md={2}>
                    <FormControl fullWidth size='small'>
                      <InputLabel id='pause-filter-label'>Subestado</InputLabel>
                      <Select
                          labelId='pause-filter-label'
                          label='Subestado'
                          value={filters.pauseCode}
                          onChange={e => setFilters({ ...filters, pauseCode: String(e.target.value) })}
                      >
                        <MenuItem value=''>Todos</MenuItem>
                        {(pauseCodes.data?.items || []).map((item: any) => (
                            <MenuItem key={item.pauseCode} value={item.pauseCode}>
                              {item.pauseCode} - {item.visibleName}
                            </MenuItem>
                        ))}
                      </Select>
                    </FormControl>
                  </Grid>
                  <Grid item xs={12} md={5}>
                    <TextField
                        fullWidth
                        size='small'
                        label='Buscar agente, nombre o teléfono'
                        value={filters.search}
                        onChange={e => setFilters({ ...filters, search: e.target.value })}
                    />
                  </Grid>
                  <Grid item xs={12} md={1}>
                    <Button
                        fullWidth
                        variant='outlined'
                        onClick={() => setFilters({ campaign: '', status: '', pauseCode: '', search: '' })}
                    >
                      Limpiar
                    </Button>
                  </Grid>
                </Grid>
              </Stack>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Stack gap={1}>
                <Typography variant='h6'>Agentes realtime ({agents.data?.total ?? rows.length})</Typography>
                <Box sx={{ maxHeight: '62vh', overflow: 'auto' }}>
                  <Table size='small' stickyHeader>
                    <TableHead>
                      <TableRow>
                        <TableCell>Estado</TableCell>
                        <TableCell>Subestado</TableCell>
                        <TableCell>Agente</TableCell>
                        <TableCell>Nombre</TableCell>
                        <TableCell>Anexo</TableCell>
                        <TableCell>Campaña</TableCell>
                        <TableCell>Lead/Teléfono</TableCell>
                        <TableCell>Conectado a</TableCell>
                        <TableCell>Tiempo estado</TableCell>
                        <TableCell>Última llamada</TableCell>
                        <TableCell>Gestionadas hoy</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {rows.map((row: any) => (
                          <TableRow key={row.agentUser}>
                            <TableCell>
                              <Chip
                                  size='small'
                                  label={row.visibleStatus || 'Desconocido'}
                                  sx={{ backgroundColor: statusColor(row.visibleStatus), color: '#fff', fontWeight: 600 }}
                              />
                            </TableCell>
                            <TableCell>{row.subStatus || '-'}</TableCell>
                            <TableCell>{row.agentUser || '-'}</TableCell>
                            <TableCell>{row.agentName || row.agentUser || '-'}</TableCell>
                            <TableCell>{row.extension || '-'}</TableCell>
                            <TableCell>{row.campaignId || '-'}</TableCell>
                            <TableCell>{row.currentPhone || '-'}</TableCell>
                            <TableCell>{row.connectedTo || '-'}</TableCell>
                            <TableCell>{formatSeconds(row.stateSeconds)}</TableCell>
                            <TableCell>{formatDate(row.lastCallTime)}</TableCell>
                            <TableCell>{row.completedToday ?? 0}</TableCell>
                          </TableRow>
                      ))}
                      {rows.length === 0 && !agents.isLoading && (
                          <TableRow>
                            <TableCell colSpan={11}>Sin datos para los filtros actuales.</TableCell>
                          </TableRow>
                      )}
                    </TableBody>
                  </Table>
                </Box>
              </Stack>
            </CardContent>
          </Card>
        </Stack>
      </Container>
  );
}
