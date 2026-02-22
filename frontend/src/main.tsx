import React from 'react'; import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom'; import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CssBaseline } from '@mui/material'; import { App } from './app/App';
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><QueryClientProvider client={new QueryClient()}><BrowserRouter><CssBaseline/><App/></BrowserRouter></QueryClientProvider></React.StrictMode>);
