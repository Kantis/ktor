/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.darwin

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.date.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import platform.Foundation.*
import platform.darwin.*
import kotlin.coroutines.*

@OptIn(UnsafeNumber::class, ExperimentalCoroutinesApi::class)
internal class DarwinWebsocketSession(
    private val requestTime: GMTDate,
    callContext: CoroutineContext,
    private val challengeHandler: ChallengeHandler?
) : WebSocketSession {

    internal lateinit var task: NSURLSessionWebSocketTask

    internal val response = CompletableDeferred<HttpResponseData>()

    private val _incoming = Channel<Frame>(Channel.UNLIMITED)
    private val _outgoing = Channel<Frame>(Channel.UNLIMITED)
    private val socketJob = Job(callContext[Job])
    override val coroutineContext: CoroutineContext = callContext + socketJob

    override var masking: Boolean
        get() = true
        set(_) {}

    override var maxFrameSize: Long
        get() = task.maximumMessageSize.convert()
        set(value) {
            task.setMaximumMessageSize(value.convert())
        }

    override val incoming: ReceiveChannel<Frame>
        get() = _incoming

    override val outgoing: SendChannel<Frame>
        get() = _outgoing

    override val extensions: List<WebSocketExtension<*>>
        get() = emptyList()

    internal val delegate: NSURLSessionWebSocketDelegateProtocol = WebSocketDelegate()

    init {
        launch {
            receiveMessages()
        }
        launch {
            sendMessages()
        }
        coroutineContext[Job]!!.invokeOnCompletion { cause ->
            if (cause != null) {
                val code = CloseReason.Codes.INTERNAL_ERROR.code.convert<NSInteger>()
                task.cancelWithCloseCode(code, "Client failed".toByteArray().toNSData())
            }
            _incoming.close()
            _outgoing.cancel()
        }
    }

    private suspend fun receiveMessages() {
        while (true) {
            val message = task.receiveMessage()
            val frame = when (message.type) {
                NSURLSessionWebSocketMessageTypeData ->
                    Frame.Binary(true, message.data()!!.toByteArray())

                NSURLSessionWebSocketMessageTypeString ->
                    Frame.Text(true, message.string()!!.toByteArray())

                else -> throw IllegalArgumentException("Unknown message $message")
            }
            _incoming.send(frame)
        }
    }

    private suspend fun sendMessages() {
        _outgoing.consumeEach { frame ->
            when (frame.frameType) {
                FrameType.TEXT -> {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        task.sendMessage(NSURLSessionWebSocketMessage(String(frame.data))) { error ->
                            if (error == null) continuation.resume(Unit)
                            else continuation.resumeWithException(DarwinHttpRequestException(error))
                        }
                    }
                }

                FrameType.BINARY -> {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        task.sendMessage(NSURLSessionWebSocketMessage(frame.data.toNSData())) { error ->
                            if (error == null) continuation.resume(Unit)
                            else continuation.resumeWithException(DarwinHttpRequestException(error))
                        }
                    }
                }

                FrameType.CLOSE -> {
                    val data = buildPacket { writeFully(frame.data) }
                    val code = data.readShort().convert<NSInteger>()
                    val reason = data.readBytes()
                    task.cancelWithCloseCode(code, reason.toNSData())
                    return@sendMessages
                }

                FrameType.PING -> {
                    task.sendPingWithPongReceiveHandler { error ->
                        if (error != null) {
                            cancel("Error receiving pong", DarwinHttpRequestException(error))
                            return@sendPingWithPongReceiveHandler
                        }
                        _incoming.trySend(Frame.Pong(ByteReadPacket.Empty))
                    }
                }

                else -> {
                    throw IllegalArgumentException("Unknown frame type: $frame")
                }
            }
        }
    }

    override suspend fun flush() {}

    @Deprecated(
        "Use cancel() instead.",
        ReplaceWith("cancel()", "kotlinx.coroutines.cancel"),
        DeprecationLevel.ERROR
    )
    override fun terminate() {
        task.cancelWithCloseCode(CloseReason.Codes.NORMAL.code.convert(), null)
        coroutineContext.cancel()
    }

    private inner class WebSocketDelegate : NSURLSessionWebSocketDelegateProtocol, NSObject() {

        override fun URLSession(
            session: NSURLSession,
            webSocketTask: NSURLSessionWebSocketTask,
            didOpenWithProtocol: String?
        ) {
            val response = HttpResponseData(
                HttpStatusCode.OK,
                requestTime,
                Headers.Empty,
                HttpProtocolVersion.HTTP_1_1,
                this@DarwinWebsocketSession,
                coroutineContext
            )
            this@DarwinWebsocketSession.response.complete(response)
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            if (didCompleteWithError == null) {
                socketJob.cancel()
                return
            }

            val exception = DarwinHttpRequestException(didCompleteWithError)
            this@DarwinWebsocketSession.response.completeExceptionally(exception)
            socketJob.completeExceptionally(exception)
        }

        override fun URLSession(
            session: NSURLSession,
            webSocketTask: NSURLSessionWebSocketTask,
            didCloseWithCode: NSURLSessionWebSocketCloseCode,
            reason: NSData?
        ) {
            val closeReason = CloseReason(didCloseWithCode.toShort(), reason?.toByteArray()?.let { String(it) } ?: "")
            if (!_incoming.isClosedForSend) {
                _incoming.trySend(Frame.Close(closeReason))
            }
            socketJob.cancel()
            task.cancelWithCloseCode(didCloseWithCode, reason)
        }

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didReceiveChallenge: NSURLAuthenticationChallenge,
            completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit
        ) {
            val handler = challengeHandler
            if (handler != null) {
                handler(session, task, didReceiveChallenge, completionHandler)
            } else {
                completionHandler(
                    NSURLSessionAuthChallengePerformDefaultHandling,
                    didReceiveChallenge.proposedCredential
                )
            }
        }
    }
}

private suspend fun NSURLSessionWebSocketTask.receiveMessage(): NSURLSessionWebSocketMessage =
    suspendCancellableCoroutine {
        receiveMessageWithCompletionHandler { message, error ->
            if (error != null) {
                it.resumeWithException(DarwinHttpRequestException(error))
                return@receiveMessageWithCompletionHandler
            }
            if (message == null) {
                it.resumeWithException(IllegalArgumentException("Received null message"))
                return@receiveMessageWithCompletionHandler
            }

            it.resume(message)
        }
    }
