/**
 * Copyright (C) 2010-2013 Alibaba Group Holding Limited
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
package com.alibaba.rocketmq.tools.command.topic;

import com.alibaba.rocketmq.client.Validators;
import com.alibaba.rocketmq.common.TopicConfig;
import com.alibaba.rocketmq.common.sysflag.TopicSysFlag;
import com.alibaba.rocketmq.remoting.RPCHook;
import com.alibaba.rocketmq.srvutil.ServerUtil;
import com.alibaba.rocketmq.tools.admin.DefaultMQAdminExt;
import com.alibaba.rocketmq.tools.command.BrokerFilter;
import com.alibaba.rocketmq.tools.command.CommandUtil;
import com.alibaba.rocketmq.tools.command.SubCommand;
import com.google.common.base.Strings;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * 修改、创建Topic配置命令
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 * @since 2013-7-21
 */
public class UpdateTopicSubCommand implements SubCommand {

    @Override
    public String commandName() {
        return "updateTopic";
    }


    @Override
    public String commandDesc() {
        return "Update or create topic";
    }


    @Override
    public Options buildCommandlineOptions(Options options) {
        Option opt = new Option("b", "brokerAddr", true, "create topic to which broker");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("c", "clusterName", true, "create topic to which cluster");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("t", "topic", true, "topic name");
        opt.setRequired(true);
        options.addOption(opt);

        opt = new Option("r", "readQueueNums", true, "set read queue nums");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("w", "writeQueueNums", true, "set write queue nums");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("p", "perm", true, "set topic's permission(2|4|6), intro[2:W; 4:R; 6:RW]");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("o", "order", true, "set topic's order(true|false)");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("u", "unit", true, "is unit topic (true|false)");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("s", "hasUnitSub", true, "has unit sub (true|false)");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("d", "supportUnitTest", true, "support unit test (true|false)");
        opt.setRequired(false);
        options.addOption(opt);

        // comma separated zone ids.
        opt = new Option("z", "zone", true, "Zone to create topic into");
        opt.setRequired(false);
        options.addOption(opt);

        return options;
    }


