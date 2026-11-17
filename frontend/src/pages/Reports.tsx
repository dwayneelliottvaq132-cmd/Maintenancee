import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Box, Button, Link, MenuItem, Paper, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend, CartesianGrid } from 'recharts';
import { api, fileUrl } from '../api';
import { PageHeader, dateTime, downloadCsv, money, num } from '../components/common';

const firstOfYear = () => `${new Date().getFullYear()}-01-01`;
const todayIso = () => new Date().toISOString().slice(0, 10);

export default function ReportsPage() {
  const [tab, setTab] = useState(0);
  const [from, setFrom] = useState(firstOfYear());
  const [to, setTo] = useState(todayIso());
  const [groupBy, setGroupBy] = useState('asset');

  const spending = useQuery({ queryKey: ['rep-spend', from, to, groupBy], enabled: tab === 0,
    queryFn: async () => (await api.get<any[]>('/reports/spending', { params: { from, to, groupBy } })).data });
  const labor = useQuery({ queryKey: ['rep-labor', from, to], enabled: tab === 1,
    queryFn: async () => (await api.get<any[]>('/reports/labor', { params: { from, to } })).data });
  const parts = useQuery({ queryKey: ['rep-parts', from, to], enabled: tab === 2,
    queryFn: async () => (await api.get<any[]>('/reports/parts', { params: { from, to } })).data });
  const files = useQuery({ queryKey: ['rep-files'], enabled: tab === 3, queryFn: async () => (await api.get<any[]>('/reports/files')).data });

  const sp = (spending.data ?? []).map((r) => ({ ...r, labor: +r.labor, parts: +r.parts, other: +r.other, total: +r.total }));
  const sum = (rows: any[], k: string) => rows.reduce((s, r) => s + Number(r[k] ?? 0), 0);

  return (
    <>
      <PageHeader title="Spending & reports" />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }} variant="scrollable">
        <Tab label="Spending" /><Tab label="Labor by employee" /><Tab label="Parts used" /><Tab label="Monthly PDF / Excel" />
      </Tabs>
      {tab < 3 && (
        <Stack direction={{ xs: 'column', sm: 'row' }} gap={2} mb={2}>
          <TextField size="small" type="date" label="From" InputLabelProps={{ shrink: true }} value={from} onChange={(e) => setFrom(e.target.value)} />
          <TextField size="small" type="date" label="To" InputLabelProps={{ shrink: true }} value={to} onChange={(e) => setTo(e.target.value)} />
          {tab === 0 && (
            <TextField size="small" select label="Group by" value={groupBy} onChange={(e) => setGroupBy(e.target.value)} sx={{ minWidth: 180 }}>
              <MenuItem value="asset">Asset</MenuItem><MenuItem value="category">Asset category</MenuItem>
              <MenuItem value="employee">Employee (labor)</MenuItem><MenuItem value="month">Month</MenuItem>
              <MenuItem value="type">Cost type</MenuItem><MenuItem value="workorder">Work order</MenuItem>
            </TextField>
          )}
          <Button variant="outlined" onClick={() => downloadCsv(`report-${['spending-' + groupBy, 'labor', 'parts'][tab]}-${from}-to-${to}.csv`,
            [spending.data, labor.data, parts.data][tab] ?? [])}>Export CSV</Button>
        </Stack>
      )}

      {tab === 0 && (
        <>
          {sp.length > 0 && (
            <Paper sx={{ p: 2, mb: 2 }}>
              <Box height={Math.min(60 + sp.slice(0, 15).length * 32, 520)}>
                <ResponsiveContainer>
                  <BarChart data={sp.slice(0, 15)} layout={groupBy === 'month' ? 'horizontal' : 'vertical'} margin={{ left: 20 }}>
                    <CartesianGrid strokeDasharray="3 3" />
                    {groupBy === 'month' ? <><XAxis dataKey="label" fontSize={12} /><YAxis fontSize={12} /></>
                      : <><XAxis type="number" fontSize={12} /><YAxis type="category" dataKey="label" width={200} fontSize={12} /></>}
                    <Tooltip formatter={(v: number) => money(v)} /><Legend />
                    <Bar isAnimationActive={false} dataKey="labor" stackId="a" name="Labor" fill="#1565c0" />
                    <Bar isAnimationActive={false} dataKey="parts" stackId="a" name="Parts" fill="#ef6c00" />
                    <Bar isAnimationActive={false} dataKey="other" stackId="a" name="Other" fill="#78909c" />
                  </BarChart>
                </ResponsiveContainer>
              </Box>
            </Paper>
          )}
          <Paper sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead><TableRow><TableCell>{groupBy}</TableCell><TableCell align="right">Work orders</TableCell><TableCell align="right">Hours</TableCell>
                <TableCell align="right">Labor</TableCell><TableCell align="right">Parts</TableCell><TableCell align="right">Other</TableCell><TableCell align="right">Total</TableCell></TableRow></TableHead>
              <TableBody>
                {sp.map((r) => (
                  <TableRow key={r.label}><TableCell>{r.label}</TableCell><TableCell align="right">{r.workOrders}</TableCell><TableCell align="right">{num(r.hours)}</TableCell>
                    <TableCell align="right">{money(r.labor)}</TableCell><TableCell align="right">{money(r.parts)}</TableCell>
                    <TableCell align="right">{money(r.other)}</TableCell><TableCell align="right"><b>{money(r.total)}</b></TableCell></TableRow>
                ))}
                <TableRow><TableCell><b>Total</b></TableCell><TableCell /><TableCell align="right"><b>{num(sum(sp, 'hours'))}</b></TableCell>
                  <TableCell align="right"><b>{money(sum(sp, 'labor'))}</b></TableCell><TableCell align="right"><b>{money(sum(sp, 'parts'))}</b></TableCell>
                  <TableCell align="right"><b>{money(sum(sp, 'other'))}</b></TableCell><TableCell align="right"><b>{money(sum(sp, 'total'))}</b></TableCell></TableRow>
              </TableBody>
            </Table>
          </Paper>
        </>
      )}

      {tab === 1 && (
        <Paper sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead><TableRow><TableCell>Employee</TableCell><TableCell>Trade</TableCell><TableCell align="right">Work orders</TableCell>
              <TableCell align="right">Hours</TableCell><TableCell align="right">Labor cost</TableCell></TableRow></TableHead>
            <TableBody>
              {(labor.data ?? []).map((r) => (
                <TableRow key={r.employee}><TableCell>{r.employee}</TableCell><TableCell>{r.trade}</TableCell><TableCell align="right">{r.workOrders}</TableCell>
                  <TableCell align="right">{num(r.hours)}</TableCell><TableCell align="right">{money(r.cost)}</TableCell></TableRow>
              ))}
              {!labor.data?.length && <TableRow><TableCell colSpan={5}>No labor recorded in this period.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Paper>
      )}

      {tab === 2 && (
        <Paper sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead><TableRow><TableCell>Part #</TableCell><TableCell>Name</TableCell><TableCell align="right">Qty used</TableCell>
              <TableCell align="right">Cost</TableCell><TableCell align="right">Work orders</TableCell><TableCell align="right">On hand now</TableCell></TableRow></TableHead>
            <TableBody>
              {(parts.data ?? []).map((r) => (
                <TableRow key={r.partNo}><TableCell>{r.partNo}</TableCell><TableCell>{r.name}</TableCell><TableCell align="right">{num(r.qty)} {r.unit}</TableCell>
                  <TableCell align="right">{money(r.cost)}</TableCell><TableCell align="right">{r.workOrders}</TableCell><TableCell align="right">{num(r.onHand)}</TableCell></TableRow>
              ))}
              {!parts.data?.length && <TableRow><TableCell colSpan={6}>No parts used in this period.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Paper>
      )}

      {tab === 3 && (
        <Paper sx={{ p: 2 }}>
          <Typography variant="body2" color="text.secondary" mb={2}>
            The report worker builds a PDF and Excel spending pack on the 1st of every month. Files appear here.
          </Typography>
          <Table size="small">
            <TableBody>
              {(files.data ?? []).map((f) => (
                <TableRow key={f.name}>
                  <TableCell><Link href={fileUrl(`/reports/files/${f.name}`)}>{f.name}</Link></TableCell>
                  <TableCell>{num(f.size / 1024)} KB</TableCell><TableCell>{dateTime(f.modified)}</TableCell>
                </TableRow>
              ))}
              {!files.data?.length && <TableRow><TableCell>No report files yet.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Paper>
      )}
    </>
  );
}
