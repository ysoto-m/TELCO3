import { Button, Container, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { getSettings, importCsv, putSettings, reportSummary } from '../api/sdk'; import { useState } from 'react';
export default function AdminPage(){ const q=useQuery({queryKey:['settings'],queryFn:getSettings}); const save=useMutation({mutationFn:putSettings}); const rep=useMutation({mutationFn:reportSummary});
 const [file,setFile]=useState<File|null>(null); const imp=useMutation({mutationFn:importCsv});
 const s:any=q.data||{};
 return <Container sx={{py:3}}><Stack gap={2}><Typography variant='h5'>Admin</Typography><TextField label='Base URL' defaultValue={s.baseUrl||''} onChange={e=>s.baseUrl=e.target.value}/><TextField label='API User' defaultValue={s.apiUser||''} onChange={e=>s.apiUser=e.target.value}/><TextField label='API Pass' type='password' onChange={e=>s.apiPass=e.target.value}/><TextField label='Source' defaultValue={s.source||''} onChange={e=>s.source=e.target.value}/><Button variant='contained' onClick={()=>save.mutate(s)}>Guardar settings</Button><input type='file' accept='.csv' onChange={e=>setFile(e.target.files?.[0]||null)}/><Button onClick={()=>file&&imp.mutate(file)}>Importar CSV</Button><Button onClick={()=>rep.mutate({from:'2024-01-01',to:'2030-01-01'})}>Cargar resumen</Button><pre>{JSON.stringify(rep.data,null,2)}</pre></Stack></Container>
}
