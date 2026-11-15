package com.guard.server

import io.ktor.http.HttpStatusCode

class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

fun badRequest(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, message)
fun unauthorized(message: String = "Authentication required"): Nothing =
    throw ApiException(HttpStatusCode.Unauthorized, message)
fun forbidden(message: String = "Not allowed"): Nothing =
    throw ApiException(HttpStatusCode.Forbidden, message)
fun notFound(message: String = "Not found"): Nothing =
    throw ApiException(HttpStatusCode.NotFound, message)
fun conflict(message: String): Nothing = throw ApiException(HttpStatusCode.Conflict, message)
