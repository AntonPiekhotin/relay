package com.relay.apigateway.config

import org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions.lb
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
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
            .build()
}