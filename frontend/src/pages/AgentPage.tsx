import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  Container,
  Divider,
  FormControlLabel,
  Menu,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  agentLogout,
  agentSessionHeartbeat,
  connectVicidialCampaign,
  dialNext,
  connectVicidialPhone,
  disconnectVicidialPhone,
  getActiveLead,
  getAgentProfile,
  getContext,
  hangupCall,
  manual2Dispositions,
  manual2LookupContact,
  manual2SaveGestion,
  manual2Subtipificaciones,
  manualDial,
  getVicidialCampaigns,
  getVicidialStatus,
  getCampaignDetails,
  pauseAction,
  previewAction,
  retryInteraction,
  saveInteraction,
  updateAgentProfilePass,
} from '../api/sdk';
import AuthStepper from '../components/ui/AuthStepper';
import ViciCard from '../components/ui/ViciCard';

const heartbeatIntervalMs = 15000;
const sessionStorageKey = 'agent.crm.session.id';

function ensureBrowserSessionId(): string {
  const existing = sessionStorage.getItem(sessionStorageKey);
  if (existing) {
    return existing;
  }
  const generated = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.round(Math.random() * 1_000_000)}`;
  sessionStorage.setItem(sessionStorageKey, generated);
  return generated;
}

export default function AgentPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const env = (import.meta as any).env || {};
  const defaultPhoneCode = ((import.meta as any).env?.VITE_DEFAULT_PHONE_CODE || '1').toString();
  const backendBaseUrl = ((env.VITE_BACKEND_BASE_URL || env.VITE_API_BASE_URL || 'http://localhost:8080') as string).replace(/\/$/, '');
  const browserSessionIdRef = useRef<string>(ensureBrowserSessionId());
  const heartbeatInFlightRef = useRef(false);
  const exitSignalSentRef = useRef(false);
  const lastKnownStatusRef = useRef('');
  const [dispo, setDispo] = useState('');
  const [notes, setNotes] = useState('');
  const [manual2Form, setManual2Form] = useState({
    nombres: '',
    apellidos: '',
    documento: '',
    origen: 'MANUAL2',
    comentario: '',
    subtipificacion: '',
  });
  const hydratedManual2PhoneRef = useRef('');
  const [phoneLogin, setPhoneLogin] = useState('');
  const [campaign, setCampaign] = useState('');
  const [remember, setRemember] = useState(true);
  const [profileMenuAnchor, setProfileMenuAnchor] = useState<null | HTMLElement>(null);
  const [agentPass, setAgentPass] = useState('');
  const [manualNumber, setManualNumber] = useState('');
  const [manualCode, setManualCode] = useState(defaultPhoneCode);
  const [dialingBanner, setDialingBanner] = useState(false);

  const profile = useQuery({ queryKey: ['agent-profile'], queryFn: getAgentProfile });
  const status = useQuery({ queryKey: ['status'], queryFn: getVicidialStatus, refetchInterval: 10000 });
  const campaignsQuery = useQuery({
    queryKey: ['campaigns'],
    queryFn: getVicidialCampaigns,
    enabled: Boolean(status.data?.phoneConnected),
  });

  const active = useQuery({
    queryKey: ['active-lead'],
    queryFn: getActiveLead,
    enabled: Boolean(status.data?.campaign),
    refetchInterval: status.data?.campaign
      ? (status.data?.mode === 'manual' ? 2500 : 7000)
      : false,
    refetchIntervalInBackground: true,
  });

  const leadId = Number(active.data?.lead?.leadId || active.data?.leadId || 0) || undefined;


  const selectedCampaign = status.data?.campaign || campaign;
  const campaignDetails = useQuery({
    queryKey: ['campaign-details', selectedCampaign],
    queryFn: () => getCampaignDetails(selectedCampaign || ""),
    enabled: Boolean(selectedCampaign),
  });

  const context = useQuery({
    queryKey: ['context', leadId],
    queryFn: () => getContext({ leadId }),
    enabled: Boolean(status.data?.campaign),
    refetchInterval: status.data?.campaign
      ? (status.data?.mode === 'manual' ? 2500 : 7000)
      : false,
    refetchIntervalInBackground: true,
  });
  const c: any = context.data;

  const mode = (campaignDetails.data?.campaign?.mode || context.data?.mode || 'predictive') as 'predictive' | 'manual';
  const isManualFlow = mode === 'manual';
  const runtimeCampaignForManual2 = status.data?.campaign || campaign || '';
  const isManual2ByCampaign = runtimeCampaignForManual2.toUpperCase() === 'MANUAL2';
  const runtimeLeadPhone = c?.lead?.phoneNumber || active.data?.lead?.phoneNumber || manualNumber || '';
  const normalizedRuntimeLeadPhone = runtimeLeadPhone.replace(/[^0-9]/g, '');

  const manual2DispositionsQuery = useQuery({
    queryKey: ['manual2-dispositions', runtimeCampaignForManual2],
    queryFn: () => manual2Dispositions(runtimeCampaignForManual2 || 'Manual2'),
    enabled: Boolean(runtimeCampaignForManual2) && isManual2ByCampaign,
    staleTime: 30000,
  });

  const manual2SubtipificacionesQuery = useQuery({
    queryKey: ['manual2-subtipificaciones', runtimeCampaignForManual2, dispo],
    queryFn: () => manual2Subtipificaciones(runtimeCampaignForManual2 || 'Manual2', dispo || undefined),
    enabled: Boolean(runtimeCampaignForManual2) && isManual2ByCampaign,
    staleTime: 30000,
  });

  const manual2ContactQuery = useQuery({
    queryKey: ['manual2-contact', normalizedRuntimeLeadPhone],
    queryFn: () => manual2LookupContact(normalizedRuntimeLeadPhone),
    enabled: isManual2ByCampaign && normalizedRuntimeLeadPhone.length >= 6,
  });

  const connectPhone = useMutation({
    mutationFn: connectVicidialPhone,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['status'] });
      qc.invalidateQueries({ queryKey: ['campaigns'] });
    },
  });

  const connectCampaign = useMutation({
    mutationFn: connectVicidialCampaign,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['status'] });
      qc.invalidateQueries({ queryKey: ['agent-profile'] });
    },
  });

  const disconnect = useMutation({
    mutationFn: disconnectVicidialPhone,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['status'] });
      qc.invalidateQueries({ queryKey: ['campaigns'] });
      qc.invalidateQueries({ queryKey: ['agent-profile'] });
    },
  });

  const updatePass = useMutation({
    mutationFn: updateAgentProfilePass,
    onSuccess: () => {
      setAgentPass('');
      qc.invalidateQueries({ queryKey: ['agent-profile'] });
    },
  });

  const save = useMutation({ mutationFn: saveInteraction });
  const saveManual2GestionMut = useMutation({
    mutationFn: manual2SaveGestion,
    onSuccess: () => {
      setDispo('');
      setNotes('');
      setManual2Form({
        nombres: '',
        apellidos: '',
        documento: '',
        origen: 'MANUAL2',
        comentario: '',
        subtipificacion: '',
      });
      hydratedManual2PhoneRef.current = '';
      qc.invalidateQueries({ queryKey: ['manual2-contact'] });
      qc.invalidateQueries({ queryKey: ['manual2-history'] });
      qc.invalidateQueries({ queryKey: ['context'] });
    },
  });

  const manualNext = useMutation({
    mutationFn: dialNext,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['active-lead'] });
      qc.invalidateQueries({ queryKey: ['context'] });
    },
  });

  const manualDialMut = useMutation({
    mutationFn: manualDial,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['active-lead'] });
      qc.invalidateQueries({ queryKey: ['context'] });
    },
  });

  const logoutMut = useMutation({ mutationFn: agentLogout });

  const hangupMut = useMutation({
    mutationFn: hangupCall,
    onSuccess: () => {
      setDialingBanner(false);
      qc.invalidateQueries({ queryKey: ['active-lead'] });
      qc.invalidateQueries({ queryKey: ['context'] });
      qc.invalidateQueries({ queryKey: ['status'] });
    },
  });

  const logout = async () => {
    exitSignalSentRef.current = true;
    try {
      await logoutMut.mutateAsync({ reason: 'USER_LOGOUT' });
    } catch {
      // silent: local logout must continue even if backend cleanup fails
    } finally {
      localStorage.removeItem('token');
      localStorage.removeItem('role');
      sessionStorage.removeItem(sessionStorageKey);
      navigate('/login', { replace: true });
    }
  };

  const retry = useMutation({ mutationFn: retryInteraction });


  useEffect(() => {
    if (active.data?.code === 'VICIDIAL_DIALING') {
      setDialingBanner(true);
    }
  }, [active.data?.code]);

  useEffect(() => {
    if (!dialingBanner) return;
    let attempt = 0;
    const timer = setInterval(async () => {
      attempt += 1;
      await qc.invalidateQueries({ queryKey: ['active-lead'] });
      await qc.invalidateQueries({ queryKey: ['context'] });
      const latest = await getActiveLead();
      if (latest?.ok || latest?.code !== 'VICIDIAL_DIALING' || attempt >= 10) {
        setDialingBanner(false);
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [dialingBanner, qc]);

  useEffect(() => {
    lastKnownStatusRef.current = String(
      c?.runtime?.agentStatus || active.data?.lead?.agentStatus || status.data?.status || ''
    ).toUpperCase();
  }, [c?.runtime?.agentStatus, active.data?.lead?.agentStatus, status.data?.status]);

  useEffect(() => {
    if (!localStorage.getItem('token')) {
      return;
    }
    let cancelled = false;
    const tick = async () => {
      if (cancelled || heartbeatInFlightRef.current) {
        return;
      }
      heartbeatInFlightRef.current = true;
      try {
        await agentSessionHeartbeat({
          sessionId: browserSessionIdRef.current,
          lastKnownVicidialStatus: lastKnownStatusRef.current,
        });
      } catch {
        // silent: next interval retries
      } finally {
        heartbeatInFlightRef.current = false;
      }
    };

    tick();
    const timer = window.setInterval(tick, heartbeatIntervalMs);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  useEffect(() => {
    const notifyBrowserExit = () => {
      if (exitSignalSentRef.current) {
        return;
      }
      exitSignalSentRef.current = true;
      const query = new URLSearchParams({
        sessionId: browserSessionIdRef.current,
        agentUser: profile.data?.agentUser || '',
        reason: 'BROWSER_EXIT',
      }).toString();
      const endpoint = `${backendBaseUrl}/api/agent/session/browser-exit?${query}`;
      const payload = JSON.stringify({
        sessionId: browserSessionIdRef.current,
        agentUser: profile.data?.agentUser,
        reason: 'BROWSER_EXIT',
      });
      let delivered = false;
      try {
        if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
          delivered = navigator.sendBeacon(endpoint);
        }
      } catch {
        delivered = false;
      }
      if (!delivered) {
        const token = localStorage.getItem('token');
        fetch(endpoint, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          body: payload,
          keepalive: true,
        }).catch(() => {
          // best effort
        });
      }
    };

    window.addEventListener('pagehide', notifyBrowserExit);
    window.addEventListener('beforeunload', notifyBrowserExit);
    return () => {
      window.removeEventListener('pagehide', notifyBrowserExit);
      window.removeEventListener('beforeunload', notifyBrowserExit);
    };
  }, [backendBaseUrl, profile.data?.agentUser]);

  useEffect(() => {
    if (!isManual2ByCampaign) {
      hydratedManual2PhoneRef.current = '';
      return;
    }
    if (!normalizedRuntimeLeadPhone) {
      hydratedManual2PhoneRef.current = '';
      return;
    }
    if (hydratedManual2PhoneRef.current !== normalizedRuntimeLeadPhone) {
      hydratedManual2PhoneRef.current = normalizedRuntimeLeadPhone;
      setManual2Form({
        nombres: '',
        apellidos: '',
        documento: '',
        origen: 'MANUAL2',
        comentario: '',
        subtipificacion: '',
      });
    }
  }, [isManual2ByCampaign, normalizedRuntimeLeadPhone]);

  useEffect(() => {
    if (!isManual2ByCampaign || !manual2ContactQuery.data?.found || !manual2ContactQuery.data?.contacto) {
      return;
    }
    const contacto = manual2ContactQuery.data.contacto;
    setManual2Form((prev) => ({
      ...prev,
      nombres: prev.nombres || contacto.nombres || '',
      apellidos: prev.apellidos || contacto.apellidos || '',
      documento: prev.documento || contacto.documento || '',
      origen: prev.origen || contacto.origen || 'MANUAL2',
    }));
  }, [isManual2ByCampaign, manual2ContactQuery.data]);

  const phoneConnected = Boolean(status.data?.phoneConnected);
  const campaignConnected = Boolean(status.data?.campaign);
  const canType = phoneConnected && campaignConnected;
  const runtimeAgentStatus = String(c?.runtime?.agentStatus || active.data?.lead?.agentStatus || '').toUpperCase();
  const runtimeClassification = String(c?.runtime?.classification || active.data?.details?.classification || '').toUpperCase();
  const runtimeCallId = c?.runtime?.callId || active.data?.lead?.callId || c?.lead?.callId || manualDialMut.data?.callId || manualNext.data?.callId;
  const runtimeLeadId = c?.runtime?.leadId || active.data?.lead?.leadId || c?.lead?.leadId || leadId;
  const runtimeUniqueId = c?.runtime?.uniqueId || active.data?.lead?.uniqueId || c?.lead?.uniqueId;
  const runtimeChannel = c?.runtime?.channel || active.data?.lead?.channel || c?.lead?.channel;
  const dialingInProgress = active.data?.code === 'VICIDIAL_DIALING' || dialingBanner;
  const hasNoLeadSignal = active.data?.code === 'VICIDIAL_NO_ACTIVE_LEAD'
    || runtimeAgentStatus === 'READY'
    || runtimeAgentStatus === 'PAUSED'
    || runtimeClassification === 'READY'
    || runtimeClassification === 'AGENT_PAUSED'
    || runtimeClassification === 'NO_ACTIVE_LEAD'
    || runtimeClassification === 'NO_ACTIVE_CALL';
  const hasStrongCallEvidence = Boolean(runtimeUniqueId || runtimeChannel);
  const hasWeakCallEvidence = Boolean(runtimeCallId || runtimeLeadId);
  const incallWithEvidence = runtimeAgentStatus === 'INCALL'
    && (hasStrongCallEvidence || (hasWeakCallEvidence && !hasNoLeadSignal));
  const callStillActive = dialingInProgress || incallWithEvidence;
  const canFinalizeDisposition = canType && Boolean(dispo) && !callStillActive;
  const currentLeadPhone = c?.lead?.phoneNumber || active.data?.lead?.phoneNumber || manualNumber || '';
  const currentLeadDni = c?.lead?.dni || active.data?.lead?.dni || '';
  const currentLeadCampaign = c?.lead?.campaign || status.data?.campaign || campaign || '';
  const isManual2Campaign = currentLeadCampaign.toUpperCase() === 'MANUAL2';
  const manual2DispoItems = (manual2DispositionsQuery.data?.items || []).map((item:any) => ({
    status: String(item?.status || ''),
    label: String(item?.label || item?.status || ''),
  })).filter((item:{status:string;label:string}) => Boolean(item.status));
  const fallbackDispoItems = (c?.dispoOptions || []).map((value:string) => ({ status: value, label: value }));
  const dispoItems: Array<{status:string;label:string}> = isManual2Campaign
    ? (manual2DispoItems.length ? manual2DispoItems : fallbackDispoItems)
    : fallbackDispoItems;
  const subtipItems: Array<{codigo:string;nombre:string}> = (manual2SubtipificacionesQuery.data?.items || [])
    .map((item:any) => ({
      codigo: String(item?.codigo || ''),
      nombre: String(item?.nombre || item?.codigo || ''),
    }))
    .filter((item:{codigo:string;nombre:string}) => Boolean(item.codigo));
  useEffect(() => {
    if (!isManual2Campaign || !manual2Form.subtipificacion) {
      return;
    }
    if (!subtipItems.some((item) => item.codigo === manual2Form.subtipificacion)) {
      setManual2Form((prev) => ({ ...prev, subtipificacion: '' }));
    }
  }, [isManual2Campaign, manual2Form.subtipificacion, subtipItems]);
  const canHangup = campaignConnected && (runtimeAgentStatus === 'INCALL' || Boolean(runtimeCallId));
  const availableCampaigns = campaignsQuery.data?.campaigns || [];

  useEffect(() => {
    if (!phoneLogin && profile.data?.lastPhoneLogin) {
      setPhoneLogin(profile.data.lastPhoneLogin);
    }
  }, [phoneLogin, profile.data?.lastPhoneLogin]);

  const topInfo = useMemo(
    () => ({
      phone: status.data?.phoneLogin || profile.data?.lastPhoneLogin || '-',
      campaign: status.data?.campaign || profile.data?.lastCampaign || '-',
    }),
    [status.data, profile.data],
  );

  const activeStep = !phoneConnected ? 1 : !campaignConnected ? 2 : 3;

  return (
    <Container maxWidth='lg' sx={{ py: { xs: 2, sm: 3 } }}>
      <Stack gap={2}>
        <Stack
          direction={{ xs: 'column', md: 'row' }}
          justifyContent='space-between'
          alignItems={{ xs: 'flex-start', md: 'center' }}
          gap={1.5}
        >
          <Stack gap={1}>
            <Typography variant='h4' fontWeight={800}>
              Atención {mode}
            </Typography>
            <AuthStepper activeStep={activeStep} labels={['Extensión', 'Campaña', 'Tipificación']} />
          </Stack>

          <Stack direction='row' gap={1} flexWrap='wrap' alignItems='center'>
            <Chip label={`Usuario: ${profile.data?.agentUser || '-'}`} size='small' variant='outlined' />
            <Chip label={`Anexo: ${topInfo.phone}`} size='small' variant='outlined' />
            <Chip
              label={phoneConnected ? 'Phone: conectado' : 'Phone: desconectado'}
              size='small'
              color={phoneConnected ? 'success' : 'default'}
            />
            <Chip label={`Campaña: ${topInfo.campaign}`} size='small' variant='outlined' />
            <Button variant='outlined' onClick={(e) => setProfileMenuAnchor(e.currentTarget)}>
              Cuenta
            </Button>
          </Stack>
        </Stack>

        <Menu anchorEl={profileMenuAnchor} open={Boolean(profileMenuAnchor)} onClose={() => setProfileMenuAnchor(null)}>
          <Box sx={{ p: 2, minWidth: 320 }}>
            <Typography variant='subtitle2'>Perfil</Typography>
            <Typography variant='body2' color='text.secondary'>
              Usuario: {profile.data?.agentUser || '-'}
            </Typography>
            <Typography variant='body2' color='text.secondary'>
              Anexo: {topInfo.phone}
            </Typography>
            <Typography variant='body2' color='text.secondary' sx={{ mb: 1 }}>
              Agent pass: {profile.data?.hasAgentPass ? '••••••••' : 'No configurado'}
            </Typography>

            <TextField
              size='small'
              fullWidth
              type='password'
              label='Nuevo agent_pass'
              value={agentPass}
              onChange={(e) => setAgentPass(e.target.value)}
            />
            <Button
              sx={{ mt: 1 }}
              variant='contained'
              fullWidth
              disabled={!agentPass}
              onClick={() => updatePass.mutate({ agentPass })}
            >
              Guardar
            </Button>

            <Divider sx={{ my: 1 }} />

            <MenuItem onClick={() => setProfileMenuAnchor(null)}>Perfil</MenuItem>
            <MenuItem
              onClick={() => {
                setProfileMenuAnchor(null);
                logout();
              }}
            >
              Cerrar sesión
            </MenuItem>
          </Box>
        </Menu>

        <ViciCard title='Paso 1 · Conectar anexo' subtitle='Conecta tu extensión antes de seleccionar campaña.'>
          <Stack gap={1.5}>
            <Stack direction={{ xs: 'column', sm: 'row' }} gap={1}>
              <TextField
                label='Phone Login (anexo)'
                value={phoneLogin}
                onChange={(e) => setPhoneLogin(e.target.value)}
                fullWidth
                helperText='phone_pass se genera automáticamente como anexo_{phone_login}'
              />
              <Button
                variant='contained'
                disabled={!phoneLogin || phoneConnected}
                onClick={() => connectPhone.mutate({ phoneLogin })}
              >
                Conectar anexo
              </Button>
              <Button color='warning' variant='outlined' onClick={() => disconnect.mutate()} disabled={!phoneConnected}>
                Desconectar
              </Button>
            </Stack>

            {connectPhone.error && <Alert severity='error'>No fue posible conectar anexo.</Alert>}
            {connectPhone.data?.raw && <Alert severity='success'>Anexo conectado correctamente.</Alert>}
            {campaignsQuery.isError && <Alert severity='error'>No fue posible cargar campañas.</Alert>}
          </Stack>
        </ViciCard>

        <ViciCard title='Paso 2 · Seleccionar campaña' subtitle='Elige una campaña disponible para continuar.'>
          <Stack gap={1.5}>
            {!profile.data?.hasAgentPass && (
              <Alert severity='warning'>Debes configurar tu agent_pass en Perfil antes de conectar campaña.</Alert>
            )}

            <Stack direction={{ xs: 'column', md: 'row' }} gap={1} alignItems={{ xs: 'stretch', md: 'center' }}>
              <TextField
                select
                label='Campaña'
                value={campaign}
                onChange={(e) => setCampaign(e.target.value)}
                fullWidth
                disabled={!phoneConnected || !availableCampaigns.length || campaignConnected}
              >
                {availableCampaigns.map((d: string) => (
                  <MenuItem key={d} value={d}>
                    {d}
                  </MenuItem>
                ))}
              </TextField>

              <FormControlLabel
                control={<Checkbox checked={remember} onChange={(e) => setRemember(e.target.checked)} />}
                label='Recordar'
              />

              <Button
                variant='contained'
                disabled={!profile.data?.hasAgentPass || !campaign || !phoneConnected || campaignConnected}
                onClick={() =>
                  connectCampaign.mutate({
                    campaignId: campaign,
                    rememberCredentials: remember,
                  })
                }
              >
                Conectar campaña
              </Button>
            </Stack>

            {connectCampaign.error && <Alert severity='error'>No fue posible conectar campaña.</Alert>}
          </Stack>
        </ViciCard>

        <ViciCard title='Paso 3 · Tipificación' subtitle='Guarda la gestión del lead activo.'>
          <Stack gap={1.5}>
            {!canType && (
              <Alert severity='info'>Debes conectar anexo y campaña para habilitar disposición, notas y guardado.</Alert>
            )}

            <Divider />

            <Box sx={{ p: 1.5, borderRadius: 2, bgcolor: 'background.default', overflowX: 'auto' }}>
              <pre>{JSON.stringify(c?.lead, null, 2)}</pre>
            </Box>

            <Box sx={{ p: 1.5, borderRadius: 2, bgcolor: 'background.default', overflowX: 'auto' }}>
              <pre>{JSON.stringify(c?.customer, null, 2)}</pre>
            </Box>

            {isManual2Campaign && (
              <Stack gap={1} sx={{ p: 1.5, borderRadius: 2, bgcolor: 'grey.100' }}>
                <Typography variant='subtitle2' fontWeight={700}>Formulario Manual2</Typography>
                {normalizedRuntimeLeadPhone ? (
                  <Alert severity={manual2ContactQuery.data?.found ? 'success' : 'info'}>
                    {manual2ContactQuery.data?.found
                      ? 'Telefono encontrado en BD del sistema. Puedes actualizar datos y guardar gestion.'
                      : 'Telefono no registrado. Completa datos y se creara contacto/referido al guardar.'}
                  </Alert>
                ) : (
                  <Alert severity='info'>Aun no hay telefono de llamada para precargar formulario Manual2.</Alert>
                )}
                {manual2ContactQuery.data?.totalGestiones > 0 && (
                  <Alert severity='info'>
                    Historial detectado para este telefono: {manual2ContactQuery.data?.totalGestiones} gestiones.
                  </Alert>
                )}
                <Stack direction={{ xs: 'column', md: 'row' }} gap={1}>
                  <TextField
                    label='Nombres'
                    value={manual2Form.nombres}
                    onChange={(e) => setManual2Form((prev) => ({ ...prev, nombres: e.target.value }))}
                    fullWidth
                  />
                  <TextField
                    label='Apellidos'
                    value={manual2Form.apellidos}
                    onChange={(e) => setManual2Form((prev) => ({ ...prev, apellidos: e.target.value }))}
                    fullWidth
                  />
                  <TextField
                    label='Documento'
                    value={manual2Form.documento}
                    onChange={(e) => setManual2Form((prev) => ({ ...prev, documento: e.target.value }))}
                    fullWidth
                  />
                </Stack>
                <TextField
                  label='Comentario formulario'
                  multiline
                  minRows={2}
                  value={manual2Form.comentario}
                  onChange={(e) => setManual2Form((prev) => ({ ...prev, comentario: e.target.value }))}
                />
                <Stack direction={{ xs: 'column', md: 'row' }} gap={1}>
                  <TextField
                    label='Origen'
                    value={manual2Form.origen}
                    onChange={(e) => setManual2Form((prev) => ({ ...prev, origen: e.target.value }))}
                    fullWidth
                  />
                  <TextField
                    select
                    label='Subtipificacion'
                    value={manual2Form.subtipificacion}
                    onChange={(e) => setManual2Form((prev) => ({ ...prev, subtipificacion: e.target.value }))}
                    fullWidth
                  >
                    <MenuItem value=''>Sin subtipificacion</MenuItem>
                    {subtipItems.map((item) => (
                      <MenuItem key={item.codigo} value={item.codigo}>
                        {item.nombre}
                      </MenuItem>
                    ))}
                  </TextField>
                </Stack>
              </Stack>
            )}

            <TextField
              select
              label='Disposición'
              value={dispo}
              onChange={(e) => setDispo(e.target.value)}
              disabled={!canType}
            >
              {dispoItems.map((d) => (
                <MenuItem key={d.status} value={d.status}>
                  {d.label}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              label='Notas'
              multiline
              minRows={3}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              disabled={!canType}
            />

            <Button
              variant='contained'
              disabled={!canFinalizeDisposition || save.isPending || saveManual2GestionMut.isPending}
              onClick={() => {
                if (isManual2Campaign) {
                  const recordingFromHangup = String(hangupMut.data?.details?.recordingFilename || '');
                  const fallbackRecordingFromRuntime = String(c?.runtime?.details?.recordingFilename || '');
                  const resolvedRecording = recordingFromHangup || fallbackRecordingFromRuntime;
                  saveManual2GestionMut.mutate({
                    campaignId: currentLeadCampaign || 'Manual2',
                    phoneNumber: normalizedRuntimeLeadPhone || currentLeadPhone,
                    nombres: manual2Form.nombres,
                    apellidos: manual2Form.apellidos,
                    documento: manual2Form.documento,
                    origen: manual2Form.origen,
                    comentario: manual2Form.comentario,
                    tipificacion: dispo,
                    disposicion: dispo,
                    subtipificacion: manual2Form.subtipificacion || null,
                    observaciones: notes,
                    modoLlamada: mode,
                    leadId: leadId || null,
                    callId: runtimeCallId || null,
                    uniqueId: c?.runtime?.uniqueId || c?.lead?.uniqueId || null,
                    nombreAudio: resolvedRecording || null,
                    duracion: Number(c?.runtime?.details?.liveCallSeconds || 0) || null,
                  });
                  return;
                }
                save.mutate({
                  mode,
                  leadId,
                  phoneNumber: currentLeadPhone,
                  campaign: currentLeadCampaign,
                  dni: currentLeadDni,
                  dispo,
                  notes,
                  extra: {},
                });
              }}
            >
              Guardar gestión
            </Button>

            {callStillActive && canType && (
              <Alert severity='info'>
                La tipificación final se habilita cuando la llamada termine. Puedes seguir editando disposición y notas como borrador.
              </Alert>
            )}

            {save.isError && (save.error as any)?.response?.data?.code === 'VICIDIAL_CALL_STILL_ACTIVE' && (
              <Alert severity='warning'>
                La llamada sigue activa. Cuelga primero para confirmar la tipificación final.
              </Alert>
            )}

            {saveManual2GestionMut.isError && (saveManual2GestionMut.error as any)?.response?.data?.code === 'VICIDIAL_CALL_STILL_ACTIVE' && (
              <Alert severity='warning'>
                La llamada sigue activa. Finaliza la llamada antes de guardar la gestiÃ³n Manual2.
              </Alert>
            )}

            {saveManual2GestionMut.data?.ok && (
              <Alert severity='success'>
                GestiÃ³n Manual2 guardada correctamente en la BD del sistema.
              </Alert>
            )}

            {saveManual2GestionMut.isError && (
              <Alert severity='error'>
                No se pudo guardar la gestiÃ³n final de Manual2.
              </Alert>
            )}

            {save.data?.syncStatus !== 'SYNCED' && save.data?.id && (
              <Button onClick={() => retry.mutate(save.data.id)}>Reintentar</Button>
            )}

            {isManualFlow && (active.data?.code === 'VICIDIAL_NO_ACTIVE_LEAD' || !c?.lead) && (
              <Alert severity='info'>
                No hay lead activo. Presiona &quot;Siguiente / Dial Next&quot; para solicitar el próximo lead.
              </Alert>
            )}

            {isManualFlow && (
              <Stack gap={1} sx={{ p: 2, borderRadius: 2, bgcolor: 'grey.100' }}>
                <Typography variant='subtitle1' fontWeight={700}>
                  Marcación manual · Contact Center
                </Typography>

                <Stack direction={{ xs: 'column', md: 'row' }} gap={1}>
                  <Button
                    variant='contained'
                    onClick={() =>
                      manualNext.mutate({
                        campaignId: status.data?.campaign || campaign || '',
                      }, {
                        onSuccess: (resp:any) => {
                          if (resp?.result?.classification === 'DIALING_NO_LEAD_YET') {
                            setDialingBanner(true);
                          }
                        }
                      })
                    }
                    disabled={!campaignConnected || manualNext.isPending}
                  >
                    SIGUIENTE
                  </Button>

                  <TextField
                    label='Número a marcar'
                    value={manualNumber}
                    onChange={(e) => setManualNumber(e.target.value.replace(/[^0-9]/g, ''))}
                    size='small'
                  />

                  <TextField
                    label='Código país'
                    value={manualCode}
                    onChange={(e) => setManualCode(e.target.value.replace(/[^0-9]/g, ''))}
                    size='small'
                    sx={{ maxWidth: 140 }}
                  />

                  <Button
                    variant='contained'
                    color='secondary'
                    disabled={!campaignConnected || !manualNumber || manualDialMut.isPending}
                    onClick={() =>
                      manualDialMut.mutate({
                        campaignId: status.data?.campaign || campaign || '',
                        phoneNumber: manualNumber,
                        phoneCode: manualCode || defaultPhoneCode,
                        dialTimeout: 60,
                        dialPrefix: '9',
                        preview: 'NO',
                      })
                    }
                  >
                    MARCAR
                  </Button>
                </Stack>
              </Stack>
            )}

            {manualNext.data?.ok && !dialingBanner && <Alert severity='success'>Marcación manual solicitada correctamente.</Alert>}
            {manualNext.error && (manualNext.error as any)?.response?.data?.code === 'VICIDIAL_NO_LEADS' && (
              <Alert severity='warning'>No hay leads en hopper para esta campaña. No se reintentará automáticamente.</Alert>
            )}
            {dialingBanner && <Alert severity='info'>Marcando...</Alert>}
            {manualDialMut.isError && <Alert severity='error'>No fue posible ejecutar MANUAL DIAL.</Alert>}
            {manualDialMut.data?.ok && (
              <Alert severity='success'>Llamada solicitada. Call ID: {manualDialMut.data?.callId || 'N/D'}.</Alert>
            )}
            {hangupMut.data?.ok && <Alert severity='success'>Se envió colgado de llamada al backend/Vicidial.</Alert>}
            {hangupMut.data && !hangupMut.data.ok && (
              <Alert severity='info'>No se detectó llamada activa para colgar.</Alert>
            )}
            {hangupMut.isError && <Alert severity='error'>No fue posible ejecutar colgado de llamada.</Alert>}

            {isManualFlow && (
              <Stack direction={{ xs: 'column', sm: 'row' }} gap={1}>
                <Button onClick={() => previewAction({ leadId, campaign: c?.lead?.campaign, action: 'DIALONLY' })}>
                  DIALONLY
                </Button>
                <Button onClick={() => previewAction({ leadId, campaign: c?.lead?.campaign, action: 'SKIP' })}>
                  SKIP
                </Button>
                <Button onClick={() => previewAction({ leadId, campaign: c?.lead?.campaign, action: 'FINISH' })}>
                  FINISH
                </Button>
              </Stack>
            )}

            <Stack direction={{ xs: 'column', sm: 'row' }} gap={1}>
              <Button variant='outlined' onClick={() => pauseAction({ action: 'PAUSE' })}>
                Pause
              </Button>
              <Button variant='outlined' onClick={() => pauseAction({ action: 'RESUME' })}>
                Resume
              </Button>
              <Button
                variant='contained'
                color='error'
                disabled={!canHangup || hangupMut.isPending}
                onClick={() =>
                  hangupMut.mutate({
                    campaignId: currentLeadCampaign,
                    dispo: isManual2Campaign ? undefined : (dispo || 'N'),
                    mode,
                    leadId,
                    phoneNumber: currentLeadPhone,
                    dni: currentLeadDni,
                    notes,
                    extra: {
                      source: 'agent-ui-hangup',
                      skipCrmInteractionSave: true,
                    },
                  })
                }
              >
                COLGAR
              </Button>
            </Stack>
          </Stack>
        </ViciCard>
      </Stack>
    </Container>
  );
}
