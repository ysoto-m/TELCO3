import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
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
export default function LoginPage() {
    const nav = useNavigate();
    const [errorMsg, setErrorMsg] = useState('');
    const { register, handleSubmit, formState } = useForm({
        resolver: zodResolver(schema),
    });
    return (_jsx(Container, { maxWidth: 'sm', sx: { minHeight: '100dvh', display: 'grid', placeItems: 'center', py: 4 }, children: _jsx(Stack, { spacing: 2, sx: { width: '100%' }, children: _jsx(ViciCard, { title: 'Vicidial CRM', subtitle: 'Acceso de agente al flujo operativo', children: _jsxs(Stack, { component: 'form', spacing: 2, onSubmit: handleSubmit(async (values) => {
                        setErrorMsg('');
                        try {
                            const r = await login(values);
                            localStorage.setItem('token', r.accessToken);
                            localStorage.setItem('role', r.role);
                            nav(r.role === 'REPORT_ADMIN' ? '/admin' : `/agent?agent_user=${r.username}`);
                        }
                        catch {
                            setErrorMsg('No se pudo iniciar sesión. Verifica tus credenciales.');
                        }
                    }), children: [_jsx(AuthStepper, { activeStep: 1, labels: ['Login', 'Extensión', 'Campaña'] }), _jsx(Typography, { variant: 'h4', fontWeight: 800, children: "Iniciar sesi\u00F3n" }), errorMsg && _jsx(Alert, { severity: 'error', children: errorMsg }), _jsx(TextField, { label: 'Usuario', autoComplete: 'username', autoFocus: true, ...register('username'), error: Boolean(formState.errors.username), helperText: formState.errors.username?.message }), _jsx(TextField, { label: 'Contrase\u00F1a', type: 'password', autoComplete: 'current-password', ...register('password'), error: Boolean(formState.errors.password), helperText: formState.errors.password?.message }), _jsx(Box, { children: _jsx(Button, { type: 'submit', variant: 'contained', size: 'large', fullWidth: true, sx: { py: 1.2, fontWeight: 700 }, disabled: formState.isSubmitting, children: formState.isSubmitting ? 'Ingresando...' : 'Ingresar' }) })] }) }) }) }));
}
