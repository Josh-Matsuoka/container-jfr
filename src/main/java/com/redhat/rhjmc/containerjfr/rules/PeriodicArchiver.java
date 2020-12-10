/*-
 * #%L
 * Container JFR
 * %%
 * Copyright (C) 2020 Red Hat, Inc.
 * %%
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * #L%
 */
package com.redhat.rhjmc.containerjfr.rules;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.client.utils.URLEncodedUtils;

import com.redhat.rhjmc.containerjfr.core.log.Logger;
import com.redhat.rhjmc.containerjfr.core.net.Credentials;
import com.redhat.rhjmc.containerjfr.net.web.http.AbstractAuthenticatedRequestHandler;
import com.redhat.rhjmc.containerjfr.platform.ServiceRef;
import com.redhat.rhjmc.containerjfr.util.HttpStatusCodeIdentifier;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

class PeriodicArchiver implements Runnable {

    private final WebClient webClient;
    private final ServiceRef serviceRef;
    private final Credentials credentials;
    private final String recordingName;
    private final int preserveArchives;
    private final Logger logger;

    private final Queue<String> previousRecordings;

    PeriodicArchiver(
            WebClient webClient,
            ServiceRef serviceRef,
            Credentials credentials,
            String recordingName,
            int preserveArchives,
            Logger logger) {
        this.webClient = webClient;
        this.serviceRef = serviceRef;
        this.credentials = credentials;
        this.recordingName = recordingName;
        this.preserveArchives = preserveArchives;
        this.logger = logger;

        this.previousRecordings = new ArrayDeque<>(this.preserveArchives);
    }

    @Override
    public void run() {
        logger.trace(String.format("PeriodicArchiver for %s running", recordingName));

        try {
            performArchival();
        } catch (InterruptedException | ExecutionException e) {
            logger.error(e);
        }
    }

    void performArchival() throws InterruptedException, ExecutionException {
        while (this.previousRecordings.size() > this.preserveArchives - 1) {
            pruneArchive(this.previousRecordings.remove());
        }

        // FIXME using an HTTP request to localhost here works well enough, but is needlessly
        // complex. The API handler targeted here should be refactored to extract the logic that
        // creates the recording from the logic that simply figures out the recording parameters
        // from the POST form, path param, and headers. Then the handler should consume the API
        // exposed by this refactored chunk, and this refactored chunk can also be consumed here
        // rather than firing HTTP requests to ourselves

        // FIXME don't hardcode this path
        URI path =
                URI.create(
                                "/api/v1/targets/"
                                        + URLEncodedUtils.formatSegments(
                                                serviceRef.getJMXServiceUrl().toString())
                                        + "/recordings/"
                                        + URLEncodedUtils.formatSegments(recordingName))
                        .normalize();
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        if (credentials != null) {
            headers.add(
                    AbstractAuthenticatedRequestHandler.JMX_AUTHORIZATION_HEADER,
                    String.format(
                            "Basic %s",
                            Base64.encodeBase64String(
                                    String.format(
                                                    "%s:%s",
                                                    credentials.getUsername(),
                                                    credentials.getPassword())
                                            .getBytes())));
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        this.webClient
                .patch(path.toString())
                .timeout(30_000L)
                .putHeaders(headers)
                .sendBuffer(
                        Buffer.buffer("save"),
                        ar -> {
                            if (ar.failed()) {
                                this.logger.error(
                                        new IOException("Periodic archival failed", ar.cause()));
                                future.completeExceptionally(ar.cause());
                                return;
                            }
                            HttpResponse<Buffer> resp = ar.result();
                            if (!HttpStatusCodeIdentifier.isSuccessCode(resp.statusCode())) {
                                this.logger.error(resp.bodyAsString());
                                future.completeExceptionally(new IOException(resp.bodyAsString()));
                                return;
                            }
                            future.complete(resp.bodyAsString());
                        });
        this.previousRecordings.add(future.get());
    }

    Future<Boolean> pruneArchive(String recordingName) {
        logger.trace(String.format("Pruning %s", recordingName));
        URI path =
                URI.create("/api/v1/recordings/" + URLEncodedUtils.formatSegments(recordingName))
                        .normalize();
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        if (credentials != null) {
            headers.add(
                    AbstractAuthenticatedRequestHandler.JMX_AUTHORIZATION_HEADER,
                    String.format(
                            "Basic %s",
                            Base64.encodeBase64String(
                                    String.format(
                                                    "%s:%s",
                                                    credentials.getUsername(),
                                                    credentials.getPassword())
                                            .getBytes())));
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        this.webClient
                .delete(path.toString())
                .timeout(30_000L)
                .putHeaders(headers)
                .send(
                        ar -> {
                            if (ar.failed()) {
                                this.logger.error(
                                        new IOException("Archival prune failed", ar.cause()));
                                future.completeExceptionally(ar.cause());
                                return;
                            }
                            HttpResponse<Buffer> resp = ar.result();
                            if (!HttpStatusCodeIdentifier.isSuccessCode(resp.statusCode())) {
                                this.logger.error(resp.bodyAsString());
                                future.completeExceptionally(new IOException(resp.bodyAsString()));
                                return;
                            }
                            previousRecordings.remove(recordingName);
                            future.complete(true);
                        });
        return future;
    }
}