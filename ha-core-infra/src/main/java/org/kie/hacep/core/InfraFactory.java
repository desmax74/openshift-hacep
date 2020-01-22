/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kie.hacep.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.kie.hacep.EnvConfig;
import org.kie.hacep.consumer.DroolsConsumerHandler;
import org.kie.hacep.core.infra.DefaultSessionSnapShooter;
import org.kie.hacep.core.infra.SessionSnapshooter;
import org.kie.hacep.core.infra.consumer.ConsumerHandler;
import org.kie.hacep.core.infra.consumer.DefaultKafkaConsumer;
import org.kie.hacep.core.infra.consumer.EventConsumer;
import org.kie.hacep.core.infra.consumer.ItemToProcess;
import org.kie.hacep.core.infra.consumer.LocalConsumer;
import org.kie.hacep.core.infra.utils.ConsumerUtilsCoreImpl;
import org.kie.remote.RemoteKieSession;
import org.kie.remote.RemoteStreamingKieSession;
import org.kie.remote.TopicsConfig;
import org.kie.remote.impl.ClientUtils;
import org.kie.remote.impl.RemoteKieSessionImpl;
import org.kie.remote.impl.RemoteStreamingKieSessionImpl;
import org.kie.remote.impl.consumer.KafkaListenerThread;
import org.kie.remote.impl.consumer.Listener;
import org.kie.remote.impl.consumer.ListenerThread;
import org.kie.remote.impl.consumer.LocalListenerThread;
import org.kie.remote.impl.producer.EventProducer;
import org.kie.remote.impl.producer.LocalProducer;
import org.kie.remote.impl.producer.Producer;

import static org.kie.remote.CommonConfig.LOCAL_MESSAGE_SYSTEM_CONF;
import static org.kie.remote.util.ConfigurationUtil.readBoolean;

public class InfraFactory {

    private InfraFactory(){}

    public static EventConsumer getEventConsumer(EnvConfig config) {
        return config.isLocal() ? new LocalConsumer(config) : new DefaultKafkaConsumer(config,getProducer(false));
    }

    public static SessionSnapshooter getSnapshooter(EnvConfig envConfig) {
        return new DefaultSessionSnapShooter(envConfig);
    }

    public static ConsumerHandler getConsumerHandler(Producer producer, EnvConfig envConfig) {
        return new DroolsConsumerHandler(producer, envConfig, getSnapshooter(envConfig), new ConsumerUtilsCoreImpl());
    }

    public static KafkaConsumer getConsumer(String topic, Properties properties) {
        KafkaConsumer consumer = new KafkaConsumer(properties);
        List<PartitionInfo> infos = consumer.partitionsFor(topic);
        List<TopicPartition> partitions = new ArrayList<>();
        if (infos != null) {
            for (PartitionInfo partition : infos) {
                partitions.add(new TopicPartition(topic, partition.partition()));
            }
        }
        consumer.assign(partitions);

        Map<TopicPartition, Long> offsets = consumer.endOffsets(partitions);
        Long lastOffset = 0l;
        for (Map.Entry<TopicPartition, Long> entry : offsets.entrySet()) {
            lastOffset = entry.getValue();
        }
        if (lastOffset == 0) {
            lastOffset = 1l;// this is to start the seek with offset -1 on empty topic
        }
        Set<TopicPartition> assignments = consumer.assignment();
        for (TopicPartition part : assignments) {
            consumer.seek(part, lastOffset - 1);
        }
        return consumer;
    }

    public static Listener getListener(Properties props, boolean isLocal){
        return new Listener(props, InfraFactory.getListenerThread(TopicsConfig.getDefaultTopicsConfig(), isLocal, props));
    }

    public static ListenerThread getListenerThread(TopicsConfig topicsConfig,
                                             boolean isLocal,
                                             Properties configuration) {
        return isLocal ?
                new LocalListenerThread(topicsConfig) :
                new KafkaListenerThread(getMergedConf(configuration), topicsConfig);
    }

    public static Properties getMergedConf(Properties configuration) {
        Properties conf = ClientUtils.getConfiguration(ClientUtils.CONSUMER_CONF);
        conf.putAll(configuration);
        return conf;
    }


    public static RemoteKieSession createRemoteKieSession(Properties configuration, Listener listener, Producer producer) {
        return new RemoteKieSessionImpl(configuration, listener, producer);
    }

    public static RemoteKieSession createRemoteKieSession(Properties configuration, TopicsConfig envConfig, Listener listener, Producer producer) {
        return new RemoteKieSessionImpl(configuration, envConfig, listener, producer);
    }

    public static Producer getProducer(Properties configuration) {
        return getProducer(readBoolean(configuration, LOCAL_MESSAGE_SYSTEM_CONF));
    }

    public static Producer getProducer(boolean isLocal) {
        return isLocal ? new LocalProducer() : new EventProducer();
    }

    public static RemoteStreamingKieSession createRemoteStreamingKieSession(Properties configuration, Listener listener, Producer producer) {
        return new RemoteStreamingKieSessionImpl(configuration, listener, producer);
    }

    public static RemoteStreamingKieSession createRemoteStreamingKieSession(Properties configuration, TopicsConfig envConfig, Listener listener, Producer producer) {
        return new RemoteStreamingKieSessionImpl(configuration, envConfig, listener, producer);
    }

    public static ItemToProcess getItemToProcess(ConsumerRecord record) {
        return new ItemToProcess(record.key().toString(), record.offset(), record.value());
    }
}
