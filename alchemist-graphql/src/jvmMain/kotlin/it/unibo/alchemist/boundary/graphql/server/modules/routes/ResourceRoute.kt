/*
 * Copyright (C) 2010-2023, Danilo Pianini and contributors
 * listed, for each module, in the respective subproject's build.gradle.kts file.
 *
 * This file is part of Alchemist, and is distributed under the terms of the
 * GNU General Public License, with a linking exception,
 * as described in the file LICENSE in the Alchemist distribution's top directory.
 */

package it.unibo.alchemist.boundary.graphql.server.modules.routes

import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Route

/**
 * Loads the index file when clients request the root path.
 */
fun Route.resourceRoute() {
    staticResources("/", "", index = "index.html")
}
