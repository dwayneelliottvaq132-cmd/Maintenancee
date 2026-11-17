import { ReactNode, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField,
  Typography, Alert, FormControlLabel, Checkbox, Autocomplete,
} from '@mui/material';
import { api, Asset, Location, Part, User, WorkInstruction, errMsg } from '../api';

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} gap={1} mb={2}>
      <Box>
        <Typography variant="h5" fontWeight={600}>{title}</Typography>
        {subtitle && <Typography color="text.secondary" variant="body2">{subtitle}</Typography>}
      </Box>
      {actions && <Stack direction="row" gap={1} flexWrap="wrap">{actions}</Stack>}
    </Stack>
  );
}

const STATUS_COLORS: Record<string, 'default' | 'primary' | 'secondary' | 'success' | 'warning' | 'error' | 'info'> = {
  OPEN: 'default', ASSIGNED: 'info', IN_PROGRESS: 'primary', COMPLETED: 'warning', SIGNED_OFF: 'success',
  CANCELLED: 'default', NEW: 'warning', APPROVED: 'success', REJECTED: 'error', ACTIVE: 'success', DOWN: 'error',
  RETIRED: 'default', LOW: 'default', MEDIUM: 'info', HIGH: 'warning', URGENT: 'error',
};

export function StatusChip({ value }: { value?: string | null }) {
  if (!value) return null;
  return <Chip size="small" label={value.replace('_', ' ')} color={STATUS_COLORS[value] ?? 'default'}
    variant={value === 'CANCELLED' || value === 'RETIRED' ? 'outlined' : 'filled'} />;
}

export const money = (v: unknown) =>
  Number(v ?? 0).toLocaleString(undefined, { style: 'currency', currency: 'USD' });
export const num = (v: unknown) => Number(v ?? 0).toLocaleString(undefined, { maximumFractionDigits: 2 });
export const date = (v?: string | null) => (v ? new Date(v.length === 10 ? v + 'T00:00:00' : v).toLocaleDateString() : '—');
export const dateTime = (v?: string | null) => (v ? new Date(v).toLocaleString() : '—');
export const today = () => new Date().toISOString().slice(0, 10);

/** Shared lookup lists used to turn ids into names. */
export function useLookups() {
  const users = useQuery({ queryKey: ['users'], queryFn: async () => (await api.get<User[]>('/users')).data });
  const assets = useQuery({ queryKey: ['assets'], queryFn: async () => (await api.get<Asset[]>('/assets')).data });
  const locations = useQuery({ queryKey: ['locations'], queryFn: async () => (await api.get<Location[]>('/locations')).data });
  const parts = useQuery({ queryKey: ['parts'], queryFn: async () => (await api.get<Part[]>('/parts')).data });
  const instructions = useQuery({ queryKey: ['instructions'], queryFn: async () => (await api.get<WorkInstruction[]>('/instructions')).data });
  const byId = <T extends { id: number }>(list?: T[]) => new Map((list ?? []).map((x) => [x.id, x]));
  return {
    users: users.data ?? [], assets: assets.data ?? [], locations: locations.data ?? [], parts: parts.data ?? [],
    instructions: instructions.data ?? [],
    userById: byId(users.data), assetById: byId(assets.data), locationById: byId(locations.data),
    partById: byId(parts.data), instructionById: byId(instructions.data),
  };
}

export type Field = {
  name: string; label: string; type?: 'text' | 'number' | 'date' | 'textarea' | 'select' | 'checkbox' | 'password' | 'autocomplete';
  options?: { value: any; label: string }[]; required?: boolean; half?: boolean; helper?: string;
};

