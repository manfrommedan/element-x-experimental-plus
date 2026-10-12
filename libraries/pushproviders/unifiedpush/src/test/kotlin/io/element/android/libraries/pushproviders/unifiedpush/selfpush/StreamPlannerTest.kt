/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush.selfpush

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamPlannerTest {
    private val registrations = mapOf("https://ntfy.sh" to setOf("upA", "upB"))

    private fun activeStream(
        protocol: SelfPushProtocol = SelfPushProtocol.WEBSOCKET,
        readToken: String? = null,
        topics: Set<String> = setOf("upA", "upB"),
        alive: Boolean = true,
    ) = ActiveStreamInfo(protocol = protocol, readToken = readToken, topics = topics, alive = alive)

    private fun plan(
        active: Map<String, ActiveStreamInfo>,
        registrations: Map<String, Set<String>> = this.registrations,
        protocol: SelfPushProtocol = SelfPushProtocol.WEBSOCKET,
        readToken: String? = null,
        force: Boolean = false,
    ) = planStreams(registrations, active, protocol, { readToken }, force)

    @Test
    fun `matching live stream is kept`() {
        val plan = plan(mapOf("https://ntfy.sh" to activeStream()))

        assertThat(plan.toOpen).isEmpty()
        assertThat(plan.toCancel).isEmpty()
    }

    @Test
    fun `missing server opens, gone server cancels`() {
        val plan = plan(
            active = mapOf("https://old.example.org" to activeStream()),
            registrations = mapOf("https://ntfy.sh" to setOf("upA")),
        )

        assertThat(plan.toOpen).isEqualTo(mapOf("https://ntfy.sh" to setOf("upA")))
        assertThat(plan.toCancel).containsExactly("https://old.example.org")
    }

    @Test
    fun `a second account joining reopens the server stream with both topics`() {
        val plan = plan(
            active = mapOf("https://ntfy.sh" to activeStream(topics = setOf("upA"))),
        )

        assertThat(plan.toOpen).isEqualTo(mapOf("https://ntfy.sh" to setOf("upA", "upB")))
        assertThat(plan.toCancel).containsExactly("https://ntfy.sh")
    }

    @Test
    fun `protocol change reopens`() {
        val plan = plan(
            active = mapOf("https://ntfy.sh" to activeStream()),
            protocol = SelfPushProtocol.JSON_STREAM,
        )

        assertThat(plan.toOpen).isEqualTo(registrations)
        assertThat(plan.toCancel).containsExactly("https://ntfy.sh")
    }

    @Test
    fun `token change reopens`() {
        val plan = plan(
            active = mapOf("https://ntfy.sh" to activeStream()),
            readToken = "tk_new",
        )

        assertThat(plan.toOpen).isEqualTo(registrations)
    }

    @Test
    fun `dead stream counts as absent`() {
        val plan = plan(
            active = mapOf("https://ntfy.sh" to activeStream(alive = false)),
        )

        assertThat(plan.toOpen).isEqualTo(registrations)
        // The dead job is not cancelled again; the map entry is simply replaced.
        assertThat(plan.toCancel).isEmpty()
    }

    @Test
    fun `force reopens even a matching stream`() {
        val plan = plan(
            active = mapOf("https://ntfy.sh" to activeStream()),
            force = true,
        )

        assertThat(plan.toOpen).isEqualTo(registrations)
        assertThat(plan.toCancel).containsExactly("https://ntfy.sh")
    }

    @Test
    fun `token is scoped per server`() {
        val tokens = mapOf("https://a.example.org" to "tk_a", "https://b.example.org" to null)
        val active = mapOf(
            "https://a.example.org" to activeStream(readToken = "tk_a", topics = setOf("upA")),
            "https://b.example.org" to activeStream(readToken = null, topics = setOf("upB")),
        )
        val registrations = mapOf("https://a.example.org" to setOf("upA"), "https://b.example.org" to setOf("upB"))

        val plan = planStreams(registrations, active, SelfPushProtocol.WEBSOCKET, { tokens[it] }, force = false)

        assertThat(plan.toOpen).isEmpty()
        assertThat(plan.toCancel).isEmpty()
    }
}
