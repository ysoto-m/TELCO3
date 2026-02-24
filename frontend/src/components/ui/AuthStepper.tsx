import { Box, Chip, Stack } from '@mui/material';

type AuthStepperProps = {
  activeStep: number;
  labels: string[];
};

export default function AuthStepper({ activeStep, labels }: AuthStepperProps) {
  return (
    <Stack direction='row' spacing={1} sx={{ overflowX: 'auto', pb: 0.5 }}>
      {labels.map((label, index) => {
        const step = index + 1;
        const isActive = step === activeStep;
        const isComplete = step < activeStep;

        return (
          <Box key={label} sx={{ flexShrink: 0 }}>
            <Chip
              label={`${step}. ${label}`}
              color={isActive ? 'primary' : isComplete ? 'success' : 'default'}
              variant={isActive ? 'filled' : 'outlined'}
              sx={{ fontWeight: 600 }}
            />
          </Box>
        );
      })}
    </Stack>
  );
}
