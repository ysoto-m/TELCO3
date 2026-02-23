import { Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Divider, FormControlLabel, Menu, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { connectVicidial, disconnectVicidial, getActiveLead, getAgentProfile, getAvailableCampaigns, getContext, getVicidialStatus, pauseAction, previewAction, retryInteraction, saveInteraction, updateAgentProfilePass } from '../api/sdk';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useMemo, useState } from 'react';

export default function AgentPage(){
 const [sp]=useSearchParams();
 const navigate = useNavigate();
 const qc = useQueryClient();
 const agentUser=sp.get('agent_user')||'';
 const mode=sp.get('mode')||'predictive';
 const [dispo,setDispo]=useState('');
 const [notes,setNotes]=useState('');
 const [phoneLogin,setPhoneLogin]=useState('');
 const [campaign,setCampaign]=useState('');
 const [remember,setRemember]=useState(true);
 const [campaignError,setCampaignError]=useState<string>('');
 const [profileMenuAnchor, setProfileMenuAnchor] = useState<null | HTMLElement>(null);
 const [agentPass, setAgentPass] = useState('');

 const logout = () => {
  localStorage.removeItem('token');
  localStorage.removeItem('role');
  sessionStorage.clear();
  navigate('/login', { replace: true });
 };

 const profile = useQuery({queryKey:['agent-profile'],queryFn:getAgentProfile});
 const status=useQuery({queryKey:['status'],queryFn:getVicidialStatus,refetchInterval:10000});

 const active=useQuery({queryKey:['a',agentUser],queryFn:()=>getActiveLead(agentUser),enabled:!sp.get('lead_id')&&!!agentUser});
 const leadId=Number(sp.get('lead_id')||active.data?.leadId||0)||undefined;
 const contextEnabled = !!agentUser;
 const context=useQuery({queryKey:['c',agentUser,leadId],queryFn:()=>getContext({agentUser,leadId,phone:sp.get('phone_number')||undefined,campaign:sp.get('campaign')||undefined,mode}),enabled:contextEnabled});

 const campaignsMutation=useMutation({mutationFn:getAvailableCampaigns,onSuccess:(data)=>{
  setCampaignError(data.error || '');
  if (!campaign && data.campaigns?.length) setCampaign(data.campaigns[0]);
 }});
 const connect=useMutation({mutationFn:connectVicidial,onSuccess:()=>{qc.invalidateQueries({queryKey:['status']});qc.invalidateQueries({queryKey:['agent-profile']});}});
 const disconnect=useMutation({mutationFn:disconnectVicidial,onSuccess:()=>{qc.invalidateQueries({queryKey:['status']});qc.invalidateQueries({queryKey:['agent-profile']});}});
 const updatePass=useMutation({mutationFn:updateAgentProfilePass,onSuccess:()=>{setAgentPass('');qc.invalidateQueries({queryKey:['agent-profile']});}});

 const save=useMutation({mutationFn:saveInteraction});
 const retry=useMutation({mutationFn:retryInteraction});
 const c:any=context.data;

 const connected = Boolean(status.data?.connected);
 const selectedCampaign = campaign || status.data?.connectedCampaign;
 const canType = connected && !!selectedCampaign;
 const availableCampaigns = campaignsMutation.data?.campaigns || [];

 const topInfo = useMemo(() => ({
  phone: status.data?.connectedPhoneLogin || profile.data?.lastPhoneLogin || '-',
  campaign: status.data?.connectedCampaign || profile.data?.lastCampaign || '-',
 }), [status.data, profile.data]);

 return <Container sx={{py:3}}><Stack gap={2}>
  <Stack direction='row' justifyContent='space-between' alignItems='center'>
    <Typography variant='h5'>Atención {mode}</Typography>
    <Stack direction='row' gap={1} alignItems='center'>
      <Chip label={`Anexo: ${topInfo.phone}`} size='small' variant='outlined' />
      <Chip label={connected ? 'Vicidial: conectado' : 'Vicidial: desconectado'} size='small' color={connected ? 'success' : 'default'} />
      <Chip label={`Campaña: ${topInfo.campaign}`} size='small' variant='outlined' />
      <Button variant='text' onClick={(e)=>setProfileMenuAnchor(e.currentTarget)}>Perfil</Button>
      <Button variant='outlined' color='inherit' onClick={logout}>Cerrar sesión</Button>
    </Stack>
  </Stack>

  <Menu anchorEl={profileMenuAnchor} open={Boolean(profileMenuAnchor)} onClose={()=>setProfileMenuAnchor(null)}>
    <Box sx={{p:2, minWidth:300}}>
      <Typography variant='subtitle2' sx={{mb:1}}>Actualizar agent_pass</Typography>
      <TextField size='small' fullWidth type='password' label='Nuevo agent_pass' value={agentPass} onChange={e=>setAgentPass(e.target.value)} />
      <Button sx={{mt:1}} variant='contained' fullWidth disabled={!agentPass} onClick={()=>updatePass.mutate({agentPass})}>Guardar</Button>
    </Box>
  </Menu>

  <Card><CardContent><Stack gap={1}>
    <Typography variant='h6'>Paso 1: Conectar Vicidial</Typography>
    {!profile.data?.hasAgentPass && <Alert severity='warning'>Debes configurar tu agent_pass en Perfil antes de conectar.</Alert>}
    <Stack direction='row' gap={1}>
      <TextField label='Agent User' value={agentUser} disabled fullWidth/>
      <TextField label='Phone Login (anexo)' value={phoneLogin} onChange={e=>setPhoneLogin(e.target.value)} fullWidth helperText='phone_pass se genera automáticamente como anexo_{phone_login}'/>
    </Stack>
    <Stack direction='row' gap={1} alignItems='center'>
      <Button variant='outlined' disabled={!phoneLogin} onClick={()=>campaignsMutation.mutate({phoneLogin})}>Cargar campañas</Button>
      <TextField select label='Campaña' value={campaign} onChange={e=>setCampaign(e.target.value)} fullWidth disabled={!availableCampaigns.length}>
        {availableCampaigns.map((d:string)=><MenuItem key={d} value={d}>{d}</MenuItem>)}
      </TextField>
    </Stack>
    <FormControlLabel control={<Checkbox checked={remember} onChange={e=>setRemember(e.target.checked)} />} label='Recordar credenciales' />
    {campaignError && <Alert severity='error'>No fue posible consultar campañas: {campaignError}</Alert>}
    {connect.data?.raw && <Alert severity={connect.data.ok?'success':'error'}>{connect.data.raw}</Alert>}
    <Stack direction='row' gap={1}>
      <Button variant='contained' disabled={!profile.data?.hasAgentPass || !phoneLogin || !campaign} onClick={()=>connect.mutate({phoneLogin,campaign,rememberCredentials:remember})}>Conectar a campaña</Button>
      <Button color='warning' onClick={()=>disconnect.mutate()}>Desconectar</Button>
    </Stack>
  </Stack></CardContent></Card>

  <Card><CardContent><Stack gap={1}>
    <Typography variant='h6'>Paso 2: Tipificación</Typography>
    {!canType && <Alert severity='info'>Debes estar conectado a Vicidial y seleccionar campaña para habilitar disposición, notas y guardado.</Alert>}
    <Divider />
    <pre>{JSON.stringify(c?.lead,null,2)}</pre>
    <pre>{JSON.stringify(c?.customer,null,2)}</pre>
    <TextField select label='Disposición' value={dispo} onChange={e=>setDispo(e.target.value)} disabled={!canType}>{(c?.dispoOptions||[]).map((d:string)=><MenuItem key={d} value={d}>{d}</MenuItem>)}</TextField>
    <TextField label='Notas' multiline minRows={3} value={notes} onChange={e=>setNotes(e.target.value)} disabled={!canType}/>
    <Button variant='contained' disabled={!canType || !dispo} onClick={()=>save.mutate({agentUser,mode,leadId,phoneNumber:c?.lead?.phoneNumber||'',campaign:c?.lead?.campaign||selectedCampaign||'',dni:c?.lead?.dni||'',dispo,notes,extra:{}})}>Guardar gestión</Button>
    {save.data?.syncStatus!=='SYNCED'&&save.data?.id&&<Button onClick={()=>retry.mutate(save.data.id)}>Reintentar</Button>}
    {mode==='preview'&&<Stack direction='row' gap={1}><Button onClick={()=>previewAction({agentUser,leadId,campaign:c?.lead?.campaign,action:'DIALONLY'})}>DIALONLY</Button><Button onClick={()=>previewAction({agentUser,leadId,campaign:c?.lead?.campaign,action:'SKIP'})}>SKIP</Button><Button onClick={()=>previewAction({agentUser,leadId,campaign:c?.lead?.campaign,action:'FINISH'})}>FINISH</Button></Stack>}
    <Stack direction='row' gap={1}><Button onClick={()=>pauseAction({agentUser,action:'PAUSE'})}>Pause</Button><Button onClick={()=>pauseAction({agentUser,action:'RESUME'})}>Resume</Button></Stack>
  </Stack></CardContent></Card>
 </Stack></Container>
}
