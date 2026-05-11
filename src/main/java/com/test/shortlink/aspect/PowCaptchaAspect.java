package com.test.shortlink.aspect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;
import org.aspectj.lang.reflect.MethodSignature; 
import com.test.shortlink.util.Util;
import com.test.shortlink.anno.PowCaptcha;

@Aspect
@Component
public class PowCaptchaAspect {
    private static final Logger logger = LoggerFactory.getLogger(PowCaptchaAspect.class);
    @Before("@annotation(powCaptcha)")
    public void before(JoinPoint joinPoint, PowCaptcha powCaptcha) {
        Object[] args = joinPoint.getArgs();
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = methodSignature.getParameterNames();
        String str = null,captcha=null;
        boolean foundTime=false;
        for(int i=0;i<paramNames.length;i++) {
            if(paramNames[i].equals(powCaptcha.timeParamName())) {
                if(args[i] instanceof Long) {
                    foundTime=true;
                    long time=(Long)args[i];
                    if(Math.abs(System.currentTimeMillis()/1000-time)>Util.captchaExpireSeconds) {
                        throw new IllegalArgumentException("Captcha expired");
                    }
                }else {
                    throw new IllegalArgumentException("time parameter must be of type long");
                }
            }
            if(paramNames[i].equals(powCaptcha.captchaParamName())) {
                if(args[i] instanceof String) {
                    captcha=(String)args[i];
                    str="";
                }else {
                    throw new IllegalArgumentException("Captcha parameter must be of type String");
                }
            }
        }
        if(captcha==null) {
            throw new IllegalArgumentException("Captcha parameter not found");
        }
        if(!foundTime) {
            throw new IllegalArgumentException("time parameter is required");
        }
        for(int i=0;i<powCaptcha.paramNames().length;i++) {
            boolean found=false;
            for(int j=0;j<paramNames.length;j++) {
                if(paramNames[j].equals(powCaptcha.paramNames()[i])) {
                    str+=paramNames[j]+args[j].toString();
                    found=true;
                    break;
                }
            }
            if(!found) {
                throw new IllegalArgumentException("Parameter "+powCaptcha.paramNames()[i]+" not found");
            }
        }
        logger.info("Checking captcha for method {}, parameters: {}", methodSignature.getMethod().getName(), str);
        if(!Util.powCaptchaCheck(str, captcha)) {
            throw new IllegalArgumentException("Invalid captcha");
        }
    }
}
