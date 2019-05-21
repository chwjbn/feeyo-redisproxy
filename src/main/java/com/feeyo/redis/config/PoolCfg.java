package com.feeyo.redis.config;

import java.util.ArrayList;
import java.util.List;


/**
 * 表示 Redis 集群
 *
 */
public class PoolCfg {
	
	protected final int id;
	protected final String name;
	protected final int type;
	
	protected final int maxCon;
	protected final int minCon;

	protected List<String> nodes = new ArrayList<String>();

	public PoolCfg(int id, String name, int type, int minCon, int maxCon) {
		this.id = id;
		this.name = name;
		this.type = type;
		this.minCon = minCon;
		this.maxCon = maxCon;
	}

	public int getId() {
		return id;
	}
	
	public String getName() {
		return name;
	}

	public int getType() {
		return type;
	}

	public int getMaxCon() {
		return maxCon;
	}

	public int getMinCon() {
		return minCon;
	}

	public List<String> getNodes() {
		return nodes;
	}

	public void addNode(String node) {
		this.nodes.add( node );
	}
	
	
	// Default null implement
	//
	public void loadExtraCfg() throws Exception {
		// ignore
	}
	
	public void reloadExtraCfg() throws Exception {
		// ignore
	}
	
	@Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        
        if (obj == null)
            return false;
        
        if (this.getClass() != obj.getClass())
            return false;
        
        PoolCfg other = (PoolCfg) obj;
        
        if (other.getId() == id
                && other.getMaxCon() == maxCon
                && other.getMinCon() == minCon
                && other.getName().equals(name)
                && other.getType() == type) {
            if (other.getNodes().size() != nodes.size()) {
                return false;
            } else {
                for (String string : other.getNodes()) {
                    if (!nodes.contains(string)) {
                        return false;
                    }
                }
                return true;
            }
        } else {
            return false;
        }
    }
}
