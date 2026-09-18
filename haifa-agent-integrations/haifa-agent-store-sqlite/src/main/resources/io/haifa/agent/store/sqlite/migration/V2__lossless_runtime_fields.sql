ALTER TABLE run ADD COLUMN waiting_request_type TEXT;
ALTER TABLE run ADD COLUMN termination_description TEXT;
ALTER TABLE configuration_snapshot ADD COLUMN content_payload_hash TEXT;
ALTER TABLE checkpoint_payload ADD COLUMN payload_hash TEXT;
