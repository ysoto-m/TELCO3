import { Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Divider, FormControlLabel, Menu, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { connectVicidialCampaign, connectVicidialPhone, disconnectVicidialPhone, getActiveLead, getAgentProfile, getContext, getVicidialCampaigns, getVicidialStatus, pauseAction, previewAction, retryInteraction, saveInteraction, updateAgentProfilePass } from '../api/sdk';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useEffect, useMemo, useState } from 'react';

export default function AgentPage(){
 const [sp]=useSearchParams();
 const navigate = useNavigate();
 const qc = useQueryClient();
 const mode=(sp.get('mode')||'predictive') as 'predictive' | 'manual';
 const [dispo,setDispo]=useState('');
 const [notes,setNotes]=useState('');
 const [phoneLogin,setPhoneLogin]=useState('');
 const [campaign,setCampaign]=useState('');
 const [remember,setRemember]=useState(true);
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
 const campaignsQuery=useQuery({queryKey:['campaigns'],queryFn:getVicidialCampaigns,enabled:Boolean(status.data?.phoneConnected)});

 const active=useQuery({queryKey:['active-lead'],queryFn:getActiveLead,enabled:Boolean(status.data?.campaign)});
 const leadId=Number(sp.get('lead_id')||active.data?.leadId||0)||undefined;
 const context=useQuery({queryKey:['context',leadId,mode],queryFn:()=>getContext({leadId,phone:sp.get('phone_number')||undefined,campaign:sp.get('campaign')||status.data?.campaign||undefined,mode}),enabled:Boolean(status.data?.campaign)});

 const connectPhone=useMutation({mutationFn:connectVicidialPhone,onSuccess:()=>{qc.invalidateQueries({queryKey:['status']});qc.invalidateQueries({queryKey:['campaigns']});}});
 const connectCampaign=useMutation({mutationFn:connectVicidialCampaign,onSuccess:()=>{qc.invalidateQueries({queryKey:['status']});qc.invalidateQueries({queryKey:['agent-profile']});}});
 const disconnect=useMutation({mutationFn:disconnectVicidialPhone,onSuccess:()=>{qc.invalidateQueries({queryKey:['status']});qc.invalidateQueries({queryKey:['campaigns']});qc.invalidateQueries({queryKey:['agent-profile']});}});
 const updatePass=useMutation({mutationFn:updateAgentProfilePass,onSuccess:()=>{setAgentPass('');qc.invalidateQueries({queryKey:['agent-profile']});}});

 const save=useMutation({mutationFn:saveInteraction});
 const retry=useMutation({mutationFn:retryInteraction});
 const c:any=context.data;

 const phoneConnected = Boolean(status.data?.phoneConnected);
 const campaignConnected = Boolean(status.data?.campaign);
 const canType = phoneConnected && campaignConnected;
 const availableCampaigns = campaignsQuery.data?.campaigns || [];


 useEffect(() => {
  if (!phoneLogin && profile.data?.lastPhoneLogin) {
    setPhoneLogin(profile.data.lastPhoneLogin);
  }
 }, [phoneLogin, profile.data?.lastPhoneLogin]);

 const topInfo = useMemo(() => ({
  phone: status.data?.phoneLogin || profile.data?.lastPhoneLogin || '-',
  campaign: status.data?.campaign || profile.data?.lastCampaign || '-',
 }), [status.data, profile.data]);

 return <Container sx={{py:3}}><Stack gap={2}>
  <Stack direction='row' justifyContent='space-between' alignItems='center'>
    <Typography variant='h5'>Atención {mode}</Typography>
    <Stack direction='row' gap={1} alignItems='center'>
      <Chip label={`Usuario: ${profile.data?.agentUser || '-'}`} size='small' variant='outlined' />
      <Chip label={`Anexo: ${topInfo.phone}`} size='small' variant='outlined' />
      <Chip label={phoneConnected ? 'Phone: conectado' : 'Phone: desconectado'} size='small' color={phoneConnected ? 'success' : 'default'} />
      <Chip label={`Campaña: ${topInfo.campaign}`} size='small' variant='outlined' />
      <Button variant='text' onClick={(e)=>setProfileMenuAnchor(e.currentTarget)}>Cuenta</Button>
    </Stack>
  </Stack>

  <Menu anchorEl={profileMenuAnchor} open={Boolean(profileMenuAnchor)} onClose={()=>setProfileMenuAnchor(null)}>
    <Box sx={{p:2, minWidth:320}}>
      <Typography variant='subtitle2'>Perfil</Typography>
      <Typography variant='body2' color='text.secondary'>Usuario: {profile.data?.agentUser || '-'}</Typography>
      <Typography variant='body2' color='text.secondary'>Anexo: {topInfo.phone}</Typography>
      <Typography variant='body2' color='text.secondary' sx={{mb:1}}>Agent pass: {profile.data?.hasAgentPass ? '••••••••' : 'No configurado'}</Typography>
      <TextField size='small' fullWidth type='password' label='Nuevo agent_pass' value={agentPass} onChange={e=>setAgentPass(e.target.value)} />
      <Button sx={{mt:1}} variant='contained' fullWidth disabled={!agentPass} onClick={()=>updatePass.mutate({agentPass})}>Guardar</Button>
      <Divider sx={{my:1}} />
      <MenuItem onClick={()=>{setProfileMenuAnchor(null);}}>Perfil</MenuItem>
      <MenuItem onClick={()=>{setProfileMenuAnchor(null);logout();}}>Cerrar sesión</MenuItem>
    </Box>
  </Menu>

  <Card><CardContent><Stack gap={1}>
    <Typography variant='h6'>Paso 1: Conectar anexo</Typography>
    <Stack direction='row' gap={1}>
      <TextField label='Phone Login (anexo)' value={phoneLogin} onChange={e=>setPhoneLogin(e.target.value)} fullWidth helperText='phone_pass se genera automáticamente como anexo_{phone_login}'/>
      <Button variant='contained' disabled={!phoneLogin || phoneConnected} onClick={()=>connectPhone.mutate({phoneLogin})}>Conectar anexo</Button>
      <Button color='warning' onClick={()=>disconnect.mutate()} disabled={!phoneConnected}>Desconectar</Button>
    </Stack>
    {connectPhone.error && <Alert severity='error'>No fue posible conectar anexo.</Alert>}
    {connectPhone.data?.raw && <Alert severity='success'>Anexo conectado correctamente.</Alert>}
    {campaignsQuery.isError && <Alert severity='error'>No fue posible cargar campañas.</Alert>}

    <Divider />
    <Typography variant='h6'>Paso 2: Seleccionar campaña</Typography>
    {!profile.data?.hasAgentPass && <Alert severity='warning'>Debes configurar tu agent_pass en Perfil antes de conectar campaña.</Alert>}
    <Stack direction='row' gap={1} alignItems='center'>
      <TextField select label='Campaña' value={campaign} onChange={e=>setCampaign(e.target.value)} fullWidth disabled={!phoneConnected || !availableCampaigns.length || campaignConnected}>
        {availableCampaigns.map((d:string)=><MenuItem key={d} value={d}>{d}</MenuItem>)}
      </TextField>
      <FormControlLabel control={<Checkbox checked={remember} onChange={e=>setRemember(e.target.checked)} />} label='Recordar' />
      <Button variant='contained' disabled={!profile.data?.hasAgentPass || !campaign || !phoneConnected || campaignConnected} onClick={()=>connectCampaign.mutate({campaignId:campaign,mode,rememberCredentials:remember})}>Conectar campaña</Button>
    </Stack>
    {connectCampaign.error && <Alert severity='error'>No fue posible conectar campaña.</Alert>}
  </Stack></CardContent></Card>

  <Card><CardContent><Stack gap={1}>
    <Typography variant='h6'>Paso 3: Tipificación</Typography>
    {!canType && <Alert severity='info'>Debes conectar anexo y campaña para habilitar disposición, notas y guardado.</Alert>}
    <Divider />
    <pre>{JSON.stringify(c?.lead,null,2)}</pre>
    <pre>{JSON.stringify(c?.customer,null,2)}</pre>
    <TextField select label='Disposición' value={dispo} onChange={e=>setDispo(e.target.value)} disabled={!canType}>{(c?.dispoOptions||[]).map((d:string)=><MenuItem key={d} value={d}>{d}</MenuItem>)}</TextField>
    <TextField label='Notas' multiline minRows={3} value={notes} onChange={e=>setNotes(e.target.value)} disabled={!canType}/>
    <Button variant='contained' disabled={!canType || !dispo} onClick={()=>save.mutate({mode,leadId,phoneNumber:c?.lead?.phoneNumber||'',campaign:c?.lead?.campaign||status.data?.campaign||'',dni:c?.lead?.dni||'',dispo,notes,extra:{}})}>Guardar gestión</Button>
    {save.data?.syncStatus!=='SYNCED'&&save.data?.id&&<Button onClick={()=>retry.mutate(save.data.id)}>Reintentar</Button>}
    {mode==='manual'&&<Stack direction='row' gap={1}><Button onClick={()=>previewAction({leadId,campaign:c?.lead?.campaign,action:'DIALONLY'})}>DIALONLY</Button><Button onClick={()=>previewAction({leadId,campaign:c?.lead?.campaign,action:'SKIP'})}>SKIP</Button><Button onClick={()=>previewAction({leadId,campaign:c?.lead?.campaign,action:'FINISH'})}>FINISH</Button></Stack>}
    <Stack direction='row' gap={1}><Button onClick={()=>pauseAction({action:'PAUSE'})}>Pause</Button><Button onClick={()=>pauseAction({action:'RESUME'})}>Resume</Button></Stack>
  </Stack></CardContent></Card>
 </Stack></Container>;
}
