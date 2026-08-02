-- Baseline for the user service schema.
--
-- Captures what `ddl-auto` had been producing up to this point, so it is a starting line rather
-- than a change: an existing database and a fresh one end up identical. From here the schema
-- moves only through migrations, and `ddl-auto: validate` holds the entities to it.
--
-- Constraint and index names are spelled out. Left implicit, Postgres invents them
-- (`users_email_key`), and a later migration that has to drop one would be guessing.

-- Profiles. `users`, not `user`, because `user` is reserved in Postgres.
-- The id is the Keycloak subject rather than a generated key, so a profile and its identity
-- share a primary key (ARCHITECTURE.md §8.1).
CREATE TABLE users (
    id          varchar(64)              NOT NULL,
    email       varchar(256)             NOT NULL,
    first_name  varchar(128)             NOT NULL,
    last_name   varchar(128)             NOT NULL,
    avatar_url  varchar(512),
    created_at  timestamp(6) with time zone NOT NULL,
    updated_at  timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);

-- Search orders by last name; without this it is a sequential scan over every profile.
CREATE INDEX idx_users_last_name ON users (last_name);

-- Avatar bytes, deliberately in their own table: profile reads, search and contact listing all
-- select whole `User` entities, and a blob on that table would ride along with every one of them.
--
-- `bytea`, not `oid`. Postgres large objects carry their own lifecycle and vacuum problems, and
-- the entity pins the JDBC type to VARBINARY precisely to avoid them.
CREATE TABLE user_avatars (
    user_id      varchar(64)              NOT NULL,
    content_type varchar(64)              NOT NULL,
    bytes        bytea                    NOT NULL,
    size_bytes   integer                  NOT NULL,
    updated_at   timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_user_avatars PRIMARY KEY (user_id)
);

-- A directed edge: `owner_id` keeps `contact_user_id` in their list. Adding is one-sided.
--
-- Column order in the primary key is load-bearing and is NOT what Hibernate generated. Hibernate
-- ordered the composite key alphabetically — (contact_user_id, owner_id) — which leaves owner_id
-- as the trailing column, so the every-request queries that filter on owner_id alone
-- (list, count, exists) could not use the primary key index at all. Owner first fixes that; the
-- separate index below still answers the reverse direction.
CREATE TABLE contacts (
    owner_id        varchar(64)              NOT NULL,
    contact_user_id varchar(64)              NOT NULL,
    created_at      timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_contacts PRIMARY KEY (owner_id, contact_user_id)
);

-- "Who has me" — needed the moment a user is notified that somebody added them (§16.3).
CREATE INDEX idx_contacts_contact_user ON contacts (contact_user_id);