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
