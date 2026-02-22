import { Box, Button, Container, TextField, Typography } from '@mui/material';
import { useForm } from 'react-hook-form'; import { z } from 'zod'; import { zodResolver } from '@hookform/resolvers/zod'; import { login } from '../api/sdk'; import { useNavigate } from 'react-router-dom';
const schema=z.object({username:z.string().min(1),password:z.string().min(1)});
export default function LoginPage(){ const nav=useNavigate(); const {register,handleSubmit}=useForm({resolver:zodResolver(schema)});
 return <Container maxWidth='sm'><Box component='form' onSubmit={handleSubmit(async(v)=>{const r=await login(v);localStorage.setItem('token',r.accessToken);localStorage.setItem('role',r.role);nav(r.role==='REPORT_ADMIN'?'/admin':'/agent?mode=predictive&agent_user='+r.username);})} sx={{mt:10,display:'grid',gap:2}}><Typography variant='h4'>Agent UI</Typography><TextField label='Usuario' {...register('username')}/><TextField label='Password' type='password' {...register('password')}/><Button type='submit' variant='contained'>Ingresar</Button></Box></Container>
}
