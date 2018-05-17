/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper.server.quorum;

import java.io.File;
import java.io.IOException;

import javax.management.JMException;
import javax.security.sasl.SaslException;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.jmx.ManagedUtil;
import org.apache.zookeeper.server.DatadirCleanupManager;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * <h2>Configuration file</h2>
 *
 * When the main() method of this class is used to start the program, the first
 * argument is used as a path to the config file, which will be used to obtain
 * configuration information. This file is a Properties file, so keys and
 * values are separated by equals (=) and the key/value pairs are separated
 * by new lines. The following is a general summary of keys used in the
 * configuration file. For full details on this see the documentation in
 * docs/index.html
 * <ol>
 * <li>dataDir - The directory where the ZooKeeper data is stored.</li>
 * <li>dataLogDir - The directory where the ZooKeeper transaction log is stored.</li>
 * <li>clientPort - The port used to communicate with clients.</li>
 * <li>tickTime - The duration of a tick in milliseconds. This is the basic
 * unit of time in ZooKeeper.</li>
 * <li>initLimit - The maximum number of ticks that a follower will wait to
 * initially synchronize with a leader.</li>
 * <li>syncLimit - The maximum number of ticks that a follower will wait for a
 * message (including heartbeats) from the leader.</li>
 * <li>server.<i>id</i> - This is the host:port[:port] that the server with the
 * given id will use for the quorum protocol.</li>
 * </ol>
 * In addition to the config file. There is a file in the data directory called
 * "myid" that contains the server id as an ASCII decimal value.
 *
 */
@InterfaceAudience.Public
public class QuorumPeerMain {

	private static final Logger LOG = LoggerFactory.getLogger(QuorumPeerMain.class);

	private static final String USAGE = "Usage: QuorumPeerMain configfile";

	protected QuorumPeer quorumPeer;

	/**
	 * 以复制模式启动Zookeeper服务器，通过命令行传入配置文件路径args
	 */
	public static void main(String[] args) {
		// 创建一个QuorumPeerMain对象
		QuorumPeerMain main = new QuorumPeerMain();
		try {
			main.initializeAndRun(args);
		} catch (IllegalArgumentException e) {
			LOG.error("Invalid arguments, exiting abnormally", e);
			LOG.info(USAGE);
			System.err.println(USAGE);
			System.exit(2);
		} catch (ConfigException e) {
			LOG.error("Invalid config, exiting abnormally", e);
			System.err.println("Invalid config, exiting abnormally");
			System.exit(2);
		} catch (Exception e) {
			LOG.error("Unexpected exception, exiting abnormally", e);
			System.exit(1);
		}
		LOG.info("Exiting normally");
		System.exit(0);
	}

	/**
	 * 初始化并运行服务器
	 * @param args 
	 * @throws ConfigException
	 * @throws IOException
	 */
	protected void initializeAndRun(String[] args) throws ConfigException, IOException {
		// 创建一个QuorumPeerConfig配置对象，用于解析配置文件的相关配置
		QuorumPeerConfig config = new QuorumPeerConfig();

		if (args.length == 1) {
			// 解析配置文件
			config.parse(args[0]);
		}

		// 启动并调度清理任务
		DatadirCleanupManager purgeMgr = new DatadirCleanupManager(config.getDataDir(),
				config.getDataLogDir(), config.getSnapRetainCount(), config.getPurgeInterval());
		purgeMgr.start();

		/**
		 * 判断以单机还是复制形式运行Zookeeper
		 */
		if (args.length == 1 && config.servers.size() > 0) {
			// 复制集群模式运行
			runFromConfig(config);
		} else {
			LOG.warn("Either no config or no quorum defined in config, running " + " in standalone mode");
			// 单机模式运行Zookeeper
			ZooKeeperServerMain.main(args);
		}
	}

	/**
	 * 加载配置运行服务器
	 * @param config
	 * @throws IOException
	 */
	public void runFromConfig(QuorumPeerConfig config) throws IOException {
		try {
			ManagedUtil.registerLog4jMBeans();
		} catch (JMException e) {
			LOG.warn("Unable to register log4j JMX control", e);
		}

		LOG.info("Starting quorum peer");
		try {
			// 创建一个ServerCnxnFactory，默认为NIOServerCnxnFactory
			ServerCnxnFactory cnxnFactory = ServerCnxnFactory.createFactory();

			// 对ServerCnxnFactory进行相关配置
			cnxnFactory.configure(config.getClientPortAddress(), config.getMaxClientCnxns());

			quorumPeer = getQuorumPeer();

			quorumPeer.setQuorumPeers(config.getServers());
			quorumPeer.setTxnFactory(
					new FileTxnSnapLog(new File(config.getDataDir()), new File(config.getDataLogDir())));
			quorumPeer.setElectionType(config.getElectionAlg());
			quorumPeer.setMyid(config.getServerId());
			quorumPeer.setTickTime(config.getTickTime());
			quorumPeer.setInitLimit(config.getInitLimit());
			quorumPeer.setSyncLimit(config.getSyncLimit());
			quorumPeer.setQuorumListenOnAllIPs(config.getQuorumListenOnAllIPs());
			quorumPeer.setCnxnFactory(cnxnFactory);
			quorumPeer.setQuorumVerifier(config.getQuorumVerifier());
			quorumPeer.setClientPortAddress(config.getClientPortAddress());
			quorumPeer.setMinSessionTimeout(config.getMinSessionTimeout());
			quorumPeer.setMaxSessionTimeout(config.getMaxSessionTimeout());
			quorumPeer.setZKDatabase(new ZKDatabase(quorumPeer.getTxnFactory()));
			quorumPeer.setLearnerType(config.getPeerType());
			quorumPeer.setSyncEnabled(config.getSyncEnabled());

			// sets quorum sasl authentication configurations
			quorumPeer.setQuorumSaslEnabled(config.quorumEnableSasl);
			if (quorumPeer.isQuorumSaslAuthEnabled()) {
				quorumPeer.setQuorumServerSaslRequired(config.quorumServerRequireSasl);
				quorumPeer.setQuorumLearnerSaslRequired(config.quorumLearnerRequireSasl);
				quorumPeer.setQuorumServicePrincipal(config.quorumServicePrincipal);
				quorumPeer.setQuorumServerLoginContext(config.quorumServerLoginContext);
				quorumPeer.setQuorumLearnerLoginContext(config.quorumLearnerLoginContext);
			}

			quorumPeer.setQuorumCnxnThreadsSize(config.quorumCnxnThreadsSize);
			quorumPeer.initialize();

			quorumPeer.start();
			quorumPeer.join();
		} catch (InterruptedException e) {
			// warn, but generally this is ok
			LOG.warn("Quorum Peer interrupted", e);
		}
	}

	// @VisibleForTesting
	protected QuorumPeer getQuorumPeer() throws SaslException {
		return new QuorumPeer();
	}
}
