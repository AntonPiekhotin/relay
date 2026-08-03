package com.relay.notification.config

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader

/**
 * Wires the Firebase Admin SDK only when `relay.push.fcm.enabled=true`, so a machine without
 * credentials runs exactly as before (LoggingPushSender).
 *
 * Credentials resolve in two ways:
 * 1. `relay.push.fcm.credentials-location` — any Spring resource, e.g.
 *    `classpath:fcm-service-account-credentials.json` or `file:/etc/secrets/fcm.json`.
 *    Classpath keys are a local-dev convenience: the file is gitignored, but `bootJar` bakes
 *    resources into the artifact, so a jar built this way contains the secret — never publish it.
 * 2. Otherwise, Application Default Credentials — the `GOOGLE_APPLICATION_CREDENTIALS` env var,
 *    a `gcloud auth application-default login`, or (on GCP) the machine identity.
 *
 * Failing fast is deliberate: with the flag on but no credentials resolvable, the service
 * refuses to start rather than run silently push-less.
 */
@Configuration
@ConditionalOnProperty("relay.push.fcm.enabled", havingValue = "true")
class FirebaseConfig(
    @Value("\${relay.push.fcm.credentials-location:}") private val credentialsLocation: String,
    private val resourceLoader: ResourceLoader
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun firebaseMessaging(): FirebaseMessaging {
        val app = FirebaseApp.getApps().firstOrNull()
            ?: FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(loadCredentials())
                    .setHttpTransport(NetHttpTransport())
                    .build()
            )
        logger.info("Firebase initialized")
        return FirebaseMessaging.getInstance(app)
    }

    private fun loadCredentials(): GoogleCredentials {
        if (credentialsLocation.isBlank()) {
            logger.info("Loading Firebase credentials via Application Default Credentials")
            return GoogleCredentials.getApplicationDefault()
        }
        val resource = resourceLoader.getResource(credentialsLocation)
        check(resource.exists()) {
            "relay.push.fcm.credentials-location points at '$credentialsLocation' but nothing is there"
        }
        logger.info("Loading Firebase credentials from {}", resource.description)
        return resource.inputStream.use { GoogleCredentials.fromStream(it) }
    }
}