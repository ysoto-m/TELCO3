import { Card, CardContent, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';

type ViciCardProps = {
  title: string;
  subtitle?: string;
  children: ReactNode;
};

export default function ViciCard({ title, subtitle, children }: ViciCardProps) {
  return (
    <Card
      elevation={0}
      sx={{
        borderRadius: 4,
        border: '1px solid',
        borderColor: 'divider',
        backdropFilter: 'blur(6px)',
        boxShadow: (theme) =>
          theme.palette.mode === 'dark'
            ? '0 12px 40px rgba(0,0,0,0.45)'
            : '0 8px 30px rgba(15, 23, 42, 0.08)',
      }}
    >
      <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
        <Stack spacing={0.5} sx={{ mb: 2 }}>
          <Typography variant='h6' fontWeight={700}>
            {title}
          </Typography>
          {subtitle && (
            <Typography variant='body2' color='text.secondary'>
              {subtitle}
            </Typography>
          )}
        </Stack>
        {children}
      </CardContent>
    </Card>
  );
}
