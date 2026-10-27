package org.mundhenk.nxbackup.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** BackendSink against a scripted backend built from the same HttpServer. */
class BackendSinkTest {
    private val requests = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<ByteArray>()
    private val log = CopyOnWriteArrayList<String>()
    @Volatile private var putStatus = 201
    @Volatile private var headStatus = 404
    @Volatile private var healthStatus = 200
    private lateinit var backend: HttpServer

    @Before
    fun setUp() {
        backend = HttpServer(0, { req ->
            requests += "${req.method} ${req.path} auth=${req.header("authorization")} cam=${req.header("x-nx-camera")} cls=${req.header("x-nx-class")} ct=${req.header("content-type")} len=${req.contentLength}"
            when {
                req.path == "/health" -> HttpResponse.empty(healthStatus)
                req.path == "/api/v1/events" -> { bodies += req.body.readBytes(); HttpResponse.empty(if (req.header("authorization") == null) 401 else 204) }
                req.method == "HEAD" -> HttpResponse.empty(headStatus)
                req.method == "PUT" -> { bodies += req.body.readBytes(); HttpResponse.empty(putStatus) }
                else -> HttpResponse.notFound()
            }
        })
        backend.start()
    }

    @After
    fun tearDown() = backend.stop()

    private fun sink(token: String = "t0k") = BackendSink("http://127.0.0.1:${backend.boundPort}/", { log += it }, token)
    private fun file(size: Long = 5) = IncomingFile("SAM 1.JPG", "2026-10-27", size, "image/jpeg", "object.item.imageItem", "10.0.0.7")

    @Test
    fun storeAndExistsFollowTheContract() {
        val s = sink()
        assertFalse(s.exists(file()))
        headStatus = 200
        assertTrue(s.exists(file()))
        assertTrue(requests.last(), requests.last().startsWith("HEAD /api/v1/files/2026-10-27/SAM+1.JPG auth=Bearer t0k"))
        headStatus = 401
        assertFalse(s.exists(file()))
        assertTrue(log.last().contains("rejected the token"))

        assertEquals(StoreResult.STORED, s.store(file(), ByteArrayInputStream("hello".toByteArray())))
        assertArrayEquals("hello".toByteArray(), bodies.last())
        assertTrue(requests.last(), requests.last().contains("cam=10.0.0.7 cls=object.item.imageItem ct=image/jpeg len=5"))
        putStatus = 200
        assertEquals(StoreResult.ALREADY_EXISTS, s.store(file(), ByteArrayInputStream("hello".toByteArray())))
        // unknown size -> chunked upload
        putStatus = 201
        assertEquals(StoreResult.STORED, s.store(file(-1), ByteArrayInputStream(ByteArray(300_000) { it.toByte() })))
        assertTrue(requests.joinToString(" | "), requests.last().endsWith("len=-1"))
        assertEquals(300_000, bodies.last().size)
        putStatus = 401
        try { s.store(file(1), ByteArrayInputStream("x".toByteArray())); throw AssertionError() } catch (e: IOException) { assertTrue(e.message, e.message!!.contains("401 - wrong backend token")) }
        putStatus = 507
        try { s.store(file(1), ByteArrayInputStream("x".toByteArray())); throw AssertionError() } catch (e: IOException) { assertTrue(e.message, e.message!!.contains("HTTP 507")) }
    }

    @Test
    fun eventsHealthAndFailures() {
        val s = sink()
        s.onBackupStart("10.0.0.7"); s.onBackupDone("10.0.0.7", true)
        assertEquals("""{"type":"start","camera":"10.0.0.7","sysOff":false}""", bodies[0].toString(Charsets.UTF_8))
        assertEquals("""{"type":"done","camera":"10.0.0.7","sysOff":true}""", bodies[1].toString(Charsets.UTF_8))
        assertTrue(s.reachable())
        healthStatus = 503
        assertFalse(s.reachable())
        // without a token the backend answers 401 -> logged, not thrown
        sink("").onBackupStart("10.0.0.7")
        assertTrue(log.last(), log.last().contains("backend event 'start' -> HTTP 401"))
        assertFalse(requests.last().contains("auth=Bearer"))
        // backend gone: everything degrades to log lines / false
        backend.stop()
        val dead = BackendSink("http://127.0.0.1:${backend.boundPort}", { log += it }, "t", connectTimeoutMs = 500)
        assertFalse(dead.exists(file()))
        assertTrue(log.last().startsWith("backend HEAD failed"))
        dead.onBackupDone("c", false)
        assertTrue(log.last().startsWith("backend event 'done' failed"))
        assertFalse(dead.reachable())
        try { dead.store(file(1), ByteArrayInputStream("x".toByteArray())); throw AssertionError() } catch (_: IOException) {}
    }
}
