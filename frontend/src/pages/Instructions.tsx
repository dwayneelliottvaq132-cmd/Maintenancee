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
      <PageHeader title="Work instructions" subtitle="Step-by-step procedures attached to PM schedules and work orders"
        actions={isSupervisor && <Button variant="contained" onClick={() => nav('/instructions/new')}>New instruction</Button>} />
      <Paper sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead><TableRow><TableCell>Title</TableCell><TableCell>Version</TableCell><TableCell>Est. time</TableCell>
            <TableCell>PPE</TableCell><TableCell>Updated</TableCell></TableRow></TableHead>
          <TableBody>
            {instructions.map((i) => (
              <TableRow key={i.id} hover sx={{ cursor: 'pointer' }} onClick={() => nav(`/instructions/${i.id}`)}>
                <TableCell><b>{i.title}</b></TableCell><TableCell>v{i.version}</TableCell>
                <TableCell>{i.estimatedMinutes ? `${i.estimatedMinutes} min` : '—'}</TableCell>
                <TableCell>{i.requiredPpe}</TableCell><TableCell>{dateTime(i.updatedAt)}</TableCell>
              </TableRow>
            ))}
            {!instructions.length && <TableRow><TableCell colSpan={5}>No work instructions yet.</TableCell></TableRow>}
          </TableBody>
        </Table>
      </Paper>
    </>
  );
}
