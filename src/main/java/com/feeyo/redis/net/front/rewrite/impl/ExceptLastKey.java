package com.feeyo.redis.net.front.rewrite.impl;

import com.feeyo.net.codec.redis.RedisRequest;
import com.feeyo.redis.config.UserCfg;
import com.feeyo.redis.net.front.rewrite.KeyIllegalException;
import com.feeyo.redis.net.front.rewrite.KeyRewriteStrategy;

/**
 * 除了最后一个Key, 其他Key 都需要变换
 * 
 * @author zhuam
 *
 */
public class ExceptLastKey extends KeyRewriteStrategy {

	@Override
	public void rewriteKey(RedisRequest request, UserCfg userCfg) throws KeyIllegalException {
		byte[][] args = request.getArgs();
		for (int i = 1; i < args.length - 1; i++) {
			checkKeyIllegalCharacter(userCfg.getKeyExpr(), args[i]);
			//
			args[i] = concat(userCfg.getPrefix(), args[i]);
		}		
	}

	@Override
	public byte[] getKey(RedisRequest request) {
		if ( request.getNumArgs() >  1 )
			return request.getArgs()[1];
		return null;
	}

}
