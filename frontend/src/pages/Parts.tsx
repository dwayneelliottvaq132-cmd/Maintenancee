import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Button, Dialog, DialogContent, DialogTitle, FormControlLabel, Paper, Stack, Switch, Table, TableBody, TableCell,
  TableHead, TableRow, TextField,
} from '@mui/material';
import { api, Part } from '../api';
import { useAuth } from '../auth';
import { FormDialog, PageHeader, dateTime, money, num, useLookups } from '../components/common';

const partFields = (isNew: boolean) => [
  { name: 'partNo', label: 'Part number', required: true, half: true },
  { name: 'name', label: 'Name / description', required: true, half: true },
  { name: 'unit', label: 'Unit (ea, box, L, ft)', half: true },
  { name: 'unitCost', label: 'Unit cost ($)', type: 'number' as const, half: true },
  ...(isNew ? [{ name: 'qtyOnHand', label: 'Opening quantity', type: 'number' as const, half: true }] : []),
  { name: 'reorderPoint', label: 'Reorder when at or below', type: 'number' as const, half: true },
  { name: 'binLocation', label: 'Bin / storage location', half: true },
  { name: 'supplier', label: 'Supplier', half: true },
  ...(!isNew ? [{ name: 'active', label: 'Active', type: 'checkbox' as const }] : []),
];

