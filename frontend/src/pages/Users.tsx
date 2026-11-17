import { t, tEnum } from '../i18n';
import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button, Paper, Table, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import { api, User } from '../api';
import { useAuth } from '../auth';
import { FormDialog, PageHeader, StatusChip, enumOpts, money, useLookups } from '../components/common';

export default function UsersPage() {
  const qc = useQueryClient();
  const { isSupervisor, can } = useAuth();
  const isAdmin = can('ADMIN');
  const { users } = useLookups();
  const [edit, setEdit] = useState<Partial<User> & { password?: string } | null>(null);
  const reload = () => qc.invalidateQueries({ queryKey: ['users'] });

  return (
    <>
      <PageHeader title={t("Employees & users")} subtitle={t("People who request, perform and approve maintenance. Hourly rates drive labor cost.")}
        actions={isAdmin && <Button variant="contained" onClick={() => setEdit({ role: 'TECHNICIAN', active: true, hourlyRate: 0 })}>{t("Add user")}</Button>} />
      <Paper sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead><TableRow><TableCell>{t("Name")}</TableCell><TableCell>{t("Username")}</TableCell><TableCell>{t("Role")}</TableCell><TableCell>{t("Trade")}</TableCell>
            {isSupervisor && <TableCell align="right">{t("Hourly rate")}</TableCell>}<TableCell>{t("Email")}</TableCell><TableCell>{t("Status")}</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {users.map((u) => (
              <TableRow key={u.id} sx={{ opacity: u.active ? 1 : 0.5 }}>
                <TableCell><b>{u.fullName}</b></TableCell><TableCell>{u.username}</TableCell><TableCell>{tEnum(u.role)}</TableCell>
                <TableCell>{u.trade}</TableCell>{isSupervisor && <TableCell align="right">{money(u.hourlyRate)}</TableCell>}
                <TableCell>{u.email}</TableCell><TableCell><StatusChip value={u.active ? 'ACTIVE' : 'RETIRED'} /></TableCell>
                <TableCell>{isAdmin && <Button size="small" onClick={() => setEdit(u)}>{t("Edit")}</Button>}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
      <FormDialog open={!!edit} title={edit?.id ? t('Edit {name}', { name: edit.fullName }) : t('Add user')} initial={edit ?? {}} onClose={() => setEdit(null)}
        fields={[
          { name: 'fullName', label: t("Full name"), required: true, half: true },
          { name: 'username', label: t("Username"), required: !edit?.id, half: true, helper: edit?.id ? t('Username cannot be changed') : undefined },
          { name: 'role', label: t("Role"), type: 'select', half: true, required: true, options: enumOpts('REQUESTER', 'TECHNICIAN', 'SUPERVISOR', 'ADMIN') },
          { name: 'trade', label: t("Trade (Electrician, HVAC…)"), half: true },
          { name: 'hourlyRate', label: t("Hourly rate ($)"), type: 'number', half: true },
          { name: 'email', label: t("Email (for alerts)"), half: true },
          { name: 'password', label: edit?.id ? t('Reset password (leave blank to keep)') : t('Temporary password (8+ chars)'), type: 'password', required: !edit?.id,
            helper: t("User will be asked to change it at next login") },
          ...(edit?.id ? [{ name: 'active', label: t("Active (can log in)"), type: 'checkbox' as const }] : []),
        ]}
        onSubmit={(v) => (v.id ? api.put(`/users/${v.id}`, v) : api.post('/users', v)).then(reload)} />
    </>
  );
}
