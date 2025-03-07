/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.swagger

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlin.test.*

class SwaggerTest {

    @Test
    fun testSwaggerFromResources() = testApplication {
        routing {
            swaggerUI("swagger")
        }

        val response = client.get("/swagger").bodyAsText()
        assertEquals(
            """
            <!DOCTYPE html>
            <html>
              <head>
                <title>Swagger UI</title>
                <link href="https://unpkg.com/swagger-ui-dist@4.14.0/swagger-ui.css" rel="stylesheet">
              </head>
              <body>
                <div id="swagger-ui"></div>
                <script src="https://unpkg.com/swagger-ui-dist@4.14.0/swagger-ui-bundle.js" crossorigin="anonymous"></script>
                <script src="https://unpkg.com/swagger-ui-dist@4.14.0/swagger-ui-standalone-preset.js" crossorigin="anonymous"></script>
                <script>window.onload = function() {
                window.ui = SwaggerUIBundle({
                    url: '/swagger/documentation.json',
                    dom_id: '#swagger-ui',
                    presets: [
                        SwaggerUIBundle.presets.apis,
                        SwaggerUIStandalonePreset
                    ],
                    layout: 'StandaloneLayout'
                });
            }</script>
              </body>
            </html>
            
            """.trimIndent(),
            response
        )
    }

    @Test
    fun testSwaggerFileIsServed() = testApplication {
        routing {
            swaggerUI("swagger")
        }

        val response = client.get("/swagger/documentation.json").bodyAsText()
        assertEquals("""{"hello":"world"}""".filter { it.isLetterOrDigit() }, response.filter { it.isLetterOrDigit() })
    }
}
