import { t, tEnum, locale } from '../i18n';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Box, Button, Paper, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, Typography, IconButton, Chip,
} from '@mui/material';
import ChevronLeft from '@mui/icons-material/ChevronLeft';
import ChevronRight from '@mui/icons-material/ChevronRight';
import { api, errMsg } from '../api';
import { useAuth } from '../auth';
import { FormDialog, PageHeader, date, enumOpts, num, opts, today, useLookups } from '../components/common';

export function PmDialog({ open, onClose, initial, onSaved }: { open: boolean; onClose: () => void; initial: any; onSaved: () => void }) {
  const lk = useLookups();
  const init = useMemo(() => ({ frequencyType: 'MONTHS', intervalValue: 1, leadDays: 0, priority: 'MEDIUM', active: true,
    nextDue: today(), ...initial }), [open]); // eslint-disable-line
  return (
    <FormDialog open={open} onClose={onClose} title={initial?.id ? t('Edit PM schedule') : t('New PM schedule')} initial={init}
      fields={[
        { name: 'title', label: t("Task title (e.g. Quarterly filter change)"), required: true },
        { name: 'assetId', label: t("Asset"), type: 'autocomplete', required: true, options: opts(lk.assets, (a) => a.id, (a) => `${a.tag} — ${a.name}`) },
        { name: 'instructionId', label: t("Work instruction"), type: 'select', options: opts(lk.instructions, (i) => i.id, (i) => i.title) },
        { name: 'frequencyType', label: t("Repeat by"), type: 'select', half: true, required: true, options: [
          { value: 'DAYS', label: t("Days") }, { value: 'WEEKS', label: t("Weeks") }, { value: 'MONTHS', label: t("Months") },
          { value: 'METER', label: t("Meter reading (hours/miles)") }] },
        { name: 'intervalValue', label: t("Every (interval)"), type: 'number', half: true, required: true },
        { name: 'nextDue', label: t("Next due date (calendar schedules)"), type: 'date', half: true },
        { name: 'nextMeter', label: t("Next due at meter (meter schedules)"), type: 'number', half: true,
          helper: t("Leave blank to use current reading + interval") },
        { name: 'leadDays', label: t("Create work order this many days early"), type: 'number', half: true },
        { name: 'priority', label: t("Priority"), type: 'select', half: true, options: enumOpts('LOW', 'MEDIUM', 'HIGH', 'URGENT') },
        { name: 'defaultAssigneeId', label: t("Assign to"), type: 'select',
          options: opts(lk.users.filter((u) => u.active && u.role !== 'REQUESTER'), (u) => u.id, (u) => u.fullName) },
        { name: 'active', label: t("Active"), type: 'checkbox' },
      ]}
      onSubmit={async (v) => {
        if (v.id) await api.put(`/pm-schedules/${v.id}`, v); else await api.post('/pm-schedules', v);
        onSaved();
      }} />
  );
}

/** Project the next occurrences of a calendar schedule up to `until`. */
function occurrences(s: any, until: Date): Date[] {
  if (!s.nextDue || s.frequencyType === 'METER' || !s.active) return [];
  const out: Date[] = [];
  let d = new Date(s.nextDue + 'T00:00:00');
  for (let i = 0; i < 400 && d <= until; i++) {
    out.push(new Date(d));
    if (s.frequencyType === 'DAYS') d.setDate(d.getDate() + s.intervalValue);
    else if (s.frequencyType === 'WEEKS') d.setDate(d.getDate() + 7 * s.intervalValue);
    else d.setMonth(d.getMonth() + s.intervalValue);
  }
  return out;
}

function Calendar({ schedules }: { schedules: any[] }) {
  const [month, setMonth] = useState(() => { const d = new Date(); d.setDate(1); return d; });
  const end = new Date(month.getFullYear(), month.getMonth() + 1, 0);
  const byDay = new Map<number, any[]>();
  schedules.forEach((s) => occurrences(s, end).forEach((d) => {
    if (d.getMonth() === month.getMonth() && d.getFullYear() === month.getFullYear()) {
      byDay.set(d.getDate(), [...(byDay.get(d.getDate()) ?? []), s]);
    }
  }));
  const blanks = month.getDay();
  const cells = [...Array(blanks).fill(null), ...Array.from({ length: end.getDate() }, (_, i) => i + 1)];
  const shift = (n: number) => setMonth(new Date(month.getFullYear(), month.getMonth() + n, 1));
  const todayD = new Date();
  return (
    <Paper sx={{ p: 2 }}>
      <Box display="flex" alignItems="center" mb={1}>
        <IconButton onClick={() => shift(-1)}><ChevronLeft /></IconButton>
        <Typography fontWeight={600} sx={{ minWidth: 160, textAlign: 'center' }}>
          {month.toLocaleDateString(locale(), { month: 'long', year: 'numeric' })}</Typography>
        <IconButton onClick={() => shift(1)}><ChevronRight /></IconButton>
      </Box>
      <Box display="grid" gridTemplateColumns="repeat(7, minmax(0, 1fr))" gap={0.5}>
        {[0, 1, 2, 3, 4, 5, 6].map((i) => new Date(2023, 0, 1 + i).toLocaleDateString(locale(), { weekday: 'short' })).map((d) => <Typography key={d} variant="caption" textAlign="center">{d}</Typography>)}
        {cells.map((day, i) => {
          const isToday = day && todayD.getDate() === day && todayD.getMonth() === month.getMonth() && todayD.getFullYear() === month.getFullYear();
          return (
            <Box key={i} sx={{ minHeight: 84, border: 1, borderColor: isToday ? 'primary.main' : 'divider', borderRadius: 1, p: 0.5, bgcolor: day ? 'background.paper' : 'transparent', overflow: 'hidden' }}>
              {day && <Typography variant="caption" fontWeight={600}>{day}</Typography>}
              {(byDay.get(day) ?? []).map((s) => (
                <Chip key={s.id} size="small" color="primary" variant="outlined" label={`${s.assetTag}: ${s.title}`}
                  sx={{ display: 'flex', mb: 0.25, maxWidth: '100%', fontSize: 11, height: 20 }} title={`${s.assetName} — ${s.title}`} />
              ))}
            </Box>
          );
        })}
      </Box>
      <Typography variant="caption" color="text.secondary">{t("Projected from each schedule's next due date. Meter-based schedules are not shown.")}</Typography>
    </Paper>
  );
}

