import { useState } from 'react';
import { Alert, Box, Button, Paper, TextField, Typography } from '@mui/material';
import { api, errMsg } from '../api';
import { useAuth } from '../auth';

export default function AccountPage({ forced }: { forced?: boolean }) {
  const { user, refresh, logout } = useAuth();
  const [cur, setCur] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  const save = async () => {
    if (next !== confirm) { setMsg({ ok: false, text: 'New passwords do not match' }); return; }
    try {
      await api.post('/auth/password', { currentPassword: cur, newPassword: next });
      setMsg({ ok: true, text: 'Password changed' });
      setCur(''); setNext(''); setConfirm('');
      await refresh();
    } catch (e) { setMsg({ ok: false, text: errMsg(e) }); }
  };

  return (
    <Box display="flex" justifyContent="center" p={forced ? 4 : 0}>
      <Paper sx={{ p: 3, width: '100%', maxWidth: 420 }}>
        <Typography variant="h6" gutterBottom>{forced ? 'Set a new password' : 'My account'}</Typography>
        <Typography color="text.secondary" mb={2}>
          {user?.fullName} ({user?.username}) · {user?.role.toLowerCase()}
          {forced && <><br />You must choose a new password before continuing.</>}
        </Typography>
        {msg && <Alert severity={msg.ok ? 'success' : 'error'} sx={{ mb: 2 }}>{msg.text}</Alert>}
        <TextField label="Current password" type="password" fullWidth margin="dense" value={cur} onChange={(e) => setCur(e.target.value)} />
        <TextField label="New password (8+ characters)" type="password" fullWidth margin="dense" value={next} onChange={(e) => setNext(e.target.value)} />
        <TextField label="Confirm new password" type="password" fullWidth margin="dense" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        <Box display="flex" gap={1} mt={2}>
          <Button variant="contained" onClick={save}>Change password</Button>
          {forced && <Button onClick={logout}>Log out</Button>}
        </Box>
      </Paper>
    </Box>
  );
}
