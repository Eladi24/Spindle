package io.github.eladimany.spindle.data.server

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps opaque tokens to content-URI strings for [MediaHttpServer] — the
 * server exposes `/t/{token}`, never a real file path (hard constraint #6).
 * Tokens are scoped to the current queue: [clear] drops every token when the
 * queue is replaced, so a URL handed to the Node from a previous session
 * can't resolve to anything afterward.
 */
@Singleton
class TokenRegistry @Inject constructor() {
    private val tokens = ConcurrentHashMap<String, String>()

    fun tokenFor(trackUri: String): String {
        val token = UUID.randomUUID().toString()
        tokens[token] = trackUri
        return token
    }

    fun resolve(token: String): String? = tokens[token]

    fun clear() {
        tokens.clear()
    }
}
