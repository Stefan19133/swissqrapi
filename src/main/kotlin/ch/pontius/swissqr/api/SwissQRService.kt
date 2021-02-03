package ch.pontius.swissqr.api

import ch.pontius.swissqr.api.basics.*
import ch.pontius.swissqr.api.cli.Cli
import ch.pontius.swissqr.api.db.DataAccessLayer
import ch.pontius.swissqr.api.handlers.qr.GenerateQRCodeHandler
import ch.pontius.swissqr.api.handlers.qr.GenerateQRCodeSimpleHandler
import ch.pontius.swissqr.api.handlers.qr.ScanQRCodeHandler
import ch.pontius.swissqr.api.model.service.status.Status
import ch.pontius.swissqr.api.model.config.Config
import ch.pontius.swissqr.api.model.access.Access
import ch.pontius.swissqr.api.model.users.Token
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder
import io.javalin.apibuilder.ApiBuilder.after
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.plugin.openapi.OpenApiOptions
import io.javalin.plugin.openapi.OpenApiPlugin
import io.javalin.plugin.openapi.ui.SwaggerOptions
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Entry point for Swiss QR API Service.
 *
 * @author Ralph Gasser
 * @version 1.0.0
 */
fun main(args: Array<String>) {

    val logger = LoggerFactory.getLogger("ch.pontius.swissqr.api.Main")

    /* Load config file and start service. */
    val configPath = Paths.get(args.firstOrNull() ?: "./config.json")
    val config = Files.newBufferedReader(configPath).use { reader ->
        kotlinx.serialization.json.Json.decodeFromString(Config.serializer(), reader.readText())
    }

    /* Initialize data access layer. */
    val dataAccessLayer = DataAccessLayer(config.data)

    /* Prepare list of HTTP handlers. */
    val handlers = listOf(
        GenerateQRCodeHandler(),
        GenerateQRCodeSimpleHandler(),
        ScanQRCodeHandler()
    )

    /* Initialize Javalin. */
    val javalin = Javalin.create { c ->
        c.registerPlugin(OpenApiPlugin(getOpenApiOptions()))
        c.defaultContentType = "application/json"
        c.accessManager(AccessManager(dataAccessLayer.tokenStore))
        c.enableCorsForAllOrigins()
        c.requestLogger { ctx, f -> logger.info("Request ${ctx.req.requestURI} completed in ${f}ms.")}
        c.server { config.server.server() }
        c.enforceSsl = config.server.sslEnforce
    }.routes {
        path("api") {
            path("public") {
                handlers.forEach { handler ->
                    path(handler.route) {
                        when (handler) {
                            is GetRestHandler -> ApiBuilder.get(handler::get, handler.requiredPermissions)
                            is PostRestHandler -> ApiBuilder.post(handler::post, handler.requiredPermissions)
                            is PatchRestHandler -> ApiBuilder.patch(handler::patch, handler.requiredPermissions)
                            is DeleteRestHandler -> ApiBuilder.delete(handler::delete, handler.requiredPermissions)
                        }
                    }
                }
                after {
                    val token = it.attribute<Token>(AccessManager.API_KEY_PARAM)
                    if (token is Token) {
                        dataAccessLayer.accessLogs.append(Access(token.id, it.ip(), it.path(), it.method(), it.status(), System.currentTimeMillis()))
                    }
                }
            }
        }
    }.before {
        logger.info("${it.req.method} request to ${it.path()} with params (${it.queryParamMap().map { e -> "${e.key}=${e.value}" }.joinToString()}) from ${it.req.remoteAddr}")
    }.error(401) {
        it.json(Status.ErrorStatus(401, "Unauthorized request!"))
    }.exception(Exception::class.java) { e, ctx ->
        ctx.status(500).json(Status.ErrorStatus(500, "Internal server error: ${e.message}"))
        logger.error("Exception during handling of request to ${ctx.path()}", e)
    }.start()

    /* Starts CLI loop (blocking). */
    Cli(dataAccessLayer, config).loop()

    /* Continues when CLI exits; causes Javalin will shutdown. */
    javalin.stop()
}


private fun getOpenApiOptions(): OpenApiOptions {
    val contact = Contact()
    contact.name = "pontius software GmbH"
    contact.email = "rg@pontius.ch"
    contact.url = "https://www.pontius.ch"

    val applicationInfo: Info = Info()
        .version("1.0.0")
        .title("Swiss QR Web Service")
        .description("A web service that can be used to generate and read Swiss QR codes.")
        .contact(contact)

    return OpenApiOptions(applicationInfo).apply {
        path("/swagger-docs") // endpoint for OpenAPI json
        swagger(SwaggerOptions("/swagger-ui"))
        activateAnnotationScanningFor("ch.pontius.swissqr.api.handlers.qr")
    }
}