export default function PmPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const { isSupervisor } = useAuth();
  const [tab, setTab] = useState(0);
  const [edit, setEdit] = useState<any | null>(null);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const { data = [], refetch } = useQuery({ queryKey: ['pm'], queryFn: async () => (await api.get<any[]>('/pm-schedules')).data });
  const reload = () => { refetch(); qc.invalidateQueries({ queryKey: ['dashboard'] }); };

  const run = async (fn: () => Promise<any>, ok: (r: any) => string) => {
    try { const r = await fn(); setMsg({ ok: true, text: ok(r.data) }); reload(); } catch (e) { setMsg({ ok: false, text: errMsg(e) }); }
  };

  return (
    <>
      <PageHeader title={t("Preventive maintenance")} subtitle={t("Recurring tasks. Work orders are created automatically each night when a task comes due.")}
        actions={isSupervisor && <>
          <Button variant="outlined" onClick={() => run(() => api.post('/pm-schedules/generate-due'), (r) => t('{n} work order(s) created', { n: r.length }))}>{t("Generate due now")}</Button>
          <Button variant="contained" onClick={() => setEdit({})}>{t("New schedule")}</Button>
        </>} />
      {msg && <Alert severity={msg.ok ? 'success' : 'error'} sx={{ mb: 2 }} onClose={() => setMsg(null)}>{msg.text}</Alert>}
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}><Tab label={t("List")} /><Tab label={t("Calendar")} /></Tabs>
      {tab === 0 && (
        <Paper sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead><TableRow><TableCell>{t("Task")}</TableCell><TableCell>{t("Asset")}</TableCell><TableCell>{t("Every")}</TableCell><TableCell>{t("Next due")}</TableCell>
              <TableCell>{t("Instruction")}</TableCell><TableCell>{t("Assigned")}</TableCell><TableCell>{t("Open WO")}</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {data.map((s) => (
                <TableRow key={s.id} sx={{ opacity: s.active ? 1 : 0.5 }}>
                  <TableCell>{s.title}{!s.active && ` (${t('inactive')})`}</TableCell>
                  <TableCell>{s.assetTag} — {s.assetName}</TableCell>
                  <TableCell>{s.intervalValue} {s.frequencyType === 'METER' ? (s.meterUnit ?? t('units')) : tEnum(s.frequencyType).toLowerCase()}</TableCell>
                  <TableCell sx={{ color: s.overdue ? 'error.main' : undefined }}>
                    {s.frequencyType === 'METER' ? t('at {n} (now {m})', { n: num(s.nextMeter), m: num(s.assetMeter) }) : date(s.nextDue)}</TableCell>
                  <TableCell>{s.instructionTitle}</TableCell><TableCell>{s.assigneeName}</TableCell>
                  <TableCell>{s.openWorkOrderId && <Button size="small" onClick={() => nav(`/work-orders/${s.openWorkOrderId}`)}>{s.openWorkOrderNumber}</Button>}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    {isSupervisor && <>
                      {!s.openWorkOrderId && s.active && <Button size="small"
                        onClick={() => run(() => api.post(`/pm-schedules/${s.id}/generate-now`), (r) => t('Created {n}', { n: r.number }))}>{t("Create WO")}</Button>}
                      <Button size="small" onClick={() => setEdit(s)}>{t("Edit")}</Button>
                    </>}
                  </TableCell>
                </TableRow>
              ))}
              {!data.length && <TableRow><TableCell colSpan={8}>{t("No PM schedules yet.")}</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Paper>
      )}
      {tab === 1 && <Calendar schedules={data} />}
      <PmDialog open={!!edit} initial={edit ?? {}} onClose={() => setEdit(null)} onSaved={reload} />
    </>
  );
}
