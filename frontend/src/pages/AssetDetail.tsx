import { t, tEnum } from '../i18n';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Box, Button, Link as MuiLink, Paper, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, Typography,
} from '@mui/material';
import { api, Asset, errMsg, fileUrl } from '../api';
import { useAuth } from '../auth';
import { FormDialog, PageHeader, StatusChip, date, dateTime, money, num, useLookups } from '../components/common';
import { assetFields } from './Assets';
import { PmDialog } from './PmSchedules';

export default function AssetDetailPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const qc = useQueryClient();
  const { isSupervisor, isStaff } = useAuth();
  const lk = useLookups();
  const [tab, setTab] = useState(0);
  const [edit, setEdit] = useState(false);
  const [meter, setMeter] = useState(false);
  const [pm, setPm] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const { data, refetch } = useQuery({ queryKey: ['asset', id], queryFn: async () => (await api.get(`/assets/${id}`)).data });
  if (!data) return null;
  const a: Asset = data.asset;
  const reload = () => { refetch(); qc.invalidateQueries({ queryKey: ['assets'] }); };

  const upload = async (f?: File) => {
    if (!f) return;
    const fd = new FormData();
    fd.append('file', f);
    try { await api.post(`/assets/${id}/documents`, fd); setErr(null); reload(); } catch (e) { setErr(errMsg(e)); }
  };

  const info: [string, any][] = [
    [t('Category'), a.category], [t('Location'), lk.locationById.get(a.locationId!)?.name],
    [t('Part of'), a.parentAssetId ? lk.assetById.get(a.parentAssetId)?.tag : null],
    [t('Manufacturer'), a.manufacturer], [t('Model'), a.model], [t('Serial number'), a.serialNumber],
    [t('Install date'), date(a.installDate)], [t('Purchase cost'), a.purchaseCost != null ? money(a.purchaseCost) : null],
    [t('Warranty until'), date(a.warrantyUntil)], [t('Meter reading'), `${num(a.meterReading)} ${a.meterUnit ?? ''}`],
    [t('Lifetime maintenance cost'), money(data.costs.total)],
  ];

  return (
    <>
      <PageHeader title={`${a.tag} — ${a.name}`} subtitle={a.category}
        actions={<>
          <StatusChip value={a.status} />
          {isStaff && <Button variant="contained" onClick={() => nav(`/work-orders?new=1&assetId=${a.id}`)}>{t("New work order")}</Button>}
          {isStaff && <Button variant="outlined" onClick={() => setMeter(true)}>{t("Record meter")}</Button>}
          {isSupervisor && <Button variant="outlined" onClick={() => setEdit(true)}>{t("Edit")}</Button>}
        </>} />
      {err && <Alert severity="error" sx={{ mb: 2 }}>{err}</Alert>}
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }} variant="scrollable">
        <Tab label={t("Details")} /><Tab label={t('Work history ({n})', { n: data.workOrders.length })} />
        <Tab label={t('PM schedules ({n})', { n: data.schedules.length })} /><Tab label={t("Costs")} /><Tab label={t('Documents ({n})', { n: data.documents.length })} />
      </Tabs>

      {tab === 0 && (
        <Paper sx={{ p: 2 }}>
          <Box display="grid" gridTemplateColumns={{ xs: '1fr', sm: '1fr 1fr', md: '1fr 1fr 1fr' }} gap={2}>
            {info.map(([k, v]) => (
              <Box key={k}><Typography variant="body2" color="text.secondary">{k}</Typography><Typography>{v || '—'}</Typography></Box>
            ))}
          </Box>
          {a.notes && <Box mt={2}><Typography variant="body2" color="text.secondary">{t("Notes")}</Typography><Typography whiteSpace="pre-wrap">{a.notes}</Typography></Box>}
          {data.children.length > 0 && (
            <Box mt={2}>
              <Typography variant="body2" color="text.secondary">{t("Components")}</Typography>
              {data.children.map((c: any) => <Button key={c.id} size="small" onClick={() => nav(`/assets/${c.id}`)}>{c.tag} — {c.name}</Button>)}
            </Box>
          )}
        </Paper>
      )}

      {tab === 1 && (
        <Paper sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead><TableRow><TableCell>{t("WO")}</TableCell><TableCell>{t("Title")}</TableCell><TableCell>{t("Type")}</TableCell><TableCell>{t("Status")}</TableCell>
              <TableCell>{t("Assigned")}</TableCell><TableCell>{t("Created")}</TableCell><TableCell>{t("Completed")}</TableCell><TableCell align="right">{t("Cost")}</TableCell></TableRow></TableHead>
            <TableBody>
              {data.workOrders.map((w: any) => (
                <TableRow key={w.id} hover sx={{ cursor: 'pointer' }} onClick={() => nav(`/work-orders/${w.id}`)}>
                  <TableCell>{w.number}</TableCell><TableCell>{w.title}</TableCell><TableCell>{tEnum(w.type)}</TableCell>
                  <TableCell><StatusChip value={w.status} /></TableCell><TableCell>{w.assignees}</TableCell>
                  <TableCell>{date(w.createdAt)}</TableCell><TableCell>{date(w.completedAt)}</TableCell>
                  <TableCell align="right">{money(w.totalCost)}</TableCell>
                </TableRow>
              ))}
              {!data.workOrders.length && <TableRow><TableCell colSpan={8}>{t("No work orders yet.")}</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Paper>
      )}

      {tab === 2 && (
        <Paper sx={{ overflowX: 'auto' }}>
          {isSupervisor && <Box p={1}><Button onClick={() => setPm(true)}>{t("Add PM schedule")}</Button></Box>}
          <Table size="small">
            <TableHead><TableRow><TableCell>{t("Task")}</TableCell><TableCell>{t("Every")}</TableCell><TableCell>{t("Next due")}</TableCell><TableCell>{t("Active")}</TableCell></TableRow></TableHead>
            <TableBody>
              {data.schedules.map((s: any) => (
                <TableRow key={s.id}>
                  <TableCell>{s.title}</TableCell>
                  <TableCell>{s.intervalValue} {s.frequencyType === 'METER' ? (a.meterUnit ?? t('units')) : tEnum(s.frequencyType).toLowerCase()}</TableCell>
                  <TableCell>{s.frequencyType === 'METER' ? t('at {n}', { n: `${num(s.nextMeter)} ${a.meterUnit ?? ''}` }) : date(s.nextDue)}</TableCell>
                  <TableCell>{s.active ? t('Yes') : t('No')}</TableCell>
                </TableRow>
              ))}
              {!data.schedules.length && <TableRow><TableCell colSpan={4}>{t("No preventive maintenance scheduled.")}</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Paper>
      )}

      {tab === 3 && (
        <Box display="grid" gridTemplateColumns={{ xs: '1fr', md: '1fr 1fr' }} gap={2}>
          <Paper sx={{ p: 2 }}>
            <Typography fontWeight={600} mb={1}>{t("By cost type")}</Typography>
            <Table size="small"><TableBody>
              {data.costs.byType.map((r: any) => <TableRow key={r.type}><TableCell>{tEnum(r.type)}</TableCell>
                <TableCell align="right">{r.type === 'LABOR' ? `${num(r.hours)} h` : ''}</TableCell><TableCell align="right">{money(r.amount)}</TableCell></TableRow>)}
              <TableRow><TableCell><b>{t("Total")}</b></TableCell><TableCell /><TableCell align="right"><b>{money(data.costs.total)}</b></TableCell></TableRow>
            </TableBody></Table>
          </Paper>
          <Paper sx={{ p: 2 }}>
            <Typography fontWeight={600} mb={1}>{t("By year")}</Typography>
            <Table size="small"><TableBody>
              {data.costs.byYear.map((r: any) => <TableRow key={r.year}><TableCell>{r.year}</TableCell><TableCell align="right">{money(r.amount)}</TableCell></TableRow>)}
              {!data.costs.byYear.length && <TableRow><TableCell>{t("No spending recorded.")}</TableCell></TableRow>}
            </TableBody></Table>
          </Paper>
        </Box>
      )}

      {tab === 4 && (
        <Paper sx={{ p: 2 }}>
          {isStaff && <Button component="label" variant="outlined" sx={{ mb: 2 }}>{t("Upload manual / photo / drawing")}
            <input hidden type="file" onChange={(e) => upload(e.target.files?.[0])} /></Button>}
          <Table size="small">
            <TableBody>
              {data.documents.map((d: any) => (
                <TableRow key={d.id}>
                  <TableCell><MuiLink href={fileUrl(`/assets/${id}/documents/${d.id}`)} target="_blank">{d.fileName}</MuiLink></TableCell>
                  <TableCell>{lk.userById.get(d.uploadedBy)?.fullName}</TableCell><TableCell>{dateTime(d.uploadedAt)}</TableCell>
                  <TableCell align="right">{isSupervisor && <Button size="small" color="error"
                    onClick={async () => { await api.delete(`/assets/${id}/documents/${d.id}`); reload(); }}>{t("Delete")}</Button>}</TableCell>
                </TableRow>
              ))}
              {!data.documents.length && <TableRow><TableCell>{t("No documents uploaded.")}</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Paper>
      )}

      <FormDialog open={edit} title={t("Edit asset")} fields={assetFields(lk, a.id)} initial={a} onClose={() => setEdit(false)}
        onSubmit={async (v) => { await api.put(`/assets/${a.id}`, v); reload(); }} />
      <FormDialog open={meter} title={t('Record meter reading ({unit})', { unit: a.meterUnit ?? t('units') })} initial={{ reading: a.meterReading }}
        fields={[{ name: 'reading', label: t("Current reading"), type: 'number', required: true }]} onClose={() => setMeter(false)}
        onSubmit={async (v) => { await api.post(`/assets/${a.id}/meter`, v); reload(); }} />
      <PmDialog open={pm} onClose={() => setPm(false)} initial={{ assetId: a.id }} onSaved={reload} />
    </>
  );
}
