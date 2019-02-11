package com.feeyo.config.loader;

import com.feeyo.config.NetFlowCfg;
import com.feeyo.config.PoolCfg;
import com.feeyo.config.UserCfg;
import com.feeyo.kafka.config.KafkaPoolCfg;
import com.feeyo.kafka.config.TopicCfg;
import com.feeyo.kafka.net.backend.broker.zk.ZkClientx;
import com.feeyo.redis.net.backend.pool.PoolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.FileInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

public class RemoteConfigLoader extends AbstractConfigLoader {

    private static Logger LOGGER = LoggerFactory.getLogger(RemoteConfigLoader.class);

    private String id;
    private String zkServerHost;
    private static String BASE_PATH="/feeyo/config";

    public RemoteConfigLoader(String id, String zkServerHost) {
        this.id = id;
        this.zkServerHost = zkServerHost;
        ZkClientx.getZkClient(zkServerHost);
    }

    public Map<String, String> loadServerMap() throws Exception {
        String uri = AbstractConfigLoader.buidCfgAbsPathFor("server.xml");
        Map<String, String> map = new HashMap<>();
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

    public Map<Integer, PoolCfg> loadPoolMap() throws Exception {
        String uri = AbstractConfigLoader.buidCfgAbsPathFor("pool.xml");

        Map<Integer, PoolCfg> map = new HashMap<>();

        try {

            NodeList nodesElements = loadXmlDoc(uri).getElementsByTagName("pool");
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
                if (type == PoolType.KAFKA_CLUSTER) {
                    poolCfg = new KafkaPoolCfg(id, name, type, minCon, maxCon, latencyThreshold);
                } else {
                    poolCfg = new PoolCfg(id, name, type, minCon, maxCon, latencyThreshold);
                }

                List<Node> nodeList = getChildNodes(nodesElement, "node");
                for (Node node : nodeList) {
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

    public Map<String, UserCfg> loadUserMap(Map<Integer, PoolCfg> poolMap) throws Exception {
        String uri = AbstractConfigLoader.buidCfgAbsPathFor("user.xml");

        Pattern defaultKeyRule = loadDefaultKeyRule(uri);
        Map<String, UserCfg> map = new HashMap<>();
        try {
            NodeList nodeList = loadXmlDoc(uri).getElementsByTagName("user");
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                NamedNodeMap nameNodeMap = node.getAttributes();
                int poolId = getIntAttribute(nameNodeMap, "poolId", -1);
                String password = getAttribute(nameNodeMap, "password", null);
                String prefix = getAttribute(nameNodeMap, "prefix", null);
                if (prefix != null && prefix.trim().equals("")) {
                    prefix = null;
                }
                int selectDb = getIntAttribute(nameNodeMap, "selectDb", -1);
                int maxCon = getIntAttribute(nameNodeMap, "maxCon", 800);
                int isAdmin = getIntAttribute(nameNodeMap, "isAdmin", 0);
                boolean isReadonly = getBooleanAttribute(nameNodeMap, "readonly", false);
                String rule = getAttribute(nameNodeMap, "keyRule", null);
                Pattern keyRule = defaultKeyRule;
                if (rule != null && !rule.isEmpty()) {
                    keyRule = Pattern.compile(rule);
                }

                PoolCfg poolCfg = poolMap.get(poolId);
                int poolType = poolCfg.getType();

                UserCfg userCfg = new UserCfg(poolId, poolType, password, prefix, selectDb, maxCon, isAdmin != 0,
                        isReadonly, keyRule);

                // 非kafka pool的用户不能充当生产者 和 消费者
                if (poolType != 3) {
                    for (Map.Entry<Integer, PoolCfg> entry : poolMap.entrySet()) {
                        PoolCfg pc = entry.getValue();
                        if (pc instanceof KafkaPoolCfg) {
                            KafkaPoolCfg kpc = (KafkaPoolCfg) pc;
                            Map<String, TopicCfg> topicMap = kpc.getTopicCfgMap();
                            for (Map.Entry<String, TopicCfg> topicEntry : topicMap.entrySet()) {
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

    public Map<String, NetFlowCfg> loadNetFlowMap() throws Exception {
        String uri = AbstractConfigLoader.buidCfgAbsPathFor("netflow.xml");
        Map<String, NetFlowCfg> map = new HashMap<>();
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

                if (perSecondMaxSize < 1048576 || requestMaxSize < 262144) {
                    throw new Exception(" These parameters perSecondMaxSize or requestMaxSize have errors !!");
                }

                NetFlowCfg nfc = new NetFlowCfg(password, perSecondMaxSize, requestMaxSize, isControl);
                map.put(password, nfc);
            }
        } catch (Exception e) {
            LOGGER.error("load netflow.xml err " + e);
            throw e;
        }
        return map;
    }

    public Properties loadMailProperties() throws Exception {
        String uri = AbstractConfigLoader.buidCfgAbsPathFor("mail.properties");
        Properties props = new Properties();
        props.load(new FileInputStream(uri));
        return props;
    }

}
