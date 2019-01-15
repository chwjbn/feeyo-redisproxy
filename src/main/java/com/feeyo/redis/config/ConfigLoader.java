package com.feeyo.redis.config;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.feeyo.kafka.config.KafkaPoolCfg;
import com.feeyo.kafka.config.TopicCfg;
import com.feeyo.redis.net.backend.pool.PoolType;


public class ConfigLoader {
	
	private static Logger LOGGER = LoggerFactory.getLogger( ConfigLoader.class );
	
	/**
	 * 获取 server.xml
	 * 
	 * @param uri
	 * @return
	 * @throws Exception 
	 */
	public static Map<String, String> loadServerMap(String uri) throws Exception {	
		Map<String, String> map = new HashMap<String, String>();
		try {
			Element element = loadXmlDoc(uri).getDocumentElement();
			NodeList children = element.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				Node node = children.item(i);
				if (node instanceof Element) {
					Element e = (Element) node;
					String name = e.getNodeName();
					if ("property".equals(name)) {
						String key = e.getAttribute("name");
						String value = e.getTextContent();
						map.put(key, value);		
					}
				}
			}

		} catch (Exception e) {
			LOGGER.error("load server.xml err " + e);
			throw e;
		}		
		return map;
		
	}
	
	/**
	 *  获取连接池配置
	 * 
	 * @param uri
	 * @return
	 * @throws Exception 
	 */
	public static Map<Integer, PoolCfg> loadPoolMap(String uri) throws Exception {

		Map<Integer, PoolCfg> map = new HashMap<Integer, PoolCfg>();
		
		try {

			NodeList nodesElements = loadXmlDoc( uri ).getElementsByTagName("pool");
			for (int i = 0; i < nodesElements.getLength(); i++) {
				Node nodesElement = nodesElements.item(i);

				NamedNodeMap nameNodeMap = nodesElement.getAttributes();
				int id = getIntAttribute(nameNodeMap, "id", -1);
				String name = getAttribute(nameNodeMap, "name", null);
				int type = getIntAttribute(nameNodeMap, "type", 0);
				int minCon = getIntAttribute(nameNodeMap, "minCon", 5);
				int maxCon = getIntAttribute(nameNodeMap, "maxCon", 100);
				float latencyThreshold = getFloatAttribute(nameNodeMap, "latencyThreshold", 0.5F);
				
				PoolCfg poolCfg;
				if (type == PoolType.KAFKA_CLUSTER ) {
					poolCfg = new KafkaPoolCfg(id, name, type, minCon, maxCon, latencyThreshold);
				} else {
					poolCfg = new PoolCfg(id, name, type, minCon, maxCon, latencyThreshold);
				}
				
				List<Node> nodeList = getChildNodes(nodesElement, "node");
				for (int j = 0; j < nodeList.size(); j++) {
					Node node = nodeList.get(j);
					NamedNodeMap attrs = node.getAttributes();
					String ip = getAttribute(attrs, "ip", null);
					int port = getIntAttribute(attrs, "port", 6379);
					String suffix = getAttribute(attrs, "suffix", null);
					if (type == 2 && suffix == null) {
						throw new Exception(
								"Customer Cluster nodes need to set unique suffix property");
					} else {
						poolCfg.addNode(ip + ":" + port + ":" + suffix);
					}
				}
				
				// load extra config
				poolCfg.loadExtraCfg();
				
				map.put(id, poolCfg);
			}
		} catch (Exception e) {
			LOGGER.error("loadPoolCfg err ", e);
			throw e;
		}
		return map;
	}
	
	public static Map<String, UserCfg> loadUserMap(Map<Integer, PoolCfg> poolMap, String uri) throws Exception  {

		Pattern defaultKeyRule = loadDefaultKeyRule(uri);
		Map<String, UserCfg> map = new HashMap<String, UserCfg>();
		try {
			NodeList nodeList = loadXmlDoc(uri).getElementsByTagName("user");
			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				NamedNodeMap nameNodeMap = node.getAttributes();				
				int poolId = getIntAttribute(nameNodeMap, "poolId", -1);
				String password = getAttribute(nameNodeMap, "password", null);
				String prefix = getAttribute(nameNodeMap, "prefix", null);
				if ( prefix != null && prefix.trim().equals("") ) {
					prefix = null;
				}
				int selectDb = getIntAttribute(nameNodeMap, "selectDb", -1);
				int maxCon = getIntAttribute(nameNodeMap, "maxCon", 800);
				int isAdmin = getIntAttribute(nameNodeMap, "isAdmin", 0);				
				boolean isReadonly = getBooleanAttribute(nameNodeMap, "readonly", false);
				String rule = getAttribute(nameNodeMap, "keyRule", null);
				Pattern keyRule = defaultKeyRule;
				if (rule != null  && !rule.isEmpty()) {
					keyRule = Pattern.compile(rule);
				}
				
				PoolCfg poolCfg = poolMap.get(poolId);
				int poolType = poolCfg.getType();
				
				UserCfg userCfg = new UserCfg(poolId, poolType, password, prefix, selectDb, maxCon, isAdmin == 0 ? false : true, 
						isReadonly, keyRule);
				
				// 非kafka pool的用户不能充当生产者 和 消费者
				if (poolType != 3) {
					for (Entry<Integer, PoolCfg> entry : poolMap.entrySet()) {
						PoolCfg pc = entry.getValue();
						if (pc instanceof KafkaPoolCfg) {
							KafkaPoolCfg kpc = (KafkaPoolCfg) pc;
							Map<String, TopicCfg> topicMap = kpc.getTopicCfgMap();
							for (Entry<String, TopicCfg> topicEntry : topicMap.entrySet()) {
								TopicCfg tc = topicEntry.getValue();
								if (tc.isConsumer(password)) {
									LOGGER.error(tc.getName() + ": kafka consumer can not be a user who is not in the kakfa pool");
									throw new Exception(tc.getName() + ": kafka consumer can not be a user who is not in the kakfa pool");
								}
								if (tc.isProducer(password)) {
									LOGGER.error(tc.getName() + ": kafa producer can not be a user who is not in the kakfa pool");
									throw new Exception(tc.getName() + ": kafa producer can not be a user who is not in the kakfa pool");
								}
							}
						}
					}
				}
				
				map.put(password, userCfg);
			}
		} catch (Exception e) {
			LOGGER.error("load user.xml err " + e);
			throw e;
		}
		return map;
	}
	
	private static Pattern loadDefaultKeyRule(String uri) {

		try {
			NodeList nodeList = loadXmlDoc(uri).getElementsByTagName("all");
			if (nodeList != null && nodeList.getLength() > 0) {
				Node node = nodeList.item(0);
				NamedNodeMap nameNodeMap = node.getAttributes();		
				String rule = getAttribute(nameNodeMap, "defaultKeyRule", null);
				if (rule != null  && !rule.isEmpty()) {
					return Pattern.compile(rule);
				}
			}
		} catch (Exception e) {
		}
		return null;
	}
	

	/*
	 * load netflow.xml
	 */
	public static Map<String, NetFlowCfg> loadNetFlowMap(String uri) throws Exception {

		Map<String, NetFlowCfg> map = new HashMap<String, NetFlowCfg>();
		try {
			NodeList nodeList = loadXmlDoc(uri).getElementsByTagName("user");
			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				NamedNodeMap nameNodeMap = node.getAttributes();			
				
				String password = getAttribute(nameNodeMap, "password", null);
				
				int perSecondMaxSize = getIntAttribute(nameNodeMap, "perSecondMaxSize", Integer.MAX_VALUE);
				perSecondMaxSize = perSecondMaxSize == -1 ? 1024 * 1024 * 5 : perSecondMaxSize;
				
				int requestMaxSize = getIntAttribute(nameNodeMap, "requestMaxSize", Integer.MAX_VALUE);
				requestMaxSize = requestMaxSize == -1 ? 1024 * 256 : requestMaxSize;
				
				boolean isControl = getBooleanAttribute(nameNodeMap, "control", true);
				
				if(perSecondMaxSize < 1048576 || requestMaxSize < 262144 ) {
					throw new Exception(" These parameters perSecondMaxSize or requestMaxSize have errors !!");
				}
				
				NetFlowCfg nfc = new NetFlowCfg(password, perSecondMaxSize , requestMaxSize, isControl);
				map.put(password, nfc);
			}
		} catch (Exception e) {
			LOGGER.error("load netflow.xml err " + e);
			throw e;
		}
		return map;
	}
	
	
	/*
	 * load mail properties
	 */
	public static Properties loadMailProperties(String uri) throws Exception {
		Properties props = new Properties();
		try {
			props.load(new FileInputStream(uri));
		} catch (Exception e) {
			LOGGER.error("load mail property error", e);
			throw e;
			
		}
		return props;
	}
	

	private static Document loadXmlDoc(String uri) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.parse(uri);
		return doc;
	}

	private static String getAttribute(NamedNodeMap map, String attr, String defaultVal) {
		return getValue(map.getNamedItem(attr), defaultVal);
	}

	private static float getFloatAttribute(NamedNodeMap map, String attr, float defaultVal) {
		return getFloatValue(map.getNamedItem(attr), defaultVal);
	}
	
	private static int getIntAttribute(NamedNodeMap map, String attr, int defaultVal) {
		return getIntValue(map.getNamedItem(attr), defaultVal);
	}
	
	static short getShortAttribute(NamedNodeMap map, String attr, short defaultVal) {
		return getShortValue(map.getNamedItem(attr), defaultVal);
	}
	
	private static boolean getBooleanAttribute(NamedNodeMap map, String attr, boolean defaultVal) {
		return getBooleanValue(map.getNamedItem(attr), defaultVal);
	}


	private static String getValue(Node node, String defaultVal) {
		return node == null ? defaultVal : node.getNodeValue();
	}
	
	private static float getFloatValue(Node node, float defaultVal) {
		return node == null ? defaultVal : Float.valueOf(node.getNodeValue());
	}
	
	private static int getIntValue(Node node, int defaultVal) {
		return node == null ? defaultVal : Integer.valueOf(node.getNodeValue());
	}
	
	private static short getShortValue(Node node, short defaultVal) {
		return node == null ? defaultVal : Short.valueOf(node.getNodeValue());
	}
	
	private static boolean getBooleanValue(Node node, boolean defaultVal) {
		return node == null ? defaultVal : Boolean.valueOf(node.getNodeValue());
	}

	private static List<Node> getChildNodes(Node theNode, String childElName) {
		LinkedList<Node> nodes = new LinkedList<Node>();
		NodeList childs = theNode.getChildNodes();
		for (int j = 0; j < childs.getLength(); j++) {
			if (childs.item(j).getNodeType() == Document.ELEMENT_NODE && childs.item(j).getNodeName().equals(childElName)) {
				nodes.add(childs.item(j));
			}
		}
		return nodes;
	}
	
	public static String buidCfgAbsPathFor(String fileName) {
		StringBuffer path = new StringBuffer();
		path.append( System.getProperty("FEEYO_HOME") ).append( File.separator );
		path.append( "conf" ).append( File.separator ).append( fileName );
        return path.toString();
	}

}