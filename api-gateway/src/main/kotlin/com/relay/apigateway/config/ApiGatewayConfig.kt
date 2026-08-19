package com.relay.apigateway.config

import com.relay.common.observability.RequestId
import org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions.lb
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import org.springframework.web.servlet.function.HandlerFilterFunction
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Configuration
class ApiGatewayConfig {

    @Bean
    fun restTemplate(): RestTemplate {
        return RestTemplate()
    }

    @Bean
    fun authRoute(): RouterFunction<ServerResponse> = forward("auth", "/api/v1/auth/**")

    @Bean
    fun userRoute(): RouterFunction<ServerResponse> = forward("user", "/api/v1/user/**")

    @Bean
    fun callRoute(): RouterFunction<ServerResponse> = forward("call", "/api/v1/call/**")

    @Bean
    fun messageRoute(): RouterFunction<ServerResponse> = forward("message", "/api/v1/message/**")

    @Bean
    fun notificationRoute(): RouterFunction<ServerResponse> =
        forward("notification", "/api/v1/notification/**")

    /**
     * One route per service, each declared as its own [RouterFunction] bean — the MVC flavour of
     * the gateway composes routes from beans rather than from a single `RouteLocator`.
     *
     * [lb] resolves `serviceId` through Eureka and puts the chosen instance's URI on the request,
     * which is what the argument-less [http] handler then proxies to.
     */
    private fun forward(serviceId: String, pathPattern: String): RouterFunction<ServerResponse> =
        route(serviceId)
            .route(RequestPredicates.path(pathPattern), http())
            .filter(lb(serviceId))
            .filter(propagateRequestId())
            .build()

    /**
     * Copies this request's correlation id onto the proxied request, so the downstream service's
     * own `RequestIdFilter` adopts it rather than minting a second one and breaking the chain.
     *
     * `RequestIdFilter` from `common` has already established the id in the MDC by the time a route
     * runs; this only has to forward it. Applied inside [forward], so all five routes get it from
     * one place.
     *
     * A `HandlerFilterFunction` rather than `BeforeFilterFunctions.addRequestHeader`, because that
     * helper only expands URI variables and cannot take a value computed per request. Gateway MVC
     * proxies from the [ServerRequest], so the header has to be added there rather than on the
     * `HttpServletRequest`.
     */
    private fun propagateRequestId(): HandlerFilterFunction<ServerResponse, ServerResponse> =
        HandlerFilterFunction { request, next ->
            next.handle(
                ServerRequest.from(request)
                    .header(RequestId.HEADER, RequestId.currentOrNew())
                    .build(),
            )
        }
}