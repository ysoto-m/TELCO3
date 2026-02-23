import { Alert, Box, Button, Card, CardContent, Container, Grid, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { adminAgents, adminCampaigns, adminCreateUser, adminExportCsvUrl, adminInteractions, adminSettings, adminSummary, adminUpdateSettings, adminUsers } from '../api/sdk';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

const today = new Date().toISOString().slice(0, 10);

export default function AdminPage(){
  const navigate = useNavigate();
  const [filters, setFilters] = useState({ campaign: '', agentUser: '', dispo: '', from: today, to: today, page: 0, size: 20 });
  const summary = useQuery({ queryKey:['admin-summary'], queryFn: adminSummary, refetchInterval: 8000 });
  const agents = useQuery({ queryKey:['admin-agents'], queryFn: adminAgents, refetchInterval: 8000 });
  const campaigns = useQuery({ queryKey:['admin-campaigns'], queryFn: adminCampaigns });
  const interactions = useQuery({ queryKey:['admin-interactions',filters], queryFn:()=>adminInteractions(filters) });
  const users = useQuery({ queryKey:['admin-users'], queryFn: adminUsers });
  const settings = useQuery({ queryKey:['admin-settings'], queryFn: adminSettings });
  const createUser = useMutation({ mutationFn: adminCreateUser, onSuccess: ()=>users.refetch() });
  const updateSettings = useMutation({ mutationFn: adminUpdateSettings, onSuccess: ()=>settings.refetch() });
  const [newUser,setNewUser]=useState({username:'',password:'',role:'AGENT',active:true});
  const [cfg,setCfg]=useState<any>(null);

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('role');
    sessionStorage.clear();
    navigate('/login', { replace: true });
  };

  const exportUrl = useMemo(()=>adminExportCsvUrl(filters), [filters]);
  const k:any = summary.data || {};
  const rows:any[] = agents.data?.items || [];
  const ir:any = interactions.data || {};

  return <Container maxWidth='xl' sx={{py:3}}>
    <Stack gap={2}>
      <Stack direction='row' justifyContent='space-between' alignItems='center'>
        <Typography variant='h4'>Admin Dashboard</Typography>
        <Button variant='outlined' color='inherit' onClick={logout}>Cerrar sesión</Button>
      </Stack>
      {k.degraded && <Alert severity='warning'>Vicidial degradado: se muestran datos parciales/locales.</Alert>}
      <Grid container spacing={2}>
        {[['Agentes activos',k.activeAgents],['Incall',k.incallAgents],['Pausados',k.pausedAgents],['Interacciones hoy',k.interactionsToday]].map(([label,value])=><Grid item xs={12} md={3} key={String(label)}><Card><CardContent><Typography variant='caption'>{label}</Typography><Typography variant='h4'>{String(value??0)}</Typography></CardContent></Card></Grid>)}
      </Grid>

      <Card><CardContent><Stack gap={1}><Typography variant='h6'>Agentes live</Typography>
        <TextField label='Buscar agente' value={filters.agentUser} onChange={e=>setFilters({...filters,agentUser:e.target.value,page:0})} sx={{maxWidth:320}}/>
        <Box sx={{maxHeight:260, overflow:'auto'}}>
          <Table size='small'><TableHead><TableRow><TableCell>Agent</TableCell><TableCell>Status</TableCell><TableCell>Campaña</TableCell><TableCell>Extensión</TableCell><TableCell>Duración</TableCell></TableRow></TableHead><TableBody>
            {rows.filter(r=>!filters.agentUser || String(r.agentUser||'').includes(filters.agentUser)).map((r,idx)=><TableRow key={idx}><TableCell>{r.agentUser||'-'}</TableCell><TableCell>{r.status||'-'}</TableCell><TableCell>{r.campaign||'-'}</TableCell><TableCell>{r.extension||'-'}</TableCell><TableCell>{r.duration||'-'}</TableCell></TableRow>)}
          </TableBody></Table>
        </Box>
      </Stack></CardContent></Card>

      <Card><CardContent><Typography variant='h6'>Campañas</Typography><Stack direction='row' gap={1} flexWrap='wrap'>{(campaigns.data?.items||[]).map((c:any,i:number)=><Alert key={i} severity='info'>{String(c)}</Alert>)}</Stack></CardContent></Card>

      <Card><CardContent><Stack gap={1}><Typography variant='h6'>Interactions</Typography>
        <Stack direction='row' gap={1}><TextField label='Campaign' value={filters.campaign} onChange={e=>setFilters({...filters,campaign:e.target.value,page:0})}/><TextField label='Dispo' value={filters.dispo} onChange={e=>setFilters({...filters,dispo:e.target.value,page:0})}/><TextField type='date' label='Desde' value={filters.from} onChange={e=>setFilters({...filters,from:e.target.value,page:0})} InputLabelProps={{shrink:true}}/><TextField type='date' label='Hasta' value={filters.to} onChange={e=>setFilters({...filters,to:e.target.value,page:0})} InputLabelProps={{shrink:true}}/><Button href={exportUrl} target='_blank'>Export CSV</Button></Stack>
        <Table size='small'><TableHead><TableRow><TableCell>ID</TableCell><TableCell>Agent</TableCell><TableCell>Campaign</TableCell><TableCell>Dispo</TableCell><TableCell>Fecha</TableCell></TableRow></TableHead><TableBody>
          {(ir.items||[]).map((r:any)=><TableRow key={r.id}><TableCell>{r.id}</TableCell><TableCell>{r.agentUser}</TableCell><TableCell>{r.campaign}</TableCell><TableCell>{r.dispo}</TableCell><TableCell>{r.createdAt}</TableCell></TableRow>)}
        </TableBody></Table>
      </Stack></CardContent></Card>

      <Grid container spacing={2}>
        <Grid item xs={12} md={6}><Card><CardContent><Stack gap={1}><Typography variant='h6'>Usuarios</Typography>
          <Stack direction='row' gap={1}><TextField label='Username' value={newUser.username} onChange={e=>setNewUser({...newUser,username:e.target.value})}/><TextField label='Password' type='password' value={newUser.password} onChange={e=>setNewUser({...newUser,password:e.target.value})}/><TextField label='Role' value={newUser.role} onChange={e=>setNewUser({...newUser,role:e.target.value})}/><Button variant='contained' onClick={()=>createUser.mutate(newUser)}>Crear</Button></Stack>
          <Table size='small'><TableHead><TableRow><TableCell>User</TableCell><TableCell>Role</TableCell><TableCell>Activo</TableCell></TableRow></TableHead><TableBody>{(users.data||[]).map((u:any)=><TableRow key={u.id}><TableCell>{u.username}</TableCell><TableCell>{u.role}</TableCell><TableCell>{String(u.active)}</TableCell></TableRow>)}</TableBody></Table>
        </Stack></CardContent></Card></Grid>
        <Grid item xs={12} md={6}><Card><CardContent><Stack gap={1}><Typography variant='h6'>Settings Vicidial</Typography>
          <TextField label='Base URL' value={(cfg||settings.data||{}).baseUrl||''} onChange={e=>setCfg({...cfg,...settings.data,baseUrl:e.target.value})}/>
          <TextField label='API User' value={(cfg||settings.data||{}).apiUser||''} onChange={e=>setCfg({...cfg,...settings.data,apiUser:e.target.value})}/>
          <TextField label='API Pass' type='password' onChange={e=>setCfg({...cfg,...settings.data,apiPass:e.target.value})}/>
          <TextField label='Source' value={(cfg||settings.data||{}).source||''} onChange={e=>setCfg({...cfg,...settings.data,source:e.target.value})}/>
          <Button variant='contained' onClick={()=>updateSettings.mutate(cfg||settings.data)}>Guardar settings</Button>
        </Stack></CardContent></Card></Grid>
      </Grid>
    </Stack>
  </Container>;
}
