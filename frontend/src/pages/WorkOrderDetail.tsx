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
    ['work-orders', 'dashboard', 'parts', 'assets', 'pm', 'wo-audit'].forEach((k) => qc.invalidateQueries({ queryKey: [k] }));
  };
  const act = async (fn: () => Promise<any>) => {
    try { await fn(); setErr(null); reload(); } catch (e) { setErr(errMsg(e)); }
  };

  const doneRequired = data.steps.filter((s) => s.requiresCheck && s.done).length;
  const totalRequired = data.steps.filter((s) => s.requiresCheck).length;

  return (
    <>
      <PageHeader title={`${wo.number} — ${wo.title}`}
        subtitle={`${wo.type} work order · created ${dateTime(wo.createdAt)}`}
        actions={<Box className="no-print" display="flex" gap={1} flexWrap="wrap" alignItems="center">
          <StatusChip value={wo.status} /><StatusChip value={wo.priority} />
          {(wo.status === 'OPEN' || wo.status === 'ASSIGNED') && <Button variant="contained" onClick={() => act(() => api.post(`/work-orders/${id}/start`))}>Start work</Button>}
          {!closed && wo.status !== 'COMPLETED' && <Button variant="contained" color="success" onClick={() => setDialog('complete')}>Mark complete</Button>}
          {wo.status === 'COMPLETED' && isSupervisor && <Button variant="contained" color="success" onClick={() => setDialog('signoff')}>Sign off</Button>}
          {wo.status === 'COMPLETED' && isSupervisor && <Button variant="outlined" color="warning" onClick={() => setDialog('reopen')}>Send back</Button>}
          {!closed && isSupervisor && <Button onClick={() => setDialog('edit')}>Edit</Button>}
          {!closed && isSupervisor && <Button color="error" onClick={() => setDialog('cancel')}>Cancel WO</Button>}
          <IconButton onClick={() => window.print()} title="Print work order"><PrintIcon /></IconButton>
        </Box>} />
      {err && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setErr(null)}>{err}</Alert>}
      {wo.status === 'COMPLETED' && <Alert severity="warning" sx={{ mb: 2 }}>Completed by {userName(wo.completedBy)} on {dateTime(wo.completedAt)} — waiting for supervisor sign-off.</Alert>}
      {wo.status === 'SIGNED_OFF' && <Alert severity="success" sx={{ mb: 2 }}>Signed off by <b>{wo.signatureName}</b> ({userName(wo.signedOffBy)}) on {dateTime(wo.signedOffAt)}{wo.signOffNotes ? ` — ${wo.signOffNotes}` : ''}</Alert>}
      {wo.status === 'IN_PROGRESS' && wo.signOffNotes && <Alert severity="info" sx={{ mb: 2 }}>Sent back by supervisor: {wo.signOffNotes}</Alert>}

      <Box display="grid" gridTemplateColumns={{ xs: '1fr', lg: '2fr 1fr' }} gap={2}>
        <Stack gap={2}>
          <Paper sx={{ p: 2 }}>
            <Box display="grid" gridTemplateColumns={{ xs: '1fr 1fr', md: 'repeat(4, 1fr)' }} gap={2}>
              <Info label="Asset" value={asset ? <Link to={`/assets/${asset.id}`}>{asset.tag} — {asset.name}</Link> : '—'} />
              <Info label="Location" value={lk.locationById.get(wo.locationId!)?.name} />
              <Info label="Due" value={date(wo.dueDate)} />
              <Info label="Started" value={dateTime(wo.startedAt)} />
            </Box>
            {wo.description && <Typography mt={2} whiteSpace="pre-wrap">{wo.description}</Typography>}
            {wo.requestId && <Typography mt={1} variant="body2" color="text.secondary">Created from maintenance request #{wo.requestId}</Typography>}
            {wo.completionNotes && <Box mt={2}><Typography variant="body2" color="text.secondary">Completion notes</Typography><Typography whiteSpace="pre-wrap">{wo.completionNotes}</Typography></Box>}
          </Paper>

          {data.instruction && (
            <Paper sx={{ p: 2 }}>
              <Typography variant="subtitle1" fontWeight={600}>
                Work instruction: {data.instruction.title} <Typography component="span" variant="body2" color="text.secondary">v{data.instruction.version}</Typography>
              </Typography>
              {data.instruction.safetyNotes && <Alert severity="warning" sx={{ my: 1 }}><b>Safety:</b> {data.instruction.safetyNotes}</Alert>}
              <Typography variant="body2" color="text.secondary">
                {data.instruction.requiredPpe && <>PPE: {data.instruction.requiredPpe} · </>}
                {data.instruction.toolsRequired && <>Tools: {data.instruction.toolsRequired} · </>}
                {data.instruction.estimatedMinutes && <>Est. {data.instruction.estimatedMinutes} min</>}
              </Typography>
              <Typography variant="body2" mt={1}>{doneRequired} of {totalRequired} required steps checked</Typography>
              <Table size="small">
                <TableBody>
                  {data.steps.map((s) => (
                    <TableRow key={s.stepId}>
                      <TableCell padding="checkbox">
                        <Checkbox checked={s.done} disabled={closed}
                          onChange={(e) => act(() => api.put(`/work-orders/${id}/steps/${s.stepId}`, { done: e.target.checked }))} />
                      </TableCell>
                      <TableCell sx={{ textDecoration: s.done ? 'line-through' : undefined }}>
                        <b>{s.seq}.</b> {s.text} {!s.requiresCheck && <Typography component="span" variant="caption" color="text.secondary">(optional)</Typography>}
                      </TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap', color: 'text.secondary', fontSize: 12 }}>{s.done ? `${userName(s.doneBy)} · ${dateTime(s.doneAt)}` : ''}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Paper>
          )}

          <CostSection title="Labor" total={data.totals.labor} extra={`${num(data.totals.hours)} hours`} canAdd={!closed} onAdd={() => setDialog('labor')}
            head={['Employee', 'Date', 'Hours', 'Rate', 'Cost', 'Note']}
            rows={data.labor.map((l) => ({ id: l.id, cells: [userName(l.userId), date(l.workDate), num(l.hours), money(l.hourlyRate), money(l.hours * l.hourlyRate), l.note],
              canDelete: !closed && (isSupervisor || l.userId === user?.id), del: () => act(() => api.delete(`/work-orders/${id}/labor/${l.id}`)) }))} />
          <CostSection title="Parts & supplies used" total={data.totals.parts} canAdd={!closed} onAdd={() => setDialog('part')}
            head={['Part', 'Qty', 'Unit cost', 'Cost', 'Used']}
            rows={data.parts.map((p) => {
              const part = lk.partById.get(p.partId);
              return { id: p.id, cells: [part ? `${part.partNo} — ${part.name}` : `#${p.partId}`, `${num(p.qty)} ${part?.unit ?? ''}`, money(p.unitCost), money(p.qty * p.unitCost), dateTime(p.usedAt)],
                canDelete: !closed, del: () => act(() => api.delete(`/work-orders/${id}/parts/${p.id}`)) };
            })} />
          <CostSection title="Other costs (contractors, rentals, services)" total={data.totals.other} canAdd={!closed} onAdd={() => setDialog('cost')}
            head={['Description', 'Vendor', 'Date', 'Amount']}
            rows={data.otherCosts.map((c) => ({ id: c.id, cells: [c.description, c.vendor, date(c.costDate), money(c.amount)],
              canDelete: !closed && isSupervisor, del: () => act(() => api.delete(`/work-orders/${id}/costs/${c.id}`)) }))} />
        </Stack>

        <Stack gap={2}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="subtitle1" fontWeight={600} mb={1}>Cost summary</Typography>
            {[['Labor', data.totals.labor], ['Parts', data.totals.parts], ['Other', data.totals.other]].map(([k, v]) => (
              <Box key={k as string} display="flex" justifyContent="space-between"><Typography>{k}</Typography><Typography>{money(v)}</Typography></Box>
            ))}
            <Divider sx={{ my: 1 }} />
            <Box display="flex" justifyContent="space-between"><Typography fontWeight={600}>Total</Typography><Typography fontWeight={600}>{money(data.totals.total)}</Typography></Box>
          </Paper>
          <Paper sx={{ p: 2 }}>
            <Typography variant="subtitle1" fontWeight={600} mb={1}>Assigned employees</Typography>
            {isSupervisor && !closed ? (
              <Autocomplete multiple options={staffUsers} getOptionLabel={(u) => u.fullName}
                value={staffUsers.filter((u) => data.assigneeIds.includes(u.id))}
                onChange={(_, v) => act(() => api.post(`/work-orders/${id}/assign`, { userIds: v.map((u) => u.id) }))}
                renderInput={(p) => <TextField {...p} size="small" placeholder="Add employee" />} />
            ) : (
              <Typography>{data.assigneeIds.map(userName).join(', ') || 'Nobody assigned'}</Typography>
            )}
          </Paper>
          {isSupervisor && (
            <Paper sx={{ p: 2 }} className="no-print">
              <Typography variant="subtitle1" fontWeight={600} mb={1}>Activity</Typography>
              {history.map((h) => (
                <Typography key={h.id} variant="body2" mb={0.5}>
                  <Typography component="span" variant="caption" color="text.secondary">{dateTime(h.createdAt)}</Typography><br />
                  <b>{h.action.replace('_', ' ').toLowerCase()}</b> {h.user ? `by ${h.user}` : ''} {h.details ? `— ${h.details}` : ''}
                </Typography>
              ))}
            </Paper>
          )}
        </Stack>
      </Box>

      <FormDialog open={dialog === 'labor'} title="Log labor" onClose={() => setDialog(null)} initial={{ userId: user?.id, workDate: today() }}
        fields={[
          ...(isSupervisor ? [{ name: 'userId', label: 'Employee', type: 'select' as const, required: true, options: opts(staffUsers, (u) => u.id, (u) => u.fullName) }] : []),
          { name: 'hours', label: 'Hours', type: 'number', required: true, half: true },
          { name: 'workDate', label: 'Date', type: 'date', half: true },
          { name: 'note', label: 'Note' },
        ]}
        onSubmit={(v) => api.post(`/work-orders/${id}/labor`, v).then(reload)} />
      <FormDialog open={dialog === 'part'} title="Use parts from stock" onClose={() => setDialog(null)} initial={{ qty: 1 }}
        fields={[
          { name: 'partId', label: 'Part', type: 'autocomplete', required: true,
            options: opts(lk.parts.filter((p) => p.active), (p) => p.id, (p) => `${p.partNo} — ${p.name} (${num(p.qtyOnHand)} ${p.unit} on hand)`) },
          { name: 'qty', label: 'Quantity', type: 'number', required: true },
        ]}
        onSubmit={(v) => api.post(`/work-orders/${id}/parts`, v).then(reload)} />
      <FormDialog open={dialog === 'cost'} title="Add other cost" onClose={() => setDialog(null)} initial={{ costDate: today() }}
        fields={[
          { name: 'description', label: 'Description', required: true },
          { name: 'vendor', label: 'Vendor / contractor', half: true },
          { name: 'amount', label: 'Amount ($)', type: 'number', required: true, half: true },
          { name: 'costDate', label: 'Date', type: 'date', half: true },
        ]}
        onSubmit={(v) => api.post(`/work-orders/${id}/costs`, v).then(reload)} />
      <FormDialog open={dialog === 'complete'} title="Mark work complete" submitLabel="Complete" onClose={() => setDialog(null)} initial={{}}
        fields={[
          { name: 'notes', label: 'What was done / findings', type: 'textarea', required: true },
          ...(asset ? [{ name: 'meterReading', label: `Meter reading now (${asset.meterUnit ?? 'optional'})`, type: 'number' as const }] : []),
        ]}
        onSubmit={(v) => api.post(`/work-orders/${id}/complete`, v).then(reload)} />
      <FormDialog open={dialog === 'signoff'} title="Supervisor sign-off" submitLabel="Sign off" onClose={() => setDialog(null)}
        initial={{ signatureName: '' }}
        fields={[
          { name: 'signatureName', label: `Type your full name to sign (${user?.fullName})`, required: true,
            helper: `By signing you confirm the work was inspected and completed. Total cost ${money(data.totals.total)}.` },
          { name: 'notes', label: 'Notes', type: 'textarea' },
        ]}
        onSubmit={(v) => api.post(`/work-orders/${id}/sign-off`, v).then(reload)} />
      <FormDialog open={dialog === 'reopen'} title="Send back to technician" onClose={() => setDialog(null)} initial={{}}
        fields={[{ name: 'reason', label: 'What still needs doing?', type: 'textarea', required: true }]}
        onSubmit={(v) => api.post(`/work-orders/${id}/reopen`, v).then(reload)} />
      <FormDialog open={dialog === 'cancel'} title="Cancel work order" submitLabel="Cancel work order" onClose={() => setDialog(null)} initial={{}}
        fields={[{ name: 'reason', label: 'Reason', type: 'textarea', required: true }]}
        onSubmit={(v) => api.post(`/work-orders/${id}/cancel`, v).then(() => nav('/work-orders'))} />
      <FormDialog open={dialog === 'edit'} title="Edit work order" onClose={() => setDialog(null)} initial={wo}
        fields={[
          { name: 'title', label: 'Title', required: true },
          { name: 'description', label: 'Details', type: 'textarea' },
          { name: 'assetId', label: 'Asset', type: 'autocomplete', options: opts(lk.assets, (a) => a.id, (a) => `${a.tag} — ${a.name}`) },
          { name: 'priority', label: 'Priority', type: 'select', half: true, options: ['LOW', 'MEDIUM', 'HIGH', 'URGENT'].map((p) => ({ value: p, label: p })) },
          { name: 'dueDate', label: 'Due date', type: 'date', half: true },
          { name: 'instructionId', label: 'Work instruction', type: 'select', options: opts(lk.instructions, (i) => i.id, (i) => i.title) },
        ]}
        onSubmit={(v) => api.put(`/work-orders/${id}`, v).then(reload)} />
    </>
  );
}

function Info({ label, value }: { label: string; value: any }) {
  return <Box><Typography variant="body2" color="text.secondary">{label}</Typography><Typography>{value || '—'}</Typography></Box>;
}

function CostSection({ title, total, extra, head, rows, canAdd, onAdd }: {
  title: string; total: number; extra?: string; head: string[]; canAdd: boolean; onAdd: () => void;
  rows: { id: number; cells: any[]; canDelete: boolean; del: () => void }[];
}) {
  return (
    <Paper sx={{ p: 2, overflowX: 'auto' }}>
      <Box display="flex" justifyContent="space-between" alignItems="center" mb={1}>
        <Typography variant="subtitle1" fontWeight={600}>{title}</Typography>
        <Box display="flex" alignItems="center" gap={2}>
          <Typography variant="body2" color="text.secondary">{extra ? `${extra} · ` : ''}{money(total)}</Typography>
          {canAdd && <Button size="small" variant="outlined" onClick={onAdd} className="no-print">Add</Button>}
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
