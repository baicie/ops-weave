-- Twenty bounded metric metadata rows, no metric values or source bodies.
ALTER TABLE integration.source_inspection DROP CONSTRAINT source_inspection_body_check;
ALTER TABLE integration.source_inspection ADD CONSTRAINT source_inspection_body_check
 CHECK (octet_length(body::text)<=262144);
