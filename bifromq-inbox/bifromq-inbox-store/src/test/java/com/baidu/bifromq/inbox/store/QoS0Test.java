/*
 * Copyright (c) 2023. The BifroMQ Authors. All Rights Reserved.
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

package com.baidu.bifromq.inbox.store;

import static com.baidu.bifromq.type.QoS.AT_MOST_ONCE;

import org.testng.annotations.Test;

public class QoS0Test extends InboxInsertTest {
    @Test(groups = "integration")
    public void fetchWithoutStartAfter() {
        fetchWithoutStartAfter(AT_MOST_ONCE);
    }

    @Test(groups = "integration")
    public void fetchWithStartAfter() {
        fetchWithStartAfter(AT_MOST_ONCE);
    }

    @Test(groups = "integration")
    public void commit() {
        commit(AT_MOST_ONCE);
    }

    @Test(groups = "integration")
    public void commitAll() {
        commitAll(AT_MOST_ONCE);
    }

    @Test(groups = "integration")
    public void insertDropOldest() {
        insertDropOldest(AT_MOST_ONCE);
    }

    @Test(groups = "integration")
    public void insertDropYoungest() {
        insertDropYoungest(AT_MOST_ONCE);
    }
}

