import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Button, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField } from '@mui/material';
import { api, Asset } from '../api';
import { useAuth } from '../auth';
import { FormDialog, PageHeader, StatusChip, date, enumOpts, money, opts, useLookups, Field } from '../components/common';

export function assetFields(lk: ReturnType<typeof useLookups>, selfId?: number): Field[] {
  return [
    { name: 'tag', label: 'Asset tag / ID', required: true, half: true },
    { name: 'name', label: 'Name', required: true, half: true },
    { name: 'category', label: 'Category (e.g. HVAC, Pumps, Vehicles)', half: true },
    { name: 'locationId', label: 'Location', type: 'select', half: true, options: opts(lk.locations, (l) => l.id, (l) => l.name) },
    { name: 'status', label: 'Status', type: 'select', half: true, options: enumOpts('ACTIVE', 'DOWN', 'RETIRED') },
    { name: 'criticality', label: 'Criticality', type: 'select', half: true, options: enumOpts('LOW', 'MEDIUM', 'HIGH') },
    { name: 'parentAssetId', label: 'Part of (parent asset)', type: 'autocomplete',
      options: opts(lk.assets.filter((a) => a.id !== selfId), (a) => a.id, (a) => `${a.tag} — ${a.name}`) },
    { name: 'manufacturer', label: 'Manufacturer', half: true },
    { name: 'model', label: 'Model', half: true },
    { name: 'serialNumber', label: 'Serial number', half: true },
    { name: 'installDate', label: 'Install date', type: 'date', half: true },
    { name: 'purchaseCost', label: 'Purchase cost ($)', type: 'number', half: true },
    { name: 'warrantyUntil', label: 'Warranty until', type: 'date', half: true },
    { name: 'meterUnit', label: 'Meter unit (hours, miles, cycles)', half: true },
    { name: 'notes', label: 'Notes', type: 'textarea' },
  ];
}

export default function AssetsPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const { isSupervisor } = useAuth();
  const lk = useLookups();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('');
  const [open, setOpen] = useState(false);
  const blank = useMemo(() => ({ status: 'ACTIVE', criticality: 'MEDIUM' } as Partial<Asset>), [open]);

  const rows = lk.assets.filter((a) =>
    (!status || a.status === status) &&
    (!q || [a.tag, a.name, a.category, a.locationName, a.manufacturer, a.model, a.serialNumber].some((v) => v?.toLowerCase().includes(q.toLowerCase()))));

  return (
    <>
      <PageHeader title="Assets" subtitle="Equipment, facilities and vehicles you maintain"
        actions={isSupervisor && <Button variant="contained" onClick={() => setOpen(true)}>Add asset</Button>} />
      <Stack direction={{ xs: 'column', sm: 'row' }} gap={2} mb={2}>
        <TextField size="small" label="Search" value={q} onChange={(e) => setQ(e.target.value)} sx={{ minWidth: 260 }} />
        <TextField size="small" select label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 160 }}>
          <MenuItem value="">All</MenuItem>
          {['ACTIVE', 'DOWN', 'RETIRED'].map((s) => <MenuItem key={s} value={s}>{s}</MenuItem>)}
        </TextField>
      </Stack>
      <Paper sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Tag</TableCell><TableCell>Name</TableCell><TableCell>Category</TableCell><TableCell>Location</TableCell>
              <TableCell>Status</TableCell><TableCell>Criticality</TableCell><TableCell align="right">Open WOs</TableCell>
              <TableCell>Next PM</TableCell><TableCell align="right">Lifetime cost</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((a) => (
              <TableRow key={a.id} hover sx={{ cursor: 'pointer' }} onClick={() => nav(`/assets/${a.id}`)}>
                <TableCell><b>{a.tag}</b></TableCell><TableCell>{a.name}</TableCell><TableCell>{a.category}</TableCell>
                <TableCell>{a.locationName}</TableCell><TableCell><StatusChip value={a.status} /></TableCell>
                <TableCell><StatusChip value={a.criticality} /></TableCell><TableCell align="right">{a.openWorkOrders}</TableCell>
                <TableCell>{date(a.nextPmDue)}</TableCell><TableCell align="right">{money(a.lifetimeCost)}</TableCell>
              </TableRow>
            ))}
            {!rows.length && <TableRow><TableCell colSpan={9}>No assets found.</TableCell></TableRow>}
          </TableBody>
        </Table>
      </Paper>
      <FormDialog open={open} title="Add asset" fields={assetFields(lk)} initial={blank} onClose={() => setOpen(false)}
        onSubmit={async (v) => {
          const { data } = await api.post('/assets', v);
          qc.invalidateQueries({ queryKey: ['assets'] });
          nav(`/assets/${data.id}`);
        }} />
    </>
  );
}
