/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.Callback
import org.moqui.util.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RestClientStreamTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(RestClientStreamTests.class)

    @Shared Server server
    @Shared int port
    @Shared SseTestHandler handler
    @Shared RestClient.RequestFactory requestFactory

    def setupSpec() {
        handler = new SseTestHandler()
        server = new Server(0)
        server.setHandler(handler)
        server.start()
        port = ((ServerConnector) server.connectors[0]).localPort
        requestFactory = new RestClient.SimpleRequestFactory()
        logger.info("RestClientStreamTests server on port ${port}")
    }

    def cleanupSpec() {
        if (requestFactory != null) requestFactory.destroy()
        if (server != null) server.stop()
    }

    def setup() {
        handler.reset()
    }

    private RestClient client(String path) {
        return new RestClient()
                .withRequestFactory(requestFactory)
                .uri("http://127.0.0.1:${port}${path}")
                .acceptContentType("text/event-stream")
    }

    def "streamSse parses events, ignores comments, concatenates data, and treats [DONE] as complete"() {
        when:
        List events = []
        boolean completed = false
        client("/sse").streamSse(new RestClient.SseConsumer() {
            @Override boolean onEvent(String event, String data, String id) {
                events.add([event: event, data: data, id: id])
                return true
            }
            @Override void onComplete() { completed = true }
        })

        then:
        completed
        events.size() == 2
        events[0].event == "message"
        events[0].data == "hello"
        events[0].id == "1"
        events[1].event == null
        events[1].data == "line1\nline2"
        events.every { it.data != "[DONE]" }
    }

    def "stream retries 429 then hands over 200 SSE body"() {
        when:
        List events = []
        boolean completed = false
        client("/sse-429").retry(0.05F, 2).streamSse(new RestClient.SseConsumer() {
            @Override boolean onEvent(String event, String data, String id) {
                events.add(data)
                return true
            }
            @Override void onComplete() { completed = true }
        })

        then:
        handler.sse429Hits.get() == 2
        completed
        events == ["after-retry"]
    }

    def "RestStream close aborts further chunks"() {
        when:
        RestClient.RestStream stream = client("/sse-slow").timeout(15).stream()
        List events = []
        String line
        StringBuilder data = new StringBuilder()
        while ((line = stream.reader().readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    events.add(data.toString())
                    stream.close()
                    break
                }
            } else if (line.startsWith("data:")) {
                String value = line.substring(5)
                if (value.startsWith(" ")) value = value.substring(1)
                data.append(value)
            }
        }
        boolean handlerFinished = handler.slowDone.await(3, TimeUnit.SECONDS)
        boolean moreEvents = false
        try {
            while ((line = stream.reader().readLine()) != null) {
                if (line.contains("chunk-")) moreEvents = true
            }
        } catch (Exception ignored) { }

        then:
        stream.getStatusCode() == 200
        stream.getContentType() == "text/event-stream"
        events == ["chunk-1"]
        !moreEvents
        handlerFinished
        handler.slowAborted.get()
        handler.slowChunks.get() >= 1 && handler.slowChunks.get() <= 2

        cleanup:
        if (stream != null) stream.close()
    }

    def "SseConsumer returning false closes the stream and stops events"() {
        when:
        List events = []
        boolean completed = false
        client("/sse-slow").timeout(15).streamSse(new RestClient.SseConsumer() {
            @Override boolean onEvent(String event, String data, String id) {
                events.add(data)
                return false
            }
            @Override void onComplete() { completed = true }
        })
        boolean handlerFinished = handler.slowDone.await(3, TimeUnit.SECONDS)

        then:
        events == ["chunk-1"]
        !completed
        handlerFinished
        handler.slowAborted.get()
        handler.slowChunks.get() >= 1 && handler.slowChunks.get() <= 2
    }

    static class SseTestHandler extends Handler.Abstract {
        final AtomicInteger sse429Hits = new AtomicInteger()
        final AtomicInteger slowChunks = new AtomicInteger()
        final AtomicBoolean slowAborted = new AtomicBoolean()
        final AtomicInteger generation = new AtomicInteger()
        volatile CountDownLatch slowDone = new CountDownLatch(1)

        void reset() {
            generation.incrementAndGet()
            sse429Hits.set(0)
            slowChunks.set(0)
            slowAborted.set(false)
            slowDone = new CountDownLatch(1)
        }

        @Override
        boolean handle(Request request, Response response, Callback callback) throws Exception {
            String path = request.getHttpURI().getPath()
            try {
                if ("/sse".equals(path)) {
                    writeSseHeaders(response)
                    writeChunk(response, "event: message\ndata: hello\nid: 1\n\n", false)
                    writeChunk(response, ": keep-alive\n\n", false)
                    writeChunk(response, "data: line1\ndata: line2\n\n", false)
                    writeChunk(response, "data: [DONE]\n\n", true)
                    callback.succeeded()
                } else if ("/sse-429".equals(path)) {
                    int n = sse429Hits.incrementAndGet()
                    if (n == 1) {
                        response.setStatus(429)
                        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain")
                        Content.Sink.write(response, true, ByteBuffer.wrap("too many".getBytes(StandardCharsets.UTF_8)))
                        callback.succeeded()
                    } else {
                        writeSseHeaders(response)
                        writeChunk(response, "data: after-retry\n\n", false)
                        writeChunk(response, "data: [DONE]\n\n", true)
                        callback.succeeded()
                    }
                } else if ("/sse-slow".equals(path)) {
                    CountDownLatch done = slowDone
                    try {
                        int gen = generation.get()
                        writeSseHeaders(response)
                        for (int i = 1; i <= 5; i++) {
                            if (generation.get() != gen) break
                            writeChunk(response, "data: chunk-" + i + "\n\n", i == 5)
                            slowChunks.incrementAndGet()
                            if (i < 5) Thread.sleep(250)
                        }
                        callback.succeeded()
                    } catch (Exception e) {
                        slowAborted.set(true)
                        callback.failed(e)
                    } finally {
                        done.countDown()
                    }
                } else {
                    Response.writeError(request, response, callback, 404)
                    return true
                }
            } catch (IOException e) {
                callback.failed(e)
            }
            return true
        }

        private static void writeSseHeaders(Response response) {
            response.setStatus(200)
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/event-stream; charset=UTF-8")
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-cache")
        }

        private static void writeChunk(Response response, String text, boolean last) throws IOException {
            Content.Sink.write(response, last, ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)))
        }
    }
}
