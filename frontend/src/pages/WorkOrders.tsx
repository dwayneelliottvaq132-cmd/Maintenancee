import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Box, Button, FormControlLabel, MenuItem, Paper, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField,
  ToggleButton, ToggleButtonGroup, Typography, Card, CardActionArea, CardContent,
} from '@mui/material';
import { api } from '../api';
import { FormDialog, PageHeader, StatusChip, date, enumOpts, money, opts, useLookups } from '../components/common';

const STATUSES = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'SIGNED_OFF', 'CANCELLED'];

export default function WorkOrdersPage() {
  const nav = useNavigate();
  const [params, setParams] = useSearchParams();
  const lk = useLookups();
  const [status, setStatus] = useState(params.get('status') ?? '');
  const [type, setType] = useState('');
  const [mine, setMine] = useState(params.get('mine') === '1');
  const [showClosed, setShowClosed] = useState(false);
  const [view, setView] = useState<'list' | 'board'>('list');
  const [q, setQ] = useState('');
  const creating = params.get('new') === '1';

  const { data = [] } = useQuery({
    queryKey: ['work-orders', status, type, mine, showClosed],
    queryFn: async () => (await api.get<any[]>('/work-orders', { params: { status: status || undefined, type: type || undefined, mine, open: !showClosed && !status } })).data,
  });
  const rows = data.filter((w) => !q || `${w.number} ${w.title} ${w.assetTag} ${w.assetName} ${w.assignees}`.toLowerCase().includes(q.toLowerCase()));
  const initial = useMemo(() => ({ type: 'CORRECTIVE', priority: 'MEDIUM', assetId: params.get('assetId') ? Number(params.get('assetId')) : null, assigneeIds: [] }), [creating]); // eslint-disable-line

  return (
    <>
      <PageHeader title="Work orders" actions={<Button variant="contained" onClick={() => setParams({ new: '1' })}>New work order</Button>} />
      <Stack direction={{ xs: 'column', md: 'row' }} gap={2} mb={2} alignItems={{ md: 'center' }} flexWrap="wrap">
        <TextField size="small" label="Search" value={q} onChange={(e) => setQ(e.target.value)} />
        <TextField size="small" select label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 160 }}>
          <MenuItem value="">Any</MenuItem>{STATUSES.map((s) => <MenuItem key={s} value={s}>{s.replace('_', ' ')}</MenuItem>)}
        </TextField>
        <TextField size="small" select label="Type" value={type} onChange={(e) => setType(e.target.value)} sx={{ minWidth: 140 }}>
          <MenuItem value="">Any</MenuItem>{['PM', 'CORRECTIVE', 'REQUEST'].map((s) => <MenuItem key={s} value={s}>{s}</MenuItem>)}
        </TextField>
        <FormControlLabel control={<Switch checked={mine} onChange={(e) => setMine(e.target.checked)} />} label="Assigned to me" />
        <FormControlLabel control={<Switch checked={showClosed} onChange={(e) => setShowClosed(e.target.checked)} />} label="Include closed" />
        <ToggleButtonGroup size="small" exclusive value={view} onChange={(_, v) => v && setView(v)}>
          <ToggleButton value="list">List</ToggleButton><ToggleButton value="board">Board</ToggleButton>
        </ToggleButtonGroup>
      </Stack>

      {view === 'list' ? (
        <Paper sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead><TableRow><TableCell>WO</TableCell><TableCell>Title</TableCell><TableCell>Asset</TableCell><TableCell>Type</TableCell>
              <TableCell>Priority</TableCell><TableCell>Status</TableCell><TableCell>Assigned</TableCell><TableCell>Due</TableCell><TableCell align="right">Cost</TableCell></TableRow></TableHead>
            <TableBody>
              {rows.map((w) => (
                <TableRow key={w.id} hover sx={{ cursor: 'pointer' }} onClick={() => nav(`/work-orders/${w.id}`)}>
                  <TableCell><b>{w.number}</b></TableCell><TableCell>{w.title}</TableCell>
                  <TableCell>{w.assetTag}</TableCell><TableCell>{w.type}</TableCell>
                  <TableCell><StatusChip value={w.priority} /></TableCell><TableCell><StatusChip value={w.status} /></TableCell>
                  <TableCell>{w.assignees}</TableCell>
                  <TableCell sx={{ color: w.overdue ? 'error.main' : undefined, fontWeight: w.overdue ? 600 : undefined }}>{date(w.dueDate)}</TableCell>
                  <TableCell align="right">{money(w.totalCost)}</TableCell>
                </TableRow>
              ))}
              {!rows.length && <TableRow><TableCell colSpan={9}>No work orders match.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Paper>
      ) : (
        <Box display="grid" gridTemplateColumns={{ xs: '1fr', sm: '1fr 1fr', lg: 'repeat(4, 1fr)' }} gap={2}>
          {['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED'].map((s) => (
            <Box key={s} sx={{ bgcolor: 'grey.100', borderRadius: 2, p: 1 }}>
              <Typography fontWeight={600} p={1}>{s.replace('_', ' ')} ({rows.filter((w) => w.status === s).length})</Typography>
              {rows.filter((w) => w.status === s).map((w) => (
                <Card key={w.id} sx={{ mb: 1 }}>
                  <CardActionArea onClick={() => nav(`/work-orders/${w.id}`)}>
                    <CardContent sx={{ p: 1.5 }}>
                      <Typography variant="body2" color="text.secondary">{w.number} · {w.assetTag}</Typography>
                      <Typography fontWeight={500}>{w.title}</Typography>
                      <Stack direction="row" gap={1} mt={0.5} alignItems="center">
                        <StatusChip value={w.priority} />
                        <Typography variant="caption" color={w.overdue ? 'error' : 'text.secondary'}>Due {date(w.dueDate)}</Typography>
                      </Stack>
                      {w.assignees && <Typography variant="caption" display="block">{w.assignees}</Typography>}
                    </CardContent>
                  </CardActionArea>
                </Card>
              ))}
            </Box>
          ))}
        </Box>
      )}

      <FormDialog open={creating} title="New work order" initial={initial} onClose={() => setParams({})} submitLabel="Create"
        fields={[
          { name: 'title', label: 'What needs to be done?', required: true },
          { name: 'description', label: 'Details', type: 'textarea' },
          { name: 'assetId', label: 'Asset', type: 'autocomplete', options: opts(lk.assets, (a) => a.id, (a) => `${a.tag} — ${a.name}`) },
          { name: 'type', label: 'Type', type: 'select', half: true, options: enumOpts('CORRECTIVE', 'PM') },
          { name: 'priority', label: 'Priority', type: 'select', half: true, options: enumOpts('LOW', 'MEDIUM', 'HIGH', 'URGENT') },
          { name: 'dueDate', label: 'Due date', type: 'date', half: true },
          { name: 'instructionId', label: 'Work instruction', type: 'select', half: true, options: opts(lk.instructions, (i) => i.id, (i) => i.title) },
          { name: 'assigneeId', label: 'Assign to', type: 'select',
            options: opts(lk.users.filter((u) => u.active && u.role !== 'REQUESTER'), (u) => u.id, (u) => `${u.fullName}${u.trade ? ' (' + u.trade + ')' : ''}`) },
        ]}
        onSubmit={async (v: any) => {
          const { assigneeId, assigneeIds, ...wo } = v;
          const { data } = await api.post('/work-orders', { workOrder: wo, assigneeIds: assigneeId ? [assigneeId] : [] });
          nav(`/work-orders/${data.id}`);
        }} />
    </>
  );
}
