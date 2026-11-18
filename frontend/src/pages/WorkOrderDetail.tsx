import { t, tEnum } from '../i18n';
import { useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Autocomplete, Box, Button, Checkbox, Divider, IconButton, Paper, Stack, Table, TableBody, TableCell, TableHead,
  TableRow, TextField, Typography,
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import PrintIcon from '@mui/icons-material/Print';
import { api, errMsg, WorkOrderDetail } from '../api';
import { useAuth } from '../auth';
import { FormDialog, PageHeader, StatusChip, date, dateTime, money, num, opts, today, useLookups } from '../components/common';

export default function WorkOrderDetailPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const qc = useQueryClient();
  const { user, isSupervisor } = useAuth();
  const lk = useLookups();
  const [err, setErr] = useState<string | null>(null);
  const [dialog, setDialog] = useState<null | 'labor' | 'part' | 'cost' | 'complete' | 'signoff' | 'reopen' | 'cancel' | 'edit'>(null);
  const { data, refetch } = useQuery({ queryKey: ['wo', id], queryFn: async () => (await api.get<WorkOrderDetail>(`/work-orders/${id}`)).data });
  const { data: history = [] } = useQuery({
    queryKey: ['wo-audit', id], enabled: isSupervisor,
    queryFn: async () => (await api.get<any[]>('/audit', { params: { entity: 'work_order', entityId: id } })).data,
  });
  if (!data) return null;
  const wo = data.workOrder;
  const closed = wo.status === 'SIGNED_OFF' || wo.status === 'CANCELLED';
  const asset = wo.assetId ? lk.assetById.get(wo.assetId) : undefined;
  const userName = (uid?: number | null) => (uid ? lk.userById.get(uid)?.fullName ?? `#${uid}` : '—');
  const staffUsers = lk.users.filter((u) => u.active && u.role !== 'REQUESTER');

  const reload = () => {
    refetch();
    ['work-orders', 'dashboard', 'parts', 'part-tx', 'assets', 'asset', 'pm', 'wo-audit'].forEach((k) => qc.invalidateQueries({ queryKey: [k] }));
  };
  const act = async (fn: () => Promise<any>) => {
    try { await fn(); setErr(null); reload(); } catch (e) { setErr(errMsg(e)); }
  };

  const doneRequired = data.steps.filter((s) => s.requiresCheck && s.done).length;
  const totalRequired = data.steps.filter((s) => s.requiresCheck).length;

  return (
    <>
      <PageHeader title={`${wo.number} — ${wo.title}`}
        subtitle={t('{type} work order · created {when}', { type: tEnum(wo.type), when: dateTime(wo.createdAt) })}
        actions={<Box className="no-print" display="flex" gap={1} flexWrap="wrap" alignItems="center">
          <StatusChip value={wo.status} /><StatusChip value={wo.priority} />
          {(wo.status === 'OPEN' || wo.status === 'ASSIGNED') && <Button variant="contained" onClick={() => act(() => api.post(`/work-orders/${id}/start`))}>{t("Start work")}</Button>}
          {!closed && wo.status !== 'COMPLETED' && <Button variant="contained" color="success" onClick={() => setDialog('complete')}>{t("Mark complete")}</Button>}
          {wo.status === 'COMPLETED' && isSupervisor && <Button variant="contained" color="success" onClick={() => setDialog('signoff')}>{t("Sign off")}</Button>}
          {wo.status === 'COMPLETED' && isSupervisor && <Button variant="outlined" color="warning" onClick={() => setDialog('reopen')}>{t("Send back")}</Button>}
          {!closed && isSupervisor && <Button onClick={() => setDialog('edit')}>{t("Edit")}</Button>}
          {!closed && isSupervisor && <Button color="error" onClick={() => setDialog('cancel')}>{t("Cancel WO")}</Button>}
          <IconButton onClick={() => window.print()} title={t("Print work order")}><PrintIcon /></IconButton>
        </Box>} />
      {err && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setErr(null)}>{err}</Alert>}
      {wo.status === 'COMPLETED' && <Alert severity="warning" sx={{ mb: 2 }}>{t('Completed by {name} on {when} — waiting for supervisor sign-off.', { name: userName(wo.completedBy), when: dateTime(wo.completedAt) })}</Alert>}
      {wo.status === 'SIGNED_OFF' && <Alert severity="success" sx={{ mb: 2 }}>{t('Signed off by')} <b>{wo.signatureName}</b> ({userName(wo.signedOffBy)}) {t('on')} {dateTime(wo.signedOffAt)}{wo.signOffNotes ? ` — ${wo.signOffNotes}` : ''}</Alert>}
      {wo.status === 'IN_PROGRESS' && wo.signOffNotes && <Alert severity="info" sx={{ mb: 2 }}>{t("Sent back by supervisor:")} {wo.signOffNotes}</Alert>}

      <Box display="grid" gridTemplateColumns={{ xs: '1fr', lg: '2fr 1fr' }} gap={2}>
        <Stack gap={2}>
          <Paper sx={{ p: 2 }}>
            <Box display="grid" gridTemplateColumns={{ xs: '1fr 1fr', md: 'repeat(4, 1fr)' }} gap={2}>
              <Info label={t("Asset")} value={asset ? <Link to={`/assets/${asset.id}`}>{asset.tag} — {asset.name}</Link> : '—'} />
              <Info label={t("Location")} value={lk.locationById.get(wo.locationId!)?.name} />
              <Info label={t("Due")} value={date(wo.dueDate)} />
              <Info label={t("Started")} value={dateTime(wo.startedAt)} />
            </Box>
            {wo.description && <Typography mt={2} whiteSpace="pre-wrap">{wo.description}</Typography>}
            {wo.requestId && <Typography mt={1} variant="body2" color="text.secondary">{t("Created from maintenance request #")}{wo.requestId}</Typography>}
            {wo.completionNotes && <Box mt={2}><Typography variant="body2" color="text.secondary">{t("Completion notes")}</Typography><Typography whiteSpace="pre-wrap">{wo.completionNotes}</Typography></Box>}
          </Paper>

          {data.instruction && (
            <Paper sx={{ p: 2 }}>
              <Typography variant="subtitle1" fontWeight={600}>
                {t("Work instruction:")} {data.instruction.title} <Typography component="span" variant="body2" color="text.secondary">v{data.instruction.version}</Typography>
              </Typography>
              {data.instruction.safetyNotes && <Alert severity="warning" sx={{ my: 1 }}><b>{t("Safety:")}</b> {data.instruction.safetyNotes}</Alert>}
              <Typography variant="body2" color="text.secondary">
                {data.instruction.requiredPpe && <>{t("PPE:")} {data.instruction.requiredPpe} · </>}
                {data.instruction.toolsRequired && <>{t("Tools:")} {data.instruction.toolsRequired} · </>}
                {data.instruction.estimatedMinutes && <>{t("Est.")} {data.instruction.estimatedMinutes} min</>}
              </Typography>
              <Typography variant="body2" mt={1}>{t('{a} of {b} required steps checked', { a: doneRequired, b: totalRequired })}</Typography>
              <Table size="small">
                <TableBody>
                  {data.steps.map((s) => (
                    <TableRow key={s.stepId}>
                      <TableCell padding="checkbox">
                        <Checkbox checked={s.done} disabled={closed}
                          onChange={(e) => act(() => api.put(`/work-orders/${id}/steps/${s.stepId}`, { done: e.target.checked }))} />
                      </TableCell>
                      <TableCell sx={{ textDecoration: s.done ? 'line-through' : undefined }}>
                        <b>{s.seq}.</b> {s.text} {!s.requiresCheck && <Typography component="span" variant="caption" color="text.secondary">({t('optional')})</Typography>}
                      </TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap', color: 'text.secondary', fontSize: 12 }}>{s.done ? `${userName(s.doneBy)} · ${dateTime(s.doneAt)}` : ''}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Paper>
          )}

          <CostSection title={t("Labor")} total={data.totals.labor} extra={t('{n} hours', { n: num(data.totals.hours) })} canAdd={!closed} onAdd={() => setDialog('labor')}
            head={[t('Employee'), t('Date'), t('Hours'), t('Rate'), t('Cost'), t('Note')]}
            rows={data.labor.map((l) => ({ id: l.id, cells: [userName(l.userId), date(l.workDate), num(l.hours), l.hourlyRate == null ? '—' : money(l.hourlyRate), l.hourlyRate == null ? '—' : money(l.hours * l.hourlyRate), l.note],
              canDelete: !closed && (isSupervisor || l.userId === user?.id), del: () => act(() => api.delete(`/work-orders/${id}/labor/${l.id}`)) }))} />
          <CostSection title={t("Parts & supplies used")} total={data.totals.parts} canAdd={!closed} onAdd={() => setDialog('part')}
            head={[t('Part'), t('Qty'), t('Unit cost'), t('Cost'), t('Used')]}
            rows={data.parts.map((p) => {
              const part = lk.partById.get(p.partId);
              return { id: p.id, cells: [part ? `${part.partNo} — ${part.name}` : `#${p.partId}`, `${num(p.qty)} ${part?.unit ?? ''}`, money(p.unitCost), money(p.qty * p.unitCost), dateTime(p.usedAt)],
                canDelete: !closed, del: () => act(() => api.delete(`/work-orders/${id}/parts/${p.id}`)) };
            })} />
          <CostSection title={t("Other costs (contractors, rentals, services)")} total={data.totals.other} canAdd={!closed} onAdd={() => setDialog('cost')}
            head={[t('Description'), t('Vendor'), t('Date'), t('Amount')]}
            rows={data.otherCosts.map((c) => ({ id: c.id, cells: [c.description, c.vendor, date(c.costDate), money(c.amount)],
              canDelete: !closed && isSupervisor, del: () => act(() => api.delete(`/work-orders/${id}/costs/${c.id}`)) }))} />
        </Stack>

        <Stack gap={2}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="subtitle1" fontWeight={600} mb={1}>{t("Cost summary")}</Typography>
            {[[t('Labor'), data.totals.labor], [t('Parts'), data.totals.parts], [t('Other'), data.totals.other]].map(([k, v]) => (
              <Box key={k as string} display="flex" justifyContent="space-between"><Typography>{k}</Typography><Typography>{v == null ? '—' : money(v)}</Typography></Box>
            ))}
            <Divider sx={{ my: 1 }} />
            <Box display="flex" justifyContent="space-between"><Typography fontWeight={600}>{t("Total")}</Typography><Typography fontWeight={600}>{data.totals.total == null ? '—' : money(data.totals.total)}</Typography></Box>
          </Paper>
          <Paper sx={{ p: 2 }}>
            <Typography variant="subtitle1" fontWeight={600} mb={1}>{t("Assigned employees")}</Typography>
            {isSupervisor && !closed ? (
              <Autocomplete multiple options={staffUsers} getOptionLabel={(u) => u.fullName}
                value={staffUsers.filter((u) => data.assigneeIds.includes(u.id))}
                onChange={(_, v) => act(() => api.post(`/work-orders/${id}/assign`, { userIds: v.map((u) => u.id) }))}
                renderInput={(p) => <TextField {...p} size="small" placeholder={t("Add employee")} />} />
            ) : (
              <Typography>{data.assigneeIds.map(userName).join(', ') || t('Nobody assigned')}</Typography>
            )}
          </Paper>
          {isSupervisor && (
            <Paper sx={{ p: 2 }} className="no-print">
              <Typography variant="subtitle1" fontWeight={600} mb={1}>{t("Activity")}</Typography>
              {history.map((h) => (
                <Typography key={h.id} variant="body2" mb={0.5}>
                  <Typography component="span" variant="caption" color="text.secondary">{dateTime(h.createdAt)}</Typography><br />
                  <b>{tEnum('ACT_' + h.action).toLowerCase()}</b> {h.user ? t('by {name}', { name: h.user }) : ''} {h.details ? `— ${h.details}` : ''}
                </Typography>
              ))}
            </Paper>
          )}
        </Stack>
      </Box>

      <FormDialog open={dialog === 'labor'} title={t("Log labor")} onClose={() => setDialog(null)} initial={{ userId: user?.id, workDate: today() }}
        fields={[
          ...(isSupervisor ? [{ name: 'userId', label: t("Employee"), type: 'select' as const, required: true, options: opts(staffUsers, (u) => u.id, (u) => u.fullName) }] : []),
          { name: 'hours', label: t("Hours"), type: 'number', required: true, half: true },
          { name: 'workDate', label: t("Date"), type: 'date', half: true },
          { name: 'note', label: t("Note") },
        ]}
        onSubmit={(v) => api.post(`/work-orders/${id}/labor`, v).then(reload)} />
      <FormDialog open={dialog === 'part'} title={t("Use parts from stock")} onClose={() => setDialog(null)} initial={{ qty: 1 }}
        fields={[
          { name: 'partId', label: t("Part"), type: 'autocomplete', required: true,
            options: opts(lk.parts.filter((p) => p.active), (p) => p.id, (p) => `${p.partNo} — ${p.name} (${t('{n} on hand', { n: `${num(p.qtyOnHand)} ${p.unit}` })})`) },
          { name: 'qty', label: t("Quantity"), type: 'number', required: true },
        ]}
        onSubmit={(v) => api.post(`/work-orders/${id}/parts`, v).then(reload)} />
      <FormDialog open={dialog === 'cost'} title={t("Add other cost")} onClose={() => setDialog(null)} initial={{ costDate: today() }}
        fields={[
          { name: 'description', label: t("Description"), required: true },
          { name: 'vendor', label: t("Vendor / contractor"), half: true },
          { name: 'amount', label: t("Amount ($)"), type: 'number', required: true, half: true },
          { name: 'costDate', label: t("Date"), type: 'date', half: true },
        ]}
        onSubmit={(v) => api.post(`/work-orders/${id}/costs`, v).then(reload)} />
      <FormDialog open={dialog === 'complete'} title={t("Mark work complete")} submitLabel={t("Complete")} onClose={() => setDialog(null)} initial={{}}
        fields={[
          { name: 'notes', label: t("What was done / findings"), type: 'textarea', required: true },
          ...(asset ? [{ name: 'meterReading', label: t('Meter reading now ({unit})', { unit: asset.meterUnit ?? t('optional') }), type: 'number' as const }] : []),
        ]}
        onSubmit={(v) => api.post(`/work-orders/${id}/complete`, v).then(reload)} />
      <FormDialog open={dialog === 'signoff'} title={t("Supervisor sign-off")} submitLabel={t("Sign off")} onClose={() => setDialog(null)}
        initial={{ signatureName: '' }}
        fields={[
          { name: 'signatureName', label: t('Type your full name to sign ({name})', { name: user?.fullName }), required: true,
            helper: t('By signing you confirm the work was inspected and completed. Total cost {total}.', { total: data.totals.total == null ? '—' : money(data.totals.total) }) },
          { name: 'notes', label: t("Notes"), type: 'textarea' },
        ]}
        onSubmit={(v) => api.post(`/work-orders/${id}/sign-off`, v).then(reload)} />
      <FormDialog open={dialog === 'reopen'} title={t("Send back to technician")} onClose={() => setDialog(null)} initial={{}}
        fields={[{ name: 'reason', label: t("What still needs doing?"), type: 'textarea', required: true }]}
        onSubmit={(v) => api.post(`/work-orders/${id}/reopen`, v).then(reload)} />
      <FormDialog open={dialog === 'cancel'} title={t("Cancel work order")} submitLabel={t("Cancel work order")} onClose={() => setDialog(null)} initial={{}}
        fields={[{ name: 'reason', label: t("Reason"), type: 'textarea', required: true }]}
        onSubmit={(v) => api.post(`/work-orders/${id}/cancel`, v).then(() => nav('/work-orders'))} />
      <FormDialog open={dialog === 'edit'} title={t("Edit work order")} onClose={() => setDialog(null)} initial={wo}
        fields={[
          { name: 'title', label: t("Title"), required: true },
          { name: 'description', label: t("Details"), type: 'textarea' },
          { name: 'assetId', label: t("Asset"), type: 'autocomplete', options: opts(lk.assets, (a) => a.id, (a) => `${a.tag} — ${a.name}`) },
          { name: 'priority', label: t("Priority"), type: 'select', half: true, options: ['LOW', 'MEDIUM', 'HIGH', 'URGENT'].map((p) => ({ value: p, label: tEnum(p) })) },
          { name: 'dueDate', label: t("Due date"), type: 'date', half: true },
          { name: 'instructionId', label: t("Work instruction"), type: 'select', options: opts(lk.instructions, (i) => i.id, (i) => i.title) },
        ]}
        onSubmit={(v) => api.put(`/work-orders/${id}`, v).then(reload)} />
    </>
  );
}

function Info({ label, value }: { label: string; value: any }) {
  return <Box><Typography variant="body2" color="text.secondary">{label}</Typography><Typography>{value || '—'}</Typography></Box>;
}

function CostSection({ title, total, extra, head, rows, canAdd, onAdd }: {
  title: string; total: number | null; extra?: string; head: string[]; canAdd: boolean; onAdd: () => void;
  rows: { id: number; cells: any[]; canDelete: boolean; del: () => void }[];
}) {
  return (
    <Paper sx={{ p: 2, overflowX: 'auto' }}>
      <Box display="flex" justifyContent="space-between" alignItems="center" mb={1}>
        <Typography variant="subtitle1" fontWeight={600}>{title}</Typography>
        <Box display="flex" alignItems="center" gap={2}>
          <Typography variant="body2" color="text.secondary">{extra ? `${extra} · ` : ''}{total == null ? '—' : money(total)}</Typography>
          {canAdd && <Button size="small" variant="outlined" onClick={onAdd} className="no-print">{t("Add")}</Button>}
        </Box>
      </Box>
      {rows.length > 0 && (
        <Table size="small">
          <TableHead><TableRow>{head.map((h) => <TableCell key={h}>{h}</TableCell>)}<TableCell /></TableRow></TableHead>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.id}>
                {r.cells.map((c, i) => <TableCell key={i}>{c}</TableCell>)}
                <TableCell padding="checkbox">{r.canDelete && <IconButton size="small" onClick={r.del} className="no-print"><DeleteIcon fontSize="small" /></IconButton>}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Paper>
  );
}
