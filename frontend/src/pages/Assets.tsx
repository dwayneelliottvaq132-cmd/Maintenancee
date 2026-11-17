import { t, tEnum } from '../i18n';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Button, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField } from '@mui/material';
import { api, Asset } from '../api';
import { useAuth } from '../auth';
import { FormDialog, PageHeader, StatusChip, date, enumOpts, money, opts, useLookups, Field } from '../components/common';

export function assetFields(lk: ReturnType<typeof useLookups>, selfId?: number): Field[] {
  return [
    { name: 'tag', label: t("Asset tag / ID"), required: true, half: true },
    { name: 'name', label: t("Name"), required: true, half: true },
    { name: 'category', label: t("Category (e.g. HVAC, Pumps, Vehicles)"), half: true },
    { name: 'locationId', label: t("Location"), type: 'select', half: true, options: opts(lk.locations, (l) => l.id, (l) => l.name) },
    { name: 'status', label: t("Status"), type: 'select', half: true, options: enumOpts('ACTIVE', 'DOWN', 'RETIRED') },
    { name: 'criticality', label: t("Criticality"), type: 'select', half: true, options: enumOpts('LOW', 'MEDIUM', 'HIGH') },
    { name: 'parentAssetId', label: t("Part of (parent asset)"), type: 'autocomplete',
      options: opts(lk.assets.filter((a) => a.id !== selfId), (a) => a.id, (a) => `${a.tag} — ${a.name}`) },
    { name: 'manufacturer', label: t("Manufacturer"), half: true },
    { name: 'model', label: t("Model"), half: true },
    { name: 'serialNumber', label: t("Serial number"), half: true },
    { name: 'installDate', label: t("Install date"), type: 'date', half: true },
    { name: 'purchaseCost', label: t("Purchase cost ($)"), type: 'number', half: true },
    { name: 'warrantyUntil', label: t("Warranty until"), type: 'date', half: true },
    { name: 'meterUnit', label: t("Meter unit (hours, miles, cycles)"), half: true },
    { name: 'notes', label: t("Notes"), type: 'textarea' },
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
      <PageHeader title={t("Assets")} subtitle={t("Equipment, facilities and vehicles you maintain")}
        actions={isSupervisor && <Button variant="contained" onClick={() => setOpen(true)}>{t("Add asset")}</Button>} />
      <Stack direction={{ xs: 'column', sm: 'row' }} gap={2} mb={2}>
        <TextField size="small" label={t("Search")} value={q} onChange={(e) => setQ(e.target.value)} sx={{ minWidth: 260 }} />
        <TextField size="small" select label={t("Status")} value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 160 }}>
          <MenuItem value="">{t("All")}</MenuItem>
          {['ACTIVE', 'DOWN', 'RETIRED'].map((s) => <MenuItem key={s} value={s}>{tEnum(s)}</MenuItem>)}
        </TextField>
      </Stack>
      <Paper sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>{t("Tag")}</TableCell><TableCell>{t("Name")}</TableCell><TableCell>{t("Category")}</TableCell><TableCell>{t("Location")}</TableCell>
              <TableCell>{t("Status")}</TableCell><TableCell>{t("Criticality")}</TableCell><TableCell align="right">{t("Open WOs")}</TableCell>
              <TableCell>{t("Next PM")}</TableCell><TableCell align="right">{t("Lifetime cost")}</TableCell>
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
            {!rows.length && <TableRow><TableCell colSpan={9}>{t("No assets found.")}</TableCell></TableRow>}
          </TableBody>
        </Table>
      </Paper>
      <FormDialog open={open} title={t("Add asset")} fields={assetFields(lk)} initial={blank} onClose={() => setOpen(false)}
        onSubmit={async (v) => {
          const { data } = await api.post('/assets', v);
          qc.invalidateQueries({ queryKey: ['assets'] });
          nav(`/assets/${data.id}`);
        }} />
    </>
  );
}
