import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CssBaseline, GlobalStyles, ThemeProvider, createTheme } from '@mui/material';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './app/App';

const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;

const theme = createTheme({
  palette: {
    mode: prefersDark ? 'dark' : 'light',
    primary: { main: '#2563eb' },
    secondary: { main: '#7c3aed' },
    background: {
      default: prefersDark ? '#0b1220' : '#f5f7fb',
      paper: prefersDark ? '#111827' : '#ffffff',
    },
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily: 'Inter, system-ui, -apple-system, Segoe UI, Roboto, sans-serif',
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={new QueryClient()}>
      <ThemeProvider theme={theme}>
        <BrowserRouter>
          <CssBaseline />
          <GlobalStyles styles={{ ':root': { colorScheme: prefersDark ? 'dark' : 'light' } }} />
          <App />
        </BrowserRouter>
      </ThemeProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
