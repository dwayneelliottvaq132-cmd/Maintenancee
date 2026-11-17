import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Box, Button, Paper, TextField, Typography } from '@mui/material';
import { useAuth } from '../auth';
import { errMsg } from '../api';

export default function LoginPage() {
  const { login } = useAuth();
  const nav = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(null);
    try { await login(username, password); nav('/'); } catch (err) { setError(errMsg(err)); } finally { setBusy(false); }
  };

  return (
    <Box minHeight="100vh" display="flex" alignItems="center" justifyContent="center" bgcolor="background.default" p={2}>
      <Paper component="form" onSubmit={submit} sx={{ p: 4, width: '100%', maxWidth: 380 }}>
        <Typography variant="h5" fontWeight={600} gutterBottom>Maintenance Manager</Typography>
        <Typography color="text.secondary" mb={3}>Sign in to continue</Typography>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <TextField label="Username" fullWidth margin="normal" autoFocus value={username} onChange={(e) => setUsername(e.target.value)} />
        <TextField label="Password" type="password" fullWidth margin="normal" value={password} onChange={(e) => setPassword(e.target.value)} />
        <Button type="submit" variant="contained" fullWidth size="large" sx={{ mt: 2 }} disabled={busy}>Sign in</Button>
      </Paper>
    </Box>
  );
}
