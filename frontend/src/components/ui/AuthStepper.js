import { jsx as _jsx } from "react/jsx-runtime";
import { Box, Chip, Stack } from '@mui/material';
export default function AuthStepper({ activeStep, labels }) {
    return (_jsx(Stack, { direction: 'row', spacing: 1, sx: { overflowX: 'auto', pb: 0.5 }, children: labels.map((label, index) => {
            const step = index + 1;
            const isActive = step === activeStep;
            const isComplete = step < activeStep;
            return (_jsx(Box, { sx: { flexShrink: 0 }, children: _jsx(Chip, { label: `${step}. ${label}`, color: isActive ? 'primary' : isComplete ? 'success' : 'default', variant: isActive ? 'filled' : 'outlined', sx: { fontWeight: 600 } }) }, label));
        }) }));
}
