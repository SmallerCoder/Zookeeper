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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.auth.QuorumAuth;
import org.apache.zookeeper.server.quorum.flexible.QuorumHierarchical;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@InterfaceAudience.Public
public class QuorumPeerConfig {

	private static final Logger LOG = LoggerFactory.getLogger(QuorumPeerConfig.class);

	protected InetSocketAddress clientPortAddress;
	protected String dataDir;
	protected String dataLogDir;
	protected int tickTime = ZooKeeperServer.DEFAULT_TICK_TIME;
	protected int maxClientCnxns = 60;
	/** defaults to -1 if not set explicitly */
	protected int minSessionTimeout = -1;
	/** defaults to -1 if not set explicitly */
	protected int maxSessionTimeout = -1;

	protected int initLimit;
	protected int syncLimit;
	protected int electionAlg = 3;
	protected int electionPort = 2182;
	protected boolean quorumListenOnAllIPs = false;
	protected final HashMap<Long, QuorumServer> servers = new HashMap<Long, QuorumServer>();
	protected final HashMap<Long, QuorumServer> observers = new HashMap<Long, QuorumServer>();

	protected long serverId;
	protected HashMap<Long, Long> serverWeight = new HashMap<Long, Long>();
	protected HashMap<Long, Long> serverGroup = new HashMap<Long, Long>();
	protected int numGroups = 0;
	protected QuorumVerifier quorumVerifier;
	protected int snapRetainCount = 3;
	protected int purgeInterval = 0;
	protected boolean syncEnabled = true;

	protected LearnerType peerType = LearnerType.PARTICIPANT;

	/** Configurations for the quorumpeer-to-quorumpeer sasl authentication */
	protected boolean quorumServerRequireSasl = false;
	protected boolean quorumLearnerRequireSasl = false;
	protected boolean quorumEnableSasl = false;
	protected String quorumServicePrincipal =
			QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE;
	protected String quorumLearnerLoginContext =
			QuorumAuth.QUORUM_LEARNER_SASL_LOGIN_CONTEXT_DFAULT_VALUE;
	protected String quorumServerLoginContext =
			QuorumAuth.QUORUM_SERVER_SASL_LOGIN_CONTEXT_DFAULT_VALUE;
	protected int quorumCnxnThreadsSize;

	/**
	 * Minimum snapshot retain count.
	 * @see org.apache.zookeeper.server.PurgeTxnLog#purge(File, File, int)
	 */
	private final int MIN_SNAP_RETAIN_COUNT = 3;

	@SuppressWarnings("serial")
	public static class ConfigException extends Exception {

		public ConfigException(String msg) {
			super(msg);
		}

		public ConfigException(String msg, Exception e) {
			super(msg, e);
		}
	}

	private static String[] splitWithLeadingHostname(String s) throws ConfigException {
		/* Does it start with an IPv6 literal? */
		if (s.startsWith("[")) {
			int i = s.indexOf("]:");
			if (i < 0) {
				throw new ConfigException(s + " starts with '[' but has no matching ']:'");
			}

			String[] sa = s.substring(i + 2).split(":");
			String[] nsa = new String[sa.length + 1];
			nsa[0] = s.substring(1, i);
			System.arraycopy(sa, 0, nsa, 1, sa.length);

			return nsa;
		} else {
			return s.split(":");
		}
	}

	/**
	 * 解析Zookeeper配置文件
	 * @param path
	 * @throws ConfigException
	 */
	public void parse(String path) throws ConfigException {
		// 配置文件
		File configFile = new File(path);

		LOG.info("Reading configuration from: " + configFile);

		try {
			// 如果配置文件不存在，则抛出异常
			if (!configFile.exists()) {
				throw new IllegalArgumentException(configFile.toString() + " file is missing");
			}
			// 初始化属性类，用于解析properties属性文件
			Properties cfg = new Properties();
			// 将配置文件读进输入流
			FileInputStream in = new FileInputStream(configFile);
			try {
				// 加载配置文件，生成Properties属性集
				cfg.load(in);
			} finally {
				// 最后关闭输入流，这里采用手动关闭，也可以使用java 7中的带资源的try()自动关闭
				in.close();
			}

			parseProperties(cfg);
		} catch (IOException e) {
			throw new ConfigException("Error processing " + path, e);
		} catch (IllegalArgumentException e) {
			throw new ConfigException("Error processing " + path, e);
		}
	}

