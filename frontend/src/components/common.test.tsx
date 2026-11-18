import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { FormDialog, money } from './common';

describe('FormDialog', () => {
  it('blocks submit until required fields are filled, then submits values', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const onClose = vi.fn();
    render(<FormDialog open title="New request" initial={{ priority: 'MEDIUM' }} onClose={onClose} onSubmit={onSubmit}
      fields={[{ name: 'title', label: 'Problem', required: true }, { name: 'hours', label: 'Hours', type: 'number' }]} />);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/Please fill in: Problem/)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText(/Problem/), { target: { value: 'Leaking valve' } });
    fireEvent.change(screen.getByLabelText(/Hours/), { target: { value: '2.5' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith({ priority: 'MEDIUM', title: 'Leaking valve', hours: 2.5 }));
    expect(onClose).toHaveBeenCalled();
  });

  it('shows the API error message and stays open when save fails', async () => {
    const onClose = vi.fn();
    const err = { response: { data: { error: 'Not enough Air filter in stock' } } };
    render(<FormDialog open title="Use part" initial={{}} onClose={onClose} onSubmit={() => Promise.reject(err)}
      fields={[{ name: 'qty', label: 'Qty', type: 'number' }]} />);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Not enough Air filter in stock')).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });
});

describe('money', () => {
  it('formats numbers and numeric strings as currency', () => {
    expect(money('1234.5')).toBe('$1,234.50');
    expect(money(null)).toBe('$0.00');
  });
});

describe('dates', () => {
  it('today() is the local calendar date, not the UTC one', async () => {
    const { today, isoLocal } = await import('./common');
    const d = new Date();
    expect(today()).toBe(isoLocal(d));
    expect(isoLocal(new Date(2026, 10, 18, 23, 30))).toBe('2026-11-18');
  });
  it('calendar month steps clamp to month end like the server', async () => {
    const { addMonths } = await import('../pages/PmSchedules');
    const feb = addMonths(new Date(2026, 0, 31), 1);
    expect([feb.getMonth(), feb.getDate()]).toEqual([1, 28]);
    const leap = addMonths(new Date(2024, 0, 31), 1);
    expect(leap.getDate()).toBe(29);
  });
});

describe('FormDialog reopening', () => {
  it('discards an abandoned edit when opened again', async () => {
    const { rerender } = render(<FormDialog open title="Edit" initial={{ title: 'Pump' }} onClose={() => {}} onSubmit={vi.fn()}
      fields={[{ name: 'title', label: 'Title' }]} />);
    fireEvent.change(screen.getByLabelText(/Title/), { target: { value: 'Typo' } });
    rerender(<FormDialog open={false} title="Edit" initial={{ title: 'Pump' }} onClose={() => {}} onSubmit={vi.fn()} fields={[{ name: 'title', label: 'Title' }]} />);
    rerender(<FormDialog open title="Edit" initial={{ title: 'Pump' }} onClose={() => {}} onSubmit={vi.fn()} fields={[{ name: 'title', label: 'Title' }]} />);
    expect((screen.getByLabelText(/Title/) as HTMLInputElement).value).toBe('Pump');
  });
  it('keeps what is being typed when the parent re-renders with a new initial object', () => {
    const { rerender } = render(<FormDialog open title="New" initial={{}} onClose={() => {}} onSubmit={vi.fn()} fields={[{ name: 'title', label: 'Title' }]} />);
    fireEvent.change(screen.getByLabelText(/Title/), { target: { value: 'Half typed' } });
    rerender(<FormDialog open title="New" initial={{}} onClose={() => {}} onSubmit={vi.fn()} fields={[{ name: 'title', label: 'Title' }]} />);
    expect((screen.getByLabelText(/Title/) as HTMLInputElement).value).toBe('Half typed');
  });
});
