import { Alert, Box, Button, Card, CardContent, Container, Grid, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  adminAgents,
  adminCampaigns,
  adminCreateUser,
  adminExportCsvUrl,
  adminInteractions,
  adminManual2ExportCsvUrl,
  adminManual2Report,
  adminManual2SaveSubtipificacion,
  adminManual2SetSubtipificacionActivo,
  adminManual2Subtipificaciones,
  adminSettings,
  adminSummary,
  adminUpdateAgentPass,
  adminUpdateSettings,
  adminUsers,
  adminValidacionClaroPeruExportCsvUrl,
  adminValidacionClaroPeruReport
} from '../api/sdk';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

const today = new Date().toISOString().slice(0, 10);
const MASKED_SECRET = '********';

function buildEditableConfig(source:any) {
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

export default function AdminPage(){
  const navigate = useNavigate();
  const [filters, setFilters] = useState({ campaign: '', agentUser: '', dispo: '', from: today, to: today, page: 0, size: 20 });
  const [manual2Filters, setManual2Filters] = useState({ campana: 'Manual2', agente: '', tipificacion: '', disposicion: '', subtipificacion: '', telefono: '', cliente: '', from: today, to: today, page: 0, size: 20 });
  const [validacionFilters, setValidacionFilters] = useState({ campana: 'ValidacionClaroPeru', agente: '', tipificacion: '', disposicion: '', subtipificacion: '', telefono: '', documento: '', encuesta: '', from: today, to: today, page: 0, size: 20 });
  const [subtipForm, setSubtipForm] = useState({ campana: 'Manual2', codigo: '', nombre: '', tipificacion: '', activo: true });
  const summary = useQuery({ queryKey:['admin-summary'], queryFn: adminSummary, refetchInterval: 8000 });
  const agents = useQuery({ queryKey:['admin-agents'], queryFn: adminAgents, refetchInterval: 8000 });
  const campaigns = useQuery({ queryKey:['admin-campaigns'], queryFn: adminCampaigns });
  const interactions = useQuery({ queryKey:['admin-interactions',filters], queryFn:()=>adminInteractions(filters) });
  const manual2Report = useQuery({ queryKey:['admin-manual2-report',manual2Filters], queryFn:()=>adminManual2Report(manual2Filters) });
  const validacionReport = useQuery({ queryKey:['admin-validacion-claro-peru-report',validacionFilters], queryFn:()=>adminValidacionClaroPeruReport(validacionFilters) });
  const subtipificaciones = useQuery({ queryKey:['admin-manual2-subtipificaciones', subtipForm.campana], queryFn:()=>adminManual2Subtipificaciones(subtipForm.campana) });
  const users = useQuery({ queryKey:['admin-users'], queryFn: adminUsers });
  const settings = useQuery({ queryKey:['admin-settings'], queryFn: adminSettings });
  const createUser = useMutation({ mutationFn: adminCreateUser, onSuccess: ()=>users.refetch() });
  const updateSettings = useMutation({ mutationFn: adminUpdateSettings, onSuccess: ()=>settings.refetch() });
  const saveSubtipificacion = useMutation({
    mutationFn: adminManual2SaveSubtipificacion,
    onSuccess: ()=>{
      setSubtipForm((prev)=>({ ...prev, codigo:'', nombre:'' }));
      subtipificaciones.refetch();
    }
  });
  const toggleSubtipificacion = useMutation({
    mutationFn: ({codigo, activo}:{codigo:string;activo:boolean})=>adminManual2SetSubtipificacionActivo(codigo, { campana: subtipForm.campana, activo }),
    onSuccess: ()=>subtipificaciones.refetch()
  });
  const [newUser,setNewUser]=useState({username:'',password:'',role:'AGENT',active:true});
  const [cfg,setCfg]=useState<any>(null);
  const [agentPassByUser, setAgentPassByUser] = useState<Record<number,string>>({});
  const updateAgentPass = useMutation({ mutationFn: ({id,agentPass}:{id:number;agentPass:string})=>adminUpdateAgentPass(id,{agentPass}) });

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('role');
    sessionStorage.clear();
    navigate('/login', { replace: true });
  };

  const exportUrl = useMemo(()=>adminExportCsvUrl(filters), [filters]);
  const manual2ExportUrl = useMemo(()=>adminManual2ExportCsvUrl(manual2Filters), [manual2Filters]);
  const validacionExportUrl = useMemo(()=>adminValidacionClaroPeruExportCsvUrl(validacionFilters), [validacionFilters]);
  const k:any = summary.data || {};
  const rows:any[] = agents.data?.items || [];
  const ir:any = interactions.data || {};
  const manual2Rows:any = manual2Report.data || {};
  const validacionRows:any = validacionReport.data || {};
  const formCfg = cfg || buildEditableConfig(settings.data);

  const mergeCfg = (patch:any) => {
    setCfg((prev:any) => ({ ...(prev || buildEditableConfig(settings.data)), ...patch }));
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

  return <Container maxWidth='xl' sx={{py:3}}>
    <Stack gap={2}>
      <Stack direction='row' justifyContent='space-between' alignItems='center'>
        <Typography variant='h4'>Admin Dashboard</Typography>
        <Stack direction='row' gap={1}>
          <Button variant='outlined' onClick={()=>navigate('/admin/realtime')}>Realtime</Button>
          <Button variant='outlined' color='inherit' onClick={logout}>Cerrar sesión</Button>
        </Stack>
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

      <Card><CardContent><Stack gap={1}><Typography variant='h6'>Reporte Manual2 (BD sistema)</Typography>
        <Stack direction='row' gap={1} flexWrap='wrap'>
          <TextField label='CampaÃ±a' value={manual2Filters.campana} onChange={e=>setManual2Filters({...manual2Filters,campana:e.target.value,page:0})}/>
          <TextField label='Agente' value={manual2Filters.agente} onChange={e=>setManual2Filters({...manual2Filters,agente:e.target.value,page:0})}/>
          <TextField label='Tipificacion' value={manual2Filters.tipificacion} onChange={e=>setManual2Filters({...manual2Filters,tipificacion:e.target.value,page:0})}/>
          <TextField label='Disposicion' value={manual2Filters.disposicion} onChange={e=>setManual2Filters({...manual2Filters,disposicion:e.target.value,page:0})}/>
          <TextField label='Subtipificacion' value={manual2Filters.subtipificacion} onChange={e=>setManual2Filters({...manual2Filters,subtipificacion:e.target.value,page:0})}/>
          <TextField label='Telefono' value={manual2Filters.telefono} onChange={e=>setManual2Filters({...manual2Filters,telefono:e.target.value,page:0})}/>
          <TextField label='Cliente' value={manual2Filters.cliente} onChange={e=>setManual2Filters({...manual2Filters,cliente:e.target.value,page:0})}/>
          <TextField type='date' label='Desde' value={manual2Filters.from} onChange={e=>setManual2Filters({...manual2Filters,from:e.target.value,page:0})} InputLabelProps={{shrink:true}}/>
          <TextField type='date' label='Hasta' value={manual2Filters.to} onChange={e=>setManual2Filters({...manual2Filters,to:e.target.value,page:0})} InputLabelProps={{shrink:true}}/>
          <Button href={manual2ExportUrl} target='_blank'>Export CSV</Button>
        </Stack>
        <Table size='small'><TableHead><TableRow>
          <TableCell>Fecha</TableCell><TableCell>Agente</TableCell><TableCell>CampaÃ±a</TableCell><TableCell>TelÃ©fono</TableCell><TableCell>Cliente</TableCell><TableCell>Tipificacion</TableCell><TableCell>Dispo</TableCell><TableCell>Subtip</TableCell><TableCell>Comentario</TableCell><TableCell>Audio</TableCell><TableCell>Lead</TableCell><TableCell>Call</TableCell><TableCell>Unique</TableCell>
        </TableRow></TableHead><TableBody>
          {(manual2Rows.items||[]).map((r:any)=><TableRow key={r.id}><TableCell>{r.fechaGestion}</TableCell><TableCell>{r.agente}</TableCell><TableCell>{r.campana}</TableCell><TableCell>{r.telefono}</TableCell><TableCell>{`${r.nombres||''} ${r.apellidos||''}`.trim()}</TableCell><TableCell>{r.tipificacion}</TableCell><TableCell>{r.disposicion}</TableCell><TableCell>{r.subtipificacion}</TableCell><TableCell>{r.comentario || r.observaciones}</TableCell><TableCell>{r.nombreAudio}</TableCell><TableCell>{r.leadId}</TableCell><TableCell>{r.callId}</TableCell><TableCell>{r.uniqueId}</TableCell></TableRow>)}
        </TableBody></Table>
      </Stack></CardContent></Card>

      <Card><CardContent><Stack gap={1}><Typography variant='h6'>Reporte ValidacionClaroPeru (BD sistema)</Typography>
        <Stack direction='row' gap={1} flexWrap='wrap'>
          <TextField label='Campana' value={validacionFilters.campana} onChange={e=>setValidacionFilters({...validacionFilters,campana:e.target.value,page:0})}/>
          <TextField label='Agente' value={validacionFilters.agente} onChange={e=>setValidacionFilters({...validacionFilters,agente:e.target.value,page:0})}/>
          <TextField label='Tipificacion' value={validacionFilters.tipificacion} onChange={e=>setValidacionFilters({...validacionFilters,tipificacion:e.target.value,page:0})}/>
          <TextField label='Disposicion' value={validacionFilters.disposicion} onChange={e=>setValidacionFilters({...validacionFilters,disposicion:e.target.value,page:0})}/>
          <TextField label='Subtipificacion' value={validacionFilters.subtipificacion} onChange={e=>setValidacionFilters({...validacionFilters,subtipificacion:e.target.value,page:0})}/>
          <TextField label='Telefono' value={validacionFilters.telefono} onChange={e=>setValidacionFilters({...validacionFilters,telefono:e.target.value,page:0})}/>
          <TextField label='Documento' value={validacionFilters.documento} onChange={e=>setValidacionFilters({...validacionFilters,documento:e.target.value,page:0})}/>
          <TextField label='Encuesta (SI/NO)' value={validacionFilters.encuesta} onChange={e=>setValidacionFilters({...validacionFilters,encuesta:e.target.value,page:0})}/>
          <TextField type='date' label='Desde' value={validacionFilters.from} onChange={e=>setValidacionFilters({...validacionFilters,from:e.target.value,page:0})} InputLabelProps={{shrink:true}}/>
          <TextField type='date' label='Hasta' value={validacionFilters.to} onChange={e=>setValidacionFilters({...validacionFilters,to:e.target.value,page:0})} InputLabelProps={{shrink:true}}/>
          <Button href={validacionExportUrl} target='_blank'>Export CSV</Button>
        </Stack>
        <Table size='small'><TableHead><TableRow>
          <TableCell>Fecha</TableCell><TableCell>Agente</TableCell><TableCell>Campana</TableCell><TableCell>Telefono</TableCell><TableCell>Nombres</TableCell><TableCell>Documento</TableCell><TableCell>Tipificacion</TableCell><TableCell>Dispo</TableCell><TableCell>Subtip</TableCell><TableCell>Comentario</TableCell><TableCell>Encuesta</TableCell><TableCell>Audio</TableCell><TableCell>Lead</TableCell><TableCell>Call</TableCell><TableCell>Unique</TableCell>
        </TableRow></TableHead><TableBody>
          {(validacionRows.items||[]).map((r:any)=><TableRow key={r.id}><TableCell>{r.fechaGestion}</TableCell><TableCell>{r.agente}</TableCell><TableCell>{r.campana}</TableCell><TableCell>{r.telefono}</TableCell><TableCell>{`${r.nombres||''} ${r.apellidos||''}`.trim()}</TableCell><TableCell>{r.documento}</TableCell><TableCell>{r.tipificacion}</TableCell><TableCell>{r.disposicion}</TableCell><TableCell>{r.subtipificacion}</TableCell><TableCell>{r.comentario}</TableCell><TableCell>{r.encuesta}</TableCell><TableCell>{r.nombreAudio}</TableCell><TableCell>{r.leadId}</TableCell><TableCell>{r.callId}</TableCell><TableCell>{r.uniqueId}</TableCell></TableRow>)}
        </TableBody></Table>
      </Stack></CardContent></Card>

      <Card><CardContent><Stack gap={1}><Typography variant='h6'>Subtipificaciones por campana</Typography>
        <Stack direction='row' gap={1} flexWrap='wrap'>
          <TextField label='Campana' value={subtipForm.campana} onChange={e=>setSubtipForm({...subtipForm,campana:e.target.value})}/>
          <TextField label='Tipificacion' value={subtipForm.tipificacion} onChange={e=>setSubtipForm({...subtipForm,tipificacion:e.target.value})}/>
          <TextField label='Codigo' value={subtipForm.codigo} onChange={e=>setSubtipForm({...subtipForm,codigo:e.target.value})}/>
          <TextField label='Nombre' value={subtipForm.nombre} onChange={e=>setSubtipForm({...subtipForm,nombre:e.target.value})}/>
          <Button
            variant='contained'
            disabled={!subtipForm.tipificacion || !subtipForm.codigo || !subtipForm.nombre || saveSubtipificacion.isPending}
            onClick={()=>saveSubtipificacion.mutate({
              campana: subtipForm.campana || 'Manual2',
              tipificacion: subtipForm.tipificacion,
              codigo: subtipForm.codigo,
              nombre: subtipForm.nombre,
              activo: subtipForm.activo,
            })}
          >
            Guardar
          </Button>
        </Stack>
        {saveSubtipificacion.isError && <Alert severity='error'>No se pudo guardar la subtipificacion.</Alert>}
        <Table size='small'><TableHead><TableRow><TableCell>Tipificacion</TableCell><TableCell>Codigo</TableCell><TableCell>Nombre</TableCell><TableCell>Activo</TableCell><TableCell>Accion</TableCell></TableRow></TableHead><TableBody>
          {(subtipificaciones.data?.items||[]).map((s:any)=><TableRow key={`${s.campana}-${s.codigo}`}><TableCell>{s.tipificacion}</TableCell><TableCell>{s.codigo}</TableCell><TableCell>{s.nombre}</TableCell><TableCell>{String(s.activo)}</TableCell><TableCell><Button size='small' variant='outlined' onClick={()=>toggleSubtipificacion.mutate({codigo:s.codigo, activo:!s.activo})}>{s.activo?'Desactivar':'Activar'}</Button></TableCell></TableRow>)}
        </TableBody></Table>
      </Stack></CardContent></Card>

      <Grid container spacing={2}>
        <Grid item xs={12} md={6}><Card><CardContent><Stack gap={1}><Typography variant='h6'>Usuarios</Typography>
          <Stack direction='row' gap={1}><TextField label='Username' value={newUser.username} onChange={e=>setNewUser({...newUser,username:e.target.value})}/><TextField label='Password' type='password' value={newUser.password} onChange={e=>setNewUser({...newUser,password:e.target.value})}/><TextField label='Role' value={newUser.role} onChange={e=>setNewUser({...newUser,role:e.target.value})}/><Button variant='contained' onClick={()=>createUser.mutate(newUser)}>Crear</Button></Stack>
          <Table size='small'><TableHead><TableRow><TableCell>User</TableCell><TableCell>Role</TableCell><TableCell>Activo</TableCell><TableCell>agent_pass</TableCell></TableRow></TableHead><TableBody>{(users.data||[]).map((u:any)=><TableRow key={u.id}><TableCell>{u.username}</TableCell><TableCell>{u.role}</TableCell><TableCell>{String(u.active)}</TableCell><TableCell><Stack direction='row' gap={1}><TextField size='small' type='password' placeholder='Nuevo agent_pass' value={agentPassByUser[u.id]||''} onChange={e=>setAgentPassByUser({...agentPassByUser,[u.id]:e.target.value})}/><Button size='small' variant='outlined' onClick={()=>updateAgentPass.mutate({id:u.id,agentPass:agentPassByUser[u.id]||''})} disabled={!(agentPassByUser[u.id]||'')}>Guardar</Button></Stack></TableCell></TableRow>)}</TableBody></Table>
        </Stack></CardContent></Card></Grid>
        <Grid item xs={12} md={6}><Card><CardContent><Stack gap={1}><Typography variant='h6'>Settings Vicidial</Typography>
          <TextField label='Base URL' value={formCfg.baseUrl} onChange={e=>mergeCfg({baseUrl:e.target.value})}/>
          <TextField label='API User' value={formCfg.apiUser} onChange={e=>mergeCfg({apiUser:e.target.value})}/>
          <TextField label='API Pass' type='password' placeholder='********' value={formCfg.apiPass} onChange={e=>mergeCfg({apiPass:e.target.value})}/>
          <TextField label='Source' value={formCfg.source} onChange={e=>mergeCfg({source:e.target.value})}/>
          <TextField label='DB Host' value={formCfg.dbHost} onChange={e=>mergeCfg({dbHost:e.target.value})}/>
          <TextField label='DB Port' value={formCfg.dbPort} onChange={e=>mergeCfg({dbPort:e.target.value})}/>
          <TextField label='DB Name' value={formCfg.dbName} onChange={e=>mergeCfg({dbName:e.target.value})}/>
          <TextField label='DB User' value={formCfg.dbUser} onChange={e=>mergeCfg({dbUser:e.target.value})}/>
          <TextField label='DB Pass' type='password' placeholder='********' value={formCfg.dbPass} onChange={e=>mergeCfg({dbPass:e.target.value})}/>
          <Button variant='contained' onClick={()=>updateSettings.mutate(buildSettingsPayload())}>Guardar settings</Button>
        </Stack></CardContent></Card></Grid>
      </Grid>
    </Stack>
  </Container>;
}
