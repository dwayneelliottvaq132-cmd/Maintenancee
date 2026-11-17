import { t, tEnum } from '../i18n';
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
  { name: 'partNo', label: t("Part number"), required: true, half: true },
  { name: 'name', label: t("Name / description"), required: true, half: true },
  { name: 'unit', label: t("Unit (ea, box, L, ft)"), half: true },
  { name: 'unitCost', label: t("Unit cost ($)"), type: 'number' as const, half: true },
  ...(isNew ? [{ name: 'qtyOnHand', label: t("Opening quantity"), type: 'number' as const, half: true }] : []),
  { name: 'reorderPoint', label: t("Reorder when at or below"), type: 'number' as const, half: true },
  { name: 'binLocation', label: t("Bin / storage location"), half: true },
  { name: 'supplier', label: t("Supplier"), half: true },
  ...(!isNew ? [{ name: 'active', label: t("Active"), type: 'checkbox' as const }] : []),
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
      <PageHeader title={t("Parts & supplies")} subtitle={t('Inventory value {v}', { v: money(stockValue) })}
        actions={isSupervisor && <Button variant="contained" onClick={() => setDlg({ mode: 'new' })}>{t("Add part")}</Button>} />
      <Stack direction="row" gap={2} mb={2} alignItems="center" flexWrap="wrap">
        <TextField size="small" label={t("Search")} value={q} onChange={(e) => setQ(e.target.value)} />
        <FormControlLabel control={<Switch checked={low} onChange={(e) => setLow(e.target.checked)} />} label={t("Low stock only")} />
      </Stack>
      <Paper sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead><TableRow><TableCell>{t("Part #")}</TableCell><TableCell>{t("Name")}</TableCell><TableCell align="right">{t("On hand")}</TableCell>
            <TableCell align="right">{t("Reorder at")}</TableCell><TableCell align="right">{t("Unit cost")}</TableCell><TableCell align="right">{t("Value")}</TableCell>
            <TableCell>{t("Bin")}</TableCell><TableCell>{t("Supplier")}</TableCell><TableCell /></TableRow></TableHead>
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
                    <Button size="small" onClick={() => setDlg({ mode: 'receive', part: p })}>{t("Receive")}</Button>
                    {isSupervisor && <Button size="small" onClick={() => setDlg({ mode: 'adjust', part: p })}>{t("Count")}</Button>}
                    {isSupervisor && <Button size="small" onClick={() => setDlg({ mode: 'edit', part: p })}>{t("Edit")}</Button>}
                    <Button size="small" onClick={() => setDlg({ mode: 'history', part: p })}>{t("History")}</Button>
                  </TableCell>
                </TableRow>
              );
            })}
            {!rows.length && <TableRow><TableCell colSpan={9}>{t("No parts found.")}</TableCell></TableRow>}
          </TableBody>
        </Table>
      </Paper>

      <FormDialog open={dlg?.mode === 'new'} title={t("Add part")} fields={partFields(true)} initial={blank} onClose={() => setDlg(null)}
        onSubmit={(v) => api.post('/parts', v).then(reload)} />
      <FormDialog open={dlg?.mode === 'edit'} title={t("Edit part")} fields={partFields(false)} initial={dlg?.part ?? {}} onClose={() => setDlg(null)}
        onSubmit={(v) => api.put(`/parts/${dlg!.part!.id}`, v).then(reload)} />
      <FormDialog open={dlg?.mode === 'receive'} title={t('Receive stock: {name}', { name: dlg?.part?.name })} onClose={() => setDlg(null)}
        initial={{ unitCost: dlg?.part?.unitCost }} submitLabel={t("Receive")}
        fields={[
          { name: 'qty', label: t("Quantity received"), type: 'number', required: true, half: true },
          { name: 'unitCost', label: t("Unit cost paid ($)"), type: 'number', half: true },
          { name: 'reference', label: t("PO / invoice / packing slip #") },
        ]}
        onSubmit={(v) => api.post(`/parts/${dlg!.part!.id}/receive`, v).then(reload)} />
      <FormDialog open={dlg?.mode === 'adjust'} title={t('Stock count: {name}', { name: dlg?.part?.name })} onClose={() => setDlg(null)}
        initial={{ countedQty: dlg?.part?.qtyOnHand, reason: t('Stock count') }} submitLabel={t("Update quantity")}
        fields={[
          { name: 'countedQty', label: t("Counted quantity on shelf"), type: 'number', required: true },
          { name: 'reason', label: t("Reason") },
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
      <DialogTitle>{t("Stock history —")} {part.partNo} {part.name}</DialogTitle>
      <DialogContent>
        <Table size="small">
          <TableHead><TableRow><TableCell>{t("When")}</TableCell><TableCell>{t("Type")}</TableCell><TableCell align="right">{t("Qty")}</TableCell>
            <TableCell align="right">{t("Unit cost")}</TableCell><TableCell>{t("Reference")}</TableCell><TableCell>{t("By")}</TableCell></TableRow></TableHead>
          <TableBody>
            {data.map((t) => (
              <TableRow key={t.id}>
                <TableCell>{dateTime(t.createdAt)}</TableCell><TableCell>{tEnum(t.type)}</TableCell>
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
