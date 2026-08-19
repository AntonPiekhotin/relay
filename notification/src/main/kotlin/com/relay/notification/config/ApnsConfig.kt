package com.relay.notification.config

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.ApnsClientBuilder
import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader

/**
 * Wires the APNs client only when `relay.push.apns.enabled=true`, so a machine without an Apple
 * signing key runs exactly as before — iOS call pushes stay on FCM's data-only path (which cannot
 * ring a locked phone; that limitation is why this config exists at all).
 *
 * Token-based auth (a .p8 signing key + team id + key id), never certificates — one key serves
 * every app under the team and does not expire annually. The key resolves like the FCM
 * credentials: any Spring resource location, defaulting to the gitignored `secrets/` directory
 * outside the build tree, so no jar can bake it in.
 *
 * Failing fast is deliberate and mirrors [FirebaseConfig]: with the flag on but the key missing
 * or the ids blank, the service refuses to start rather than run silently VoIP-less.
 */
@Configuration
@ConditionalOnProperty("relay.push.apns.enabled", havingValue = "true")
class ApnsConfig(
    @Value("\${relay.push.apns.signing-key-location}") private val signingKeyLocation: String,
    @Value("\${relay.push.apns.team-id}") private val teamId: String,
    @Value("\${relay.push.apns.key-id}") private val keyId: String,
    @Value("\${relay.push.apns.environment:sandbox}") private val environment: String,
    private val resourceLoader: ResourceLoader
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun apnsClient(): ApnsClient {
        check(teamId.isNotBlank() && keyId.isNotBlank()) {
            "relay.push.apns.enabled is true but team-id or key-id is blank"
        }
        val resource = resourceLoader.getResource(signingKeyLocation)
        check(resource.exists()) {
            "relay.push.apns.signing-key-location points at '$signingKeyLocation' but nothing is there"
        }
        val host = when (environment.lowercase()) {
            "production" -> ApnsClientBuilder.PRODUCTION_APNS_HOST
            else -> ApnsClientBuilder.DEVELOPMENT_APNS_HOST
        }
        logger.info("APNs client starting against {} with key {}", host, resource.description)
        return ApnsClientBuilder()
            .setApnsServer(host)
            .setSigningKey(
                resource.inputStream.use { ApnsSigningKey.loadFromInputStream(it, teamId, keyId) }
            )
            .build()
        // Spring's inferred destroy method calls close(), which drains in-flight sends.
    }
}
