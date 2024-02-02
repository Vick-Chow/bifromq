/*
 * Copyright (c) 2024. The BifroMQ Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.baidu.bifromq.mqtt.handler;

import static com.baidu.bifromq.mqtt.handler.MQTTSessionIdUtil.userSessionId;
import static com.baidu.bifromq.plugin.eventcollector.ThreadLocalEventPool.getLocal;

import com.baidu.bifromq.basehlc.HLC;
import com.baidu.bifromq.baserpc.utils.FutureTracker;
import com.baidu.bifromq.inbox.client.IInboxClient;
import com.baidu.bifromq.inbox.rpc.proto.ExpireReply;
import com.baidu.bifromq.inbox.rpc.proto.ExpireRequest;
import com.baidu.bifromq.inbox.rpc.proto.GetReply;
import com.baidu.bifromq.inbox.rpc.proto.GetRequest;
import com.baidu.bifromq.inbox.storage.proto.InboxVersion;
import com.baidu.bifromq.inbox.storage.proto.LWT;
import com.baidu.bifromq.mqtt.handler.record.GoAway;
import com.baidu.bifromq.mqtt.session.IMQTTPersistentSession;
import com.baidu.bifromq.mqtt.session.IMQTTTransientSession;
import com.baidu.bifromq.mqtt.session.MQTTSessionContext;
import com.baidu.bifromq.plugin.eventcollector.Event;
import com.baidu.bifromq.plugin.eventcollector.IEventCollector;
import com.baidu.bifromq.plugin.eventcollector.mqttbroker.clientconnected.ClientConnected;
import com.baidu.bifromq.plugin.settingprovider.ISettingProvider;
import com.baidu.bifromq.sysprops.BifroMQSysProp;
import com.baidu.bifromq.type.ClientInfo;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class MQTTConnectHandler extends ChannelDuplexHandler {
    protected static final boolean SANITY_CHECK = BifroMQSysProp.MQTT_UTF8_SANITY_CHECK.get();

    public record ExistingSession(long incarnation, long version) {

    }

    protected record OkOrGoAway(ClientInfo clientInfo, GoAway goAway) {
        public OkOrGoAway(GoAway goAway) {
            this(null, goAway);
        }

        public OkOrGoAway(ClientInfo clientInfo) {
            this(clientInfo, null);
        }
    }

    private static final int MIN_CLIENT_KEEP_ALIVE_DURATION = 5;
    private static final int MAX_CLIENT_KEEP_ALIVE_DURATION = 2 * 60 * 60;
    private final FutureTracker cancellableTasks = new FutureTracker();
    private ChannelHandlerContext ctx;
    private MQTTSessionContext sessionCtx;
    private IInboxClient inboxClient;
    private IEventCollector eventCollector;
    private ISettingProvider settingProvider;


    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        sessionCtx = ChannelAttrs.mqttSessionContext(ctx);
        inboxClient = sessionCtx.inboxClient;
        eventCollector = sessionCtx.eventCollector;
        settingProvider = sessionCtx.settingProvider;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancellableTasks.stop();
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        assert msg instanceof MqttConnectMessage;
        MqttConnectMessage connMsg = (MqttConnectMessage) msg;
        GoAway goAway = sanityCheck(connMsg);
        if (goAway != null) {
            handleGoAway(goAway);
            return;
        }
        long reqId = System.nanoTime();
        cancellableTasks.track(authenticate(connMsg))
            .thenComposeAsync(okOrGoAway -> {
                if (okOrGoAway.goAway != null) {
                    handleGoAway(okOrGoAway.goAway);
                    return CompletableFuture.completedFuture(null);
                } else {
                    ClientInfo clientInfo = okOrGoAway.clientInfo;
                    TenantSettings settings = new TenantSettings(clientInfo.getTenantId(), settingProvider);
                    GoAway isInvalid = validate(connMsg, settings, clientInfo);
                    if (isInvalid != null) {
                        handleGoAway(isInvalid);
                        return CompletableFuture.completedFuture(null);
                    }
                    LWT willMessage = connMsg.variableHeader().isWillFlag() ? getWillMessage(connMsg) : null;
                    int keepAliveSeconds = keepAliveSeconds(connMsg.variableHeader().keepAliveTimeSeconds());
                    String userSessionId = userSessionId(clientInfo);
                    String requestClientId = connMsg.payload().clientIdentifier();
                    if (isCleanSession(connMsg, settings)) {
                        if (requestClientId.isEmpty()) {
                            int sessionExpiryInterval = getSessionExpiryInterval(connMsg, settings);
                            if (sessionExpiryInterval == 0) {
                                setupTransientSessionHandler(connMsg,
                                    settings,
                                    userSessionId,
                                    keepAliveSeconds,
                                    false,
                                    willMessage,
                                    clientInfo);

                            } else {
                                setupPersistentSessionHandler(connMsg, settings, userSessionId,
                                    keepAliveSeconds, sessionExpiryInterval, null, willMessage, clientInfo);
                            }
                            return CompletableFuture.completedFuture(null);
                        } else {
                            return inboxClient.expire(ExpireRequest.newBuilder()
                                    .setReqId(reqId)
                                    .setTenantId(clientInfo.getTenantId())
                                    .setInboxId(userSessionId)
                                    .setNow(HLC.INST.getPhysical())
                                    .build())
                                .exceptionallyAsync(e -> {
                                    log.error("Failed to expire inbox", e);
                                    return ExpireReply.newBuilder()
                                        .setReqId(reqId)
                                        .setCode(ExpireReply.Code.ERROR)
                                        .build();
                                }, ctx.channel().eventLoop())
                                .thenAcceptAsync(reply -> {
                                    if (reply.getCode() == ExpireReply.Code.ERROR) {
                                        handleGoAway(onCleanSessionFailed(clientInfo));
                                    } else {
                                        int sessionExpiryInterval = getSessionExpiryInterval(connMsg, settings);
                                        if (sessionExpiryInterval == 0) {
                                            setupTransientSessionHandler(connMsg,
                                                settings,
                                                userSessionId,
                                                keepAliveSeconds,
                                                reply.getCode() == ExpireReply.Code.OK,
                                                willMessage,
                                                clientInfo);
                                        } else {
                                            setupPersistentSessionHandler(connMsg, settings, userSessionId,
                                                keepAliveSeconds, sessionExpiryInterval, null, willMessage, clientInfo);
                                        }
                                    }
                                }, ctx.channel().eventLoop());
                        }
                    } else {
                        return inboxClient.get(GetRequest.newBuilder()
                                .setReqId(reqId)
                                .setTenantId(clientInfo.getTenantId())
                                .setInboxId(userSessionId)
                                .build())
                            .exceptionallyAsync(e -> {
                                log.error("Failed to get inbox", e);
                                return GetReply.newBuilder()
                                    .setReqId(reqId)
                                    .setCode(GetReply.Code.ERROR)
                                    .build();
                            }, ctx.channel().eventLoop())
                            .thenAcceptAsync(reply -> {
                                if (reply.getCode() == GetReply.Code.ERROR) {
                                    handleGoAway(onGetSessionFailed(clientInfo));
                                } else if (reply.getCode() == GetReply.Code.EXIST) {
                                    // reuse existing inbox with the highest incarnation, the old ones will be cleaned up eventually
                                    InboxVersion inbox = reply.getInbox(reply.getInboxCount() - 1);
                                    int sessionExpiryInterval = getSessionExpiryInterval(connMsg, settings);
                                    setupPersistentSessionHandler(connMsg,
                                        settings,
                                        userSessionId,
                                        keepAliveSeconds,
                                        sessionExpiryInterval,
                                        new ExistingSession(
                                            inbox.getIncarnation(),
                                            inbox.getVersion()),
                                        willMessage,
                                        clientInfo);
                                } else {
                                    int sessionExpiryInterval = getSessionExpiryInterval(connMsg, settings);
                                    if (sessionExpiryInterval == 0) {
                                        setupTransientSessionHandler(connMsg,
                                            settings,
                                            userSessionId,
                                            keepAliveSeconds,
                                            false,
                                            willMessage, clientInfo);
                                    } else {
                                        setupPersistentSessionHandler(connMsg, settings, userSessionId,
                                            keepAliveSeconds, sessionExpiryInterval, null, willMessage, clientInfo);
                                    }
                                }
                            }, ctx.channel().eventLoop());
                    }
                }
            }, ctx.channel().eventLoop());
    }


    protected abstract GoAway sanityCheck(MqttConnectMessage message);

    protected abstract CompletableFuture<OkOrGoAway> authenticate(MqttConnectMessage message);

    protected abstract GoAway validate(MqttConnectMessage message, TenantSettings settings, ClientInfo clientInfo);

    protected abstract LWT getWillMessage(MqttConnectMessage message);

    protected abstract boolean isCleanSession(MqttConnectMessage message, TenantSettings settings);

    protected abstract int getSessionExpiryInterval(MqttConnectMessage message, TenantSettings settings);

    protected abstract GoAway onCleanSessionFailed(ClientInfo clientInfo);

    protected abstract GoAway onGetSessionFailed(ClientInfo clientInfo);

    private void setupTransientSessionHandler(MqttConnectMessage connMsg,
                                              TenantSettings settings,
                                              String userSessionId,
                                              int keepAliveSeconds,
                                              boolean sessionExists,
                                              LWT willMessage,
                                              ClientInfo clientInfo) {
        ChannelHandler sessionHandler = buildTransientSessionHandler(
            connMsg,
            settings,
            userSessionId,
            keepAliveSeconds,
            willMessage,
            clientInfo);
        assert sessionHandler instanceof IMQTTTransientSession;
        ctx.pipeline().replace(this, IMQTTTransientSession.NAME, sessionHandler);
        ctx.writeAndFlush(onConnected(connMsg, settings,
            userSessionId, keepAliveSeconds, 0, false, clientInfo));
        // report client connected event
        eventCollector.report(getLocal(ClientConnected.class)
            .serverId(sessionCtx.serverId)
            .clientInfo(clientInfo)
            .userSessionId(userSessionId)
            .keepAliveTimeSeconds(keepAliveSeconds)
            .cleanSession(connMsg.variableHeader().isCleanSession())
            .sessionPresent(sessionExists)
            .lastWill(willMessage != null ? new ClientConnected.WillInfo()
                .topic(willMessage.getTopic())
                .isRetain(willMessage.getRetain())
                .qos(willMessage.getMessage().getPubQoS())
                .payload(willMessage.getMessage().getPayload().asReadOnlyByteBuffer()) : null));
    }

    private void setupPersistentSessionHandler(MqttConnectMessage connMsg,
                                               TenantSettings settings,
                                               String userSessionId,
                                               int keepAliveSeconds,
                                               int sessionExpiryInterval,
                                               @Nullable ExistingSession existingSession,
                                               @Nullable LWT willMessage,
                                               ClientInfo clientInfo) {
        ChannelHandler sessionHandler = buildPersistentSessionHandler(connMsg,
            settings,
            userSessionId,
            keepAliveSeconds,
            sessionExpiryInterval,
            existingSession,
            willMessage,
            clientInfo);
        assert sessionHandler instanceof IMQTTPersistentSession;
        ctx.pipeline().replace(this, IMQTTPersistentSession.NAME, sessionHandler);
        ctx.writeAndFlush(onConnected(connMsg,
            settings, userSessionId, keepAliveSeconds, sessionExpiryInterval, existingSession != null, clientInfo));
        // report client connected event
        eventCollector.report(getLocal(ClientConnected.class)
            .serverId(sessionCtx.serverId)
            .clientInfo(clientInfo)
            .userSessionId(userSessionId)
            .keepAliveTimeSeconds(keepAliveSeconds)
            .cleanSession(connMsg.variableHeader().isCleanSession())
            .sessionPresent(existingSession != null)
            .lastWill(willMessage != null ? new ClientConnected.WillInfo()
                .topic(willMessage.getTopic())
                .isRetain(willMessage.getRetain())
                .qos(willMessage.getMessage().getPubQoS())
                .payload(willMessage.getMessage().getPayload().asReadOnlyByteBuffer()) : null));
    }

    protected abstract ChannelHandler buildTransientSessionHandler(MqttConnectMessage connMsg,
                                                                   TenantSettings settings,
                                                                   String userSessionId,
                                                                   int keepAliveSeconds,
                                                                   @Nullable LWT willMessage,
                                                                   ClientInfo clientInfo);

    protected abstract ChannelHandler buildPersistentSessionHandler(MqttConnectMessage connMsg,
                                                                    TenantSettings settings,
                                                                    String userSessionId,
                                                                    int keepAliveSeconds,
                                                                    int sessionExpiryInterval,
                                                                    @Nullable ExistingSession existingSession,
                                                                    @Nullable LWT willMessage,
                                                                    ClientInfo clientInfo);

    protected abstract MqttConnAckMessage onConnected(MqttConnectMessage connMsg,
                                                      TenantSettings settings,
                                                      String userSessionId,
                                                      int keepAliveSeconds,
                                                      int sessionExpiryInterval,
                                                      boolean sessionExists,
                                                      ClientInfo clientInfo);

    private void handleGoAway(GoAway goAway) {
        assert ctx.channel().eventLoop().inEventLoop();
        if (!ctx.channel().isActive()) {
            return;
        }
        for (Event<?> reason : goAway.reasons()) {
            eventCollector.report(reason);
        }
        Runnable doGoAway = () -> {
            if (goAway.farewell() != null) {
                ctx.writeAndFlush(goAway.farewell()).addListener(ChannelFutureListener.CLOSE);
            } else {
                ctx.channel().close();
            }
        };
        if (goAway.rightNow()) {
            doGoAway.run();
        } else {
            ctx.channel().eventLoop().schedule(doGoAway,
                ThreadLocalRandom.current().nextInt(100, 5000), TimeUnit.MILLISECONDS);
        }
    }

    private int keepAliveSeconds(int requestKeepAliveSeconds) {
        if (requestKeepAliveSeconds == 0) {
            // 20 mins the default keep alive duration
            requestKeepAliveSeconds = sessionCtx.defaultKeepAliveTimeSeconds;
        }
        requestKeepAliveSeconds = Math.max(MIN_CLIENT_KEEP_ALIVE_DURATION, requestKeepAliveSeconds);
        requestKeepAliveSeconds = Math.min(requestKeepAliveSeconds, MAX_CLIENT_KEEP_ALIVE_DURATION);
        return requestKeepAliveSeconds;
    }
}