	/**
	 * 从Properties解析相关配置
	 * @param zkProp
	 * @throws IOException
	 * @throws ConfigException
	 */
	public void parseProperties(Properties zkProp) throws IOException, ConfigException {
		// 客户端连接服务器的端口，即服务器的监听端口
		int clientPort = 0;
		// 客户端链接服务器的地址
		String clientPortAddress = null;
		// 遍历所有的key-value键值对
		for (Entry<Object, Object> entry : zkProp.entrySet()) {
			// 获取属性键,注意这里要把首尾空格去掉
			String key = entry.getKey().toString().trim();
			// 获取键值value
			String value = entry.getValue().toString().trim();
			// 存储快照文件snapshot的目录配置
			if (key.equals("dataDir")) {
				dataDir = value;
				// 事务日志存储目录
			} else if (key.equals("dataLogDir")) {
				dataLogDir = value;
				// 客户端连接server的端口，zk启动总得有个端口吧！如果你没有配置，则会报错！一般我们会将端口配置为2181
			} else if (key.equals("clientPort")) {
				clientPort = Integer.parseInt(value);
				// 服务器IP地址
			} else if (key.equals("clientPortAddress")) {
				clientPortAddress = value.trim();
				// zk中的基本事件单位，用于心跳和session最小过期时间为2*tickTime
			} else if (key.equals("tickTime")) {
				tickTime = Integer.parseInt(value);
				// 客户端并发连接数量，注意是一个客户端跟一台服务器的并发连接数量，也就是说，假设值为3，那么某个客户端不能同时并发连接3次到同一台服务器（并发嘛！），否则会出现下面错误too many connections from /127.0.0.1 - max is 3
			} else if (key.equals("maxClientCnxns")) {
				maxClientCnxns = Integer.parseInt(value);
				// 会话最小超时时间，默认为2*ticket
			} else if (key.equals("minSessionTimeout")) {
				minSessionTimeout = Integer.parseInt(value);
				// 会话最大超时时间，默认为20*ticket
			} else if (key.equals("maxSessionTimeout")) {
				maxSessionTimeout = Integer.parseInt(value);
				// 允许follower同步和连接到leader的时间总量，以ticket为单位
			} else if (key.equals("initLimit")) {
				initLimit = Integer.parseInt(value);
				// follower与leader之间同步的时间量
			} else if (key.equals("syncLimit")) {
				syncLimit = Integer.parseInt(value);
				// zk选举算法选择，默认值为3，表示采用快速选举算法
			} else if (key.equals("electionAlg")) {
				electionAlg = Integer.parseInt(value);
				// 当设置为true时，ZooKeeper服务器将侦听来自所有可用IP地址的对等端的连接，而不仅仅是在配置文件的服务器列表中配置的地址（即集群中配置的server.1,server.2。。。。）。 它会影响处理ZAB协议和Fast Leader Election协议的连接。 默认值为false
			} else if (key.equals("quorumListenOnAllIPs")) {
				quorumListenOnAllIPs = Boolean.parseBoolean(value);
				// 服务器的角色，是观察者observer还是参与选举或成为leader,默认为PARTICIPANT
			} else if (key.equals("peerType")) {
				if (value.toLowerCase().equals("observer")) {
					peerType = LearnerType.OBSERVER;
				} else if (value.toLowerCase().equals("participant")) {
					peerType = LearnerType.PARTICIPANT;
				} else {
					throw new ConfigException("Unrecognised peertype: " + value);
				}
				// 系统属性
			} else if (key.equals("syncEnabled")) {
				syncEnabled = Boolean.parseBoolean(value);
			} else if (key.equals("autopurge.snapRetainCount")) {
				snapRetainCount = Integer.parseInt(value);
			} else if (key.equals("autopurge.purgeInterval")) {
				purgeInterval = Integer.parseInt(value);
			} else if (key.startsWith("server.")) {
				// server.3
				int dot = key.indexOf('.');
				long sid = Long.parseLong(key.substring(dot + 1));
				String parts[] = splitWithLeadingHostname(value);
				if ((parts.length != 2) && (parts.length != 3) && (parts.length != 4)) {
					LOG.error(value + " does not have the form host:port or host:port:port "
							+ " or host:port:port:type");
				}
				LearnerType type = null;
				String hostname = parts[0];
				Integer port = Integer.parseInt(parts[1]);
				Integer electionPort = null;
				if (parts.length > 2) {
					electionPort = Integer.parseInt(parts[2]);
				}
				if (parts.length > 3) {
					if (parts[3].toLowerCase().equals("observer")) {
						type = LearnerType.OBSERVER;
					} else if (parts[3].toLowerCase().equals("participant")) {
						type = LearnerType.PARTICIPANT;
					} else {
						throw new ConfigException("Unrecognised peertype: " + value);
					}
				}
				if (type == LearnerType.OBSERVER) {
					observers.put(Long.valueOf(sid),
							new QuorumServer(sid, hostname, port, electionPort, type));
				} else {
					servers.put(Long.valueOf(sid), new QuorumServer(sid, hostname, port, electionPort, type));
				}
			} else if (key.startsWith("group")) {
				int dot = key.indexOf('.');
				long gid = Long.parseLong(key.substring(dot + 1));

				numGroups++;

				String parts[] = value.split(":");
				for (String s : parts) {
					long sid = Long.parseLong(s);
					if (serverGroup.containsKey(sid))
						throw new ConfigException("Server " + sid + "is in multiple groups");
					else serverGroup.put(sid, gid);
				}

			} else if (key.startsWith("weight")) {
				int dot = key.indexOf('.');
				long sid = Long.parseLong(key.substring(dot + 1));
				serverWeight.put(sid, Long.parseLong(value));
			} else if (key.equals(QuorumAuth.QUORUM_SASL_AUTH_ENABLED)) {
				quorumEnableSasl = Boolean.parseBoolean(value);
			} else if (key.equals(QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED)) {
				quorumServerRequireSasl = Boolean.parseBoolean(value);
			} else if (key.equals(QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED)) {
				quorumLearnerRequireSasl = Boolean.parseBoolean(value);
			} else if (key.equals(QuorumAuth.QUORUM_LEARNER_SASL_LOGIN_CONTEXT)) {
				quorumLearnerLoginContext = value;
			} else if (key.equals(QuorumAuth.QUORUM_SERVER_SASL_LOGIN_CONTEXT)) {
				quorumServerLoginContext = value;
			} else if (key.equals(QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL)) {
				quorumServicePrincipal = value;
			} else if (key.equals("quorum.cnxn.threads.size")) {
				quorumCnxnThreadsSize = Integer.parseInt(value);
			} else {
				System.setProperty("zookeeper." + key, value);
			}
		}
		if (!quorumEnableSasl && quorumServerRequireSasl) {
			throw new IllegalArgumentException(QuorumAuth.QUORUM_SASL_AUTH_ENABLED
					+ " is disabled, so cannot enable " + QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED);
		}
		if (!quorumEnableSasl && quorumLearnerRequireSasl) {
			throw new IllegalArgumentException(QuorumAuth.QUORUM_SASL_AUTH_ENABLED
					+ " is disabled, so cannot enable " + QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED);
		}
		// If quorumpeer learner is not auth enabled then self won't be able to
		// join quorum. So this condition is ensuring that the quorumpeer learner
		// is also auth enabled while enabling quorum server require sasl.
		if (!quorumLearnerRequireSasl && quorumServerRequireSasl) {
			throw new IllegalArgumentException(QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED
					+ " is disabled, so cannot enable " + QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED);
		}
		// Reset to MIN_SNAP_RETAIN_COUNT if invalid (less than 3)
		// PurgeTxnLog.purge(File, File, int) will not allow to purge less
		// than 3.
		if (snapRetainCount < MIN_SNAP_RETAIN_COUNT) {
			LOG.warn("Invalid autopurge.snapRetainCount: " + snapRetainCount + ". Defaulting to "
					+ MIN_SNAP_RETAIN_COUNT);
			snapRetainCount = MIN_SNAP_RETAIN_COUNT;
		}

		if (dataDir == null) {
			throw new IllegalArgumentException("dataDir is not set");
		}
		if (dataLogDir == null) {
			dataLogDir = dataDir;
		}
		if (clientPort == 0) {
			throw new IllegalArgumentException("clientPort is not set");
		}
		if (clientPortAddress != null) {
			this.clientPortAddress =
					new InetSocketAddress(InetAddress.getByName(clientPortAddress), clientPort);
		} else {
			this.clientPortAddress = new InetSocketAddress(clientPort);
		}

		if (tickTime == 0) {
			throw new IllegalArgumentException("tickTime is not set");
		}
		if (minSessionTimeout > maxSessionTimeout) {
			throw new IllegalArgumentException(
					"minSessionTimeout must not be larger than maxSessionTimeout");
		}
		if (servers.size() == 0) {
			if (observers.size() > 0) {
				throw new IllegalArgumentException(
						"Observers w/o participants is an invalid configuration");
			}
			// Not a quorum configuration so return immediately - not an error
			// case (for b/w compatibility), server will default to standalone
			// mode.
			return;
		} else if (servers.size() == 1) {
			if (observers.size() > 0) {
				throw new IllegalArgumentException("Observers w/o quorum is an invalid configuration");
			}

			// HBase currently adds a single server line to the config, for
			// b/w compatibility reasons we need to keep this here.
			LOG.error("Invalid configuration, only one server specified (ignoring)");
			servers.clear();
		} else if (servers.size() > 1) {
			if (servers.size() == 2) {
				LOG.warn("No server failure will be tolerated. " + "You need at least 3 servers.");
			} else if (servers.size() % 2 == 0) {
				LOG.warn("Non-optimial configuration, consider an odd number of servers.");
			}
			if (initLimit == 0) {
				throw new IllegalArgumentException("initLimit is not set");
			}
			if (syncLimit == 0) {
				throw new IllegalArgumentException("syncLimit is not set");
			}
			/*
			 * If using FLE, then every server requires a separate election
			 * port.
			 */
			if (electionAlg != 0) {
				for (QuorumServer s : servers.values()) {
					if (s.electionAddr == null)
						throw new IllegalArgumentException("Missing election port for server: " + s.id);
				}
			}

			/*
			 * Default of quorum config is majority
			 */
			if (serverGroup.size() > 0) {
				if (servers.size() != serverGroup.size())
					throw new ConfigException("Every server must be in exactly one group");
				/*
				 * The deafult weight of a server is 1
				 */
				for (QuorumServer s : servers.values()) {
					if (!serverWeight.containsKey(s.id)) serverWeight.put(s.id, (long) 1);
				}

				/*
				 * Set the quorumVerifier to be QuorumHierarchical
				 */
				quorumVerifier = new QuorumHierarchical(numGroups, serverWeight, serverGroup);
			} else {
				/*
				 * The default QuorumVerifier is QuorumMaj
				 */

				LOG.info("Defaulting to majority quorums");
				quorumVerifier = new QuorumMaj(servers.size());
			}

			// Now add observers to servers, once the quorums have been
			// figured out
			servers.putAll(observers);

			File myIdFile = new File(dataDir, "myid");
			if (!myIdFile.exists()) {
				throw new IllegalArgumentException(myIdFile.toString() + " file is missing");
			}
			BufferedReader br = new BufferedReader(new FileReader(myIdFile));
			String myIdString;
			try {
				myIdString = br.readLine();
			} finally {
				br.close();
			}
			try {
				serverId = Long.parseLong(myIdString);
				MDC.put("myid", myIdString);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("serverid " + myIdString + " is not a number");
			}

			// Warn about inconsistent peer type
			LearnerType roleByServersList =
					observers.containsKey(serverId) ? LearnerType.OBSERVER : LearnerType.PARTICIPANT;
			if (roleByServersList != peerType) {
				LOG.warn("Peer type from servers list (" + roleByServersList + ") doesn't match peerType ("
						+ peerType + "). Defaulting to servers list.");

				peerType = roleByServersList;
			}
		}
	}

