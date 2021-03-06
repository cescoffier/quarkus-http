/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.server;

import java.io.IOException;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.undertow.server.handlers.BlockingHandler;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpClientUtils;
import io.undertow.testutils.TestHttpClient;
import io.undertow.util.FileUtils;
import io.undertow.httpcore.StatusCodes;

/**
 * @author Stuart Douglas
 */
@RunWith(DefaultServer.class)
@Ignore("UT3 - P1")
public class NewlineInHeadersTestCase {

    private static final String RESPONSE = "response";
    private static final String ECHO = "echo";

    @Test
    public void testNewlineInHeaders() throws IOException {
        DefaultServer.setRootHandler(new BlockingHandler(new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                String message = FileUtils.readFile(exchange.getInputStream());
                exchange.setResponseHeader(ECHO, message);
                exchange.writeAsync(RESPONSE);
            }
        }));
        final TestHttpClient client = new TestHttpClient();
        try {
            HttpPost post = new HttpPost(DefaultServer.getDefaultServerURL());
            post.setEntity(new StringEntity("test"));
            HttpResponse result = client.execute(post);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            Assert.assertEquals("test", result.getFirstHeader(ECHO).getValue());
            Assert.assertEquals(RESPONSE, HttpClientUtils.readResponse(result));

            post = new HttpPost(DefaultServer.getDefaultServerURL());
            post.setEntity(new StringEntity("test\nnewline"));
            result = client.execute(post);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            Assert.assertEquals("test newline", result.getFirstHeader(ECHO).getValue());
            Assert.assertEquals(RESPONSE, HttpClientUtils.readResponse(result));
        } finally {
            client.getConnectionManager().shutdown();
        }
    }
}