/** Generic create/edit dialog driven by a field list. */
export function FormDialog<T extends Record<string, any>>({ open, title, fields, initial, onClose, onSubmit, submitLabel = 'Save' }: {
  open: boolean; title: string; fields: Field[]; initial: T; onClose: () => void;
  onSubmit: (values: T) => Promise<unknown>; submitLabel?: string;
}) {
  const [values, setValues] = useState<T>(initial);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [lastInitial, setLastInitial] = useState(initial);
  if (initial !== lastInitial) { setLastInitial(initial); setValues(initial); setError(null); }

  const set = (k: string, v: any) => setValues((s) => ({ ...s, [k]: v }));
  const submit = async () => {
    const missing = fields.filter((f) => f.required && (values[f.name] === undefined || values[f.name] === null || values[f.name] === ''));
    if (missing.length) { setError(`Please fill in: ${missing.map((m) => m.label).join(', ')}`); return; }
    setBusy(true); setError(null);
    try { await onSubmit(values); onClose(); } catch (e) { setError(errMsg(e)); } finally { setBusy(false); }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <Box display="grid" gridTemplateColumns={{ xs: '1fr', sm: '1fr 1fr' }} gap={2} mt={1}>
          {fields.map((f) => {
            const span = f.half ? undefined : { sm: '1 / -1' };
            const v = values[f.name];
            if (f.type === 'checkbox')
              return <FormControlLabel key={f.name} sx={{ gridColumn: span }} label={f.label}
                control={<Checkbox checked={!!v} onChange={(e) => set(f.name, e.target.checked)} />} />;
            if (f.type === 'autocomplete') {
              const opt = f.options?.find((o) => o.value === v) ?? null;
              return <Autocomplete key={f.name} sx={{ gridColumn: span }} options={f.options ?? []} value={opt}
                getOptionLabel={(o) => o.label} isOptionEqualToValue={(a, b) => a.value === b.value}
                onChange={(_, o) => set(f.name, o ? o.value : null)}
                renderInput={(p) => <TextField {...p} label={f.label} required={f.required} helperText={f.helper} />} />;
            }
            return (
              <TextField key={f.name} sx={{ gridColumn: span }} label={f.label} required={f.required} helperText={f.helper}
                type={f.type === 'textarea' || f.type === 'select' ? 'text' : f.type ?? 'text'}
                select={f.type === 'select'} multiline={f.type === 'textarea'} minRows={f.type === 'textarea' ? 3 : undefined}
                InputLabelProps={f.type === 'date' ? { shrink: true } : undefined}
                value={v ?? ''} onChange={(e) => set(f.name, f.type === 'number' ? (e.target.value === '' ? null : Number(e.target.value)) : e.target.value === '' && f.type === 'select' ? null : e.target.value)}>
                {f.type === 'select' && [<MenuItem key="__none" value=""><em>None</em></MenuItem>,
                  ...(f.options ?? []).map((o) => <MenuItem key={String(o.value)} value={o.value}>{o.label}</MenuItem>)]}
              </TextField>
            );
          })}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={submit} disabled={busy}>{submitLabel}</Button>
      </DialogActions>
    </Dialog>
  );
}

export function opts<T>(list: T[], value: (t: T) => any, label: (t: T) => string) {
  return list.map((t) => ({ value: value(t), label: label(t) }));
}
export const enumOpts = (...vals: string[]) => vals.map((v) => ({ value: v, label: v.replace('_', ' ') }));

export function downloadCsv(filename: string, rows: Record<string, any>[]) {
  if (!rows.length) return;
  const cols = Object.keys(rows[0]);
  const esc = (v: any) => { const s = v == null ? '' : String(v); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
  const csv = [cols.join(','), ...rows.map((r) => cols.map((c) => esc(r[c])).join(','))].join('\n');
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
  a.download = filename;
  a.click();
}

export function Stat({ label, value, color, onClick }: { label: string; value: ReactNode; color?: string; onClick?: () => void }) {
  return (
    <Box onClick={onClick} sx={{ p: 2, borderRadius: 2, bgcolor: 'background.paper', border: 1, borderColor: 'divider',
      cursor: onClick ? 'pointer' : 'default', '&:hover': onClick ? { borderColor: 'primary.main' } : {} }}>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography variant="h5" fontWeight={600} color={color}>{value}</Typography>
    </Box>
  );
}
