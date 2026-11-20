import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CssBaseline, ThemeProvider, createTheme } from '@mui/material';
import { AuthProvider } from './auth';
import App from './App';
import { LanguageProvider } from './i18n';

const theme = createTheme({
  // Texas Precision Plating brand palette (from the TPP logo)
  palette: {
    primary: { main: '#406c4f', dark: '#2e5039', light: '#5f8a6d', contrastText: '#ffffff' },
    secondary: { main: '#fbb340', dark: '#c98a1f', contrastText: '#1f2a22' },
    background: { default: '#f3f4ef', paper: '#ffffff' },
  },
  shape: { borderRadius: 8 },
  components: {
    MuiButton: { defaultProps: { disableElevation: true } },
    MuiListItemButton: { styleOverrides: { root: {
      '&.Mui-selected': { backgroundColor: 'rgba(64,108,79,0.12)', color: '#2e5039', '& .MuiListItemIcon-root': { color: '#406c4f' } },
      '&.Mui-selected:hover': { backgroundColor: 'rgba(64,108,79,0.18)' },
    } } },
    MuiAppBar: { styleOverrides: { root: { backgroundColor: '#2e5039', borderBottom: '3px solid #fbb340' } } },
  },
});
const qc = new QueryClient({ defaultOptions: { queries: { staleTime: 15_000, retry: 1, refetchOnWindowFocus: false } } });

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <LanguageProvider>
      <QueryClientProvider client={qc}>
        <BrowserRouter>
          <AuthProvider>
            <App />
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
      </LanguageProvider>
    </ThemeProvider>
  </React.StrictMode>,
);
