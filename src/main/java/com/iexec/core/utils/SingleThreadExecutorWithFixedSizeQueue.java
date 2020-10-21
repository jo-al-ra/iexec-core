/*
 * Copyright 2020 IEXEC BLOCKCHAIN TECH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.iexec.core.utils;

import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SuppressWarnings("serial")
public class SingleThreadExecutorWithFixedSizeQueue
        extends ThreadPoolTaskExecutor {

    public SingleThreadExecutorWithFixedSizeQueue(
        int queueSize,
        String threadNamePrefix
    ) {
        super();
        this.setCorePoolSize(1);
        this.setMaxPoolSize(1);
        this.setKeepAliveSeconds(0);
        this.setQueueCapacity(queueSize);
        this.setThreadNamePrefix(threadNamePrefix);
        // Discard silently when we add a task
        //  to the already-full queue.
        this.setRejectedExecutionHandler(new DiscardPolicy());
        this.initialize();
    }
}