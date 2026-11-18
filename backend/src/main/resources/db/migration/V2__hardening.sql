-- V2: data-safety rules, history preservation, session revocation, indexes (from code review)

-- Bumped on password change / reset / deactivation; tokens carrying an older value are rejected.
ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0;

-- Steps removed or reworded in an instruction are retired (not deleted/overwritten) when work orders
-- have already checked them, so past check-off history stays accurate.
ALTER TABLE instruction_steps ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

-- ---- value checks ----
ALTER TABLE parts ADD CONSTRAINT chk_parts_qty_nonneg CHECK (qty_on_hand >= 0);
ALTER TABLE parts ADD CONSTRAINT chk_parts_cost_nonneg CHECK (unit_cost >= 0);
ALTER TABLE parts ADD CONSTRAINT chk_parts_reorder_nonneg CHECK (reorder_point >= 0);
ALTER TABLE users ADD CONSTRAINT chk_users_rate_nonneg CHECK (hourly_rate >= 0);
ALTER TABLE assets ADD CONSTRAINT chk_assets_cost_nonneg CHECK (purchase_cost IS NULL OR purchase_cost >= 0);
ALTER TABLE assets ADD CONSTRAINT chk_assets_meter_nonneg CHECK (meter_reading >= 0);
ALTER TABLE labor_entries ADD CONSTRAINT chk_labor_hours_max CHECK (hours <= 24);
ALTER TABLE labor_entries ADD CONSTRAINT chk_labor_rate_nonneg CHECK (hourly_rate >= 0);
ALTER TABLE part_usages ADD CONSTRAINT chk_usage_cost_nonneg CHECK (unit_cost >= 0);
ALTER TABLE pm_schedules ADD CONSTRAINT chk_pm_lead_nonneg CHECK (lead_days >= 0);

-- ---- deletes must not silently erase history ----
-- (the API refuses these deletes with a clear message; the database enforces it as a backstop)
ALTER TABLE work_orders DROP CONSTRAINT work_orders_asset_id_fkey,
    ADD CONSTRAINT work_orders_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES assets(id) ON DELETE RESTRICT;
ALTER TABLE work_orders DROP CONSTRAINT work_orders_location_id_fkey,
    ADD CONSTRAINT work_orders_location_id_fkey FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE RESTRICT;
ALTER TABLE work_orders DROP CONSTRAINT work_orders_instruction_id_fkey,
    ADD CONSTRAINT work_orders_instruction_id_fkey FOREIGN KEY (instruction_id) REFERENCES work_instructions(id) ON DELETE RESTRICT;
ALTER TABLE pm_schedules DROP CONSTRAINT pm_schedules_instruction_id_fkey,
    ADD CONSTRAINT pm_schedules_instruction_id_fkey FOREIGN KEY (instruction_id) REFERENCES work_instructions(id) ON DELETE RESTRICT;
ALTER TABLE wo_step_results DROP CONSTRAINT wo_step_results_step_id_fkey,
    ADD CONSTRAINT wo_step_results_step_id_fkey FOREIGN KEY (step_id) REFERENCES instruction_steps(id) ON DELETE RESTRICT;
ALTER TABLE assets DROP CONSTRAINT assets_location_id_fkey,
    ADD CONSTRAINT assets_location_id_fkey FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE RESTRICT;
ALTER TABLE assets DROP CONSTRAINT assets_parent_asset_id_fkey,
    ADD CONSTRAINT assets_parent_asset_id_fkey FOREIGN KEY (parent_asset_id) REFERENCES assets(id) ON DELETE RESTRICT;
ALTER TABLE locations DROP CONSTRAINT locations_parent_id_fkey,
    ADD CONSTRAINT locations_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES locations(id) ON DELETE RESTRICT;
ALTER TABLE maintenance_requests DROP CONSTRAINT maintenance_requests_asset_id_fkey,
    ADD CONSTRAINT maintenance_requests_asset_id_fkey FOREIGN KEY (asset_id) REFERENCES assets(id) ON DELETE RESTRICT;
ALTER TABLE maintenance_requests DROP CONSTRAINT maintenance_requests_location_id_fkey,
    ADD CONSTRAINT maintenance_requests_location_id_fkey FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE RESTRICT;

-- ---- indexes for foreign keys used in lookups and reports ----
CREATE INDEX IF NOT EXISTS idx_labor_wo ON labor_entries(work_order_id);
CREATE INDEX IF NOT EXISTS idx_labor_user_date ON labor_entries(user_id, work_date);
CREATE INDEX IF NOT EXISTS idx_part_usages_wo ON part_usages(work_order_id);
CREATE INDEX IF NOT EXISTS idx_part_usages_part ON part_usages(part_id);
CREATE INDEX IF NOT EXISTS idx_other_costs_wo ON other_costs(work_order_id);
CREATE INDEX IF NOT EXISTS idx_pm_asset ON pm_schedules(asset_id);
CREATE INDEX IF NOT EXISTS idx_stock_tx_part ON stock_transactions(part_id);
CREATE INDEX IF NOT EXISTS idx_requests_requester ON maintenance_requests(requester_id);
CREATE INDEX IF NOT EXISTS idx_wo_assign_user ON wo_assignments(user_id);
CREATE INDEX IF NOT EXISTS idx_wo_pm ON work_orders(pm_schedule_id);
CREATE INDEX IF NOT EXISTS idx_wo_due ON work_orders(due_date);
CREATE INDEX IF NOT EXISTS idx_steps_instruction ON instruction_steps(instruction_id);
CREATE INDEX IF NOT EXISTS idx_audit_entity ON audit_log(entity, entity_id);
