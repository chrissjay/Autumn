package cn.imaq.autumn.http.server.protocol;

import cn.imaq.autumn.http.protocol.AbstractHttpSession;
import cn.imaq.autumn.http.protocol.AutumnHttpRequest;
import cn.imaq.autumn.http.protocol.AutumnHttpResponse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class AIOHttpServerSession extends AbstractHttpSession {
    private static final Set<String> VALID_METHODS = new HashSet<>();

    static {
        Collections.addAll(VALID_METHODS, "GET", "POST", "PUT", "DELETE");
    }

    private AutumnHttpHandler handler;
    private AsynchronousSocketChannel cChannel;
    private String method, path, protocol;

    public AIOHttpServerSession(AutumnHttpHandler handler, AsynchronousSocketChannel cChannel) {
        this.handler = handler;
        this.cChannel = cChannel;
    }

    public void checkIdle(int timeoutSec) throws IOException {
        if (System.currentTimeMillis() - lastActive > timeoutSec * 1000) {
            timeout();
        }
    }

    @Override
    protected boolean checkStart(String line) {
        // expect: "GET /path/to/something HTTP/1.1"
        String[] words = line.split(" ", 3);
        if (words.length == 3 && VALID_METHODS.contains(words[0])) {
            method = words[0];
            path = words[1];
            protocol = words[2];
            return true;
        }
        return false;
    }

    @Override
    protected void finish() throws IOException {
        AutumnHttpRequest request = AutumnHttpRequest.builder()
                .method(method)
                .path(path)
                .protocol(protocol)
                .headers(headersMap)
                .body(body)
                .localAddress(cChannel.getLocalAddress())
                .remoteAddress(cChannel.getRemoteAddress())
                .build();
        AutumnHttpResponse response = handler.handle(request);
        writeResponse(response);
    }

    @Override
    protected void error() throws IOException {
        writeResponse(AutumnHttpResponse.builder()
                .status(400)
                .build()
        );
    }

    private void timeout() throws IOException {
        cChannel.close();
    }

    private void writeResponse(AutumnHttpResponse response) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(response.getStatus()).append(' ')
                .append(AutumnHttpResponse.ResponseCodes.get(response.getStatus())).append("\r\n");
        if (response.getHeaders() != null) {
            for (Map.Entry<String, List<String>> header : response.getHeaders().entrySet()) {
                String key = header.getKey();
                for (String value : header.getValue()) {
                    sb.append(key).append(": ").append(value).append("\r\n");
                }
            }
        }
        if (response.getContentType() != null) {
            sb.append("Content-Type: ").append(response.getContentType()).append("\r\n");
        }
        if (response.getBody() != null) {
            sb.append("Content-Length: ").append(response.getBody().length).append("\r\n");
        }
        CountDownLatch writeLatch = new CountDownLatch(1);
        cChannel.write(ByteBuffer.wrap(sb.append("\r\n").toString().getBytes()), null, new CompletionHandler<Integer, Object>() {
            @Override
            public void completed(Integer result, Object attachment) {
                if (response.getBody() != null) {
                    cChannel.write(ByteBuffer.wrap(response.getBody()), null, new CompletionHandler<Integer, Object>() {
                        @Override
                        public void completed(Integer result, Object attachment) {
                            writeLatch.countDown();
                        }

                        @Override
                        public void failed(Throwable exc, Object attachment) {
                            writeLatch.countDown();
                        }
                    });
                } else {
                    writeLatch.countDown();
                }
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                writeLatch.countDown();
            }
        });
        try {
            writeLatch.await();
        } catch (InterruptedException ignored) {
        }
    }
}