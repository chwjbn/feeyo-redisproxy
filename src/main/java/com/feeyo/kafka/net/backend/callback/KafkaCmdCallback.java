package com.feeyo.kafka.net.backend.callback;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feeyo.kafka.codec.ResponseHeader;
import com.feeyo.net.nio.NetSystem;
import com.feeyo.net.nio.util.TimeUtil;
import com.feeyo.redis.engine.manage.stat.StatUtil;
import com.feeyo.redis.net.backend.BackendConnection;
import com.feeyo.redis.net.backend.callback.AbstractBackendCallback;
import com.feeyo.redis.net.front.RedisFrontConnection;

/**
 * @see https://cwiki.apache.org/confluence/display/KAFKA/A+Guide+To+The+Kafka+Protocol#AGuideToTheKafkaProtocol-Responses
 * 
 * Response => CorrelationId ResponseMessage
 *		CorrelationId => int32
 *		ResponseMessage => MetadataResponse | ProduceResponse | FetchResponse | OffsetResponse | OffsetCommitResponse | OffsetFetchResponse
 * 
 * 
 * @author yangtao
 *
 */
public abstract class KafkaCmdCallback extends AbstractBackendCallback {
	
	private static Logger LOGGER = LoggerFactory.getLogger( KafkaCmdCallback.class );
	
	protected static final int PRODUCE_RESPONSE_SIZE = 2;
	protected static final int CONSUMER_RESPONSE_SIZE = 3;
	protected static final int OFFSET_RESPONSE_SIZE = 2;

	protected static final byte ASTERISK = '*';
	protected static final byte DOLLAR = '$';
	protected static final byte[] CRLF = "\r\n".getBytes();		
	protected static final byte[] OK =   "+OK\r\n".getBytes();
	protected static final byte[] NULL =   "$-1\r\n".getBytes();
	
	private static int HEAD_LENGTH = 4;
	
	private byte[] buffer;
	
	private void append(byte[] buf) {
		if (buffer == null) {
			buffer = buf;
		} else {
			byte[] newBuffer = new byte[this.buffer.length + buf.length];
			System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
			System.arraycopy(buf, 0, newBuffer, buffer.length, buf.length);
			this.buffer = newBuffer;
			newBuffer = null;
			buf = null;
		}
	}
	
	/**
	 * 检查有没有断包
	 */
	private boolean isComplete() {
		
		int len = this.buffer.length;
		if (len < HEAD_LENGTH) {
			return false;
		}
		
		int v0 = (this.buffer[0] & 0xff) << 24;
		int v1 = (this.buffer[1] & 0xff) << 16;  
		int v2 = (this.buffer[2] & 0xff) << 8;  
	    int v3 = (this.buffer[3] & 0xff); 
	    
	    if (v0 + v1 + v2 + v3 > len - HEAD_LENGTH) {
	    	return false;
	    }
	    
		return true;
	}
	
	@Override
	public void handleResponse(BackendConnection conn, byte[] byteBuff) throws IOException {
		
		// 防止断包
		this.append(byteBuff);
		
		if ( !this.isComplete() ) {
			return;
		}
		
		ByteBuffer buffer = NetSystem.getInstance().getBufferPool().allocate( this.buffer.length );
		try {
			// 去除头部的长度
			buffer.put(this.buffer, HEAD_LENGTH, this.buffer.length - HEAD_LENGTH);
			buffer.flip();
			
			int responseSize = this.buffer.length;
			this.buffer = null;
			
			// parse header
			ResponseHeader.parse(buffer);
			
			// parse body
			parseResponseBody(conn, buffer);
			
			// release
			RedisFrontConnection frontCon = getFrontCon( conn );
			if (frontCon != null) {
				frontCon.releaseLock();
				
				String password = frontCon.getPassword();
				String cmd = frontCon.getSession().getRequestCmd();
				String key = frontCon.getSession().getRequestKey();
				int requestSize = frontCon.getSession().getRequestSize();
				long requestTimeMills = frontCon.getSession().getRequestTimeMills();			
				long responseTimeMills = TimeUtil.currentTimeMillis();
				
				int procTimeMills =  (int)(responseTimeMills - requestTimeMills);
				int backendWaitTimeMills = (int)(conn.getLastReadTime() - conn.getLastWriteTime());

				// 数据收集
				StatUtil.collect(password, cmd, key, requestSize, responseSize, procTimeMills, backendWaitTimeMills, false, false);
			}
			
			// 后端链接释放
			conn.release();	
			
		} catch (Exception e) {
			LOGGER.error("", e);
		} finally {
			
			NetSystem.getInstance().getBufferPool().recycle(buffer);
			
		}
		
	}

	public abstract void parseResponseBody(BackendConnection conn, ByteBuffer buffer);

}