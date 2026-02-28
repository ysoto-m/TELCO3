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
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  connectVicidialCampaign,
  dialNext,
  connectVicidialPhone,
  disconnectVicidialPhone,
  getActiveLead,
  getAgentProfile,
  getContext,
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

export default function AgentPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [dispo, setDispo] = useState('');
  const [notes, setNotes] = useState('');
  const [phoneLogin, setPhoneLogin] = useState('');
  const [campaign, setCampaign] = useState('');
  const [remember, setRemember] = useState(true);
  const [profileMenuAnchor, setProfileMenuAnchor] = useState<null | HTMLElement>(null);
  const [agentPass, setAgentPass] = useState('');
  const [manualNumber, setManualNumber] = useState('');
  const [manualCode, setManualCode] = useState('51');
  const [dialingBanner, setDialingBanner] = useState(false);

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('role');
    sessionStorage.clear();
    navigate('/login', { replace: true });
  };

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
    refetchInterval: status.data?.mode === 'manual' ? false : 7000,
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
  });

  const mode = (campaignDetails.data?.campaign?.mode || context.data?.mode || 'predictive') as 'predictive' | 'manual';
  const isManualFlow = mode === 'manual';

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

  const retry = useMutation({ mutationFn: retryInteraction });

  const c: any = context.data;


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

  const phoneConnected = Boolean(status.data?.phoneConnected);
  const campaignConnected = Boolean(status.data?.campaign);
  const canType = phoneConnected && campaignConnected;
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

            <TextField
              select
              label='Disposición'
              value={dispo}
              onChange={(e) => setDispo(e.target.value)}
              disabled={!canType}
            >
              {(c?.dispoOptions || []).map((d: string) => (
                <MenuItem key={d} value={d}>
                  {d}
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
              disabled={!canType || !dispo}
              onClick={() =>
                save.mutate({
                  mode,
                  leadId,
                  phoneNumber: c?.lead?.phoneNumber || '',
                  campaign: c?.lead?.campaign || status.data?.campaign || '',
                  dni: c?.lead?.dni || '',
                  dispo,
                  notes,
                  extra: {},
                })
              }
            >
              Guardar gestión
            </Button>

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
                        phoneCode: manualCode || '51',
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
            </Stack>
          </Stack>
        </ViciCard>
      </Stack>
    </Container>
  );
}
