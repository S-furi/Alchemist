/*
 * Copyright (C) 2010-2023, Danilo Pianini and contributors
 * listed, for each module, in the respective subproject's build.gradle.kts file.
 *
 * This file is part of Alchemist, and is distributed under the terms of the
 * GNU General Public License, with a linking exception,
 * as described in the file LICENSE in the Alchemist distribution's top directory.
 */

package it.unibo.alchemist.boundary.graphql.client

import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private val apolloClient: GraphQLClient =
    DefaultGraphQLClient.Builder()
        .serverUrl()
        .addSubscriptionModule()
        .build()

/**
 * Main entrypoint of the JS application.
 */
fun main() {
    println("Client started, starting simulation...")
    window.onload = { startAndSubscribe() }
}

/**
 * Starts the simulation and subscribes to a node.
 */
fun startAndSubscribe() {
    MainScope().launch {
        println("Starting the simulation...")
        println("${apolloClient.mutation(PlaySimulationMutation()).execute().data?.play}")
        println("Starting client, collecting nodes")
        val data = apolloClient.subscription(NodeSubscription(1))
        data.toFlow()
            .onEach {
                it.data?.node?.let { node ->
                    println(
                        "Got node: ${node.id} with molecule:" +
                            "${node.contents.entries[0].molecule} -> ${node.contents.entries[0].concentration}",
                    )
                }
                // delay(100)
            }.collect()
    }
}
