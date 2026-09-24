package io.github.eladimany.spindle.data.server

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a token resolves to: the real content URI [MediaHttpServer] opens,
 * plus the `icy-name` text it sends back so the Node's display shows the
 * track (bluos-api.md §"Metadata on the Node's display") instead of nothing.
 */
data class ServedTrack(val uri: String, val icyName: String)

/**
 * Maps opaque tokens to [ServedTrack]s for [MediaHttpServer] — the server
 * exposes `/t/{token}`, never a real file path (hard constraint #6). Tokens
 * are scoped to the current queue: [clear] drops every token when the queue
 * is replaced, so a URL handed to the Node from a previous session can't
 * resolve to anything afterward.
 */
@Singleton
class TokenRegistry @Inject constructor() {
    private val tokens = ConcurrentHashMap<String, ServedTrack>()

    fun tokenFor(trackUri: String, icyName: String): String {
        val token = UUID.randomUUID().toString()
        tokens[token] = ServedTrack(trackUri, icyName)
        return token
    }

    fun resolve(token: String): ServedTrack? = tokens[token]

    fun clear() {
        tokens.clear()
    }
}
