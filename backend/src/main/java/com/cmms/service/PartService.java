package com.cmms.service;

import com.cmms.entity.Enums.StockTxType;
import com.cmms.entity.Part;
import com.cmms.entity.StockTransaction;
import com.cmms.repo.PartRepository;
import com.cmms.repo.StockTransactionRepository;
import com.cmms.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * All stock changes lock the part row first (SELECT ... FOR UPDATE), so two people using or receiving
 * the same part at the same moment can't overwrite each other's quantity. The database also refuses
 * negative stock (chk_parts_qty_nonneg).
 */
@Service
public class PartService {
    private final PartRepository parts;
    private final StockTransactionRepository txs;

    public PartService(PartRepository parts, StockTransactionRepository txs) {
        this.parts = parts;
        this.txs = txs;
    }

    public Part get(Long id) {
        return parts.findById(id).orElseThrow(() -> ApiException.notFound("Part", id));
    }

    private Part lock(Long id) {
        return parts.lockById(id).orElseThrow(() -> ApiException.notFound("Part", id));
    }

    /**
     * Receive stock. When a unit cost is given, the part's cost becomes the weighted average of the
     * old and new stock. Pass unitCost = null to keep the current cost.
     */
    @Transactional
    public Part receive(Long partId, BigDecimal qty, BigDecimal unitCost, String reference, Long userId) {
        if (qty == null || qty.signum() <= 0) throw ApiException.bad("Quantity must be greater than zero");
        if (unitCost != null && unitCost.signum() < 0) throw ApiException.bad("Unit cost cannot be negative");
        Part p = lock(partId);
        if (unitCost != null) {
            BigDecimal oldValue = p.unitCost.multiply(p.qtyOnHand);
            BigDecimal newQty = p.qtyOnHand.add(qty);
            p.unitCost = oldValue.add(unitCost.multiply(qty)).divide(newQty, 2, RoundingMode.HALF_UP);
        }
        p.qtyOnHand = p.qtyOnHand.add(qty);
        record(p.id, StockTxType.RECEIVE, qty, unitCost != null ? unitCost : p.unitCost, reference, userId);
        return parts.save(p);
    }

    @Transactional
    public Part adjust(Long partId, BigDecimal newQty, String reason, Long userId) {
        if (newQty == null || newQty.signum() < 0) throw ApiException.bad("Counted quantity cannot be negative");
        Part p = lock(partId);
        BigDecimal diff = newQty.subtract(p.qtyOnHand);
        p.qtyOnHand = newQty;
        record(p.id, StockTxType.ADJUST, diff, p.unitCost, reason, userId);
        return parts.save(p);
    }

    /** Issue stock to a work order. Fails if not enough on hand. Returns the unit cost used. */
    @Transactional
    public BigDecimal issue(Long partId, BigDecimal qty, String reference, Long userId) {
        Part p = lock(partId);
        if (!p.active) throw ApiException.conflict(p.name + " is no longer active");
        if (p.qtyOnHand.compareTo(qty) < 0) {
            throw ApiException.conflict("Not enough " + p.name + " in stock (on hand " + p.qtyOnHand.stripTrailingZeros().toPlainString()
                    + " " + p.unit + ", requested " + qty.stripTrailingZeros().toPlainString() + ")");
        }
        p.qtyOnHand = p.qtyOnHand.subtract(qty);
        parts.save(p);
        record(p.id, StockTxType.ISSUE, qty.negate(), p.unitCost, reference, userId);
        return p.unitCost;
    }

    @Transactional
    public void returnToStock(Long partId, BigDecimal qty, String reference, Long userId) {
        Part p = lock(partId);
        p.qtyOnHand = p.qtyOnHand.add(qty);
        parts.save(p);
        record(p.id, StockTxType.RETURN, qty, p.unitCost, reference, userId);
    }

    private void record(Long partId, StockTxType type, BigDecimal qty, BigDecimal cost, String ref, Long userId) {
        StockTransaction t = new StockTransaction();
        t.partId = partId;
        t.type = type;
        t.qty = qty;
        t.unitCost = cost;
        t.reference = ref;
        t.userId = userId;
        txs.save(t);
    }
}
