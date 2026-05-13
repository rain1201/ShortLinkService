package com.test.shortlink.aspect;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;

import com.test.shortlink.anno.ParamLenLimit;

@Aspect
public class ParamLenLimitAspect {
    @Before("@annotation(ParamLenLimit)")
    void before(JoinPoint joinPoint,ParamLenLimit pLimit){
        if(pLimit.maxLen().length!=pLimit.paramNames().length ||
            pLimit.maxLen().length!=pLimit.minLen().length)
            throw new IllegalArgumentException();
        Object[] args=joinPoint.getArgs();
        String[] params=((MethodSignature)(joinPoint.getSignature())).getParameterNames();
        for(byte i=0;i<pLimit.paramNames().length;i++){
            for(byte j=0;j<params.length;j++){
                if(params[j].equals(pLimit.paramNames()[i])){
                    if(args[j] instanceof String){
                        String str=(String)args[j];
                        if(str.length()>pLimit.maxLen()[i] || str.length()<pLimit.minLen()[i]){
                            throw new IllegalArgumentException("Parameter "+params[j]+" length must be between "+pLimit.minLen()[i]+" and "+pLimit.maxLen()[i]);
                        }
                    }else{
                        throw new IllegalArgumentException("Parameter "+params[j]+" must be of type String");
                    }
                }
            }
        }
    }
}
