import { Alert, Button, Card, CardContent, Container, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { agentCampaigns, agentLoginToVicidial, agentLogoutFromVicidial, agentStatus, getActiveLead, getContext, pauseAction, previewAction, retryInteraction, saveInteraction } from '../api/sdk';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useState } from 'react';

export default function AgentPage(){
 const [sp]=useSearchParams();
 const navigate = useNavigate();
 const agentUser=sp.get('agent_user')||'';
 const mode=sp.get('mode')||'predictive';
 const [dispo,setDispo]=useState('');
 const [notes,setNotes]=useState('');
 const [phoneLogin,setPhoneLogin]=useState('');
 const [phonePass,setPhonePass]=useState('');
 const [agentPass,setAgentPass]=useState('');
 const [campaign,setCampaign]=useState('');
 const [remember,setRemember]=useState(true);

 const logout = () => {
  localStorage.removeItem('token');
  localStorage.removeItem('role');
  sessionStorage.clear();
  navigate('/login', { replace: true });
 };

 const active=useQuery({queryKey:['a',agentUser],queryFn:()=>getActiveLead(agentUser),enabled:!sp.get('lead_id')&&!!agentUser});
 const leadId=Number(sp.get('lead_id')||active.data?.leadId||0)||undefined;
 const context=useQuery({queryKey:['c',agentUser,leadId],queryFn:()=>getContext({agentUser,leadId,phone:sp.get('phone_number')||undefined,campaign:sp.get('campaign')||undefined,mode}),enabled:!!agentUser});
 const status=useQuery({queryKey:['status',agentUser],queryFn:()=>agentStatus(agentUser),enabled:!!agentUser,refetchInterval:10000});
 const campaigns=useQuery({queryKey:['campaigns',agentUser],queryFn:()=>agentCampaigns(agentUser),enabled:!!agentUser});
 const connect=useMutation({mutationFn:agentLoginToVicidial});
 const disconnect=useMutation({mutationFn:agentLogoutFromVicidial});
 const save=useMutation({mutationFn:saveInteraction});
 const retry=useMutation({mutationFn:retryInteraction});
 const c:any=context.data;

 return <Container sx={{py:3}}><Stack gap={2}>
  <Stack direction='row' justifyContent='space-between' alignItems='center'>
    <Typography variant='h5'>Atención {mode}</Typography>
    <Button variant='outlined' color='inherit' onClick={logout}>Cerrar sesión</Button>
  </Stack>
  <Card><CardContent><Stack gap={1}><Typography variant='h6'>Conexión Vicidial</Typography>
    {status.data?.raw && <Typography variant='body2'>Estado actual: {status.data.status||'N/D'} / Campaña {status.data.campaign||'-'}</Typography>}
    {campaigns.data?.limitation && <Alert severity='info'>{campaigns.data.limitation}</Alert>}
    <Stack direction='row' gap={1}><TextField label='Agent User' value={agentUser} disabled fullWidth/><TextField label='Agent Pass' type='password' value={agentPass} onChange={e=>setAgentPass(e.target.value)} fullWidth/></Stack>
    <Stack direction='row' gap={1}><TextField label='Phone Login' value={phoneLogin} onChange={e=>setPhoneLogin(e.target.value)} fullWidth/><TextField label='Phone Pass' type='password' value={phonePass} onChange={e=>setPhonePass(e.target.value)} fullWidth/></Stack>
    <TextField label='Campaign' value={campaign} onChange={e=>setCampaign(e.target.value)} helperText='Si dejas vacío, intentará usar la última guardada'/>
    <Stack direction='row' gap={1}><Button variant='contained' onClick={()=>connect.mutate({agentUser,agentPass:agentPass||undefined,phoneLogin:phoneLogin||undefined,phonePass:phonePass||undefined,campaign:campaign||undefined,rememberCredentials:remember})}>Conectar a campaña</Button><Button color='warning' onClick={()=>disconnect.mutate({agentUser})}>Desconectar</Button><Button variant='text' onClick={()=>setRemember(!remember)}>{remember?'Recordar credenciales: Sí':'Recordar credenciales: No'}</Button></Stack>
    {connect.data?.raw && <Alert severity={connect.data.ok?'success':'error'}>{connect.data.raw}</Alert>}
  </Stack></CardContent></Card>
  <pre>{JSON.stringify(c?.lead,null,2)}</pre><pre>{JSON.stringify(c?.customer,null,2)}</pre><TextField select label='Disposición' value={dispo} onChange={e=>setDispo(e.target.value)}>{(c?.dispoOptions||[]).map((d:string)=><MenuItem key={d} value={d}>{d}</MenuItem>)}</TextField><TextField label='Notas' multiline minRows={3} value={notes} onChange={e=>setNotes(e.target.value)}/><Button variant='contained' onClick={()=>save.mutate({agentUser,mode,leadId,phoneNumber:c?.lead?.phoneNumber||'',campaign:c?.lead?.campaign||'',dni:c?.lead?.dni||'',dispo,notes,extra:{}})}>Guardar gestión</Button>{save.data?.syncStatus!=='SYNCED'&&save.data?.id&&<Button onClick={()=>retry.mutate(save.data.id)}>Reintentar</Button>}{mode==='preview'&&<Stack direction='row' gap={1}><Button onClick={()=>previewAction({agentUser,leadId,campaign:c?.lead?.campaign,action:'DIALONLY'})}>DIALONLY</Button><Button onClick={()=>previewAction({agentUser,leadId,campaign:c?.lead?.campaign,action:'SKIP'})}>SKIP</Button><Button onClick={()=>previewAction({agentUser,leadId,campaign:c?.lead?.campaign,action:'FINISH'})}>FINISH</Button></Stack>}<Stack direction='row' gap={1}><Button onClick={()=>pauseAction({agentUser,action:'PAUSE'})}>Pause</Button><Button onClick={()=>pauseAction({agentUser,action:'RESUME'})}>Resume</Button></Stack></Stack></Container>
}
