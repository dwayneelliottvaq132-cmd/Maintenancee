import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Paper, Table, TableBody, TableCell, TableHead, TableRow } from '@mui/material';
import { api, errMsg, Location } from '../api';
import { useAuth } from '../auth';
import { FormDialog, PageHeader, opts, useLookups } from '../components/common';

export default function LocationsPage() {
  const qc = useQueryClient();
  const { isSupervisor } = useAuth();
  const lk = useLookups();
  const [edit, setEdit] = useState<Partial<Location> | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const reload = () => { qc.invalidateQueries({ queryKey: ['locations'] }); qc.invalidateQueries({ queryKey: ['assets'] }); };

  // Full path, e.g. "Main Plant / Building B / Boiler Room"
  const path = (l: Location): string => {
    const parts: string[] = [];
    let cur: Location | undefined = l;
    for (let i = 0; cur && i < 10; i++) { parts.unshift(cur.name); cur = cur.parentId ? lk.locationById.get(cur.parentId) : undefined; }
    return parts.join(' / ');
  };
  const sorted = [...lk.locations].sort((a, b) => path(a).localeCompare(path(b)));

  return (
    <>
      <PageHeader title="Locations" subtitle="Sites, buildings, floors and areas"
        actions={isSupervisor && <Button variant="contained" onClick={() => setEdit({})}>Add location</Button>} />
      {err && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setErr(null)}>{err}</Alert>}
      <Paper sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead><TableRow><TableCell>Location</TableCell><TableCell>Description</TableCell><TableCell align="right">Assets</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {sorted.map((l) => (
              <TableRow key={l.id}>
                <TableCell>{path(l)}</TableCell><TableCell>{l.description}</TableCell>
                <TableCell align="right">{lk.assets.filter((a) => a.locationId === l.id).length}</TableCell>
                <TableCell align="right">{isSupervisor && <>
                  <Button size="small" onClick={() => setEdit(l)}>Edit</Button>
                  <Button size="small" color="error" onClick={async () => {
                    if (!confirm(`Delete ${l.name}?`)) return;
                    try { await api.delete(`/locations/${l.id}`); reload(); } catch (e) { setErr(errMsg(e)); }
                  }}>Delete</Button></>}
                </TableCell>
              </TableRow>
            ))}
            {!sorted.length && <TableRow><TableCell colSpan={4}>No locations yet.</TableCell></TableRow>}
          </TableBody>
        </Table>
      </Paper>
      <FormDialog open={!!edit} title={edit?.id ? 'Edit location' : 'Add location'} initial={edit ?? {}} onClose={() => setEdit(null)}
        fields={[
          { name: 'name', label: 'Name', required: true },
          { name: 'parentId', label: 'Inside (parent location)', type: 'select', options: opts(lk.locations.filter((l) => l.id !== edit?.id), (l) => l.id, path) },
          { name: 'description', label: 'Description', type: 'textarea' },
        ]}
        onSubmit={(v) => (v.id ? api.put(`/locations/${v.id}`, v) : api.post('/locations', v)).then(reload)} />
    </>
  );
}
