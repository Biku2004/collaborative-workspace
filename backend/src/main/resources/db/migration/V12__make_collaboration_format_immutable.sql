-- A future cross-format conversion must use an explicit migration and epoch plan.
CREATE FUNCTION prevent_collaboration_format_change() RETURNS trigger AS $$
BEGIN
    IF NEW.collaboration_format IS DISTINCT FROM OLD.collaboration_format THEN
        RAISE EXCEPTION 'collaboration format is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER documents_collaboration_format_immutable
    BEFORE UPDATE OF collaboration_format ON documents
    FOR EACH ROW EXECUTE FUNCTION prevent_collaboration_format_change();
