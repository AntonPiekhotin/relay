package com.relay.user.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * Hard ceiling on a stored picture, and the declared column width.
 *
 * 1 MiB is not arbitrary: it is the largest `VARBINARY` Hibernate will emit for H2 (`getMaxVarchar
 * Length`), and past it the mapping degrades to a large-object type on every dialect — see below.
 * A profile picture that does not fit wants an object store, not a wider column.
 */
const val AVATAR_COLUMN_BYTES = 1024 * 1024

/**
 * The picture bytes, in their own table rather than a column on [User]: profile reads, search
 * and contact listing all select whole `User` entities, and a blob on that entity would ride
 * along with every one of them.
 *
 * Bytes in Postgres are the deliberate first cut — no object store exists in the deployment yet
 * (ARCHITECTURE.md §18 media plane is unbuilt). The serving URL is ours, so moving to S3/MinIO
 * later is a change to this adapter and nothing on the client.
 *
 * The JDBC type is pinned to `VARBINARY` rather than left to the dialect. Neither `@Lob` nor a large
 * `length` works: both make Hibernate reach for a large-object type — `oid` on Postgres (server-side
 * large objects, with their own lifecycle and vacuum problems) and `blob` on H2, which the
 * PostgreSQL compatibility mode does not even have. Pinned, it is `bytea` on Postgres and
 * `varbinary(n)` on H2, which is what a few hundred KB of picture wants to be.
 */
@Entity
@Table(name = "user_avatars")
class UserAvatar(

    @Id
    @Column(name = "user_id", nullable = false, updatable = false, length = 64)
    val userId: String,

    @Column(name = "content_type", nullable = false, length = 64)
    var contentType: String,

    @Column(name = "bytes", nullable = false, length = AVATAR_COLUMN_BYTES)
    @JdbcTypeCode(SqlTypes.VARBINARY)
    var bytes: ByteArray,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Int,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)