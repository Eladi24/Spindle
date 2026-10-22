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

    // What the Node actually read, per token — the only way to tell "the Node can't
    // reach this phone" and "the Node had the whole file" from the phone's side.
    private val fetched = ConcurrentHashMap.newKeySet<String>()
    private val servedToEnd = ConcurrentHashMap.newKeySet<String>()

    fun markFetched(token: String) {
        fetched += token
    }

    fun markServedToEnd(token: String) {
        servedToEnd += token
    }

    fun wasFetched(token: String): Boolean = token in fetched

    /** The file's last byte went out for [token] — the Node has (had) all of it. */
    fun wasServedToEnd(token: String): Boolean = token in servedToEnd

    fun clear() {
        tokens.clear()
        fetched.clear()
        servedToEnd.clear()
    }
}