    @Override
    public void execute(final CommandLine commandLine, final Options options, RPCHook rpcHook) {
        final DefaultMQAdminExt defaultMQAdminExt = new DefaultMQAdminExt(rpcHook);
        defaultMQAdminExt.setInstanceName(Long.toString(System.currentTimeMillis()));

        try {
            final TopicConfig topicConfig = new TopicConfig();
            topicConfig.setReadQueueNums(8);
            topicConfig.setWriteQueueNums(8);
            topicConfig.setTopicName(commandLine.getOptionValue('t').trim());

            Validators.checkTopic(topicConfig.getTopicName());

            // readQueueNums
            if (commandLine.hasOption('r')) {
                topicConfig.setReadQueueNums(Integer.parseInt(commandLine.getOptionValue('r').trim()));
            }

            // writeQueueNums
            if (commandLine.hasOption('w')) {
                topicConfig.setWriteQueueNums(Integer.parseInt(commandLine.getOptionValue('w').trim()));
            }

            // perm
            if (commandLine.hasOption('p')) {
                topicConfig.setPerm(Integer.parseInt(commandLine.getOptionValue('p').trim()));
            }

            boolean isUnit = false;
            if (commandLine.hasOption('u')) {
                isUnit = Boolean.parseBoolean(commandLine.getOptionValue('u').trim());
            }

            boolean isCenterSync = false;
            if (commandLine.hasOption('s')) {
                isCenterSync = Boolean.parseBoolean(commandLine.getOptionValue('s').trim());
            }

            boolean supportUnitTest = false;
            if (commandLine.hasOption("d")) {
                supportUnitTest = Boolean.parseBoolean(commandLine.getOptionValue('d').trim());
            }

            int topicCenterSync = TopicSysFlag.buildSysFlag(isUnit, isCenterSync, supportUnitTest);
            topicConfig.setTopicSysFlag(topicCenterSync);

            boolean isOrder = false;
            if (commandLine.hasOption('o')) {
                isOrder = Boolean.parseBoolean(commandLine.getOptionValue('o').trim());
            }
            topicConfig.setOrder(isOrder);

            if (commandLine.hasOption('b')) {
                String addr = commandLine.getOptionValue('b').trim();

                defaultMQAdminExt.start();
                defaultMQAdminExt.createAndUpdateTopicConfig(addr, topicConfig);

                if (isOrder) {
                    // 注册顺序消息到 nameserver
                    String brokerName = CommandUtil.fetchBrokerNameByAddr(defaultMQAdminExt, addr);
                    String orderConf = brokerName + ":" + topicConfig.getWriteQueueNums();
                    defaultMQAdminExt.createOrUpdateOrderConf(topicConfig.getTopicName(), orderConf, false);
                    System.out.println(String.format("set broker orderConf. isOrder=%s, orderConf=[%s]", true, orderConf));
                }
                System.out.printf("create topic to %s success.\n", addr);
                System.out.println(topicConfig);
                return;

            } else if (commandLine.hasOption('c')) {
                String clusterName = commandLine.getOptionValue('c').trim();

                String zone = commandLine.getOptionValue("z");
                BrokerFilter brokerFilter = null;
                if (!Strings.isNullOrEmpty(zone)) {
                    Collection<String> regionIds = zone.contains(",") ? Arrays.asList(zone.split(",")) : Collections.singleton(zone);
                    brokerFilter = new RegionBrokerFilter(regionIds);
                }

                defaultMQAdminExt.start();
                Set<String> masterSet = CommandUtil.fetchMasterAddrByClusterName(defaultMQAdminExt, clusterName, brokerFilter);

                final CountDownLatch countDownLatch = new CountDownLatch(masterSet.size());
                ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                for (final String address : masterSet) {
                    executorService.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                defaultMQAdminExt.createAndUpdateTopicConfig(address, topicConfig);
                                System.out.println("updateTopic against broker[" + address + "] succeeded.");
                            } catch (Exception e) {
                                System.out.println("Abort updateTopic against broker[" + address + "]");
                                e.printStackTrace();
                            } finally {
                                countDownLatch.countDown();
                            }
                        }
                    });
                }
                countDownLatch.await();
                executorService.shutdown();

                if (isOrder) {
                    // 注册顺序消息到 nameserver
                    Set<String> brokerNameSet = CommandUtil.fetchBrokerNameByClusterName(defaultMQAdminExt, clusterName);
                    StringBuilder orderConf = new StringBuilder();
                    String splitter = "";
                    for (String s : brokerNameSet) {
                        orderConf.append(splitter).append(s).append(":").append(topicConfig.getWriteQueueNums());
                        splitter = ";";
                    }
                    defaultMQAdminExt.createOrUpdateOrderConf(topicConfig.getTopicName(), orderConf.toString(), true);
                    System.out.println(String.format("set cluster orderConf. isOrder=%s, orderConf=[%s]", true, orderConf.toString()));
                }

                System.out.println(topicConfig);
                return;
            }

            ServerUtil.printCommandLineHelp("mqadmin " + this.commandName(), options);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            defaultMQAdminExt.shutdown();
        }
    }

    private static final Pattern BROKER_NAME_PATTERN = Pattern.compile("\\p{Alpha}{1,}_(\\d{1,})_\\w*");

    private class RegionBrokerFilter implements BrokerFilter {

        private final Collection<String> regionIds;


        public RegionBrokerFilter(Collection<String> regionIds) {
            this.regionIds = regionIds;
        }

        @Override
        public boolean accept(String brokerName) {
            Matcher matcher = BROKER_NAME_PATTERN.matcher(brokerName);
            if (matcher.matches()) {
                String regionId = matcher.group(1);
                if (regionIds.contains(regionId.trim())) {
                    System.out.println(brokerName + " matches");
                    return true;
                }
            }
            return false;
        }
    }
}