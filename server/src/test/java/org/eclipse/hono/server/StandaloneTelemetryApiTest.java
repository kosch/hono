/**
 * Copyright (c) 2016, 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.server;

import static org.eclipse.hono.util.Constants.DEFAULT_TENANT;

import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.authentication.impl.AcceptAllPlainAuthenticationService;
import org.eclipse.hono.authorization.impl.InMemoryAuthorizationService;
import org.eclipse.hono.client.HonoClient;
import org.eclipse.hono.client.MessageSender;
import org.eclipse.hono.client.impl.HonoClientImpl;
import org.eclipse.hono.config.ServiceConfigProperties;
import org.eclipse.hono.connection.ConnectionFactoryImpl.ConnectionFactoryBuilder;
import org.eclipse.hono.service.registration.RegistrationAssertionHelper;
import org.eclipse.hono.service.registration.impl.FileBasedRegistrationService;
import org.eclipse.hono.telemetry.impl.MessageDiscardingTelemetryDownstreamAdapter;
import org.eclipse.hono.telemetry.impl.TelemetryEndpoint;
import org.eclipse.hono.util.MessageHelper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.proton.ProtonClientOptions;
import io.vertx.proton.ProtonHelper;

/**
 * Stand alone integration tests for Hono's Telemetry API.
 *
 */
@RunWith(VertxUnitRunner.class)
public class StandaloneTelemetryApiTest {

    private static final Logger                LOG = LoggerFactory.getLogger(StandaloneTelemetryApiTest.class);
    private static final String                DEVICE_PREFIX = "device";
    private static final String                DEVICE_1 = DEVICE_PREFIX + "1";
    private static final String                USER = "hono-client";
    private static final String                PWD = "secret";
    private static final String                SIGNING_SECRET = "signing-secret";

    private static Vertx                       vertx = Vertx.vertx();
    private static HonoServer                  server;
    private static FileBasedRegistrationService registrationAdapter;
    private static MessageDiscardingTelemetryDownstreamAdapter telemetryAdapter;
    private static HonoClient                  client;
    private static MessageSender               telemetrySender;
    private static RegistrationAssertionHelper assertionHelper;

    @BeforeClass
    public static void prepareHonoServer(final TestContext ctx) throws Exception {

        assertionHelper = new RegistrationAssertionHelper(SIGNING_SECRET);
        telemetryAdapter = new MessageDiscardingTelemetryDownstreamAdapter(vertx);
        server = new HonoServer();
        server.setSaslAuthenticatorFactory(new HonoSaslAuthenticatorFactory(vertx));
        ServiceConfigProperties configProperties = new ServiceConfigProperties();
        configProperties.setInsecurePortEnabled(true);
        configProperties.setInsecurePort(0);
        server.setConfig(configProperties);
        TelemetryEndpoint telemetryEndpoint = new TelemetryEndpoint(vertx);
        telemetryEndpoint.setTelemetryAdapter(telemetryAdapter);
        telemetryEndpoint.setRegistrationServiceSecret(SIGNING_SECRET);
        server.addEndpoint(telemetryEndpoint);
        registrationAdapter = new FileBasedRegistrationService();
        registrationAdapter.setSigningSecret(SIGNING_SECRET);

        final Future<HonoClient> setupTracker = Future.future();
        setupTracker.setHandler(ctx.asyncAssertSuccess());

        Future<String> registrationTracker = Future.future();
        Future<String> authenticationTracker = Future.future();
        Future<String> authTracker = Future.future();

        vertx.deployVerticle(registrationAdapter, registrationTracker.completer());
        vertx.deployVerticle(InMemoryAuthorizationService.class.getName(), authTracker.completer());
        vertx.deployVerticle(AcceptAllPlainAuthenticationService.class.getName(), authenticationTracker.completer());

        CompositeFuture.all(registrationTracker, authTracker)
        .compose(r -> {
            Future<String> serverTracker = Future.future();
            vertx.deployVerticle(server, serverTracker.completer());
            return serverTracker;
        }).compose(s -> {
            client = new HonoClientImpl(vertx, ConnectionFactoryBuilder.newBuilder()
                    .vertx(vertx)
                    .name("test")
                    .host(server.getInsecurePortBindAddress())
                    .port(server.getInsecurePort())
                    .user(USER)
                    .password(PWD)
                    .build());
            client.connect(new ProtonClientOptions(), setupTracker.completer());
        }, setupTracker);
    }

    @Before
    public void createSender(final TestContext ctx) {

        registrationAdapter.addDevice(DEFAULT_TENANT, DEVICE_1, null);
        telemetryAdapter.setMessageConsumer(msg -> {});
    }

