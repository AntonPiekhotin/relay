-- Baseline for the notification service schema.
--
-- Captures what `ddl-auto: update` had accumulated up to this point, so an existing database and a
-- fresh one end up identical. From here the schema moves only through migrations, and
-- `ddl-auto: validate` holds the entities to it.

-- One row per (user, device): a user carries several devices, and each re-registers its token in
-- place — hence the composite key rather than a surrogate id (ARCHITECTURE.md §19.4).
--
-- `fcm_token` and `voip_token` are separate columns on purpose (decision 26). On iOS, PushKit VoIP
-- tokens are issued through a different mechanism than APNs tokens and are not interchangeable;
-- merging them into one column would force a migration the moment calls ship.
--
-- Both are `text` rather than a bounded varchar because FCM makes no promise about token length,
-- and on Postgres `text` and `varchar(n)` are the same storage anyway.
CREATE TABLE device_tokens (
    user_id    varchar(64)              NOT NULL,
    device_id  varchar(128)             NOT NULL,
    platform   varchar(16)              NOT NULL,
    fcm_token  text,
    voip_token text,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_device_tokens PRIMARY KEY (user_id, device_id)
);