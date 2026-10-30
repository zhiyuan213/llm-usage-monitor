ALTER TABLE admin_refunds
 ADD COLUMN provider_http_status integer,
 ADD COLUMN provider_error_code text,
 ADD COLUMN provider_error_message text,
 ADD COLUMN provider_error_stage text,
 ADD COLUMN provider_error_at timestamptz,
 ADD COLUMN provider_error_request_id text;