    @After
    public void clearRegistry(final TestContext ctx) throws InterruptedException {

        registrationAdapter.clear();
        if (telemetrySender != null && telemetrySender.isOpen()) {
            Async done = ctx.async();
            telemetrySender.close(closeAttempt -> {
                ctx.assertTrue(closeAttempt.succeeded());
                done.complete();
            });
            done.await(1000L);
        }
    }

    @AfterClass
    public static void shutdown(final TestContext ctx) {

        if (client != null) {
            client.shutdown(ctx.asyncAssertSuccess());
        }
    }

    @Test(timeout = 2000l)
    public void testTelemetryUploadSucceedsForRegisteredDevice(final TestContext ctx) throws Exception {

        LOG.debug("starting telemetry upload test");
        int count = 30;
        final Async messagesReceived = ctx.async(count);
        telemetryAdapter.setMessageConsumer(msg -> {
            messagesReceived.countDown();
            LOG.debug("received message [id: {}]", msg.getMessageId());
        });

        Async sender = ctx.async();
        client.getOrCreateTelemetrySender(DEFAULT_TENANT, creationAttempt -> {
            ctx.assertTrue(creationAttempt.succeeded());
            telemetrySender = creationAttempt.result();
            sender.complete();
        });
        sender.await(1000L);

        String registrationAssertion = assertionHelper.getAssertion(DEFAULT_TENANT, DEVICE_1, 10);

        IntStream.range(0, count).forEach(i -> {
            Async waitForCredit = ctx.async();
            LOG.trace("sending message {}", i);
            telemetrySender.send(DEVICE_1, "payload" + i, "text/plain; charset=utf-8", registrationAssertion, done -> waitForCredit.complete());
            LOG.trace("sender's send queue full: {}", telemetrySender.sendQueueFull());
            waitForCredit.await();
        });

    }

    @Test(timeout = 1000l)
    public void testLinkGetsClosedWhenUploadingDataWithNonMatchingRegistrationAssertion(final TestContext ctx) throws Exception {

        String assertion = assertionHelper.getAssertion(DEFAULT_TENANT, "other-device", 10);

        client.getOrCreateTelemetrySender(DEFAULT_TENANT, ctx.asyncAssertSuccess(sender -> {
            sender.setErrorHandler(ctx.asyncAssertFailure(s -> {
                LOG.debug(s.getMessage());
            }));
            sender.send(DEVICE_1, "payload", "text/plain", assertion, capacityAvailable -> {});
        }));

    }

    @Test(timeout = 1000l)
    public void testLinkGetsClosedWhenUploadingDataWithoutRegistrationAssertion(final TestContext ctx) throws Exception {

        Message msg = ProtonHelper.message();
        msg.setBody(new Data(new Binary("payload".getBytes(StandardCharsets.UTF_8))));
        msg.setContentType("text/plain");
        MessageHelper.addDeviceId(msg, DEVICE_1);
        MessageHelper.addTenantId(msg, DEFAULT_TENANT);
        // NO registration assertion included

        client.getOrCreateTelemetrySender(DEFAULT_TENANT, ctx.asyncAssertSuccess(sender -> {
            sender.setErrorHandler(ctx.asyncAssertFailure(s -> {
                LOG.debug(s.getMessage());
            }));
            sender.send(msg, capacityAvailable -> {});
        }));

    }

    @Test(timeout = 1000l)
    public void testLinkGetsClosedWhenUploadingMalformedTelemetryDataMessage(final TestContext ctx) throws Exception {

        final Message msg = ProtonHelper.message("malformed");
        msg.setMessageId("malformed-message");

        client.getOrCreateTelemetrySender(DEFAULT_TENANT, ctx.asyncAssertSuccess(sender -> {
            sender.setErrorHandler(ctx.asyncAssertFailure(error -> {
                LOG.debug(error.getMessage());
            }));
            sender.send(msg, capacityAvailable -> {});
        }));

    }

    @Test(timeout = 2000l)
    public void testLinkGetsClosedWhenDeviceUploadsDataOriginatingFromOtherDevice(final TestContext ctx) throws Exception {

        String registrationAssertion = assertionHelper.getAssertion(DEFAULT_TENANT, "device-1", 10);

        client.getOrCreateTelemetrySender(DEFAULT_TENANT, "device-0", ctx.asyncAssertSuccess(sender -> {
            sender.setErrorHandler(ctx.asyncAssertFailure(s -> {
                LOG.debug(s.getMessage());
            }));
            sender.send("device-1", "from other device", "text/plain", registrationAssertion);
        }));
    }
}
