import { t } from '../i18n';
import { useNavigate } from 'react-router-dom';
import { Button, Paper, Table, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import { useAuth } from '../auth';
import { PageHeader, dateTime, useLookups } from '../components/common';

export default function InstructionsPage() {
  const nav = useNavigate();
  const { isSupervisor } = useAuth();
  const { instructions } = useLookups();
  return (
    <>
      <PageHeader title={t("Work instructions")} subtitle={t("Step-by-step procedures attached to PM schedules and work orders")}
        actions={isSupervisor && <Button variant="contained" onClick={() => nav('/instructions/new')}>{t("New instruction")}</Button>} />
      <Paper sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead><TableRow><TableCell>{t("Title")}</TableCell><TableCell>{t("Version")}</TableCell><TableCell>{t("Est. time")}</TableCell>
            <TableCell>{t("PPE")}</TableCell><TableCell>{t("Updated")}</TableCell></TableRow></TableHead>
          <TableBody>
            {instructions.map((i) => (
              <TableRow key={i.id} hover sx={{ cursor: 'pointer' }} onClick={() => nav(`/instructions/${i.id}`)}>
                <TableCell><b>{i.title}</b></TableCell><TableCell>v{i.version}</TableCell>
                <TableCell>{i.estimatedMinutes ? `${i.estimatedMinutes} min` : '—'}</TableCell>
                <TableCell>{i.requiredPpe}</TableCell><TableCell>{dateTime(i.updatedAt)}</TableCell>
              </TableRow>
            ))}
            {!instructions.length && <TableRow><TableCell colSpan={5}>{t("No work instructions yet.")}</TableCell></TableRow>}
          </TableBody>
        </Table>
      </Paper>
    </>
  );
}
