import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Button, Paper, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, Typography,
} from '@mui/material';
import { api, MaintenanceRequest } from '../api';
import { useAuth } from '../auth';
import { FormDialog, PageHeader, StatusChip, dateTime, enumOpts, opts, useLookups } from '../components/common';

export default function RequestsPage() {
  const nav = useNavigate();
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const { isSupervisor, isStaff } = useAuth();
  const lk = useLookups();
  const [tab, setTab] = useState(isSupervisor ? 0 : 1);
  const [review, setReview] = useState<{ r: MaintenanceRequest; mode: 'approve' | 'reject' } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const creating = params.get('new') === '1';

  const { data = [], refetch } = useQuery({
    queryKey: ['requests'], queryFn: async () => (await api.get<MaintenanceRequest[]>('/requests')).data,
  });
  const rows = tab === 0 ? data.filter((r) => r.status === 'NEW') : data;
  const blank = useMemo(() => ({ priority: 'MEDIUM' }), [creating]); // eslint-disable-line
  const reload = () => { refetch(); qc.invalidateQueries({ queryKey: ['dashboard'] }); qc.invalidateQueries({ queryKey: ['work-orders'] }); };
  const staff = lk.users.filter((u) => u.active && u.role !== 'REQUESTER');

  return (
    <>
      <PageHeader title="Maintenance requests" subtitle="Report a problem. A supervisor reviews it and turns it into a work order."
        actions={<Button variant="contained" onClick={() => setParams({ new: '1' })}>New request</Button>} />
      {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}
      {isSupervisor && (
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
          <Tab label={`Awaiting review (${data.filter((r) => r.status === 'NEW').length})`} /><Tab label="All requests" />
        </Tabs>
      )}
      <Paper sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead><TableRow><TableCell>#</TableCell><TableCell>Problem</TableCell><TableCell>Asset / location</TableCell>
            <TableCell>Priority</TableCell><TableCell>Requested by</TableCell><TableCell>Submitted</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {rows.map((r) => {
              const asset = r.assetId ? lk.assetById.get(r.assetId) : undefined;
              return (
                <TableRow key={r.id}>
                  <TableCell>{r.id}</TableCell>
                  <TableCell><b>{r.title}</b>{r.description && <Typography variant="body2" color="text.secondary">{r.description}</Typography>}
                    {r.reviewNotes && <Typography variant="body2" color={r.status === 'REJECTED' ? 'error' : 'text.secondary'}>Reviewer: {r.reviewNotes}</Typography>}</TableCell>
                  <TableCell>{asset ? `${asset.tag} — ${asset.name}` : lk.locationById.get(r.locationId!)?.name ?? '—'}</TableCell>
                  <TableCell><StatusChip value={r.priority} /></TableCell>
                  <TableCell>{lk.userById.get(r.requesterId)?.fullName}</TableCell>
                  <TableCell>{dateTime(r.createdAt)}</TableCell>
                  <TableCell><StatusChip value={r.status} /></TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    {r.status === 'NEW' && isSupervisor && <>
                      <Button size="small" variant="contained" onClick={() => setReview({ r, mode: 'approve' })}>Approve</Button>{' '}
                      <Button size="small" color="error" onClick={() => setReview({ r, mode: 'reject' })}>Reject</Button>
                    </>}
                    {r.workOrderId && isStaff && <Button size="small" onClick={() => nav(`/work-orders/${r.workOrderId}`)}>View work order</Button>}
                  </TableCell>
                </TableRow>
              );
            })}
            {!rows.length && <TableRow><TableCell colSpan={8}>No requests.</TableCell></TableRow>}
          </TableBody>
        </Table>
      </Paper>

      <FormDialog open={creating} title="New maintenance request" submitLabel="Submit request" initial={blank} onClose={() => setParams({})}
        fields={[
          { name: 'title', label: 'What is the problem?', required: true },
          { name: 'description', label: 'Details (where, when it started, anything unsafe?)', type: 'textarea' },
          { name: 'assetId', label: 'Equipment (if known)', type: 'autocomplete', options: opts(lk.assets, (a) => a.id, (a) => `${a.tag} — ${a.name}`) },
          { name: 'locationId', label: 'Location', type: 'select', half: true, options: opts(lk.locations, (l) => l.id, (l) => l.name) },
          { name: 'priority', label: 'Priority', type: 'select', half: true, options: enumOpts('LOW', 'MEDIUM', 'HIGH', 'URGENT') },
        ]}
        onSubmit={async (v) => { await api.post('/requests', v); setNotice('Request submitted. You can track its status here.'); reload(); }} />

      <FormDialog open={review?.mode === 'approve'} title={`Approve: ${review?.r.title ?? ''}`} submitLabel="Approve & create work order"
        initial={{}} onClose={() => setReview(null)}
        fields={[
          { name: 'assigneeId', label: 'Assign to', type: 'select', options: opts(staff, (u) => u.id, (u) => u.fullName) },
          { name: 'dueDate', label: 'Due date', type: 'date', half: true },
          { name: 'instructionId', label: 'Work instruction', type: 'select', half: true, options: opts(lk.instructions, (i) => i.id, (i) => i.title) },
          { name: 'notes', label: 'Notes to requester', type: 'textarea' },
        ]}
        onSubmit={async (v: any) => {
          const { data } = await api.post(`/requests/${review!.r.id}/approve`, { ...v, assigneeIds: v.assigneeId ? [v.assigneeId] : [] });
          setNotice(`Approved — work order created.`); reload();
          if (data.workOrderId) nav(`/work-orders/${data.workOrderId}`);
        }} />
      <FormDialog open={review?.mode === 'reject'} title={`Reject: ${review?.r.title ?? ''}`} submitLabel="Reject" initial={{}} onClose={() => setReview(null)}
        fields={[{ name: 'notes', label: 'Reason (shown to requester)', type: 'textarea', required: true }]}
        onSubmit={async (v) => { await api.post(`/requests/${review!.r.id}/reject`, v); reload(); }} />
    </>
  );
}
