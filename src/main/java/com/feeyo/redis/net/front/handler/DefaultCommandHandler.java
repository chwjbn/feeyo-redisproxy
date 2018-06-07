package com.feeyo.redis.net.front.handler;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.feeyo.redis.net.backend.callback.AbstractBackendCallback;
import com.feeyo.redis.net.backend.callback.DirectTransTofrontCallBack;
import com.feeyo.redis.net.backend.pool.PhysicalNode;
import com.feeyo.redis.net.codec.RedisRequest;
import com.feeyo.redis.net.front.RedisFrontConnection;
import com.feeyo.redis.net.front.bypass.BeyondTaskQueueException;
import com.feeyo.redis.net.front.bypass.BypassService;
import com.feeyo.redis.net.front.route.RouteNode;
import com.feeyo.redis.net.front.route.RouteResult;
import com.feeyo.redis.nio.util.TimeUtil;

public class DefaultCommandHandler extends AbstractCommandHandler {
	
	public DefaultCommandHandler(RedisFrontConnection frontCon) {
		super(frontCon);
	}

	@Override
	protected void commonHandle(RouteResult routeResult) throws IOException {
		
		RouteNode node = routeResult.getRouteNodes().get(0);
		RedisRequest firstRequest = routeResult.getRequests().get(0);
		
		String cmd = new String(firstRequest.getArgs()[0]).toUpperCase();
		byte[] requestKey = firstRequest.getNumArgs() > 1 ? firstRequest.getArgs()[1] : null;
		
		// 埋点
		frontCon.getSession().setRequestTimeMills(TimeUtil.currentTimeMillis());
		frontCon.getSession().setRequestCmd( cmd );
		frontCon.getSession().setRequestKey(requestKey);
		frontCon.getSession().setRequestSize(firstRequest.getSize());
		
		try {
			if (!BypassService.INSTANCE().goQueuing(firstRequest, frontCon, node.getPhysicalNode())) {
				
				writeToBackend(node.getPhysicalNode(), firstRequest.encode(), new DirectTransTofrontCallBack());
			}
			
		// 任务队列满了
		} catch (BeyondTaskQueueException e) {
			frontCon.write(BEYOND_TASK_QUEUE);
		}
		
	}
	
	public void writeToCustomerBackend(PhysicalNode physicalNode, ByteBuffer buffer, AbstractBackendCallback callBack) throws IOException {
		writeToBackend(physicalNode, buffer, callBack);
	}

	@Override
	public void frontConnectionClose(String reason) {
		super.frontConnectionClose(reason);
	}
	
	
	@Override
    public void backendConnectionError(Exception e) {
		
		super.backendConnectionError(e);
		
		if( frontCon != null && !frontCon.isClosed() ) {
			frontCon.writeErrMessage(e.toString());
		}
	}

	@Override
	public void backendConnectionClose(String reason) {
		
		super.backendConnectionClose(reason);

		if( frontCon != null && !frontCon.isClosed() ) {
			frontCon.writeErrMessage( reason );
		}
	}
	
}
