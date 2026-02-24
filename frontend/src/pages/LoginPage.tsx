import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Container, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { login } from '../api/sdk';
import AuthStepper from '../components/ui/AuthStepper';
import ViciCard from '../components/ui/ViciCard';

const schema = z.object({
  username: z.string().min(1, 'El usuario es obligatorio'),
  password: z.string().min(1, 'La contraseña es obligatoria'),
});

type LoginForm = z.infer<typeof schema>;

export default function LoginPage() {
  const nav = useNavigate();
  const [errorMsg, setErrorMsg] = useState('');
  const { register, handleSubmit, formState } = useForm<LoginForm>({
    resolver: zodResolver(schema),
  });

  return (
    <Container maxWidth='sm' sx={{ minHeight: '100dvh', display: 'grid', placeItems: 'center', py: 4 }}>
      <Stack spacing={2} sx={{ width: '100%' }}>
        <ViciCard title='Vicidial CRM' subtitle='Acceso de agente al flujo operativo'>
          <Stack
            component='form'
            spacing={2}
            onSubmit={handleSubmit(async (values) => {
              setErrorMsg('');
              try {
                const r = await login(values);
                localStorage.setItem('token', r.accessToken);
                localStorage.setItem('role', r.role);
                nav(r.role === 'REPORT_ADMIN' ? '/admin' : `/agent?mode=predictive&agent_user=${r.username}`);
              } catch {
                setErrorMsg('No se pudo iniciar sesión. Verifica tus credenciales.');
              }
            })}
          >
            <AuthStepper activeStep={1} labels={['Login', 'Extensión', 'Campaña']} />
            <Typography variant='h4' fontWeight={800}>
              Iniciar sesión
            </Typography>
            <Typography variant='body2' color='text.secondary'>
              Usa tus credenciales actuales. No se modificó el flujo de autenticación.
            </Typography>

            {errorMsg && <Alert severity='error'>{errorMsg}</Alert>}

            <TextField
              label='Usuario'
              autoComplete='username'
              autoFocus
              {...register('username')}
              error={Boolean(formState.errors.username)}
              helperText={formState.errors.username?.message}
            />
            <TextField
              label='Contraseña'
              type='password'
              autoComplete='current-password'
              {...register('password')}
              error={Boolean(formState.errors.password)}
              helperText={formState.errors.password?.message}
            />
            <Box>
              <Button
                type='submit'
                variant='contained'
                size='large'
                fullWidth
                sx={{ py: 1.2, fontWeight: 700 }}
                disabled={formState.isSubmitting}
              >
                {formState.isSubmitting ? 'Ingresando...' : 'Ingresar'}
              </Button>
            </Box>
          </Stack>
        </ViciCard>
      </Stack>
    </Container>
  );
}
