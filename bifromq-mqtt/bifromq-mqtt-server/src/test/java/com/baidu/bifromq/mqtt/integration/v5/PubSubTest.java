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

package com.baidu.bifromq.mqtt.integration.v5;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import com.baidu.bifromq.mqtt.integration.MQTTTest;
import com.baidu.bifromq.mqtt.integration.v5.client.MqttMsg;
import com.baidu.bifromq.mqtt.integration.v5.client.MqttTestClient;
import com.baidu.bifromq.plugin.authprovider.type.CheckResult;
import com.baidu.bifromq.plugin.authprovider.type.Granted;
import com.baidu.bifromq.plugin.authprovider.type.MQTT3AuthData;
import com.baidu.bifromq.plugin.authprovider.type.MQTT3AuthResult;
import com.baidu.bifromq.plugin.authprovider.type.MQTT5AuthData;
import com.baidu.bifromq.plugin.authprovider.type.MQTT5AuthResult;
import com.baidu.bifromq.plugin.authprovider.type.Ok;
import com.baidu.bifromq.plugin.authprovider.type.Success;
import com.google.protobuf.ByteString;
import io.reactivex.rxjava3.core.Observable;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.testng.annotations.Test;

public class PubSubTest extends MQTTTest {

    private final String userId = "userId";

    protected void doSetup(Method method) {
        when(authProvider.auth(any(MQTT5AuthData.class)))
            .thenReturn(CompletableFuture.completedFuture(MQTT5AuthResult.newBuilder()
                .setSuccess(Success.newBuilder()
                    .setTenantId(tenantId)
                    .setUserId(userId)
                    .build())
                .build()));
        when(authProvider.checkPermission(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(CheckResult.newBuilder()
                .setGranted(Granted.newBuilder().build())
                .build()));
    }

    @Test(groups = "integration")
    public void pubQoS0SubQoS0Transient() {
        pubSub("pubQoS0SubQoS0Transient", 0, "pubQoS0SubQoS0Transient", 0, true);
    }

    @Test(groups = "integration")
    public void pubQoS0SubQoS0Persistent() {
        pubSub("pubQoS0SubQoS0Persistent", 0, "pubQoS0SubQoS0Persistent", 0, false);
    }

    @Test(groups = "integration")
    public void pubQOS0SubQoS1Transient() {
        pubSub("pubQOS0SubQoS1Transient", 0, "pubQOS0SubQoS1Transient", 1, true);
    }

    @Test(groups = "integration")
    public void pubQOS0SubQoS1Persistent() {
        pubSub("pubQOS0SubQoS1Persistent", 0, "pubQOS0SubQoS1Persistent", 1, false);
    }

    @Test(groups = "integration")
    public void pubQOS0SubQoS2Transient() {
        pubSub("pubQOS0SubQoS2Transient", 0, "pubQOS0SubQoS2Transient", 2, true);
    }

    @Test(groups = "integration")
    public void pubQOS0SubQoS2Persistent() {
        pubSub("pubQOS0SubQoS2Persistent", 0, "pubQOS0SubQoS2Persistent", 2, false);
    }


    @Test(groups = "integration")
    public void pubQOS1SubQoS0Transient() {
        pubSub("pubQOS1SubQoS0Transient", 1, "pubQOS1SubQoS0Transient", 0, true);
    }

    @Test(groups = "integration")
    public void pubQOS1SubQoS0Persistent() {
        pubSub("pubQOS1SubQoS0Persistent", 1, "pubQOS1SubQoS0Persistent", 0, false);
    }


    @Test(groups = "integration")
    public void pubQOS1SubQoS1Transient() {
        pubSub("pubQOS1SubQoS1Transient", 1, "pubQOS1SubQoS1Transient", 1, true);
    }

    @Test(groups = "integration")
    public void pubQOS1SubQoS1Persistent() {
        pubSub("pubQOS1SubQoS1Persistent", 1, "pubQOS1SubQoS1Persistent", 1, false);
    }


    @Test(groups = "integration")
    public void pubQOS1SubQoS2Transient() {
        pubSub("pubQOS1SubQoS2Transient", 1, "pubQOS1SubQoS2Transient", 2, true);
    }

    @Test(groups = "integration")
    public void pubQOS1SubQoS2Persistent() {
        pubSub("pubQOS1SubQoS2Persistent", 1, "pubQOS1SubQoS2Persistent", 2, false);
    }


    @Test(groups = "integration")
    public void pubQOS2SubQoS0Transient() {
        pubSub("pubQOS2SubQoS0Transient", 2, "pubQOS2SubQoS0Transient", 0, true);
    }

    @Test(groups = "integration")
    public void pubQOS2SubQoS0Persistent() {
        pubSub("pubQOS2SubQoS0Persistent", 2, "pubQOS2SubQoS0Persistent", 0, false);
    }


    @Test(groups = "integration")
    public void pubQOS2SubQoS1Transient() {
        pubSub("pubQOS2SubQoS1Transient", 2, "pubQOS2SubQoS1Transient", 1, true);
    }

    @Test(groups = "integration")
    public void pubQOS2SubQoS1Persistent() {
        pubSub("pubQOS2SubQoS1Persistent", 2, "pubQOS2SubQoS1Persistent", 1, false);
    }

    @Test(groups = "integration")
    public void pubQOS2SubQoS2Transient() {
        pubSub("pubQOS2SubQoS2Transient", 2, "pubQOS2SubQoS2Transient", 2, true);
    }

    @Test(groups = "integration")
    public void pubQOS2SubQoS2Persistent() {
        pubSub("pubQOS2SubQoS2Persistent", 2, "pubQOS2SubQoS2Persistent", 2, false);
    }


    private void pubSub(String topic, int pubQoS, String topicFilter, int subQoS, boolean cleanSession) {
        MqttConnectionOptions connOpts = new MqttConnectionOptions();
        connOpts.setCleanStart(cleanSession);
        connOpts.setSessionExpiryInterval(0L);
        connOpts.setUserName(tenantId + "/" + userId);

        MqttTestClient client = new MqttTestClient(BROKER_URI);
        client.connect(connOpts);
        Observable<MqttMsg> topicSub = client.subscribe(topicFilter, subQoS);
        client.publish(topic, pubQoS, ByteString.copyFromUtf8("hello"), false);
        MqttMsg msg = topicSub.timeout(10, TimeUnit.SECONDS).blockingFirst();
        assertEquals(msg.topic, topic);
        assertEquals(msg.qos, Math.min(pubQoS, subQoS));
        assertFalse(msg.isDup);
        assertFalse(msg.isRetain);
        assertEquals(msg.payload, ByteString.copyFromUtf8("hello"));
        client.unsubscribe(topicFilter);
        client.disconnect();
        client.close();
    }
}
