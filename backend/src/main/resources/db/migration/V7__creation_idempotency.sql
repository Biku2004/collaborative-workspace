CREATE TABLE creation_requests (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    operation VARCHAR(100) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_payload TEXT NOT NULL,
    response_json TEXT,
    PRIMARY KEY (user_id, operation, request_key)
);