export default function PartsPage() {
  const qc = useQueryClient();
  const [params] = useSearchParams();
  const { isSupervisor } = useAuth();
  const lk = useLookups();
  const [low, setLow] = useState(params.get('low') === '1');
  const [q, setQ] = useState('');
  const [dlg, setDlg] = useState<{ mode: 'new' | 'edit' | 'receive' | 'adjust' | 'history'; part?: Part } | null>(null);
  const reload = () => { qc.invalidateQueries({ queryKey: ['parts'] }); qc.invalidateQueries({ queryKey: ['dashboard'] }); };
  const rows = lk.parts.filter((p) => (!low || (p.active && Number(p.qtyOnHand) <= Number(p.reorderPoint)))
    && (!q || `${p.partNo} ${p.name} ${p.supplier ?? ''} ${p.binLocation ?? ''}`.toLowerCase().includes(q.toLowerCase())));
  const stockValue = lk.parts.reduce((s, p) => s + Number(p.qtyOnHand) * Number(p.unitCost), 0);
  const blank = useMemo(() => ({ unit: 'ea', unitCost: 0, qtyOnHand: 0, reorderPoint: 0 }), [dlg]);

  return (
    <>
      <PageHeader title="Parts & supplies" subtitle={`Inventory value ${money(stockValue)}`}
        actions={isSupervisor && <Button variant="contained" onClick={() => setDlg({ mode: 'new' })}>Add part</Button>} />
      <Stack direction="row" gap={2} mb={2} alignItems="center" flexWrap="wrap">
        <TextField size="small" label="Search" value={q} onChange={(e) => setQ(e.target.value)} />
        <FormControlLabel control={<Switch checked={low} onChange={(e) => setLow(e.target.checked)} />} label="Low stock only" />
      </Stack>
      <Paper sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead><TableRow><TableCell>Part #</TableCell><TableCell>Name</TableCell><TableCell align="right">On hand</TableCell>
            <TableCell align="right">Reorder at</TableCell><TableCell align="right">Unit cost</TableCell><TableCell align="right">Value</TableCell>
            <TableCell>Bin</TableCell><TableCell>Supplier</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {rows.map((p) => {
              const isLow = Number(p.qtyOnHand) <= Number(p.reorderPoint);
              return (
                <TableRow key={p.id} sx={{ opacity: p.active ? 1 : 0.5 }}>
                  <TableCell><b>{p.partNo}</b></TableCell><TableCell>{p.name}</TableCell>
                  <TableCell align="right" sx={{ color: isLow ? 'error.main' : undefined, fontWeight: isLow ? 600 : undefined }}>{num(p.qtyOnHand)} {p.unit}</TableCell>
                  <TableCell align="right">{num(p.reorderPoint)}</TableCell><TableCell align="right">{money(p.unitCost)}</TableCell>
                  <TableCell align="right">{money(Number(p.qtyOnHand) * Number(p.unitCost))}</TableCell>
                  <TableCell>{p.binLocation}</TableCell><TableCell>{p.supplier}</TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>
                    <Button size="small" onClick={() => setDlg({ mode: 'receive', part: p })}>Receive</Button>
                    {isSupervisor && <Button size="small" onClick={() => setDlg({ mode: 'adjust', part: p })}>Count</Button>}
                    {isSupervisor && <Button size="small" onClick={() => setDlg({ mode: 'edit', part: p })}>Edit</Button>}
                    <Button size="small" onClick={() => setDlg({ mode: 'history', part: p })}>History</Button>
                  </TableCell>
                </TableRow>
              );
            })}
            {!rows.length && <TableRow><TableCell colSpan={9}>No parts found.</TableCell></TableRow>}
          </TableBody>
        </Table>
      </Paper>

      <FormDialog open={dlg?.mode === 'new'} title="Add part" fields={partFields(true)} initial={blank} onClose={() => setDlg(null)}
        onSubmit={(v) => api.post('/parts', v).then(reload)} />
      <FormDialog open={dlg?.mode === 'edit'} title="Edit part" fields={partFields(false)} initial={dlg?.part ?? {}} onClose={() => setDlg(null)}
        onSubmit={(v) => api.put(`/parts/${dlg!.part!.id}`, v).then(reload)} />
      <FormDialog open={dlg?.mode === 'receive'} title={`Receive stock: ${dlg?.part?.name ?? ''}`} onClose={() => setDlg(null)}
        initial={{ unitCost: dlg?.part?.unitCost }} submitLabel="Receive"
        fields={[
          { name: 'qty', label: 'Quantity received', type: 'number', required: true, half: true },
          { name: 'unitCost', label: 'Unit cost paid ($)', type: 'number', half: true },
          { name: 'reference', label: 'PO / invoice / packing slip #' },
        ]}
        onSubmit={(v) => api.post(`/parts/${dlg!.part!.id}/receive`, v).then(reload)} />
      <FormDialog open={dlg?.mode === 'adjust'} title={`Stock count: ${dlg?.part?.name ?? ''}`} onClose={() => setDlg(null)}
        initial={{ countedQty: dlg?.part?.qtyOnHand, reason: 'Stock count' }} submitLabel="Update quantity"
        fields={[
          { name: 'countedQty', label: 'Counted quantity on shelf', type: 'number', required: true },
          { name: 'reason', label: 'Reason' },
        ]}
        onSubmit={(v) => api.post(`/parts/${dlg!.part!.id}/adjust`, v).then(reload)} />
      {dlg?.mode === 'history' && <History part={dlg.part!} onClose={() => setDlg(null)} />}
    </>
  );
}

function History({ part, onClose }: { part: Part; onClose: () => void }) {
  const lk = useLookups();
  const { data = [] } = useQuery({ queryKey: ['part-tx', part.id], queryFn: async () => (await api.get<any[]>(`/parts/${part.id}/transactions`)).data });
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>Stock history — {part.partNo} {part.name}</DialogTitle>
      <DialogContent>
        <Table size="small">
          <TableHead><TableRow><TableCell>When</TableCell><TableCell>Type</TableCell><TableCell align="right">Qty</TableCell>
            <TableCell align="right">Unit cost</TableCell><TableCell>Reference</TableCell><TableCell>By</TableCell></TableRow></TableHead>
          <TableBody>
            {data.map((t) => (
              <TableRow key={t.id}>
                <TableCell>{dateTime(t.createdAt)}</TableCell><TableCell>{t.type}</TableCell>
                <TableCell align="right">{Number(t.qty) > 0 ? '+' : ''}{num(t.qty)}</TableCell><TableCell align="right">{money(t.unitCost)}</TableCell>
                <TableCell>{t.reference}</TableCell><TableCell>{lk.userById.get(t.userId)?.fullName}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </DialogContent>
    </Dialog>
  );
}
