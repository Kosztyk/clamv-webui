-- #44: freeze the LDAP issuer used to derive external principal hashes.
-- Keep this table on the same persistent database as external_identities and audit.
CREATE TABLE ldap_directory_identity (
    name VARCHAR(32) PRIMARY KEY,
    directory_id VARCHAR(2048) NOT NULL
);
