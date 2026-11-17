import { t, tEnum } from '../i18n';
import LanguageSwitch from '../components/LanguageSwitch';
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
    if (next !== confirm) { setMsg({ ok: false, text: t('New passwords do not match') }); return; }
    try {
      await api.post('/auth/password', { currentPassword: cur, newPassword: next });
      setMsg({ ok: true, text: t('Password changed') });
      setCur(''); setNext(''); setConfirm('');
      await refresh();
    } catch (e) { setMsg({ ok: false, text: errMsg(e) }); }
  };

  return (
    <Box display="flex" justifyContent="center" p={forced ? 4 : 0}>
      <Paper sx={{ p: 3, width: '100%', maxWidth: 420 }}>
        <Typography variant="h6" gutterBottom>{forced ? t('Set a new password') : t('My account')}</Typography>
        <Typography color="text.secondary" mb={2}>
          {user?.fullName} ({user?.username}) · {tEnum(user?.role)}
          {forced && <><br />{t("You must choose a new password before continuing.")}</>}
        </Typography>
        <Box display="flex" alignItems="center" gap={1} mb={2}><Typography>{t("Language")}:</Typography><LanguageSwitch /></Box>
        {msg && <Alert severity={msg.ok ? 'success' : 'error'} sx={{ mb: 2 }}>{msg.text}</Alert>}
        <TextField label={t("Current password")} type="password" fullWidth margin="dense" value={cur} onChange={(e) => setCur(e.target.value)} />
        <TextField label={t("New password (8+ characters)")} type="password" fullWidth margin="dense" value={next} onChange={(e) => setNext(e.target.value)} />
        <TextField label={t("Confirm new password")} type="password" fullWidth margin="dense" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        <Box display="flex" gap={1} mt={2}>
          <Button variant="contained" onClick={save}>{t("Change password")}</Button>
          {forced && <Button onClick={logout}>{t("Log out")}</Button>}
        </Box>
      </Paper>
    </Box>
  );
}
