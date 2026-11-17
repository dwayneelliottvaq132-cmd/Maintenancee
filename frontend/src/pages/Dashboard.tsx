import { t } from '../i18n';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, Link } from 'react-router-dom';
import { Box, Paper, Table, TableBody, TableCell, TableHead, TableRow, Typography, Button } from '@mui/material';
import { BarChart, Bar, XAxis, YAxis, Tooltip, Legend, ResponsiveContainer, CartesianGrid } from 'recharts';
import { api } from '../api';
import { PageHeader, Stat, StatusChip, money, date } from '../components/common';
import { useAuth } from '../auth';

export default function DashboardPage() {
  const nav = useNavigate();
  const { isSupervisor } = useAuth();
  const { data } = useQuery({ queryKey: ['dashboard'], queryFn: async () => (await api.get('/dashboard')).data });
  const c = data?.counts ?? {};
  const spend = (data?.monthlySpend ?? []).map((m: any) => ({ ...m, labor: +m.labor, parts: +m.parts, other: +m.other }));

  return (
    <>
      <PageHeader title={t("Dashboard")} actions={<>
        <Button variant="contained" onClick={() => nav('/requests?new=1')}>{t("New request")}</Button>
        <Button variant="outlined" onClick={() => nav('/work-orders?new=1')}>{t("New work order")}</Button>
      </>} />
      <Box display="grid" gridTemplateColumns={{ xs: '1fr 1fr', md: 'repeat(4, 1fr)' }} gap={2} mb={3}>
        <Stat label={t("My open work orders")} value={c.myOpenWorkOrders ?? '–'} onClick={() => nav('/work-orders?mine=1')} />
        <Stat label={t("Open work orders")} value={c.openWorkOrders ?? '–'} onClick={() => nav('/work-orders')} />
        <Stat label={t("Overdue")} value={c.overdueWorkOrders ?? '–'} color={c.overdueWorkOrders > 0 ? 'error.main' : undefined}
          onClick={() => nav('/work-orders')} />
        <Stat label={t("Awaiting sign-off")} value={c.awaitingSignOff ?? '–'} color={c.awaitingSignOff > 0 ? 'warning.main' : undefined}
          onClick={() => nav('/work-orders?status=COMPLETED')} />
        <Stat label={t("New requests")} value={c.newRequests ?? '–'} onClick={() => nav('/requests')} />
        <Stat label={t("PM due in 14 days")} value={c.pmDue14Days ?? '–'} onClick={() => nav('/pm')} />
        <Stat label={t("Low-stock parts")} value={c.lowStockParts ?? '–'} color={c.lowStockParts > 0 ? 'warning.main' : undefined}
          onClick={() => nav('/parts?low=1')} />
        <Stat label={t("Assets down")} value={c.assetsDown ?? '–'} color={c.assetsDown > 0 ? 'error.main' : undefined}
          onClick={() => nav('/assets')} />
        {isSupervisor && <>
          <Stat label={t("Spend this month")} value={money(c.spendThisMonth)} onClick={() => nav('/reports')} />
          <Stat label={t("Spend this year")} value={money(c.spendThisYear)} onClick={() => nav('/reports')} />
          <Stat label={t("PM on-time (90 days)")} value={c.pmOnTimePct == null ? '–' : `${c.pmOnTimePct}%`} />
        </>}
      </Box>

      <Box display="grid" gridTemplateColumns={{ xs: '1fr', lg: '1fr 1fr' }} gap={2}>
        {isSupervisor && (
          <Paper sx={{ p: 2, gridColumn: { lg: '1 / -1' } }}>
            <Typography variant="subtitle1" fontWeight={600} mb={1}>{t("Maintenance spending — last 12 months")}</Typography>
            <Box height={280}>
              <ResponsiveContainer>
                <BarChart data={spend}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="month" fontSize={12} />
                  <YAxis fontSize={12} tickFormatter={(v) => `$${v >= 1000 ? (v / 1000).toFixed(0) + 'k' : v}`} />
                  <Tooltip formatter={(v: number) => money(v)} />
                  <Legend />
                  <Bar isAnimationActive={false} dataKey="labor" stackId="a" name={t("Labor")} fill="#1565c0" />
                  <Bar isAnimationActive={false} dataKey="parts" stackId="a" name={t("Parts")} fill="#ef6c00" />
                  <Bar isAnimationActive={false} dataKey="other" stackId="a" name={t("Other / contractors")} fill="#78909c" />
                </BarChart>
              </ResponsiveContainer>
            </Box>
          </Paper>
        )}
        <Paper sx={{ p: 2, overflowX: 'auto' }}>
          <Typography variant="subtitle1" fontWeight={600} mb={1}>{t("Overdue work orders")}</Typography>
          <Table size="small">
            <TableHead><TableRow><TableCell>{t("WO")}</TableCell><TableCell>{t("Title")}</TableCell><TableCell>{t("Asset")}</TableCell><TableCell>{t("Due")}</TableCell><TableCell>{t("Status")}</TableCell></TableRow></TableHead>
            <TableBody>
              {(data?.overdue ?? []).map((w: any) => (
                <TableRow key={w.id} hover sx={{ cursor: 'pointer' }} onClick={() => nav(`/work-orders/${w.id}`)}>
                  <TableCell>{w.number}</TableCell><TableCell>{w.title}</TableCell><TableCell>{w.assetTag}</TableCell>
                  <TableCell sx={{ color: 'error.main' }}>{date(w.dueDate)}</TableCell><TableCell><StatusChip value={w.status} /></TableCell>
                </TableRow>
              ))}
              {!data?.overdue?.length && <TableRow><TableCell colSpan={5}>{t("Nothing overdue.")}</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Paper>
        <Paper sx={{ p: 2, overflowX: 'auto' }}>
          <Typography variant="subtitle1" fontWeight={600} mb={1}>{t("Upcoming preventive maintenance")}</Typography>
          <Table size="small">
            <TableHead><TableRow><TableCell>{t("Due")}</TableCell><TableCell>{t("Task")}</TableCell><TableCell>{t("Asset")}</TableCell></TableRow></TableHead>
            <TableBody>
              {(data?.upcomingPm ?? []).map((p: any) => (
                <TableRow key={p.id}>
                  <TableCell>{date(p.nextDue)}</TableCell><TableCell>{p.title}</TableCell><TableCell>{p.assetTag}</TableCell>
                </TableRow>
              ))}
              {!data?.upcomingPm?.length && <TableRow><TableCell colSpan={3}>{t("No PM due in the next 14 days.")} <Link to="/pm">{t("View schedules")}</Link></TableCell></TableRow>}
            </TableBody>
          </Table>
        </Paper>
      </Box>
    </>
  );
}