	public InetSocketAddress getClientPortAddress() {
		return clientPortAddress;
	}

	public String getDataDir() {
		return dataDir;
	}

	public String getDataLogDir() {
		return dataLogDir;
	}

	public int getTickTime() {
		return tickTime;
	}

	public int getMaxClientCnxns() {
		return maxClientCnxns;
	}

	public int getMinSessionTimeout() {
		return minSessionTimeout;
	}

	public int getMaxSessionTimeout() {
		return maxSessionTimeout;
	}

	public int getInitLimit() {
		return initLimit;
	}

	public int getSyncLimit() {
		return syncLimit;
	}

	public int getElectionAlg() {
		return electionAlg;
	}

	public int getElectionPort() {
		return electionPort;
	}

	public int getSnapRetainCount() {
		return snapRetainCount;
	}

	public int getPurgeInterval() {
		return purgeInterval;
	}

	public boolean getSyncEnabled() {
		return syncEnabled;
	}

	public QuorumVerifier getQuorumVerifier() {
		return quorumVerifier;
	}

	public Map<Long, QuorumServer> getServers() {
		return Collections.unmodifiableMap(servers);
	}

	public long getServerId() {
		return serverId;
	}

	public boolean isDistributed() {
		return servers.size() > 1;
	}

	public LearnerType getPeerType() {
		return peerType;
	}

	public Boolean getQuorumListenOnAllIPs() {
		return quorumListenOnAllIPs;
	}
}
